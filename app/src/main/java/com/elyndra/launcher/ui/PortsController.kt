package com.elyndra.launcher.ui

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.domain.ports.InstalledPort
import com.elyndra.launcher.domain.ports.Installable
import com.elyndra.launcher.domain.ports.PortAction
import com.elyndra.launcher.domain.ports.PortAssetPicker
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.PortLookup
import com.elyndra.launcher.domain.ports.PortRelease
import com.elyndra.launcher.domain.ports.PortStatus
import com.elyndra.launcher.domain.ports.RepoInfo
import com.elyndra.launcher.domain.ports.RomPortMatch
import com.elyndra.launcher.domain.ports.StoreFilter
import com.elyndra.launcher.domain.ports.StoreRow
import com.elyndra.launcher.domain.ports.StoreRows
import com.elyndra.launcher.ports.GitHubClient
import com.elyndra.launcher.ports.OriginalArt
import com.elyndra.launcher.ports.PortArtResolver
import com.elyndra.launcher.ports.PortCatalogRepository
import com.elyndra.launcher.update.UpdateError
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * La tienda de ports: el catálogo, sus filas, las releases de cada port
 * (pedidas solo cuando hacen falta: las cápsulas que se ven y la ficha) y la
 * ficha abierta. La instalación va en [PortInstallController].
 *
 * Es un controlador más del ViewModel (como [UpdateController]) y no un
 * ViewModel propio: la navegación, "atrás" y el mando viven en
 * [ElyndraViewModel], y la tienda tiene que entrar en esos mismos turnos.
 */
