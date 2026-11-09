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
import com.elyndra.launcher.domain.ports.PortArt
import com.elyndra.launcher.domain.ports.PortAssetPicker
import com.elyndra.launcher.domain.ports.PortAvailability
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.PortGrouping
import com.elyndra.launcher.domain.ports.PlatformGroup
import com.elyndra.launcher.domain.ports.PortLookup
import com.elyndra.launcher.domain.ports.PortRelease
import com.elyndra.launcher.domain.ports.PortStatus
import com.elyndra.launcher.domain.ports.PortUpdates
import com.elyndra.launcher.domain.ports.RepoInfo
import com.elyndra.launcher.domain.ports.RomPortMatch
import com.elyndra.launcher.domain.ports.StoreFilter
import com.elyndra.launcher.domain.ports.StoreRow
import com.elyndra.launcher.domain.ports.StoreRows
import com.elyndra.launcher.domain.ports.StoreSort
import com.elyndra.launcher.ports.GitHubClient
import com.elyndra.launcher.domain.ports.OriginalArt
import com.elyndra.launcher.ports.PortArtResolver
import com.elyndra.launcher.ports.PortCatalogRepository
import com.elyndra.launcher.ports.VrDevice
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

    private val isVrHeadset: Boolean by lazy { VrDevice.isHeadset(vm.app.packageManager) }
    var loading by mutableStateOf(false); private set
    var source by mutableStateOf<PortCatalogRepository.Source?>(null); private set

    /** Por qué se enseña el catálogo del APK (null: es el de la red). */
    var fallback by mutableStateOf<PortCatalogRepository.Fallback?>(null); private set

    var filter by mutableStateOf(StoreFilter()); private set

    /** El campo de búsqueda abierto (con el mando, el teclado en pantalla). */
    var searchOpen by mutableStateOf(false); private set

    /** El port elegido en la ficha abierta (la ficha es del juego; el selector cambia de port), o null en la portada. */
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
            // Lo que se puede instalar aquí: los de gafas de RV solo en unas gafas, y los
            // "aún no en Android" a los que la comprobación diaria les encontró un APK.
            catalog = PortAvailability.effective(loaded.catalog.ports, isVrHeadset, vm.portInstall.availability())
            source = loaded.source
            fallback = loaded.fallback
            loading = false
            // Los ports instalados con la primera versión no guardaban su sistema: con el catálogo ya se sabe.
            vm.portInstall.place()
            if (force) releases.clear()
        }
    }

    /* ── filas y estado de cada port ──────────────────────────── */

    fun owned(): List<RomPortMatch> = PortLookup.becauseYouHave(vm.library, catalog)

    fun ownedRom(port: PortEntry): RomPortMatch? = PortLookup.owned(port, vm.library)

    /** El port del catálogo para esta ROM (el que mejor casa), si lo hay. Los de solo PC no cuentan. */
    fun portFor(rom: RomEntry): PortEntry? = PortLookup.portsFor(rom, catalog.filter { it.installable }).firstOrNull()?.port

    /** La fecha de la última release ya leída de cada port (manda sobre la del catálogo). */
    private fun published(): Map<String, Long> =
        releases.mapNotNull { (id, r) -> (r as? Releases.Ready)?.latest?.release?.publishedAt?.let { id to it } }.toMap()

    fun rows(): List<StoreRow> = StoreRows.home(catalog, filter, owned(), installed.keys, published(), updateIds())

    /**
     * Los ports instalados con una release más nueva: la ya leída en la
     * tienda o la que vio la comprobación diaria (PortUpdateWorker).
     */
    fun updateIds(): Set<String> =
        catalog.filter { it.id in installed && action(it) == PortAction.Update }.mapTo(HashSet()) { it.id } +
            PortUpdates.known(installed.values.toList()).map { it.portId }

    /** Las releases de [port], esperando a tenerlas (para "Actualizar todo"). */
    suspend fun loadReleases(port: PortEntry) {
        if (releases[port.id] !is Releases.Ready) ensureReleases(port, prefetch = false, force = true)
        while (releases[port.id] == Releases.Loading) kotlinx.coroutines.delay(100)
    }

    /** El sistema original del port instalado como [packageName], si el catálogo ya está leído (ver MetadataEngine.portSystem). */
    fun systemForPackage(packageName: String): String? {
        val id = installed.values.firstOrNull { it.packageName == packageName }?.portId ?: return null
        return catalog.firstOrNull { it.id == id }?.platform
    }

    /** Los ports del mismo juego que [port], en el orden del selector de la ficha. */
    fun siblings(port: PortEntry): List<PortEntry> = PortGrouping.siblings(port, catalog.filter { it.installable }, published())

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
        // El del catálogo ya está (sin red) mientras se resuelve lo demás.
        return (originalArt[port.id] ?: PortArt.fromCatalog(port)).let { it.hero ?: it.cover }
    }

    /** La carátula del juego original (para la ficha). */
    fun coverArt(port: PortEntry): Any? {
        ownedRom(port)?.rom?.meta?.cover?.let { return File(vm.app.filesDir, it) }
        ensureArt(port)
        return (originalArt[port.id] ?: PortArt.fromCatalog(port)).cover
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
                Releases.Ready(all, port.android?.let { PortAssetPicker.latest(all, it, deviceAbis, allowDebug = port.trust.debugBuild) })
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

    /** El selector de la ficha: otro port del mismo juego (el botón grande pasa a actuar sobre él). */
    fun selectPort(id: String) {
        if (id != detailId) openDetail(id)
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

    /** El chip "Todos": quita familias, géneros y "aún no en Android" (la búsqueda sigue). */
    fun showAll() {
        filter = filter.copy(groups = emptySet(), genres = emptySet(), notOnAndroid = false, platform = null)
    }

    /** Solo los ports que irían a una carpeta de [systemId] (desde "Añadir juego" en la carpeta). */
    fun filterPlatform(systemId: String) {
        detailId = null
        filter = StoreFilter(sort = filter.sort, platform = systemId)
    }

    fun toggleGroup(group: PlatformGroup) {
        filter = filter.copy(groups = filter.groups.toggled(group))
    }

    fun toggleGenre(genre: String) {
        filter = filter.copy(genres = filter.genres.toggled(genre))
    }

    fun toggleNotOnAndroid() {
        filter = filter.copy(notOnAndroid = !filter.notOnAndroid)
    }

    fun setSort(sort: StoreSort) {
        filter = filter.copy(sort = sort)
    }

    /** L1/R1 en la tienda: de familia en familia (Todos → Nintendo → … → PC → Todos). */
    fun cycleGroup(delta: Int) {
        filter = filter.copy(groups = StoreRows.cycleGroup(filter.groups, StoreRows.groupChips(catalog), delta))
    }

    /** "Atrás" con la búsqueda o los filtros puestos: los quita (ver [BackPriority]). */
    fun clearFilters() {
        searchOpen = false
        filter = filter.cleared()
    }

    val hasFilters: Boolean get() = searchOpen || filter.active

    private fun <T> Set<T>.toggled(v: T): Set<T> = if (v in this) this - v else this + v
}
