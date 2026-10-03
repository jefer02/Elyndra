package com.elyndra.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.MatchMethod
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.library.NameCheck
import com.elyndra.launcher.library.Names
import com.elyndra.launcher.metadata.IgdbClient
import com.elyndra.launcher.metadata.LibretroNames
import com.elyndra.launcher.metadata.ScreenScraperClient
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.metadata.SteamParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

/** Un resultado del buscador de "Identificar juego". [id] es el del juego en su fuente. */
data class NameMatch(
    val title: String,
    val year: String?,
    val platform: String?,
    val thumb: String?,
    val source: Service,
    val id: String,
)

/** El diálogo abierto: de qué juego, qué hay escrito y qué ha encontrado. */
data class IdentifyState(val key: String, val rawName: String, val locked: Boolean)

/**
 * "Editar nombre / Identificar juego".
 *
 * El nombre que pone el usuario manda (GameMeta.userName + nameLocked): las
 * pasadas de metadatos no lo tocan y es con el que se busca el juego. Si elige
 * un resultado del buscador, se guarda además su id en esa fuente, para que
 * las pasadas siguientes no tengan que adivinar. Al guardar se vuelven a pedir
 * los metadatos de ese juego, solo de ese, y la interfaz se refresca sola.
 */
class IdentifyController(private val vm: ElyndraViewModel) {

    var state by mutableStateOf<IdentifyState?>(null); private set
    var query by mutableStateOf(""); private set
    val results = mutableStateListOf<NameMatch>()
    var searching by mutableStateOf(false); private set

    private var job: Job? = null

    /** Lo que tiene un juego que se puede identificar (ROM o app). */
    private fun current(key: String): Triple<String, String, GameMeta>? {
        vm.library.roms.firstOrNull { it.key == key }?.let { return Triple(it.displayTitle, it.fileName, it.meta) }
        vm.library.apps.firstOrNull { it.key == key }?.let { return Triple(it.displayTitle, it.label.ifBlank { it.packageName }, it.meta) }
        return null
    }

    fun open(key: String) {
        val (shown, raw, meta) = current(key) ?: return
        vm.dismissSheet()
        state = IdentifyState(key, raw, meta.nameLocked)
        // Propuesta: lo que se enseña si ya sirve; si no, el crudo limpio.
        query = if (NameCheck.isNameUsable(shown)) shown else NameCheck.guess(raw)
        search(immediate = true)
    }

    fun close() {
        job?.cancel()
        state = null
        results.clear()
        searching = false
    }

    fun updateQuery(text: String) {
        query = text
        search(immediate = false)
    }

    /** Busca en las fuentes que pueden identificar este juego, con una pausa mientras se escribe. */
    private fun search(immediate: Boolean) {
        val s = state ?: return
        job?.cancel()
        val q = query.trim()
        results.clear()
        if (q.length < 2) {
            searching = false
            return
        }
        job = vm.viewModelScope.launch {
            if (!immediate) delay(SEARCH_DEBOUNCE_MS)
            searching = true
            try {
                for (source in sources(s.key)) {
                    val found = runCatching { find(source, s.key, q) }.getOrElse { if (it is CancellationException) throw it else emptyList() }
                    results.addAll(found.filter { r -> results.none { it.source == r.source && it.id == r.id } })
                }
            } finally {
                searching = false
            }
        }
    }

    private fun sources(key: String): List<Service> {
        val creds = vm.app.credentials
        val rom = vm.library.roms.firstOrNull { it.key == key }
        val system = rom?.let { Systems.byId(it.systemId) }
        return buildList {
            if (rom != null && system?.ssId != null && creds.isConfigured(Service.ScreenScraper)) add(Service.ScreenScraper)
            if (creds.isConfigured(Service.Igdb)) add(Service.Igdb)
            if (rom != null && LibretroNames.folderFor(rom.systemId) != null && creds.isConfigured(Service.Libretro)) add(Service.Libretro)
            if ((rom == null || rom.systemId == "pc") && creds.isConfigured(Service.Steam)) add(Service.Steam)
        }
    }