class PortsController(
    private val vm: ElyndraViewModel,
    private val catalogRepository: PortCatalogRepository,
    private val github: GitHubClient,
) {
    /** Las releases de un port: aún pidiéndose, ya leídas o fallidas. */
    sealed interface Releases {
        data object Loading : Releases
        data class Ready(val all: List<PortRelease>, val latest: Installable?) : Releases
        data class Failed(val rateLimited: Boolean) : Releases
    }

    var catalog by mutableStateOf<List<PortEntry>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var source by mutableStateOf<PortCatalogRepository.Source?>(null); private set

    var filter by mutableStateOf(StoreFilter(showPcOnly = vm.app.settings.portsShowPcOnly)); private set

    /** El campo de búsqueda abierto (con el mando, el teclado en pantalla). */
    var searchOpen by mutableStateOf(false); private set

    /** El port de la ficha abierta, o null en la portada. */
    var detailId by mutableStateOf<String?>(null); private set

    val releases = mutableStateMapOf<String, Releases>()
    val repoInfo = mutableStateMapOf<String, RepoInfo>()

    /** El arte del juego original (para el destacado y la ficha), por id de port. */
    val originalArt = mutableStateMapOf<String, OriginalArt>()
    private val art = PortArtResolver(vm.engine, vm.app.credentials)

    /** Lo instalado, por id de port (lo rellena [PortInstallController]). */
    var installed by mutableStateOf<Map<String, InstalledPort>>(emptyMap())
        internal set

    private var loadJob: Job? = null
    private val deviceAbis: List<String> by lazy { Build.SUPPORTED_ABIS?.toList().orEmpty() }

    val detail: PortEntry? get() = detailId?.let { id -> catalog.firstOrNull { it.id == id } }

    /** Al entrar en la tienda: el catálogo si aún no está. */
    fun onOpen() {
        if (catalog.isEmpty() && loadJob?.isActive != true) load()
    }

    /** Espera a que el catálogo esté leído (para Masha, que lo consulta sin pasar por la tienda). */
    suspend fun awaitCatalog() {
        loadJob?.join()
    }

    /** Cambió la URL del catálogo: se lee otra vez la próxima vez que se entre. */
    fun invalidate() {
        loadJob?.cancel()
        catalog = emptyList()
        releases.clear()
    }

    fun load(force: Boolean = false) {
        loadJob?.cancel()
        loading = true
        loadJob = vm.viewModelScope.launch {
            val loaded = catalogRepository.load(force)
            catalog = loaded.catalog.ports
            source = loaded.source
            loading = false
            if (force) releases.clear()
        }
    }

    /* ── filas y estado de cada port ──────────────────────────── */

    fun owned(): List<RomPortMatch> = PortLookup.becauseYouHave(vm.library, catalog)

    fun ownedRom(port: PortEntry): RomPortMatch? = PortLookup.owned(port, vm.library)

    /** El port del catálogo para esta ROM (el que mejor casa), si lo hay. Los de solo PC no cuentan. */
    fun portFor(rom: RomEntry): PortEntry? = PortLookup.portsFor(rom, catalog.filter { it.installable }).firstOrNull()?.port

    fun rows(): List<StoreRow> {
        val published = releases.mapNotNull { (id, r) -> (r as? Releases.Ready)?.latest?.release?.publishedAt?.let { id to it } }.toMap()
        return StoreRows.home(catalog, filter, owned(), installed.keys, published)
    }

    /** ¿Alguna petición de releases chocó con el límite de GitHub? (aviso en la portada). */
    val rateLimited: Boolean get() = releases.values.any { it is Releases.Failed && it.rateLimited }

    /**
     * El arte grande del juego original: el de la biblioteca si el juego está
     * (local, ya descargado) o el que encuentre [PortArtResolver]. Null
     * mientras se busca o si no hay: la tienda pinta la imagen del port.
     */
    fun heroArt(port: PortEntry): Any? {
        ownedRom(port)?.rom?.meta?.let { meta ->
            (meta.hero ?: meta.screenshot ?: meta.cover)?.let { return File(vm.app.filesDir, it) }
        }
        ensureArt(port)
        return originalArt[port.id]?.let { it.hero ?: it.cover }
    }

    /** La carátula del juego original (para la ficha). */
    fun coverArt(port: PortEntry): Any? {
        ownedRom(port)?.rom?.meta?.cover?.let { return File(vm.app.filesDir, it) }
        ensureArt(port)
        return originalArt[port.id]?.cover
    }

    /**
     * Se llama al pintar: nada de escribir estado aquí. Se apunta en un
     * conjunto normal y el resultado llega por la corrutina.
     */
    private fun ensureArt(port: PortEntry) {
        if (!artRequested.add(port.id)) return
        vm.viewModelScope.launch { originalArt[port.id] = art.resolve(port) }
    }

    private val artRequested = HashSet<String>()

    /** El arte del original para llevarlo a la biblioteca al instalar (lo busca si aún no estaba). */
    suspend fun originalArtFor(port: PortEntry): OriginalArt = art.resolve(port).also { originalArt[port.id] = it }

    fun latest(port: PortEntry): Installable? = (releases[port.id] as? Releases.Ready)?.latest

    fun action(port: PortEntry): PortAction =
        PortStatus.action(port, installed[port.id], latest(port), releases[port.id] is Releases.Ready)

    /**
     * Pide las releases de [port] si aún no están. Desde la portada
     * ([prefetch]) no se pide nada con el cupo de la API casi agotado: lo
     * guarda para las fichas, que sí lo necesitan.
     */
    fun ensureReleases(port: PortEntry, prefetch: Boolean = true, force: Boolean = false) {
        if (!port.installable) return
        val current = releases[port.id]
        if (!force && (current is Releases.Ready || current == Releases.Loading)) return
        if (prefetch && (github.rateRemaining ?: Int.MAX_VALUE) < GitHubClient.PREFETCH_FLOOR) return
        releases[port.id] = Releases.Loading
        vm.viewModelScope.launch {
            releases[port.id] = try {
                val all = github.releases(port.releaseRepo, force)
                Releases.Ready(all, port.android?.let { PortAssetPicker.latest(all, it, deviceAbis) })
            } catch (e: UpdateError) {
                Releases.Failed(rateLimited = e is UpdateError.RateLimited)
            }
        }
    }

    private fun ensureRepoInfo(port: PortEntry) {
        if (repoInfo.containsKey(port.id)) return
        vm.viewModelScope.launch {
            runCatching { github.repoInfo(port.releaseRepo) }.getOrNull()?.let { repoInfo[port.id] = it }
        }
    }

    /* ── navegación dentro de la tienda ───────────────────────── */

    fun openDetail(id: String) {
        val port = catalog.firstOrNull { it.id == id } ?: return
        detailId = id
        ensureReleases(port, prefetch = false)
        ensureRepoInfo(port)
    }

    fun closeDetail() {
        detailId = null
    }

    /** Abre la tienda en la ficha de [id] (desde la ficha de un juego o desde Masha). */
    fun openPort(id: String) {
        vm.openPorts()
        if (catalog.isEmpty()) {
            vm.viewModelScope.launch {
                loadJob?.join()
                openDetail(id)
            }
        } else {
            openDetail(id)
        }
    }

    fun updateQuery(text: String) {
        filter = filter.copy(query = text)
    }

    fun toggleSearch() {
        searchOpen = !searchOpen
        if (!searchOpen) filter = filter.copy(query = "")
    }

    fun togglePlatform(platform: String) {
        filter = filter.copy(platforms = filter.platforms.toggled(platform))
    }

    fun toggleTag(tag: String) {
        filter = filter.copy(tags = filter.tags.toggled(tag))
    }

    fun togglePcOnly() {
        filter = filter.copy(showPcOnly = !filter.showPcOnly)
        vm.app.settings.portsShowPcOnly = filter.showPcOnly
    }

    /** "Atrás" con la búsqueda o los filtros puestos: los quita (ver [BackPriority]). */
    fun clearFilters() {
        searchOpen = false
        filter = StoreFilter(showPcOnly = filter.showPcOnly)
    }

    val hasFilters: Boolean get() = searchOpen || filter.active

    private fun <T> Set<T>.toggled(v: T): Set<T> = if (v in this) this - v else this + v
}
