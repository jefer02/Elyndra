package com.elyndra.launcher.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.ElyndraApplication
import com.elyndra.launcher.R
import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.PAIRS
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.data.pairIndexFor
import com.elyndra.launcher.launch.GameLauncher
import com.elyndra.launcher.metadata.ArtCandidate
import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.metadata.ArtSources
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.ui.screens.serviceName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Estado de la app. La biblioteca vive en [LibraryRepository] (persistida);
 * aquí se proyecta para la UI y se orquestan navegación, lanzamientos y
 * avisos. Añadir, Ajustes y Lucy tienen su propio controlador.
 */
class ElyndraViewModel(application: Application) : AndroidViewModel(application) {

    val app = application as ElyndraApplication
    private val repo = app.library
    private val launcher = app.launcher
    val engine = app.metadata

    /* ── navegación y selección ───────────────────────────────── */
    var screen by mutableStateOf(Screen.Library); private set
    var filter by mutableStateOf(LibraryFilter.All); private set
    var query by mutableStateOf(""); private set
    var searchOpen by mutableStateOf(false); private set
    var selectedKey by mutableStateOf<String?>(null); private set
    var folderId by mutableStateOf<String?>(null); private set
    var selectedRomKey by mutableStateOf<String?>(null); private set
    var launching by mutableStateOf<Launch?>(null); private set

    /* ── datos ────────────────────────────────────────────────── */
    var library by mutableStateOf(repo.current); private set
    var loaded by mutableStateOf(false); private set
    var installedPackages by mutableStateOf<Set<String>>(emptySet()); private set
    var metaProgress by mutableStateOf(engine.progress.value); private set

    /* ── capas superpuestas ───────────────────────────────────── */
    var dialog by mutableStateOf<DialogSpec?>(null); private set
    var sheet by mutableStateOf<ActionSheetSpec?>(null); private set
    var detailsKey by mutableStateOf<String?>(null); private set
    var achievements by mutableStateOf<AchievementsState>(AchievementsState.Idle); private set
    var toast by mutableStateOf<UiText?>(null); private set
    var artPicker by mutableStateOf<ArtPickerState?>(null); private set

    private val art = ArtSources(engine, repo, app.media)
    private var artJob: Job? = null

    val add = AddController(this)
    val settings = SettingsController(this)
    val lucy = LucyController(this)

    private var launchJob: Job? = null
    private var toastJob: Job? = null
    private val labelCache = HashMap<String, String>()

    init {
        viewModelScope.launch { repo.state.collect { library = it } }
        viewModelScope.launch { engine.progress.collect { metaProgress = it } }
        viewModelScope.launch {
            repo.awaitLoaded()
            loaded = true
            refreshInstalled()
        }
    }

    /* ── proyección de la biblioteca ──────────────────────────── */

    private class Derived(val lib: Library, val installed: Set<String>, val folders: List<LibraryItem.Folder>, val roms: Map<String, List<RomEntry>>)

    private var derivedCache: Derived? = null