    private suspend fun find(source: Service, key: String, q: String): List<NameMatch> {
        val engine = vm.engine
        val rom = vm.library.roms.firstOrNull { it.key == key }
        val system = rom?.let { Systems.byId(it.systemId) }
        return when (source) {
            Service.Steam -> engine.steam.search(q, vm.settings.lang)
                .filter { SteamParser.bestMatch(it.name, listOf(it)) != null }
                .take(MAX_PER_SOURCE)
                .map { NameMatch(it.name, null, "PC · Steam", it.image ?: SteamParser.header(it.id), Service.Steam, it.id.toString()) }
            Service.Libretro -> {
                val folder = system?.let { LibretroNames.folderFor(it.id) } ?: return emptyList()
                LibretroNames.candidates(q, engine.libretro.names(folder), MAX_PER_SOURCE).map { n ->
                    NameMatch(n, null, system.name, engine.libretro.art(folder, n).boxart, Service.Libretro, n)
                }
            }
            Service.Igdb -> engine.igdb.search(q, system?.igdbIds?.ifEmpty { null }).take(MAX_PER_SOURCE).map { g ->
                NameMatch(
                    g.name,
                    g.firstRelease?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).year.toString() },
                    system?.name,
                    g.coverId?.let { IgdbClient.imageUrl(it, "cover_small") },
                    Service.Igdb,
                    g.id.toString(),
                )
            }
            Service.ScreenScraper -> engine.screenScraper.search(system?.ssId, q).take(MAX_PER_SOURCE).map { g ->
                val regions = listOf("wor", "us", "eu", "jp")
                NameMatch(
                    g.name(regions) ?: q,
                    g.releaseDate(regions)?.take(4),
                    system?.name,
                    g.media(listOf("box-2D", "wheel"), regions)?.let { ScreenScraperClient.sizedMediaUrl(it, 120, jpg = true) },
                    Service.ScreenScraper,
                    g.id,
                )
            }
            Service.SteamGridDb, Service.RetroAchievements, Service.GooglePlay -> emptyList()
        }
    }

    /** Guarda el nombre escrito (sin identificar en ninguna fuente). */
    fun useTypedName() {
        val s = state ?: return
        val name = query.trim()
        if (name.isEmpty()) return
        apply(s.key, IdentifyRules.withUserName(meta(s.key) ?: return, name))
    }

    /** Guarda el resultado elegido: su nombre y su id en esa fuente. */
    fun pick(match: NameMatch) {
        val s = state ?: return
        apply(s.key, IdentifyRules.withMatch(meta(s.key) ?: return, match))
    }

    /** "Restablecer automático": fuera el nombre puesto a mano; vuelve el de los metadatos o el del archivo. */
    fun reset() {
        val s = state ?: return
        apply(s.key, IdentifyRules.reset(meta(s.key) ?: return))
    }

    private fun meta(key: String): GameMeta? = current(key)?.third

    private fun apply(key: String, meta: GameMeta) {
        vm.app.library.updateMeta(key) { meta }
        close()
        vm.showToast(UiText.res(R.string.identify_saved))
        // Solo este juego, y con lo nuevo: carátula, icono, fondo, logo y descripción.
        vm.engine.refresh(key)
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 450L
        const val MAX_PER_SOURCE = 5
    }
}

/** Las reglas de un nombre puesto a mano (Kotlin puro: se prueba en la JVM). */
object IdentifyRules {

    /** Lo que se identificó antes ya no vale: el juego puede ser otro. */
    private fun clearIds(m: GameMeta) = m.copy(
        ssGameId = null,
        igdbId = null,
        sgdbId = null,
        steamAppId = null,
        libretroName = null,
        matchedBy = if (m.matchedBy == MatchMethod.MANUAL) null else m.matchedBy,
        matchConfidence = if (m.matchedBy == MatchMethod.MANUAL) null else m.matchConfidence,
    )

    fun withUserName(old: GameMeta, name: String): GameMeta {
        val clean = name.trim()
        // El mismo nombre que ya tenía: solo se fija, sin olvidar la identificación.
        val same = Names.normalize(clean) == Names.normalize(old.lockedName ?: old.name.orEmpty())
        val base = if (same) old else clearIds(old)
        return base.copy(userName = clean, nameLocked = true)
    }

    fun withMatch(old: GameMeta, match: NameMatch): GameMeta {
        val name = if (match.source == Service.Libretro) Names.cleanTitle(match.title, stripExtension = false) else match.title
        val base = clearIds(old).copy(userName = name.trim(), nameLocked = true, matchedBy = MatchMethod.MANUAL, matchConfidence = 1f)
        return when (match.source) {
            Service.Steam -> base.copy(steamAppId = match.id.toLongOrNull())
            Service.Igdb -> base.copy(igdbId = match.id.toLongOrNull())
            Service.ScreenScraper -> base.copy(ssGameId = match.id)
            Service.Libretro -> base.copy(libretroName = match.id)
            Service.SteamGridDb -> base.copy(sgdbId = match.id.toLongOrNull())
            // Play identifica por paquete: no hay nada que elegir a mano.
            Service.RetroAchievements, Service.GooglePlay -> base
        }
    }

    fun reset(old: GameMeta): GameMeta = clearIds(old).copy(userName = null, nameLocked = false)
}
