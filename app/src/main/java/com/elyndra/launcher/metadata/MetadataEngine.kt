package com.elyndra.launcher.metadata

import android.content.Context
import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.data.ArtOrigin
import com.elyndra.launcher.data.FileHashes
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.GameSystem
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.MatchMethod
import com.elyndra.launcher.data.RaHashKind
import com.elyndra.launcher.data.RaInfo
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.library.DiscSheets
import com.elyndra.launcher.library.Names
import com.elyndra.launcher.library.SafFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Por qué un servicio dejó de usarse durante una pasada. */
enum class FailureKind { Credentials, Quota, Blocked, Unavailable, Network }

/**
 * "Aplicar metadatos": identifica cada juego y descarga su información.
 *
 *   ROMs: hash del archivo → ScreenScraper (jeuInfos, y jeuRecherche si no hay
 *         coincidencia), que identifica el juego y da el nombre bueno → el resto
 *         de servicios en el orden de prioridad del usuario, cada uno solo si
 *         puede mejorar algún campo → RetroAchievements (por hash rcheevos o,
 *         si no, por título) siempre, porque además trae los logros.
 *   Apps: IGDB (plataforma Android) → SteamGridDB, en el mismo orden de prioridad.
 *
 * Lo que da cada servicio se reúne aparte ([SourceData]) y se mezcla al final
 * campo a campo ([MetadataMerge]): la prioridad decide qué se queda. Cada
 * imagen guarda de qué servicio salió, y el juego cómo se identificó.
 *
 * Corre en un ámbito de aplicación y, mientras dura, ScrapeService mantiene
 * el proceso en primer plano (o el propio worker, en las pasadas automáticas).
 */
