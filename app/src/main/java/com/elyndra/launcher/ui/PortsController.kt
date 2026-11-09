package com.elyndra.launcher.ui

import android.os.Build
import androidx.annotation.StringRes
import com.elyndra.launcher.R
import com.elyndra.launcher.domain.ports.GameGroup
import com.elyndra.launcher.domain.ports.PortDisplay
import com.elyndra.launcher.domain.ports.PortFirstSeen
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
            // Cuándo entró cada port (para NUEVO): solo con el catálogo de la red, que es el que crece.
            if (loaded.source == PortCatalogRepository.Source.Remote) noteFirstSeen()
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
        if (!searchOpen) filter = filter.copy(query = "") else selectTab(StoreTab.Browse)
    }

    /** El chip "Todos": quita familias, géneros y "aún no en Android" (la búsqueda sigue). */
    fun showAll() {
        filter = filter.copy(groups = emptySet(), genres = emptySet(), notOnAndroid = false, platform = null)
    }

    /** Solo los ports que irían a una carpeta de [systemId] (desde "Añadir juego" en la carpeta). */
    fun filterPlatform(systemId: String) {
        detailId = null
        filter = StoreFilter(sort = filter.sort, platform = systemId)
        selectTab(StoreTab.Browse)
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

    /* ── pestañas ──────────────────────────────────────────────── */

    /** La pestaña a la vista (Novedades, Colecciones, Actualizaciones, Instalados, Explorar o la lista de deseos). */
    var tab by mutableStateOf(StoreTab.Latest); private set

    /** Hacia dónde fue el último cambio de pestaña (+1 a la derecha): el contenido se desliza desde ahí. */
    var tabDirection by mutableStateOf(1); private set

    fun selectTab(next: StoreTab) {
        if (next == tab) return
        val from = StoreTab.ROW.indexOf(tab).takeIf { it >= 0 } ?: StoreTab.ROW.size
        val to = StoreTab.ROW.indexOf(next).takeIf { it >= 0 } ?: StoreTab.ROW.size
        tabDirection = if (to >= from) 1 else -1
        tab = next
    }

    /** L1/R1 en la tienda: la pestaña de al lado, dando la vuelta. */
    fun cycleTab(delta: Int) {
        selectTab(StoreTab.cycle(tab, delta))
    }

    /** Una colección (familia de consolas): Explorar con ese filtro. */
    fun openCollection(group: PlatformGroup) {
        filter = StoreFilter(sort = filter.sort, groups = setOf(group))
        selectTab(StoreTab.Browse)
    }

    /* ── lista de deseos ──────────────────────────────────────── */

    var wishlist by mutableStateOf(vm.app.settings.portsWishlist); private set

    fun toggleWishlist(id: String) {
        wishlist = wishlist.toggled(id)
        vm.app.settings.portsWishlist = wishlist
    }

    /* ── lo que pinta cada pestaña ────────────────────────────── */

    private fun grouped(ports: List<PortEntry>): List<GameGroup> = PortGrouping.group(ports, installed.keys, published())

    /** Explorar: todo lo que pasa el filtro, en el orden elegido. */
    fun browseGroups(): List<GameGroup> = StoreRows.sorted(grouped(StoreRows.visible(catalog, filter)), filter.sort, published())

    fun updateGroups(): List<GameGroup> {
        val ids = updateIds()
        return grouped(catalog.filter { it.id in ids && it.id in installed })
    }

    fun installedGroups(): List<GameGroup> = grouped(catalog.filter { it.id in installed })

    /* ── NUEVO: cuándo entró cada port en el catálogo ─────────── */

    private var firstSeen: Map<String, Long> = PortFirstSeen.decode(vm.app.settings.portsFirstSeen)

    /** Cuándo apareció [id] en el catálogo (0: desde la primera lectura, o aún no se sabe). */
    fun addedAt(id: String): Long = firstSeen[id] ?: 0L

    private fun noteFirstSeen() {
        val next = PortFirstSeen.update(firstSeen, catalog.map { it.id }, System.currentTimeMillis())
        if (next != firstSeen) {
            firstSeen = next
            vm.app.settings.portsFirstSeen = PortFirstSeen.encode(next)
        }
    }

    /** Colecciones: una por familia de consolas con algún port. */
    fun collections(): List<Pair<PlatformGroup, List<GameGroup>>> =
        StoreRows.groupChips(catalog).map { g -> g to grouped(catalog.filter { StoreRows.group(it.platform) == g }) }

    /* ── hojas: orden y filtro, descargas, utilidades ─────────── */

    /** El botón redondo de orden y filtro: una hoja que se recorre con el mando. */
    fun openSortSheet() {
        val sorts = StoreSort.entries.map { s ->
            SheetAction(UiText.res(sortLabel(s)), selected = filter.sort == s) { setSort(s) }
        }
        val families = listOf(
            SheetAction(UiText.res(R.string.ports_chip_all), selected = filter.groups.isEmpty() && filter.platform == null) { showAll() },
        ) + StoreRows.groupChips(catalog).map { g ->
            SheetAction(UiText.res(groupLabel(g)), selected = g in filter.groups) { toggleGroup(g) }
        }
        val more = StoreRows.genreChips(catalog).map { g ->
            SheetAction(UiText.res(genreLabel(g)), selected = g in filter.genres) { toggleGenre(g) }
        } + listOfNotNull(
            SheetAction(UiText.res(R.string.ports_chip_not_on_android), selected = filter.notOnAndroid) { toggleNotOnAndroid() }
                .takeIf { catalog.any { !it.installable } },
        )
        vm.showSheet(
            ActionSheetSpec(
                UiText.res(R.string.store_sort_filter),
                null,
                listOfNotNull(
                    SheetGroup(UiText.res(R.string.store_sort_by_title), sorts),
                    SheetGroup(UiText.res(R.string.store_filter_by_title), families),
                    more.takeIf { it.isNotEmpty() }?.let { SheetGroup(null, it) },
                ),
            ),
        )
    }

    /** La baldosa de descargas: lo que se baja o se instala, con "Cancelar" en cada uno. */
    fun openDownloads() {
        val tasks = vm.portInstall.tasks.values.toList()
        val actions = if (tasks.isEmpty()) {
            listOf(SheetAction(UiText.res(R.string.store_no_downloads), dimmed = true) {})
        } else {
            tasks.map { t ->
                val port = when (t) {
                    is PortInstallController.Task.Downloading -> t.port
                    is PortInstallController.Task.Installing -> t.port
                }
                val detail = when (t) {
                    is PortInstallController.Task.Downloading -> UiText.Raw("${(t.progress * 100).toInt()} %")
                    is PortInstallController.Task.Installing -> UiText.res(R.string.ports_installing, PortDisplay.title(port))
                }
                SheetAction(UiText.Raw(PortDisplay.title(port)), detail = detail, icon = SheetIcon.Remove) {
                    vm.portInstall.cancel(port.id)
                }
            }
        }
        vm.showSheet(ActionSheetSpec(UiText.res(R.string.ports_downloads), null, actions))
    }

    /**
     * El corazón: la lista de deseos en una hoja (se recorre con el mando y
     * se cierra con Atrás); cada entrada abre la ficha de su port.
     */
    fun openWishlist() {
        val ports = catalog.filter { it.id in wishlist }.sortedBy { PortDisplay.title(it).lowercase() }
        val actions = if (ports.isEmpty()) {
            listOf(SheetAction(UiText.res(R.string.store_empty_wishlist), dimmed = true) {})
        } else {
            ports.map { p ->
                SheetAction(
                    UiText.Raw(PortDisplay.title(p)),
                    detail = UiText.Raw(p.name + " · " + (p.developer ?: p.releaseRepo.owner)),
                    opensSheet = true,
                ) { openDetail(p.id) }
            }
        }
        vm.showSheet(ActionSheetSpec(UiText.res(R.string.store_wishlist), null, actions))
    }

    /** Y en la tienda (y "Más"): recargar el catálogo, actualizar todo, quitar los filtros, la lista de deseos. */
    fun openUtilities() {
        val updates = updateGroups().map { it.lead }
        val actions = listOfNotNull(
            SheetAction(UiText.res(R.string.store_refresh_catalog), icon = SheetIcon.Refresh) { load(force = true) },
            SheetAction(UiText.res(R.string.ports_update_all), icon = SheetIcon.Refresh) { vm.portInstall.updateAll(updates) }
                .takeIf { updates.isNotEmpty() },
            SheetAction(UiText.res(R.string.store_clear_filters), icon = SheetIcon.Remove) { clearFilters() }
                .takeIf { hasFilters },
            SheetAction(UiText.res(R.string.store_wishlist), icon = SheetIcon.Details, opensSheet = true) { openWishlist() },
        )
        vm.showSheet(ActionSheetSpec(UiText.res(R.string.store_utilities), null, actions))
    }

    /** "Atrás" con la búsqueda o los filtros puestos: los quita (ver [BackPriority]). */
    fun clearFilters() {
        searchOpen = false
        filter = filter.cleared()
    }

    val hasFilters: Boolean get() = searchOpen || filter.active

    private fun <T> Set<T>.toggled(v: T): Set<T> = if (v in this) this - v else this + v

    companion object {
        @StringRes
        fun sortLabel(s: StoreSort): Int = when (s) {
            StoreSort.Name -> R.string.ports_sort_name
            StoreSort.RecentlyUpdated -> R.string.ports_sort_recent
            StoreSort.Trust -> R.string.ports_sort_trust
            StoreSort.Stars -> R.string.ports_sort_stars
        }

        @StringRes
        fun groupLabel(g: PlatformGroup): Int = when (g) {
            PlatformGroup.Nintendo -> R.string.ports_row_nintendo
            PlatformGroup.PlayStation -> R.string.ports_row_playstation
            PlatformGroup.Sega -> R.string.ports_row_sega
            PlatformGroup.Xbox -> R.string.ports_row_xbox
            PlatformGroup.Pc -> R.string.ports_row_pc
        }

        @StringRes
        fun genreLabel(g: String): Int = when (g) {
            "racing" -> R.string.ports_genre_racing
            "rpg" -> R.string.ports_genre_rpg
            "shooter" -> R.string.ports_genre_shooter
            "strategy" -> R.string.ports_genre_strategy
            else -> R.string.ports_genre_platformer
        }
    }
}