    private fun derived(): Derived {
        val lib = library
        val inst = installedPackages
        derivedCache?.takeIf { it.lib === lib && it.installed === inst }?.let { return it }
        val roms = lib.roms.groupBy { it.folderId }.mapValues { (_, list) ->
            list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle })
        }
        val folders = lib.folders.mapNotNull { f ->
            val system = Systems.byId(f.systemId) ?: return@mapNotNull null
            val list = roms[f.id].orEmpty()
            // El fondo elegido a mano para la carpeta manda sobre el heredado.
            val hero = f.hero ?: list.filter { it.meta.hero != null || it.meta.screenshot != null }
                .maxByOrNull { it.stats.lastPlayed }
                ?.let { it.meta.hero ?: it.meta.screenshot }
            LibraryItem.Folder(
                folder = f,
                system = system,
                romCount = list.size,
                emulatorName = f.emulatorId?.let { emulatorName(it) },
                emulatorInstalled = isEmulatorInstalled(f.emulatorId),
                minutes = list.sumOf { it.stats.minutes },
                heroPath = hero,
                coverPath = f.cover,
                logoPath = f.logo,
                iconPath = f.icon,
                emulatorPackage = emulatorPackage(f.emulatorId),
            )
        }
        return Derived(lib, inst, folders, roms).also { derivedCache = it }
    }

    /** Carpetas y apps mezcladas por nombre, con filtro y búsqueda (también dentro de las carpetas). */
    fun items(): List<LibraryItem> {
        val d = derived()
        val q = query.trim().lowercase()
        var list: List<LibraryItem> = d.folders + library.apps.map {
            LibraryItem.App(it, installedPackages.isEmpty() || it.packageName in installedPackages)
        }
        list = sorted(list)
        if (filter == LibraryFilter.Android) list = list.filterIsInstance<LibraryItem.App>()
        if (filter == LibraryFilter.Consoles) list = list.filterIsInstance<LibraryItem.Folder>()
        if (q.isNotEmpty()) {
            list = list.filter { item ->
                item.name.lowercase().contains(q) ||
                    (item is LibraryItem.Folder && d.roms[item.folder.id].orEmpty().any { it.displayTitle.lowercase().contains(q) })
            }
        }
        return list
    }

    /** Orden elegido en Ajustes. A igualdad, siempre alfabético, para que la lista no baile. */
    private fun sorted(list: List<LibraryItem>): List<LibraryItem> {
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER, LibraryItem::name)
        return when (settings.sortMode) {
            SortMode.Name -> list.sortedWith(byName)
            SortMode.PlayTime -> list.sortedWith(
                compareByDescending<LibraryItem> { minutesOf(it) }.then(byName),
            )
            SortMode.Platform -> list.sortedWith(
                // Dos parámetros de tipo: el del elemento y el de la clave que se compara.
                compareBy<LibraryItem, String>(String.CASE_INSENSITIVE_ORDER) { platformOf(it) }.then(byName),
            )
            SortMode.DateAdded -> list.sortedWith(
                compareByDescending<LibraryItem> { addedAtOf(it) }.then(byName),
            )
        }
    }

    private fun minutesOf(item: LibraryItem): Int = when (item) {
        is LibraryItem.Folder -> item.minutes
        is LibraryItem.App -> item.app.stats.minutes
    }

    private fun addedAtOf(item: LibraryItem): Long = when (item) {
        is LibraryItem.Folder -> item.folder.addedAt
        is LibraryItem.App -> item.app.addedAt
    }

    private fun platformOf(item: LibraryItem): String = when (item) {
        is LibraryItem.Folder -> item.system.name
        is LibraryItem.App -> "Android"
    }

    /** Hoja de "Ordenar por" del carrusel. */
    fun sortOptions() {
        showSheet(
            ActionSheetSpec(
                UiText.res(R.string.sort_by),
                null,
                SortMode.entries.map { mode ->
                    SheetAction(UiText.res(mode.label), selected = settings.sortMode == mode) {
                        settings.setSort(mode)
                    }
                },
            ),
        )
    }

    fun selected(): LibraryItem? {
        val all = items()
        return all.firstOrNull { it.key == selectedKey } ?: all.firstOrNull()
    }

    fun pairIndexOf(item: LibraryItem): Int = when (item) {
        is LibraryItem.Folder -> item.system.pair
        is LibraryItem.App -> pairIndexFor(item.app.packageName)
    }

    fun romPairIndex(rom: RomEntry): Int {
        val base = Systems.byId(rom.systemId)?.pair ?: 0
        return (base + pairIndexFor(rom.title)) % PAIRS.size
    }

    fun currentFolder(): LibraryItem.Folder? = derived().folders.firstOrNull { it.folder.id == folderId }

    fun folderRoms(id: String?): List<RomEntry> = id?.let { derived().roms[it] }.orEmpty()

    fun selectedRom(): RomEntry? {
        val roms = folderRoms(folderId)
        return roms.firstOrNull { it.key == selectedRomKey } ?: roms.firstOrNull()
    }

    /* ── emuladores ───────────────────────────────────────────── */

    fun emulatorName(id: String): String {
        if (id.startsWith(Emulators.CUSTOM_PREFIX)) {
            val pkg = id.removePrefix(Emulators.CUSTOM_PREFIX)
            return labelCache.getOrPut(pkg) { app.apps.label(pkg) ?: pkg }
        }
        return Emulators.byId(id)?.name ?: id
    }

    /** Paquete instalado del emulador, para sacar su icono de launcher. */
    fun emulatorPackage(id: String?): String? {
        if (id == null) return null
        if (id.startsWith(Emulators.CUSTOM_PREFIX)) {
            return id.removePrefix(Emulators.CUSTOM_PREFIX).takeIf { it in installedPackages }
        }
        val profile = Emulators.byId(id) ?: return null
        return profile.packages.firstOrNull { it in installedPackages }
    }

    fun isEmulatorInstalled(id: String?): Boolean {
        if (id == null) return false
        if (id.startsWith(Emulators.CUSTOM_PREFIX)) return id.removePrefix(Emulators.CUSTOM_PREFIX) in installedPackages
        val profile = Emulators.byId(id) ?: return false
        return profile.packages.any { it in installedPackages }
    }

    /** Emuladores del sistema, los instalados primero. */
    fun emulatorOptions(systemId: String?): List<EmulatorOption> {
        val system = Systems.byId(systemId) ?: return emptyList()
        return system.emulators.mapNotNull { Emulators.byId(it) }
            .map { EmulatorOption(it.id, it.name, it.packages.any { p -> p in installedPackages }) }
            .sortedByDescending { it.installed }
    }

    /** Primer emulador instalado del sistema; si no hay ninguno, el recomendado. */
    fun defaultEmulator(systemId: String?): String? {
        val system = Systems.byId(systemId) ?: return null
        return system.emulators.firstOrNull { isEmulatorInstalled(it) } ?: system.emulators.firstOrNull()
    }

    suspend fun refreshInstalled() {
        val pkgs = withContext(Dispatchers.IO) { app.apps.launchable().map { it.packageName }.toSet() }
        installedPackages = pkgs
    }

    /* ── navegación ───────────────────────────────────────────── */

    fun go(target: Screen) {
        if (target == Screen.Add) add.onOpen()
        if (target == Screen.Settings) settings.onOpen()
        screen = target
    }

    val canGoBack: Boolean
        get() = dialog != null || sheet != null || artPicker != null || detailsKey != null || screen != Screen.Library || searchOpen

    fun back() {
        when {
            dialog != null -> dialog = null
            sheet != null -> sheet = null
            artPicker != null -> closeArtPicker()
            detailsKey != null -> closeDetails()
            screen != Screen.Library -> go(Screen.Library)
            searchOpen -> toggleSearch()
        }
    }

    fun select(key: String) { selectedKey = key }

    fun selectRom(key: String) { selectedRomKey = key }

    fun updateFilter(f: LibraryFilter) { filter = f }

    /** Con el buscador cerrado se ignora: el campo sigue compuesto (ancho 0) y podría recibir teclas tardías. */
    fun updateQuery(q: String) { if (searchOpen) query = q }

    fun toggleSearch() {
        if (searchOpen) query = ""
        searchOpen = !searchOpen
    }

    fun open(item: LibraryItem) {
        when (item) {
            is LibraryItem.Folder -> {
                folderId = item.folder.id
                selectedRomKey = null
                screen = Screen.Folder
            }
            is LibraryItem.App -> launchApp(item)
        }
    }

    /* ── lanzamientos ─────────────────────────────────────────── */

    private fun launchApp(item: LibraryItem.App) {
        val entry = item.app
        // Un juego Android se representa siempre con su icono, no con carátula.
        showLaunch(Launch(entry.displayTitle, UiText.res(R.string.launch_android), pairIndexFor(entry.packageName), null, entry.packageName, entry.meta.icon))
        launchJob?.cancel()
        launchJob = viewModelScope.launch {
            delay(LAUNCH_DELAY_MS)
            when (launcher.launchApp(entry.packageName)) {
                GameLauncher.Outcome.Started -> startSession(entry.key)
                else -> {
                    launching = null
                    showDialog(
                        DialogSpec(
                            title = UiText.res(R.string.dialog_app_missing_title),
                            message = UiText.res(R.string.dialog_app_missing_msg, entry.displayTitle),
                            confirm = DialogButton(UiText.res(R.string.remove)) { repo.removeApp(entry.packageName) },
                            dismiss = DialogButton(UiText.res(R.string.close)) {},
                        ),
                    )
                }
            }
        }
    }

    fun openRom(rom: RomEntry) {
        val folder = repo.folder(rom.folderId) ?: return
        val emuId = rom.emulatorId ?: folder.emulatorId ?: defaultEmulator(folder.systemId)
        if (emuId == null) {
            showDialog(
                DialogSpec(
                    title = UiText.res(R.string.dialog_no_emulator_title),
                    message = UiText.res(R.string.dialog_no_emulator_msg),
                    confirm = DialogButton(UiText.res(R.string.change_emulator)) { pickFolderEmulator(folder) },
                    dismiss = DialogButton(UiText.res(R.string.close)) {},
                ),
            )
            return
        }
        if (!app.files.hasPermission(folder.treeUri)) {
            showDialog(
                DialogSpec(
                    title = UiText.res(R.string.dialog_permission_lost_title),
                    message = UiText.res(R.string.dialog_permission_lost_msg, folder.displayPath),
                    confirm = DialogButton(UiText.res(R.string.ok)) {},
                ),
            )
            return
        }
        val emuName = emulatorName(emuId)
        if (!launcher.isEmulatorInstalled(emuId)) {
            emulatorMissing(emuId, emuName, folder, rom)
            return
        }
        showLaunch(Launch(rom.displayTitle, UiText.res(R.string.launch_via, emuName.uppercase()), romPairIndex(rom), rom.meta.cover, iconPath = rom.meta.icon))
        launchJob?.cancel()
        launchJob = viewModelScope.launch {
            val vitaTitle = if (folder.systemId == "psvita") {
                withContext(Dispatchers.IO) {
                    app.scanner.readSmallText(Uri.parse(folder.treeUri), rom.docId, 256)?.lineSequence()?.firstOrNull()?.trim()
                }
            } else null
            delay(LAUNCH_DELAY_MS)
            val outcome = launcher.launchRom(emuId, launcher.romRef(folder, rom, vitaTitle))
            if (outcome == GameLauncher.Outcome.Started) {
                startSession(rom.key)
                return@launch
            }
            launching = null
            when (outcome) {
                GameLauncher.Outcome.NotInstalled -> emulatorMissing(emuId, emuName, folder, rom)
                GameLauncher.Outcome.NeedsPath -> showDialog(
                    DialogSpec(
                        title = UiText.res(R.string.dialog_needs_path_title),
                        message = UiText.res(R.string.dialog_needs_path_msg, emuName),
                        confirm = DialogButton(UiText.res(R.string.choose_other)) { pickFolderEmulator(folder) },
                        dismiss = DialogButton(UiText.res(R.string.close)) {},
                    ),
                )
                GameLauncher.Outcome.NeedsVitaTitle -> showDialog(
                    DialogSpec(
                        title = UiText.res(R.string.dialog_vita_title),
                        message = UiText.res(R.string.dialog_vita_msg),
                        confirm = DialogButton(UiText.res(R.string.ok)) {},
                    ),
                )
                is GameLauncher.Outcome.Failed -> showDialog(
                    DialogSpec(
                        title = UiText.res(R.string.dialog_launch_failed_title),
                        message = UiText.res(R.string.dialog_launch_failed_msg, emuName, outcome.reason),
                        confirm = DialogButton(UiText.res(R.string.choose_other)) { pickFolderEmulator(folder) },
                        dismiss = DialogButton(UiText.res(R.string.close)) {},
                    ),
                )
                GameLauncher.Outcome.Started -> Unit
            }
        }
    }

    private fun emulatorMissing(emuId: String, emuName: String, folder: RomFolder, rom: RomEntry) {
        val profile = Emulators.byId(emuId)
        showDialog(
            DialogSpec(
                title = UiText.res(R.string.dialog_emulator_missing_title, emuName),
                message = UiText.res(R.string.dialog_emulator_missing_msg),
                confirm = DialogButton(UiText.res(R.string.choose_other)) {
                    if (rom.emulatorId != null) pickRomEmulator(rom) else pickFolderEmulator(folder)
                },
                dismiss = DialogButton(UiText.res(R.string.close)) {},
                extra = profile?.let { p ->
                    DialogButton(UiText.res(R.string.install)) { openExternal(launcher.storeIntent(p)) }
                },
            ),
        )
    }

    private fun showLaunch(l: Launch) {
        launching = l
    }

    private suspend fun startSession(key: String) {
        repo.recordLaunch(key)
        app.settings.pendingSessionKey = key
        app.settings.pendingSessionStart = System.currentTimeMillis()
        delay(4_000)
        launching = null
    }

    fun openExternal(intent: Intent) {
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openUrl(url: String) = openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /* ── ciclo de vida ────────────────────────────────────────── */

    /** La app vuelve a primer plano: cierra la sesión de juego medida y refresca el estado. */
    fun onForeground() {
        settings.refreshLanguage()
        viewModelScope.launch {
            repo.awaitLoaded()
            val key = app.settings.pendingSessionKey
            if (key != null) {
                val start = app.settings.pendingSessionStart
                app.settings.pendingSessionKey = null
                val minutes = ((System.currentTimeMillis() - start) / 60_000L).toInt()
                if (minutes in 1..MAX_SESSION_MINUTES) repo.addPlaytime(key, minutes, start)
            }
            refreshInstalled()
            if (library.folders.isNotEmpty() && System.currentTimeMillis() - library.lastAutoScan > AUTO_RESCAN_MS) {
                repo.markAutoScan(System.currentTimeMillis())
                library.folders.forEach { rescan(it, silent = true) }
            }
        }
    }

    /** La app pasa a segundo plano (normalmente, porque arrancó el juego). */
    fun onBackground() {
        launching = null
        launchJob?.cancel()
    }

    /* ── carpetas ─────────────────────────────────────────────── */

    /** Reescanea una carpeta. En modo silencioso no avisa ni borra nada si la carpeta no responde. */
    suspend fun rescan(folder: RomFolder, silent: Boolean): LibraryRepository.ScanDiff? {
        if (!app.files.hasPermission(folder.treeUri)) return null
        val system = Systems.byId(folder.systemId) ?: return null
        val found = runCatching { app.scanner.scan(Uri.parse(folder.treeUri), folder.rootDocId, system) }.getOrNull() ?: return null
        val before = library.roms.count { it.folderId == folder.id }
        if (found.isEmpty() && before > 0) {
            if (!silent) showToast(UiText.res(R.string.toast_folder_unreachable, folder.displayPath))
            return null
        }
        val diff = repo.mergeScan(folder.id, found)
        if (diff.added.isNotEmpty() && app.settings.autoMeta) engine.start(diff.added, force = false)
        if (!silent) showToast(UiText.res(R.string.toast_rescan, diff.added.size, diff.removed))
        return diff
    }

    fun rescanFolder(folder: RomFolder) {
        viewModelScope.launch { rescan(folder, silent = false) }
    }

    fun removeFolder(folder: RomFolder) {
        val count = library.roms.count { it.folderId == folder.id }
        showDialog(
            DialogSpec(
                title = UiText.res(R.string.confirm_remove_title),
                message = UiText.plural(R.plurals.confirm_remove_folder_msg, count, count),
                confirm = DialogButton(UiText.res(R.string.remove)) {
                    library.roms.filter { it.folderId == folder.id }.forEach { app.media.deleteFor(it.key) }
                    repo.removeFolder(folder.id)
                    if (library.folders.none { it.treeUri == folder.treeUri }) app.files.releasePermission(folder.treeUri)
                    if (folderId == folder.id) screen = Screen.Library
                },
                dismiss = DialogButton(UiText.res(R.string.close)) {},
            ),
        )
    }

    fun removeApp(pkg: String, title: String) {
        showDialog(
            DialogSpec(
                title = UiText.res(R.string.confirm_remove_title),
                message = UiText.res(R.string.confirm_remove_app_msg, title),
                confirm = DialogButton(UiText.res(R.string.remove)) {
                    app.media.deleteFor("a:$pkg")
                    repo.removeApp(pkg)
                },
                dismiss = DialogButton(UiText.res(R.string.close)) {},
            ),
        )
    }

    /* ── menús de pulsación larga ─────────────────────────────── */

    fun itemOptions(item: LibraryItem) {
        val actions = when (item) {
            is LibraryItem.Folder -> listOf(
                SheetAction(UiText.res(R.string.open)) { open(item) },
                SheetAction(UiText.res(R.string.change_emulator), item.emulatorName?.let { UiText.Raw(it) }) { pickFolderEmulator(item.folder) },
                SheetAction(UiText.res(R.string.rescan_folder)) { rescanFolder(item.folder) },
                SheetAction(UiText.res(R.string.refresh_metadata)) {
                    refreshMetadata(library.roms.filter { it.folderId == item.folder.id }.map { it.key })
                },
                // Una carpeta de emulador también admite carátula, fondo y logo propios.
            ) + customizeActions(item.key, item.system.name) + listOf(
                SheetAction(UiText.res(R.string.remove_folder), destructive = true) { removeFolder(item.folder) },
            )
            is LibraryItem.App -> listOf(
                SheetAction(UiText.res(R.string.open)) { open(item) },
                SheetAction(UiText.res(R.string.details)) { showDetails(item.key) },
                SheetAction(UiText.res(R.string.refresh_metadata)) { refreshMetadata(listOf(item.key)) },
            ) + customizeActions(item.key, item.app.displayTitle) + listOf(
                SheetAction(UiText.res(R.string.remove_from_library), destructive = true) {
                    removeApp(item.app.packageName, item.app.displayTitle)
                },
            )
        }
        val subtitle = when (item) {
            is LibraryItem.Folder -> UiText.Raw(item.folder.displayPath)
            is LibraryItem.App -> UiText.Raw(item.app.packageName)
        }
        showSheet(ActionSheetSpec(UiText.Raw(item.name), subtitle, actions))
    }

    fun romOptions(rom: RomEntry) {
        showSheet(
            ActionSheetSpec(
                UiText.Raw(rom.displayTitle),
                UiText.Raw(rom.relPath),
                listOf(
                    SheetAction(UiText.res(R.string.open)) { openRom(rom) },
                    SheetAction(UiText.res(R.string.details)) { showDetails(rom.key) },
                    SheetAction(UiText.res(R.string.emulator_for_game), rom.emulatorId?.let { UiText.Raw(emulatorName(it)) }) { pickRomEmulator(rom) },
                    SheetAction(UiText.res(R.string.refresh_metadata)) { refreshMetadata(listOf(rom.key)) },
                ) + customizeActions(rom.key, rom.displayTitle),
            ),
        )
    }

    /* ── personalizar carátula / fondo / icono ────────────────── */

    /**
     * Por cada clase de imagen: "poner…" y, solo si ya hay una puesta,
     * "quitar…". Así el menú no ofrece borrar lo que no existe.
     */
    private fun customizeActions(key: String, title: String): List<SheetAction> {
        val settable = artKindsFor(key)
        return ArtKind.entries.flatMap { kind ->
            buildList {
                if (kind in settable) {
                    add(SheetAction(UiText.res(kind.label())) { chooseArtSource(key, title, kind) })
                }
                // "Quitar" se ofrece siempre que haya imagen, aunque ya no se pueda
                // volver a poner: una carátula guardada antes de este cambio se
                // quedaría si no sin manera de borrarse.
                if (art.has(key, kind)) {
                    add(SheetAction(UiText.res(kind.removeLabel()), destructive = true) { clearArt(key, kind) })
                }
            }
        }
    }

    /**
     * Qué imágenes admite cada cosa.
     *
     * La carátula solo tiene sentido en una ROM: es la portada de *ese* juego.
     * Un juego Android y una carpeta de emulador se representan con logo o
     * icono, que es lo que se ve en su card.
     */
    private fun artKindsFor(key: String): List<ArtKind> =
        if (key.startsWith("r:")) listOf(ArtKind.Cover, ArtKind.Background, ArtKind.Logo)
        else listOf(ArtKind.Background, ArtKind.Logo, ArtKind.Icon)

    fun clearArt(key: String, kind: ArtKind) {
        viewModelScope.launch {
            art.clear(key, kind)
            showToast(UiText.res(R.string.art_cleared))
        }
    }

    /** Hoja con los cuatro servicios; los que no están configurados (o no cubren el juego) salen atenuados. */
    private fun chooseArtSource(key: String, title: String, kind: ArtKind) {
        val actions = ART_SERVICES.map { service ->
            val supported = art.supports(key, service)
            val configured = app.credentials.isConfigured(service)
            val problem = when {
                !supported -> UiText.res(R.string.art_source_roms_only)
                !configured -> UiText.res(R.string.art_source_not_configured)
                else -> null
            }
            SheetAction(UiText.Raw(serviceName(service)), detail = problem, dimmed = problem != null) {
                if (problem != null) showToast(problem) else searchArt(key, title, kind, service)
            }
        }
        showSheet(ActionSheetSpec(UiText.res(kind.label()), UiText.Raw(title), actions))
    }

    private fun searchArt(key: String, title: String, kind: ArtKind, service: Service) {
        val state = ArtPickerState(key, title, kind, service)
        artPicker = state
        artJob?.cancel()
        artJob = viewModelScope.launch {
            val found = try {
                art.candidates(key, kind, service)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (artPicker === state) {
                artPicker = state.copy(loading = false, candidates = found.orEmpty(), failed = found == null)
            }
        }
    }

    fun applyArt(candidate: ArtCandidate) {
        val state = artPicker ?: return
        if (state.applying != null) return
        artPicker = state.copy(applying = candidate.url)
        artJob?.cancel()
        artJob = viewModelScope.launch {
            val ok = art.apply(state.key, state.kind, candidate.url)
            artPicker = null
            showToast(UiText.res(if (ok) R.string.art_applied else R.string.art_apply_failed))
        }
    }

    fun closeArtPicker() {
        artJob?.cancel()
        artPicker = null
    }

    fun pickFolderEmulator(folder: RomFolder) {
        emulatorSheet(folder.systemId, folder.emulatorId, allowInherit = false) { id ->
            if (id != null) repo.setFolderEmulator(folder.id, id)
        }
    }

    fun pickRomEmulator(rom: RomEntry) {
        emulatorSheet(rom.systemId, rom.emulatorId, allowInherit = true) { id -> repo.setRomEmulator(rom.id, id) }
    }

    /** Hoja con los emuladores del sistema (+ "Otra app…"). onPick(null) = heredar de la carpeta. */
    fun emulatorSheet(systemId: String, current: String?, allowInherit: Boolean, onPick: (String?) -> Unit) {
        val options = emulatorOptions(systemId).map { opt ->
            SheetAction(
                label = UiText.Raw(opt.name),
                detail = if (opt.installed) null else UiText.res(R.string.not_installed),
                selected = opt.id == current,
                dimmed = !opt.installed,
            ) { onPick(opt.id) }
        }
        val inherit = if (allowInherit) listOf(SheetAction(UiText.res(R.string.use_folder_emulator), selected = current == null) { onPick(null) }) else emptyList()
        val custom = SheetAction(
            UiText.res(R.string.other_app),
            detail = current?.takeIf { it.startsWith(Emulators.CUSTOM_PREFIX) }?.let { UiText.Raw(emulatorName(it)) },
            selected = current?.startsWith(Emulators.CUSTOM_PREFIX) == true,
        ) { appPicker { pkg -> onPick(Emulators.CUSTOM_PREFIX + pkg) } }
        showSheet(ActionSheetSpec(UiText.res(R.string.choose_emulator), Systems.byId(systemId)?.name?.let { UiText.Raw(it) }, inherit + options + custom))
    }

    /** Lista de todas las apps instaladas para usar una como emulador. */
    fun appPicker(onPick: (String) -> Unit) {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { app.apps.launchable() }
            showSheet(
                ActionSheetSpec(
                    UiText.res(R.string.other_app),
                    UiText.res(R.string.other_app_hint),
                    apps.map { a -> SheetAction(UiText.Raw(a.label), UiText.Raw(a.packageName)) { onPick(a.packageName) } },
                ),
            )
        }
    }

    /* ── metadatos por elemento y ficha de detalles ───────────── */

    fun refreshMetadata(keys: List<String>) {
        if (keys.isEmpty()) return
        if (!app.credentials.anyConfigured()) {
            showToast(UiText.res(R.string.configure_a_service))
            return
        }
        engine.start(keys, force = true)
        showToast(UiText.res(R.string.toast_metadata_started))
    }

    fun showDetails(key: String) {
        detailsKey = key
        achievements = AchievementsState.Idle
        val rom = repo.romByKey(key)
        if (rom?.meta?.ra != null) loadAchievements(key)
    }

    fun closeDetails() {
        detailsKey = null
        achievements = AchievementsState.Idle
    }

    fun loadAchievements(key: String) {
        achievements = AchievementsState.Loading
        viewModelScope.launch {
            achievements = runCatching { engine.freshAchievements(key) }.getOrNull()
                ?.let { AchievementsState.Loaded(it) } ?: AchievementsState.Failed
        }
    }

    /* ── capas ────────────────────────────────────────────────── */

    fun showDialog(spec: DialogSpec) {
        sheet = null
        dialog = spec
    }

    fun dismissDialog() { dialog = null }

    fun showSheet(spec: ActionSheetSpec) { sheet = spec }

    fun dismissSheet() { sheet = null }

    fun showToast(text: UiText) {
        toast = text
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(2_800)
            toast = null
        }
    }

    override fun onCleared() {
        viewModelScope.launch { repo.flush() }
        super.onCleared()
    }

    companion object {
        private const val LAUNCH_DELAY_MS = 650L
        private const val MAX_SESSION_MINUTES = 12 * 60
        private const val AUTO_RESCAN_MS = 6L * 60 * 60 * 1000
        private val ART_SERVICES = listOf(Service.ScreenScraper, Service.Igdb, Service.SteamGridDb, Service.RetroAchievements)
    }
}