class MetadataEngine(
    private val context: Context,
    private val repo: LibraryRepository,
    private val credentials: ServiceCredentials,
    private val settings: SettingsStore,
    private val priorityStore: MetadataPriorityStore,
    private val media: MediaCache,
    private val files: SafFiles,
    private val scope: CoroutineScope,
) {
    val screenScraper = ScreenScraperClient { credentials.ss() }
    val igdb = IgdbClient({ credentials.igdb() }, credentials)
    val steamGridDb = SteamGridDbClient { credentials.sgdbKey() }
    val retroAchievements = RetroAchievementsClient({ credentials.ra() }, context.cacheDir)

    data class Progress(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val current: String = "",
        val matched: Int = 0,
        val missed: Int = 0,
        val failures: Map<Service, FailureKind> = emptyMap(),
        val finishedAt: Long = 0,
        val cancelled: Boolean = false,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var job: Job? = null
    private val pending = LinkedHashSet<String>()

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Arranca una pasada. [keys] null = toda la biblioteca. [force] vuelve a
     * consultar también lo que ya tiene metadatos. Si ya hay una en marcha,
     * las claves nuevas se encolan para después.
     *
     * [foreground] levanta ScrapeService para que la pasada siga aunque se
     * salga a jugar; los workers no lo necesitan (WorkManager ya mantiene vivo
     * el proceso) y, desde segundo plano, Android no dejaría arrancarlo.
     */
    @Synchronized
    fun start(keys: List<String>?, force: Boolean, foreground: Boolean = true): Boolean {
        if (!credentials.anyConfigured()) return false
        if (job?.isActive == true) {
            if (keys != null) pending += keys
            return true
        }
        _progress.value = Progress(running = true)
        job = scope.launch(Dispatchers.IO) { runPass(keys, force) }
        if (foreground) ScrapeService.start(context)
        return true
    }

    /** Lo mismo, esperando a que termine: es lo que usan las pasadas automáticas. */
    suspend fun runAndWait(keys: List<String>?, force: Boolean): Progress {
        if (!start(keys, force, foreground = false)) return progress.value
        job?.join()
        return progress.value
    }

    fun cancel() {
        synchronized(this) { pending.clear() }
        job?.cancel()
    }

    /** ¿Le falta algo a este juego? Es lo que decide qué entra en una pasada normal. */
    fun needsWork(key: String): Boolean {
        val meta = repo.romByKey(key)?.meta ?: repo.appByKey(key)?.meta ?: return false
        val isApp = key.startsWith("a:")
        return meta.scrapedAt == 0L || !meta.matched || (if (isApp) meta.icon == null else meta.cover == null)
    }

    private suspend fun runPass(keys: List<String>?, force: Boolean) {
        repo.awaitLoaded()
        var batch: List<String>? = keys
        var total = 0
        var done = 0
        val failures = LinkedHashMap<Service, FailureKind>()
        try {
            while (true) {
                val lib = repo.current
                val targets = (batch ?: (lib.roms.map { it.key } + lib.apps.map { it.key }))
                    .distinct()
                    .filter { force || needsWork(it) }
                total += targets.size
                _progress.update { it.copy(running = true, total = total) }
                for (key in targets) {
                    coroutineContext.ensureActive()
                    _progress.update { it.copy(current = titleOf(key), done = done) }
                    val matched = try {
                        scrapeKey(key, force, failures)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    done++
                    _progress.update {
                        it.copy(
                            done = done,
                            matched = it.matched + if (matched) 1 else 0,
                            missed = it.missed + if (matched) 0 else 1,
                            failures = failures.toMap(),
                        )
                    }
                }
                val next = synchronized(this) { pending.toList().also { pending.clear() } }
                if (next.isEmpty()) break
                batch = next
            }
            _progress.update { it.copy(running = false, current = "", finishedAt = System.currentTimeMillis(), failures = failures.toMap()) }
        } catch (e: CancellationException) {
            _progress.update { it.copy(running = false, current = "", cancelled = true, finishedAt = System.currentTimeMillis()) }
            throw e
        } finally {
            withContext(NonCancellable) { repo.flush() }
        }
    }

    private fun titleOf(key: String): String =
        repo.romByKey(key)?.displayTitle ?: repo.appByKey(key)?.displayTitle ?: ""

    /** Actualiza solo este juego (ficha de detalles → "Actualizar metadatos"). */
    fun refresh(key: String) = start(listOf(key), force = true)

    /* ─────────────────────────────────────────────────────────── */

    private fun usable(service: Service, failures: Map<Service, FailureKind>) =
        credentials.isConfigured(service) && service !in failures

    private fun recordFailure(service: Service, e: Throwable, failures: MutableMap<Service, FailureKind>) {
        val kind = when (e) {
            is ApiException.Unauthorized -> FailureKind.Credentials.also { settings.setVerified(service.id, false) }
            is ApiException.QuotaExceeded -> FailureKind.Quota
            is ApiException.Blocked -> FailureKind.Blocked
            is ApiException.Server -> if (e.code == 401 || e.code == 423 || e.code >= 500) FailureKind.Unavailable else null
            is ApiException.Network -> FailureKind.Network
            else -> null
        }
        if (kind != null) failures[service] = kind
    }

    private fun regionsFor(lang: String): List<String> = when (lang) {
        "es" -> listOf("sp", "eu", "wor", "us")
        "pt" -> listOf("br", "pt", "eu", "wor", "us")
        "fr" -> listOf("fr", "eu", "wor", "us")
        "de" -> listOf("de", "eu", "wor", "us")
        "ja" -> listOf("jp", "wor", "us")
        else -> listOf("us", "wor", "eu")
    }

    private fun languagesFor(lang: String): List<String> = if (lang == "ja") listOf("ja", "jp", "en") else listOf(lang, "en")

    private suspend fun scrapeKey(key: String, force: Boolean, failures: MutableMap<Service, FailureKind>): Boolean {
        val lang = AppLocale.current(context)
        val priority = priorityStore.get()
        return when {
            key.startsWith("r:") -> {
                val rom = repo.romByKey(key) ?: return false
                val folder = repo.folder(rom.folderId) ?: return false
                val system = Systems.byId(rom.systemId) ?: return false
                save(key, scrapeRom(rom, folder, system, lang, priority, failures), priority)
            }
            key.startsWith("a:") -> {
                val app = repo.appByKey(key) ?: return false
                save(key, scrapeApp(app, priority, failures), priority)
            }
            else -> false
        }
    }

    /** El mejor nombre conocido: el que dio quien identificó el juego o, si nadie, el del archivo. */
    private fun bestName(results: Map<Service, SourceData>, fallback: String): String =
        results[Service.ScreenScraper]?.text?.get(MetaField.Name)
            ?: results.values.firstNotNullOfOrNull { it.text[MetaField.Name] }
            ?: fallback

    private suspend fun scrapeRom(
        rom: RomEntry,
        folder: RomFolder,
        system: GameSystem,
        lang: String,
        priority: MetadataPriority,
        failures: MutableMap<Service, FailureKind>,
    ): Map<Service, SourceData> {
        val results = LinkedHashMap<Service, SourceData>()
        val searchName = rom.title
        val wantSs = usable(Service.ScreenScraper, failures) && system.ssId != null
        val wantRa = usable(Service.RetroAchievements, failures) && system.raId != null
        val hashes = ensureHashes(rom, folder, system, wantSs, wantRa)

        // ScreenScraper identifica por hash: va primero siempre, sea cual sea la prioridad.
        if (wantSs) ssInto(results, rom, system, hashes, searchName, regionsFor(lang), languagesFor(lang), failures)

        for (service in priority.queryOrder()) {
            when (service) {
                Service.ScreenScraper -> Unit
                Service.Igdb -> if (usable(Service.Igdb, failures) && MetadataMerge.wanted(Service.Igdb, results, priority).isNotEmpty()) {
                    igdbInto(results, bestName(results, searchName), system.igdbIds.ifEmpty { null }, failures)
                }
                Service.SteamGridDb -> if (usable(Service.SteamGridDb, failures) && MetadataMerge.wanted(Service.SteamGridDb, results, priority).isNotEmpty()) {
                    sgdbInto(results, bestName(results, searchName), SteamGridDbClient.PORTRAIT, failures)
                }
                // Siempre que se pueda: aunque no aporte ningún campo, trae los logros.
                Service.RetroAchievements -> if (wantRa) raInto(results, hashes, bestName(results, searchName), system, failures)
            }
        }
        return results
    }

    private suspend fun scrapeApp(
        app: AppEntry,
        priority: MetadataPriority,
        failures: MutableMap<Service, FailureKind>,
    ): Map<Service, SourceData> {
        val results = LinkedHashMap<Service, SourceData>()
        val name = app.label
        for (service in priority.queryOrder()) {
            when (service) {
                Service.Igdb -> if (usable(Service.Igdb, failures)) {
                    igdbInto(results, name, listOf(ANDROID_IGDB), failures)
                    if (results[Service.Igdb]?.igdbId == null) igdbInto(results, name, null, failures, minSimilarity = 0.9)
                }
                Service.SteamGridDb -> if (usable(Service.SteamGridDb, failures) && MetadataMerge.wanted(Service.SteamGridDb, results, priority).isNotEmpty()) {
                    sgdbInto(results, bestName(results, name), SteamGridDbClient.SQUARE + SteamGridDbClient.PORTRAIT, failures)
                }
                // ScreenScraper y RetroAchievements no catalogan juegos Android.
                Service.ScreenScraper, Service.RetroAchievements -> Unit
            }
        }
        return results
    }

    private suspend fun ssInto(
        results: MutableMap<Service, SourceData>,
        rom: RomEntry,
        system: GameSystem,
        hashes: FileHashes?,
        searchName: String,
        regions: List<String>,
        languages: List<String>,
        failures: MutableMap<Service, FailureKind>,
    ) {
        try {
            val romType = when {
                rom.isDirectory -> "dossier"
                system.disc -> "iso"
                else -> "rom"
            }
            val data = SourceData(Service.ScreenScraper)
            var game = screenScraper.gameInfo(
                systemId = system.ssId!!,
                romName = rom.fileName,
                size = rom.size,
                romType = romType,
                crc = hashes?.crc,
                md5 = hashes?.md5,
                sha1 = hashes?.sha1,
            )
            if (game != null) {
                // jeuInfos casa por hash si se le dio; si no, por nombre de archivo y tamaño.
                if (hashes?.md5 != null || hashes?.crc != null) data.matched(MatchMethod.HASH, 1.0) else data.matched(MatchMethod.NAME, 0.9)
            } else if (searchName.length >= 4) {
                val found = screenScraper.search(system.ssId, searchName)
                    .map { g -> g to Names.similarity(searchName, g.name(regions).orEmpty()) }
                    .filter { it.second >= 0.6 }
                    .maxByOrNull { it.second }
                if (found != null) {
                    game = found.first
                    data.matched(MetadataMerge.nameMethod(found.second), found.second)
                }
            }
            if (game == null) return
            data.ssId = game.id
            data.setText(MetaField.Name, game.name(regions))
            data.setText(MetaField.Description, game.description(languages))
            data.setText(MetaField.ReleaseDate, game.releaseDate(regions))
            data.setText(MetaField.Developer, game.developer)
            data.setText(MetaField.Publisher, game.publisher)
            data.setText(MetaField.Genre, game.genre(languages))
            data.setText(MetaField.Players, game.players)
            data.rating = game.rating?.let { (it / 20.0).toFloat().coerceIn(0f, 1f) }
            game.media(listOf("box-2D", "box-3D"), regions)?.let { data.setArt(MetaField.Cover, ScreenScraperClient.sizedMediaUrl(it, 640, jpg = true)) }
            game.media(listOf("fanart", "ss", "sstitle"), regions)?.let { data.setArt(MetaField.Hero, ScreenScraperClient.sizedMediaUrl(it, 1280, jpg = true)) }
            game.media(listOf("wheel-hd", "wheel", "wheel-carbon"), regions)?.let { data.setArt(MetaField.Logo, ScreenScraperClient.sizedMediaUrl(it, 640, jpg = false)) }
            game.media(listOf("wheel-hd", "wheel", "box-2D"), regions)?.let { data.setArt(MetaField.Icon, ScreenScraperClient.sizedMediaUrl(it, 512, jpg = false)) }
            game.media(listOf("ss", "sstitle"), regions)?.let { data.setArt(MetaField.Screenshot, ScreenScraperClient.sizedMediaUrl(it, 960, jpg = true)) }
            results[Service.ScreenScraper] = data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(Service.ScreenScraper, e, failures)
        }
    }

    private suspend fun igdbInto(
        results: MutableMap<Service, SourceData>,
        name: String,
        platforms: List<Int>?,
        failures: MutableMap<Service, FailureKind>,
        minSimilarity: Double = 0.75,
    ) {
        try {
            val (best, similarity) = igdb.search(name, platforms)
                .map { it to Names.similarity(name, it.name) }
                .filter { it.second >= minSimilarity }
                .maxByOrNull { it.second } ?: return
            val data = SourceData(Service.Igdb)
            data.matched(MetadataMerge.nameMethod(similarity), similarity)
            data.igdbId = best.id
            data.setText(MetaField.Name, best.name)
            data.setText(MetaField.Description, best.summary)
            data.setText(
                MetaField.ReleaseDate,
                best.firstRelease?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate().toString() },
            )
            data.setText(MetaField.Genre, best.genres.take(3).joinToString(", "))
            data.setText(MetaField.Developer, best.developers.firstOrNull())
            data.setText(MetaField.Publisher, best.publishers.firstOrNull())
            data.setText(MetaField.Players, best.modes.joinToString(", "))
            data.rating = best.rating?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }
            best.coverId?.let { data.setArt(MetaField.Cover, IgdbClient.imageUrl(it, "cover_big_2x")) }
            (best.artworkIds.firstOrNull()?.let { IgdbClient.imageUrl(it, "1080p") }
                ?: best.screenshotIds.firstOrNull()?.let { IgdbClient.imageUrl(it, "screenshot_huge") })
                ?.let { data.setArt(MetaField.Hero, it) }
            best.coverId?.let { data.setArt(MetaField.Icon, IgdbClient.imageUrl(it, "cover_big")) }
            best.screenshotIds.firstOrNull()?.let { data.setArt(MetaField.Screenshot, IgdbClient.imageUrl(it, "screenshot_big")) }
            results[Service.Igdb] = data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(Service.Igdb, e, failures)
        }
    }

    private suspend fun sgdbInto(
        results: MutableMap<Service, SourceData>,
        name: String,
        gridDimensions: List<String>,
        failures: MutableMap<Service, FailureKind>,
    ) {
        try {
            val (game, similarity) = steamGridDb.search(name).take(8)
                .map { it to Names.similarity(name, it.name) }
                .filter { it.second >= 0.8 }
                .maxByOrNull { it.second } ?: return
            val data = SourceData(Service.SteamGridDb)
            data.matched(MetadataMerge.nameMethod(similarity), similarity)
            data.sgdbId = game.id
            // Solo se pide lo que de verdad puede ganar: cada clase de imagen es una llamada.
            val wanted = MetadataMerge.wanted(Service.SteamGridDb, results, priorityStore.get())
            if (MetaField.Cover in wanted) steamGridDb.grids(game.id, gridDimensions).firstOrNull()?.let { data.setArt(MetaField.Cover, it.url) }
            if (MetaField.Hero in wanted) steamGridDb.heroes(game.id).firstOrNull()?.let { data.setArt(MetaField.Hero, it.url) }
            if (MetaField.Logo in wanted) steamGridDb.logos(game.id).firstOrNull()?.let { data.setArt(MetaField.Logo, it.url) }
            if (MetaField.Icon in wanted) steamGridDb.icons(game.id).firstOrNull()?.let { data.setArt(MetaField.Icon, it.url) }
            results[Service.SteamGridDb] = data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(Service.SteamGridDb, e, failures)
        }
    }

    private suspend fun raInto(
        results: MutableMap<Service, SourceData>,
        hashes: FileHashes?,
        name: String,
        system: GameSystem,
        failures: MutableMap<Service, FailureKind>,
    ) {
        try {
            val list = retroAchievements.gameList(system.raId!!)
            val byHash = RaParser.matchHash(list, hashes?.ra)
            val entry = byHash ?: RaParser.matchTitle(list, name) ?: return
            val p = retroAchievements.progress(entry.id)
            val data = SourceData(Service.RetroAchievements)
            if (byHash != null) {
                data.matched(MatchMethod.HASH, 1.0)
            } else {
                val similarity = entry.title.split(" | ").maxOf { Names.similarity(name, it) }
                data.matched(MetadataMerge.nameMethod(similarity), similarity)
            }
            data.ra = raInfo(p, if (byHash != null) "hash" else "title")
            p.boxArt?.let { data.setArt(MetaField.Cover, RetroAchievementsClient.mediaUrl(it)) }
            p.imageIcon?.let { data.setArt(MetaField.Icon, RetroAchievementsClient.mediaUrl(it)) }
            data.setText(MetaField.Name, p.title)
            data.setText(MetaField.Developer, p.developer)
            data.setText(MetaField.Publisher, p.publisher)
            data.setText(MetaField.Genre, p.genre)
            data.setText(MetaField.ReleaseDate, p.released)
            results[Service.RetroAchievements] = data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(Service.RetroAchievements, e, failures)
        }
    }

    private fun raInfo(p: RaGameProgress, matchedBy: String) = RaInfo(
        gameId = p.id,
        title = p.title,
        achievements = p.total,
        earned = p.earned,
        earnedHardcore = p.earnedHardcore,
        points = p.points,
        earnedPoints = p.earnedPoints,
        matchedBy = matchedBy,
        updatedAt = System.currentTimeMillis(),
    )

    /** Mezcla lo reunido y lo guarda. Devuelve true si algún servicio reconoció el juego. */
    private suspend fun save(key: String, results: Map<Service, SourceData>, priority: MetadataPriority): Boolean {
        val merged = MetadataMerge.merge(results, priority)
        // Lo elegido a mano en "Personalizar…" no se vuelve a descargar (la descarga borraría el archivo).
        val pinned = (repo.romByKey(key)?.meta ?: repo.appByKey(key)?.meta)?.pinned.orEmpty()
        val isApp = key.startsWith("a:")
        val origins = LinkedHashMap<String, ArtOrigin>()

        suspend fun fetch(field: MetaField, allowed: Boolean): String? {
            if (!allowed) return null
            val (service, url) = merged.art[field] ?: return null
            val kind = field.artKind ?: return null
            return media.download(url, key, kind)?.also { origins[kind] = ArtOrigin(service.id, url) }
        }

        // Un juego Android no usa carátula (se representa con su icono): no se baja.
        val cover = fetch(MetaField.Cover, !isApp && "cover" !in pinned)
        // El icono es la imagen de un juego Android: se baja solo para ellos.
        val icon = fetch(MetaField.Icon, isApp && "icon" !in pinned)
        val hero = fetch(MetaField.Hero, "hero" !in pinned)
        val logo = fetch(MetaField.Logo, "logo" !in pinned)
        val shot = fetch(MetaField.Screenshot, true)
        val matched = merged.matched
        val text = merged.text
        repo.updateMeta(key) { old ->
            GameMeta(
                scrapedAt = System.currentTimeMillis(),
                matched = matched || old.matched,
                sources = if (matched) merged.sources.map { it.id } else old.sources,
                name = text[MetaField.Name] ?: old.name,
                description = text[MetaField.Description] ?: old.description,
                releaseDate = text[MetaField.ReleaseDate] ?: old.releaseDate,
                developer = text[MetaField.Developer] ?: old.developer,
                publisher = text[MetaField.Publisher] ?: old.publisher,
                genre = text[MetaField.Genre] ?: old.genre,
                players = text[MetaField.Players] ?: old.players,
                rating = merged.rating ?: old.rating,
                cover = cover ?: old.cover,
                hero = hero ?: old.hero,
                logo = logo ?: old.logo,
                screenshot = shot ?: old.screenshot,
                icon = icon ?: old.icon,
                pinned = old.pinned,
                ssGameId = merged.ssId ?: old.ssGameId,
                igdbId = merged.igdbId ?: old.igdbId,
                sgdbId = merged.sgdbId ?: old.sgdbId,
                ra = merged.ra ?: old.ra,
                artOrigins = old.artOrigins + origins,
                // Una identificación a mano no la corrige una pasada automática.
                matchedBy = if (old.matchedBy == MatchMethod.MANUAL) old.matchedBy else merged.matchedBy ?: old.matchedBy,
                matchConfidence = if (old.matchedBy == MatchMethod.MANUAL) old.matchConfidence else merged.confidence ?: old.matchConfidence,
            )
        }
        return matched
    }

    /* ── Hashes ──────────────────────────────────────────────── */

    private suspend fun ensureHashes(
        rom: RomEntry,
        folder: RomFolder,
        system: GameSystem,
        wantSs: Boolean,
        wantRa: Boolean,
    ): FileHashes? = withContext(Dispatchers.IO) {
        var h = rom.hashes?.takeIf { it.size == rom.size && it.modified == rom.modified }
            ?: FileHashes(rom.size, rom.modified)
        val ctx = coroutineContext
        val limit = settings.hashLimitMb.toLong() * 1024 * 1024
        var changed = false
        if (wantSs && !rom.isDirectory && h.md5 == null && rom.size in 1..limit) {
            files.openStream(folder.treeUri, rom.docId) { Hashing.digests(it) { !ctx.isActive } }?.let { d ->
                h = h.copy(crc = d.crc, md5 = d.md5, sha1 = d.sha1)
                changed = true
            }
        }
        if (wantRa && !h.raComputed && system.raHash != RaHashKind.None) {
            h = h.copy(ra = runCatching { raHash(rom, folder, system) }.getOrNull(), raComputed = true)
            changed = true
        }
        if (changed) repo.updateRom(rom.id) { it.copy(hashes = h) }
        h
    }

    /** Hash rcheevos de la ROM, o null si el formato no permite calcularlo (7z, CHD, CSO…). */
    private fun raHash(rom: RomEntry, folder: RomFolder, system: GameSystem): String? {
        if (rom.isDirectory) return null
        val ext = rom.fileName.substringAfterLast('.', "").lowercase()
        val tree = folder.treeUri
        return when (val kind = system.raHash) {
            RaHashKind.None -> null
            RaHashKind.Arcade -> RaHash.arcade(rom.fileName, rom.relPath.split('/').dropLast(1).lastOrNull())
            RaHashKind.Plain, RaHashKind.Nes, RaHashKind.Snes, RaHashKind.Lynx,
            RaHashKind.A7800, RaHashKind.Pce, RaHashKind.N64 -> when (ext) {
                "7z" -> null
                "zip" -> files.openStream(tree, rom.docId) { zippedCartridge(kind, it, system) }
                else -> files.openStream(tree, rom.docId) { RaHash.cartridge(kind, it, rom.size) }
            }
            RaHashKind.Nds -> if (ext in setOf("nds", "dsi", "ids", "app")) files.openRandom(tree, rom.docId) { RaHash.nds(it) } else null
            RaHashKind.Psx -> discHash(rom, tree, ext) { RaDiscHash.psx(CdImage(it)) }
            RaHashKind.Ps2 -> discHash(rom, tree, ext) { RaDiscHash.ps2(CdImage(it)) }
            RaHashKind.Psp -> when (ext) {
                "iso" -> files.openRandom(tree, rom.docId) { RaDiscHash.psp(CdImage(it)) }
                "pbp" -> files.openRandom(tree, rom.docId) { RaDiscHash.wholeFile(it) }
                else -> null
            }
        }
    }

    /** Imágenes de disco: .iso/.bin/.img directos, o la primera pista de una .cue / primer disco de una .m3u. */
    private fun discHash(rom: RomEntry, tree: String, ext: String, hash: (RandomReader) -> String?): String? = when (ext) {
        "iso", "bin", "img" -> files.openRandom(tree, rom.docId, hash)
        "cue" -> {
            val cue = files.openStream(tree, rom.docId) { String(it.readBytes().take(256 * 1024).toByteArray()) }.orEmpty()
            DiscSheets.referencedFiles("cue", rom.fileName, cue).firstOrNull()
                ?.let { files.openRandom(tree, SafFiles.siblingDocId(rom.docId, it), hash) }
        }
        "m3u" -> {
            val m3u = files.openStream(tree, rom.docId) { String(it.readBytes().take(64 * 1024).toByteArray()) }.orEmpty()
            val first = DiscSheets.referencedFiles("m3u", rom.fileName, m3u).firstOrNull()
            val firstExt = first?.substringAfterLast('.', "")?.lowercase()
            if (first == null || firstExt == "m3u") null
            else discHash(rom.copy(docId = SafFiles.siblingDocId(rom.docId, first), fileName = first), tree, firstExt ?: "", hash)
        }
        else -> null
    }

    private fun zippedCartridge(kind: RaHashKind, input: java.io.InputStream, system: GameSystem): String? {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                if (!entry.isDirectory && ext in system.extensions && ext != "zip" && ext != "7z") {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (out.size() <= RaHash.MAX_BUFFER_SIZE) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    val bytes = out.toByteArray()
                    return RaHash.cartridge(kind, ByteArrayInputStream(bytes), bytes.size.toLong())
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    /** Progreso actual de logros para la ficha de detalles (y lo guarda). */
    suspend fun freshAchievements(key: String): RaGameProgress? {
        val rom = repo.romByKey(key) ?: return null
        val gameId = rom.meta.ra?.gameId ?: return null
        if (!credentials.isConfigured(Service.RetroAchievements)) return null
        val p = retroAchievements.progress(gameId)
        repo.updateMeta(key) { it.copy(ra = raInfo(p, it.ra?.matchedBy ?: "hash")) }
        return p
    }

    private companion object {
        const val ANDROID_IGDB = 34
    }
}
