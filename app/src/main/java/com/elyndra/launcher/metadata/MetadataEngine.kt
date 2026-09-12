package com.elyndra.launcher.metadata

import android.content.Context
import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.data.FileHashes
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.GameSystem
import com.elyndra.launcher.data.LibraryRepository
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
 *         coincidencia) → IGDB para lo que falte → SteamGridDB para el arte que
 *         falte → RetroAchievements (por hash rcheevos o, si no, por título).
 *   Apps: IGDB (plataforma Android) → SteamGridDB.
 *
 * Corre en un ámbito de aplicación y, mientras dura, ScrapeService mantiene
 * el proceso en primer plano.
 */
class MetadataEngine(
    private val context: Context,
    private val repo: LibraryRepository,
    private val credentials: ServiceCredentials,
    private val settings: SettingsStore,
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
     */
    @Synchronized
    fun start(keys: List<String>?, force: Boolean): Boolean {
        if (!credentials.anyConfigured()) return false
        if (job?.isActive == true) {
            if (keys != null) pending += keys
            return true
        }
        _progress.value = Progress(running = true)
        job = scope.launch(Dispatchers.IO) { runPass(keys, force) }
        ScrapeService.start(context)
        return true
    }

    fun cancel() {
        synchronized(this) { pending.clear() }
        job?.cancel()
    }

    private fun needsWork(key: String): Boolean {
        val meta = repo.romByKey(key)?.meta ?: repo.appByKey(key)?.meta ?: return false
        return meta.scrapedAt == 0L || !meta.matched || meta.cover == null
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

    private class Draft {
        var name: String? = null
        var description: String? = null
        var releaseDate: String? = null
        var developer: String? = null
        var publisher: String? = null
        var genre: String? = null
        var players: String? = null
        var rating: Float? = null
        var coverUrl: String? = null
        var heroUrl: String? = null
        var logoUrl: String? = null
        var screenshotUrl: String? = null
        var ssId: String? = null
        var igdbId: Long? = null
        var sgdbId: Long? = null
        var ra: RaInfo? = null
        val sources = LinkedHashSet<String>()

        val needsText get() = description == null || name == null
        val needsArt get() = coverUrl == null || heroUrl == null || logoUrl == null
    }

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
        val draft = Draft()
        return when {
            key.startsWith("r:") -> {
                val rom = repo.romByKey(key) ?: return false
                val folder = repo.folder(rom.folderId) ?: return false
                val system = Systems.byId(rom.systemId) ?: return false
                scrapeRom(rom, folder, system, draft, lang, failures)
                save(key, draft, force)
            }
            key.startsWith("a:") -> {
                val app = repo.appByKey(key) ?: return false
                scrapeApp(app, draft, failures)
                save(key, draft, force)
            }
            else -> false
        }
    }

    private suspend fun scrapeRom(
        rom: RomEntry,
        folder: RomFolder,
        system: GameSystem,
        draft: Draft,
        lang: String,
        failures: MutableMap<Service, FailureKind>,
    ) {
        val regions = regionsFor(lang)
        val languages = languagesFor(lang)
        val searchName = rom.title
        val wantSs = usable(Service.ScreenScraper, failures) && system.ssId != null
        val wantRa = usable(Service.RetroAchievements, failures) && system.raId != null
        val hashes = ensureHashes(rom, folder, system, wantSs, wantRa)

        // 1 · ScreenScraper
        if (wantSs) {
            try {
                val romType = when {
                    rom.isDirectory -> "dossier"
                    system.disc -> "iso"
                    else -> "rom"
                }
                val game = screenScraper.gameInfo(
                    systemId = system.ssId!!,
                    romName = rom.fileName,
                    size = rom.size,
                    romType = romType,
                    crc = hashes?.crc,
                    md5 = hashes?.md5,
                    sha1 = hashes?.sha1,
                ) ?: if (searchName.length >= 4) {
                    screenScraper.search(system.ssId, searchName)
                        .firstOrNull { g -> Names.similarity(searchName, g.name(regions).orEmpty()) >= 0.6 }
                } else null
                if (game != null) {
                    draft.sources += Service.ScreenScraper.id
                    draft.ssId = game.id
                    draft.name = game.name(regions)
                    draft.description = game.description(languages)
                    draft.releaseDate = game.releaseDate(regions)
                    draft.developer = game.developer
                    draft.publisher = game.publisher
                    draft.genre = game.genre(languages)
                    draft.players = game.players
                    draft.rating = game.rating?.let { (it / 20.0).toFloat().coerceIn(0f, 1f) }
                    game.media(listOf("box-2D", "box-3D"), regions)?.let { draft.coverUrl = ScreenScraperClient.sizedMediaUrl(it, 640, jpg = true) }
                    game.media(listOf("fanart", "ss", "sstitle"), regions)?.let { draft.heroUrl = ScreenScraperClient.sizedMediaUrl(it, 1280, jpg = true) }
                    game.media(listOf("wheel-hd", "wheel", "wheel-carbon"), regions)?.let { draft.logoUrl = ScreenScraperClient.sizedMediaUrl(it, 640, jpg = false) }
                    game.media(listOf("ss", "sstitle"), regions)?.let { draft.screenshotUrl = ScreenScraperClient.sizedMediaUrl(it, 960, jpg = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordFailure(Service.ScreenScraper, e, failures)
            }
        }

        // 2 · IGDB para lo que falte
        if (usable(Service.Igdb, failures) && (draft.needsText || draft.coverUrl == null)) {
            igdbInto(draft, draft.name ?: searchName, system.igdbIds.ifEmpty { null }, failures)
        }

        // 3 · SteamGridDB para el arte que falte
        if (usable(Service.SteamGridDb, failures) && draft.needsArt) {
            sgdbInto(draft, draft.name ?: searchName, SteamGridDbClient.PORTRAIT, failures)
        }

        // 4 · RetroAchievements
        if (wantRa) {
            try {
                val list = retroAchievements.gameList(system.raId!!)
                val byHash = RaParser.matchHash(list, hashes?.ra)
                val entry = byHash ?: RaParser.matchTitle(list, draft.name ?: searchName)
                if (entry != null) {
                    val p = retroAchievements.progress(entry.id)
                    draft.sources += Service.RetroAchievements.id
                    draft.ra = raInfo(p, if (byHash != null) "hash" else "title")
                    if (draft.coverUrl == null) p.boxArt?.let { draft.coverUrl = RetroAchievementsClient.mediaUrl(it) }
                    if (draft.name == null) draft.name = p.title.takeIf { it.isNotBlank() }
                    if (draft.developer == null) draft.developer = p.developer
                    if (draft.publisher == null) draft.publisher = p.publisher
                    if (draft.genre == null) draft.genre = p.genre
                    if (draft.releaseDate == null) draft.releaseDate = p.released
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordFailure(Service.RetroAchievements, e, failures)
            }
        }
    }

    private suspend fun scrapeApp(app: AppEntry, draft: Draft, failures: MutableMap<Service, FailureKind>) {
        val name = app.label
        if (usable(Service.Igdb, failures)) {
            igdbInto(draft, name, listOf(ANDROID_IGDB), failures)
            if (draft.igdbId == null) igdbInto(draft, name, null, failures, minSimilarity = 0.9)
        }
        if (usable(Service.SteamGridDb, failures)) {
            sgdbInto(draft, draft.name ?: name, SteamGridDbClient.SQUARE + SteamGridDbClient.PORTRAIT, failures)
        }
    }

    private suspend fun igdbInto(
        draft: Draft,
        name: String,
        platforms: List<Int>?,
        failures: MutableMap<Service, FailureKind>,
        minSimilarity: Double = 0.75,
    ) {
        try {
            val best = igdb.search(name, platforms)
                .map { it to Names.similarity(name, it.name) }
                .filter { it.second >= minSimilarity }
                .maxByOrNull { it.second }?.first ?: return
            draft.sources += Service.Igdb.id
            draft.igdbId = best.id
            if (draft.name == null) draft.name = best.name
            if (draft.description == null) draft.description = best.summary
            if (draft.releaseDate == null) {
                draft.releaseDate = best.firstRelease?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
            }
            if (draft.genre == null) draft.genre = best.genres.take(3).joinToString(", ").ifEmpty { null }
            if (draft.developer == null) draft.developer = best.developers.firstOrNull()
            if (draft.publisher == null) draft.publisher = best.publishers.firstOrNull()
            if (draft.players == null) draft.players = best.modes.joinToString(", ").ifEmpty { null }
            if (draft.rating == null) draft.rating = best.rating?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }
            if (draft.coverUrl == null) best.coverId?.let { draft.coverUrl = IgdbClient.imageUrl(it, "cover_big_2x") }
            if (draft.heroUrl == null) {
                (best.artworkIds.firstOrNull()?.let { IgdbClient.imageUrl(it, "1080p") }
                    ?: best.screenshotIds.firstOrNull()?.let { IgdbClient.imageUrl(it, "screenshot_huge") })
                    ?.let { draft.heroUrl = it }
            }
            if (draft.screenshotUrl == null) best.screenshotIds.firstOrNull()?.let { draft.screenshotUrl = IgdbClient.imageUrl(it, "screenshot_big") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(Service.Igdb, e, failures)
        }
    }

    private suspend fun sgdbInto(draft: Draft, name: String, gridDimensions: List<String>, failures: MutableMap<Service, FailureKind>) {
        try {
            val game = steamGridDb.search(name).take(8)
                .map { it to Names.similarity(name, it.name) }
                .filter { it.second >= 0.8 }
                .maxByOrNull { it.second }?.first ?: return
            draft.sources += Service.SteamGridDb.id
            draft.sgdbId = game.id
            if (draft.coverUrl == null) steamGridDb.grids(game.id, gridDimensions).firstOrNull()?.let { draft.coverUrl = it.url }
            if (draft.heroUrl == null) steamGridDb.heroes(game.id).firstOrNull()?.let { draft.heroUrl = it.url }
            if (draft.logoUrl == null) steamGridDb.logos(game.id).firstOrNull()?.let { draft.logoUrl = it.url }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(Service.SteamGridDb, e, failures)
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

    /** Guarda lo encontrado. Devuelve true si algún servicio reconoció el juego. */
    private suspend fun save(key: String, d: Draft, force: Boolean): Boolean {
        // Lo elegido a mano en "Personalizar…" no se vuelve a descargar (la descarga borraría el archivo).
        val pinned = (repo.romByKey(key)?.meta ?: repo.appByKey(key)?.meta)?.pinned.orEmpty()
        // Un juego Android no usa carátula (se representa con su icono): no se baja.
        val isApp = key.startsWith("a:")
        val cover = d.coverUrl?.takeIf { !isApp && "cover" !in pinned }?.let { media.download(it, key, "cover") }
        val hero = d.heroUrl?.takeIf { "hero" !in pinned }?.let { media.download(it, key, "hero") }
        val logo = d.logoUrl?.takeIf { "logo" !in pinned }?.let { media.download(it, key, "logo") }
        val shot = d.screenshotUrl?.let { media.download(it, key, "shot") }
        val matched = d.sources.isNotEmpty()
        repo.updateMeta(key) { old ->
            GameMeta(
                scrapedAt = System.currentTimeMillis(),
                matched = matched || old.matched,
                sources = if (matched) d.sources.toList() else old.sources,
                name = d.name ?: old.name,
                description = d.description ?: old.description,
                releaseDate = d.releaseDate ?: old.releaseDate,
                developer = d.developer ?: old.developer,
                publisher = d.publisher ?: old.publisher,
                genre = d.genre ?: old.genre,
                players = d.players ?: old.players,
                rating = d.rating ?: old.rating,
                cover = cover ?: old.cover,
                hero = hero ?: old.hero,
                logo = logo ?: old.logo,
                screenshot = shot ?: old.screenshot,
                icon = old.icon,
                pinned = old.pinned,
                ssGameId = d.ssId ?: old.ssGameId,
                igdbId = d.igdbId ?: old.igdbId,
                sgdbId = d.sgdbId ?: old.sgdbId,
                ra = d.ra ?: old.ra,
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
