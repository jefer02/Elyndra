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
import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.BannerHub
import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.PAIRS
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.data.pairIndexFor
import com.elyndra.launcher.launch.GameLauncher
import com.elyndra.launcher.library.InstalledApp
import com.elyndra.launcher.library.PcGameIds
import com.elyndra.launcher.library.PcGames
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

    /** De dónde sale el id con el que arranca un juego de PC (ver [PcGameIds]). */
    private val pcGameIds = PcGameIds(app.scanner)

    private val art = ArtSources(engine, repo, app.media)
    private var artJob: Job? = null

    /** El mando: traduce sus botones a lo que hace cada capa (ver [InputController]). */
    val input = InputController(this)

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
            val pcId = pcGameIds.resolve(folder, rom)
            delay(LAUNCH_DELAY_MS)
            val ref = launcher.romRef(folder, rom, vitaTitle, pcId.id, pcId.assigned)
            val outcome = launcher.launchRom(emuId, ref)
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
                // Sin el archivo que exporta el runtime no hay nada que lanzar,
                // pero abrir el runtime y elegir el juego dentro sigue siendo
                // una salida: se ofrece, ya explicado, en vez de hacerlo a ciegas.
                GameLauncher.Outcome.NeedsPcLauncher -> showDialog(
                    DialogSpec(
                        title = UiText.res(R.string.dialog_pc_launcher_title, emuName),
                        message = UiText.res(R.string.dialog_pc_launcher_msg, emuName),
                        confirm = DialogButton(UiText.res(R.string.open_runtime, emuName)) { openRuntime(emuId) },
                        dismiss = DialogButton(UiText.res(R.string.close)) {},
                        // Poner el id a mano es la otra salida, y en un juego de
                        // PC suele ser la buena: se copia de la ficha del juego
                        // dentro del runtime y ya se lanza solo.
                        extra = if (usesGameId(rom)) {
                            DialogButton(UiText.res(R.string.pc_game_id)) { editPcGameId(rom, launchAfterSave = true) }
                        } else {
                            DialogButton(UiText.res(R.string.choose_other)) { pickFolderEmulator(folder) }
                        },
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

    /**
     * "Id en el runtime" del menú de un juego.
     *
     * Sale en lo que se lanza por id ([usesGameId]): es el número con el que
     * BannerHub y compañía conocen al juego dentro de su propia biblioteca, y
     * sin él lo único que se puede hacer es abrir el runtime. En el detalle va
     * el id escrito a mano, que es lo único que se puede cambiar desde ahí —
     * el que lleve dentro un archivo se lee al lanzar.
     */
    private fun pcGameIdActions(rom: RomEntry): List<SheetAction> {
        if (!usesGameId(rom)) return emptyList()
        return listOf(
            SheetAction(
                UiText.res(R.string.pc_game_id),
                detail = rom.pcGameId?.let { UiText.Raw(it) },
                icon = SheetIcon.GameId,
            ) { editPcGameId(rom) },
        )
    }

    /**
     * ¿Se lanza este juego por id, y no por archivo?
     *
     * Lo son los runtimes de Windows con biblioteca propia. Se mira también el
     * emulador y no solo el sistema porque una carpeta de archivos-id se puede
     * haber dado de alta como otra cosa (un `.iso` parece de PS2), y ahí es
     * justo donde hace falta poder poner el id a mano.
     */
    private fun usesGameId(rom: RomEntry): Boolean {
        if (rom.systemId == PcGames.SYSTEM_ID) return true
        val folder = repo.folder(rom.folderId)
        return Emulators.usesGameId(rom.emulatorId ?: folder?.emulatorId ?: defaultEmulator(rom.systemId))
    }

    /**
     * Pide el id del juego dentro del runtime; en blanco, lo borra.
     *
     * Se rellena con el id que está en vigor —el escrito a mano o el que lleve
     * dentro su archivo—, así que el diálogo enseña siempre con qué se va a
     * lanzar. Con [launchAfterSave] el juego arranca en cuanto se guarda: es
     * como se llega aquí desde un lanzamiento que se quedó sin id, y lo que se
     * quería hacer era jugar, no rellenar una ficha.
     */
    fun editPcGameId(rom: RomEntry, launchAfterSave: Boolean = false) {
        viewModelScope.launch {
            val folder = repo.folder(rom.folderId)
            val current = rom.pcGameId ?: folder?.let { pcGameIds.resolve(it, rom).id }
            showDialog(
                DialogSpec(
                    title = UiText.res(R.string.pc_game_id),
                    message = UiText.res(R.string.pc_game_id_msg),
                    confirm = DialogButton(UiText.res(if (launchAfterSave) R.string.save_and_open else R.string.save)) {},
                    dismiss = DialogButton(UiText.res(R.string.cancel)) {},
                    input = DialogInput(
                        initial = current.orEmpty(),
                        placeholder = UiText.res(R.string.pc_game_id_hint),
                    ) { typed -> savePcGameId(rom, typed, launchAfterSave) },
                ),
            )
        }
    }

    private fun savePcGameId(rom: RomEntry, typed: String, launchAfterSave: Boolean) {
        val id = BannerHub.normalizeId(typed)
        repo.setPcGameId(rom.id, id)
        if (id == null) {
            showToast(UiText.res(R.string.pc_game_id_cleared))
            return
        }
        showToast(UiText.res(R.string.pc_game_id_saved, id))
        // Se relee de la biblioteca: lo que tiene el id recién guardado es la
        // entrada nueva, no la copia con la que se abrió el menú.
        if (launchAfterSave) repo.current.roms.firstOrNull { it.id == rom.id }?.let { openRom(it) }
    }

    /** Abrir el runtime de Windows y apartarse: el juego se elige dentro. */
    private fun openRuntime(emuId: String) {
        val pkg = Emulators.byId(emuId)?.let { launcher.installedComponent(it) }?.substringBefore('/') ?: return
        launcher.launchApp(pkg)
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

    /* ── lo que entra se monta desde el polvo ─────────────────── */

    /** Cards recién añadidas que todavía tienen que montarse. */
    var materializing by mutableStateOf<Set<String>>(emptySet()); private set

    /** Imagen recién puesta que todavía tiene que montarse. */
    var materializingArt by mutableStateOf<Pair<String, ArtKind>?>(null); private set

    /**
     * Marca [keys] para que sus cards se monten al aparecer.
     *
     * El temporizador limpia lo que nadie llegó a pintar —lo que se añadió
     * fuera de pantalla, o en otra pantalla—: sin él, esa marca se quedaría
     * puesta y la card se montaría más tarde, al asomar con el scroll, sin
     * venir a cuento.
     */
    fun materialize(keys: Collection<String>) {
        val fresh = keys.toSet()
        if (fresh.isEmpty()) return
        materializing = materializing + fresh
        viewModelScope.launch {
            delay(MATERIALIZE_TIMEOUT_MS)
            materializing = materializing - fresh
        }
    }

    fun materializeArt(key: String, kind: ArtKind) {
        materializingArt = key to kind
        viewModelScope.launch {
            delay(MATERIALIZE_TIMEOUT_MS)
            if (isMaterializingArt(key, kind)) materializingArt = null
        }
    }

    /** ¿Es *esta* imagen la que se está montando? Lo pregunta quien la pinta. */
    fun isMaterializingArt(key: String, kind: ArtKind): Boolean {
        val target = materializingArt ?: return false
        return target.first == key && target.second == kind
    }

    /** Las llaman la card y la imagen al terminar de montarse. */
    fun finishMaterialize(key: String) {
        if (key in materializing) materializing = materializing - key
    }

    fun finishMaterializeArt() {
        materializingArt = null
    }

    /* ── quitar algo se ve: se deshace antes de irse ──────────── */

    /**
     * La card que se está deshaciendo ahora mismo, por su clave.
     *
     * La card que coincide se desintegra (ver `DisintegratableBox`) y el
     * borrado de verdad espera a que termine la animación.
     */
    var vanishing by mutableStateOf<String?>(null); private set

    /** Lo mismo para una imagen: de qué juego y qué clase de imagen. */
    var vanishingArt by mutableStateOf<Pair<String, ArtKind>?>(null); private set

    /** ¿Es *esta* imagen la que se está deshaciendo? Lo pregunta quien la pinta. */
    fun isVanishingArt(key: String, kind: ArtKind): Boolean {
        val target = vanishingArt ?: return false
        return target.first == key && target.second == kind
    }

    private var pendingVanish: (() -> Unit)? = null

    /**
     * Aplaza [action] hasta que termine la animación.
     *
     * Quien avisa de que ha terminado es la propia card ([finishVanish]),
     * pero el borrado no puede quedar colgando de que alguien la esté
     * pintando: si lo que se quita no está en pantalla —o su card se va
     * antes de acabar— el temporizador lo ejecuta igual.
     */
    private fun startVanish(action: () -> Unit) {
        // Si había otra desintegración en curso se cierra antes de empezar
        // esta: nunca hay dos borrados aplazados a la vez.
        finishVanish()
        pendingVanish = action
        viewModelScope.launch {
            delay(VANISH_TIMEOUT_MS)
            finishVanish()
        }
    }

    private fun vanishItem(key: String, action: () -> Unit) {
        startVanish(action)
        vanishing = key
    }

    private fun vanishArt(key: String, kind: ArtKind, action: () -> Unit) {
        startVanish(action)
        vanishingArt = key to kind
    }

    /** Lo llama la card al acabar la animación (o el temporizador); corre una sola vez. */
    fun finishVanish() {
        val action = pendingVanish ?: return
        pendingVanish = null
        vanishing = null
        vanishingArt = null
        action()
    }

    fun removeFolder(folder: RomFolder) {
        val count = library.roms.count { it.folderId == folder.id }
        showDialog(
            DialogSpec(
                title = UiText.res(R.string.confirm_remove_title),
                message = UiText.plural(R.plurals.confirm_remove_folder_msg, count, count),
                confirm = DialogButton(UiText.res(R.string.remove)) {
                    vanishItem(folder.key) {
                        library.roms.filter { it.folderId == folder.id }.forEach { app.media.deleteFor(it.key) }
                        repo.removeFolder(folder.id)
                        if (library.folders.none { it.treeUri == folder.treeUri }) app.files.releasePermission(folder.treeUri)
                        if (folderId == folder.id) screen = Screen.Library
                    }
                },
                dismiss = DialogButton(UiText.res(R.string.close)) {},
            ),
        )
    }

    /**
     * Quita un juego de la biblioteca.
     *
     * No borra el archivo —Elyndra nunca toca el almacenamiento—: lo saca de
     * la biblioteca y lo anota como quitado para que no vuelva en el
     * siguiente análisis. Se deshace con "Restaurar" en el menú de la carpeta.
     */
    fun removeRom(rom: RomEntry) {
        showDialog(
            DialogSpec(
                title = UiText.res(R.string.confirm_remove_title),
                message = UiText.res(R.string.confirm_remove_game_msg, rom.displayTitle),
                confirm = DialogButton(UiText.res(R.string.remove)) {
                    vanishItem(rom.key) {
                        app.media.deleteFor(rom.key)
                        repo.removeRom(rom.id)
                        if (selectedRomKey == rom.key) selectedRomKey = null
                        showToast(UiText.res(R.string.toast_game_removed, rom.displayTitle))
                    }
                },
                dismiss = DialogButton(UiText.res(R.string.close)) {},
            ),
        )
    }

    /** "Restaurar juegos quitados", solo si hay alguno que restaurar. */
    private fun restoreAction(folder: RomFolder): List<SheetAction> {
        if (folder.excluded.isEmpty()) return emptyList()
        return listOf(
            SheetAction(
                UiText.res(R.string.restore_removed),
                detail = UiText.plural(R.plurals.removed_games_count, folder.excluded.size, folder.excluded.size),
                icon = SheetIcon.Refresh,
            ) {
                repo.restoreRemoved(folder.id)
                rescanFolder(folder)
            },
        )
    }

    fun removeApp(pkg: String, title: String) {
        showDialog(
            DialogSpec(
                title = UiText.res(R.string.confirm_remove_title),
                message = UiText.res(R.string.confirm_remove_app_msg, title),
                confirm = DialogButton(UiText.res(R.string.remove)) {
                    vanishItem("a:$pkg") {
                        app.media.deleteFor("a:$pkg")
                        repo.removeApp(pkg)
                    }
                },
                dismiss = DialogButton(UiText.res(R.string.close)) {},
            ),
        )
    }

    /* ── menús de pulsación larga ─────────────────────────────── */

    /**
     * Menú de una card del carrusel: una carpeta de emulador o una app.
     *
     * Las acciones salen agrupadas por intención —jugar, imágenes, gestionar,
     * quitar— en vez de en una lista seguida: así "Quitar carpeta" no queda a
     * un dedo de "Abrir", y las cuatro clases de imagen se leen juntas.
     */
    fun itemOptions(item: LibraryItem) {
        val groups = when (item) {
            is LibraryItem.Folder -> listOf(
                SheetGroup(
                    UiText.res(R.string.sheet_group_play),
                    listOf(
                        SheetAction(UiText.res(R.string.open), icon = SheetIcon.Play) { open(item) },
                        SheetAction(
                            UiText.res(R.string.change_emulator),
                            detail = item.emulatorName?.let { UiText.Raw(it) },
                            icon = SheetIcon.Emulator,
                            opensSheet = true,
                        ) { pickFolderEmulator(item.folder) },
                    ),
                ),
                // Una carpeta de emulador también admite carátula, fondo, logo e icono propios.
                artworkGroup(item.key, item.system.name),
                SheetGroup(
                    UiText.res(R.string.sheet_group_manage),
                    listOf(
                        SheetAction(UiText.res(R.string.rescan_folder), icon = SheetIcon.Rescan) { rescanFolder(item.folder) },
                        SheetAction(UiText.res(R.string.refresh_metadata), icon = SheetIcon.Refresh) {
                            refreshMetadata(library.roms.filter { it.folderId == item.folder.id }.map { it.key })
                        },
                    ) + restoreAction(item.folder),
                ),
                removalGroup(UiText.res(R.string.remove_folder)) { removeFolder(item.folder) },
            )

            is LibraryItem.App -> listOf(
                SheetGroup(
                    UiText.res(R.string.sheet_group_play),
                    listOf(
                        SheetAction(UiText.res(R.string.open), icon = SheetIcon.Play) { open(item) },
                        SheetAction(UiText.res(R.string.details), icon = SheetIcon.Details, opensSheet = true) { showDetails(item.key) },
                    ),
                ),
                artworkGroup(item.key, item.app.displayTitle),
                SheetGroup(
                    UiText.res(R.string.sheet_group_manage),
                    listOf(
                        SheetAction(UiText.res(R.string.refresh_metadata), icon = SheetIcon.Refresh) { refreshMetadata(listOf(item.key)) },
                    ),
                ),
                removalGroup(UiText.res(R.string.remove_from_library)) {
                    removeApp(item.app.packageName, item.app.displayTitle)
                },
            )
        }
        val subtitle = when (item) {
            is LibraryItem.Folder -> UiText.Raw(item.folder.displayPath)
            is LibraryItem.App -> UiText.Raw(item.app.packageName)
        }
        showSheet(ActionSheetSpec(UiText.Raw(item.name), subtitle, groups, thumbOf(item)))
    }

    fun romOptions(rom: RomEntry) {
        showSheet(
            ActionSheetSpec(
                UiText.Raw(rom.displayTitle),
                UiText.Raw(rom.relPath),
                listOf(
                    SheetGroup(
                        UiText.res(R.string.sheet_group_play),
                        listOf(
                            SheetAction(UiText.res(R.string.open), icon = SheetIcon.Play) { openRom(rom) },
                            SheetAction(UiText.res(R.string.details), icon = SheetIcon.Details, opensSheet = true) { showDetails(rom.key) },
                            SheetAction(
                                UiText.res(R.string.emulator_for_game),
                                detail = rom.emulatorId?.let { UiText.Raw(emulatorName(it)) },
                                icon = SheetIcon.Emulator,
                                opensSheet = true,
                            ) { pickRomEmulator(rom) },
                        ) + pcGameIdActions(rom),
                    ),
                    artworkGroup(rom.key, rom.displayTitle),
                    SheetGroup(
                        UiText.res(R.string.sheet_group_manage),
                        listOf(
                            SheetAction(UiText.res(R.string.refresh_metadata), icon = SheetIcon.Refresh) { refreshMetadata(listOf(rom.key)) },
                        ),
                    ),
                    removalGroup(UiText.res(R.string.remove_game)) { removeRom(rom) },
                ),
                SheetThumb(coverPath = rom.meta.cover, pairIndex = romPairIndex(rom)),
            ),
        )
    }

    /** La carátula (o el icono) que se enseña en la cabecera de la hoja. */
    private fun thumbOf(item: LibraryItem): SheetThumb = when (item) {
        is LibraryItem.Folder -> SheetThumb(
            coverPath = item.coverPath,
            iconPath = item.iconPath ?: item.logoPath,
            packageName = item.emulatorPackage,
            pairIndex = pairIndexOf(item),
        )
        is LibraryItem.App -> SheetThumb(
            coverPath = item.app.meta.cover,
            iconPath = item.app.meta.icon,
            packageName = item.app.packageName,
            pairIndex = pairIndexOf(item),
        )
    }

    /** El bloque destructivo va solo y al final, con su glifo de papelera. */
    private fun removalGroup(label: UiText, action: () -> Unit) =
        SheetGroup(null, listOf(SheetAction(label, destructive = true, icon = SheetIcon.Remove, action = action)))

    /* ── personalizar carátula / fondo / icono ────────────────── */

    /**
     * El bloque "Imágenes" del menú: una fila por clase de imagen que ese
     * elemento admite, y debajo las que se pueden quitar.
     *
     * Cada fila abre el selector de origen, donde ahora lo primero es la
     * galería del propio dispositivo. Las que ya tienen imagen puesta lo
     * dicen en su detalle, así que se ve de un vistazo qué falta por poner.
     *
     * "Quitar" se ofrece siempre que haya imagen, aunque ya no se pueda
     * volver a poner: una carátula guardada antes de este cambio se quedaría
     * si no sin manera de borrarse.
     */
    private fun artworkGroup(key: String, title: String): SheetGroup {
        val settable = artKindsFor(key)
        val set = mutableListOf<SheetAction>()
        val clear = mutableListOf<SheetAction>()
        for (kind in ArtKind.entries) {
            val has = art.has(key, kind)
            if (kind in settable) {
                set += SheetAction(
                    UiText.res(kind.shortLabel()),
                    detail = if (has) UiText.res(R.string.art_set) else null,
                    icon = kind.sheetIcon(),
                    opensSheet = true,
                ) { chooseArtSource(key, title, kind) }
            }
            if (has) {
                clear += SheetAction(
                    UiText.res(kind.removeLabel()),
                    destructive = true,
                    icon = SheetIcon.Remove,
                ) { clearArt(key, kind) }
            }
        }
        return SheetGroup(UiText.res(R.string.sheet_group_artwork), set + clear)
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

    /**
     * Icono, logo y fondo automáticos de las carpetas de emulador.
     *
     * Una carpeta no tiene metadatos que scrapear —el motor no la toca—, así
     * que aquí se le pide a cada servicio configurado arte para el nombre de su
     * sistema y se guarda el primer candidato. Lo que ya tiene imagen no se
     * toca, así que no pisa lo elegido a mano ni repite descargas.
     */
    fun autoFolderArt(keys: List<String>? = null) {
        if (!app.credentials.anyConfigured()) return
        val targets = (keys ?: library.folders.map { it.key }).filter { it.startsWith("f:") }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            for (key in targets) {
                for (kind in listOf(ArtKind.Icon, ArtKind.Logo, ArtKind.Background)) {
                    if (art.has(key, kind)) continue
                    for (service in ART_SERVICES) {
                        if (!app.credentials.isConfigured(service) || !art.supports(key, service)) continue
                        val url = runCatching { art.candidates(key, kind, service) }
                            .getOrNull()?.firstOrNull()?.url ?: continue
                        if (runCatching { art.apply(key, kind, url) }.getOrDefault(false)) break
                    }
                }
            }
        }
    }

    /**
     * Pone automáticamente una imagen a un elemento, con el primer candidato
     * que dé algún servicio configurado. Es lo que usan tanto el arte
     * automático de las carpetas como las acciones de Lucy.
     */
    suspend fun applyArtAuto(key: String, kind: ArtKind): Boolean {
        if (!app.credentials.anyConfigured()) return false
        for (service in ART_SERVICES) {
            if (!app.credentials.isConfigured(service) || !art.supports(key, service)) continue
            val url = runCatching { art.candidates(key, kind, service) }
                .getOrNull()?.firstOrNull()?.url ?: continue
            if (runCatching { art.apply(key, kind, url) }.getOrDefault(false)) return true
        }
        return false
    }

    /** ¿Hay ya una imagen de esta clase puesta? */
    fun hasArt(key: String, kind: ArtKind): Boolean = art.has(key, kind)

    /* ── acciones que puede ejecutar Lucy ─────────────────── */

    /** Lanza cualquier elemento de la biblioteca por su clave. */
    fun openByKey(key: String): Boolean {
        val rom = repo.romByKey(key)
        if (rom != null) {
            go(Screen.Library)
            openRom(rom)
            return true
        }
        val entry = repo.appByKey(key) ?: return false
        go(Screen.Library)
        select(key)
        open(LibraryItem.App(entry, installed = true))
        return true
    }

    /** Quita de la biblioteca una app Android o una carpeta de emulador. */
    fun removeFromLibrary(key: String): Boolean {
        repo.appByKey(key)?.let {
            repo.removeApp(it.packageName)
            return true
        }
        repo.folderByKey(key)?.let {
            repo.removeFolder(it.id)
            return true
        }
        return false
    }

    /** Apps instaladas en el teléfono, para que Lucy pueda añadir una. */
    suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) { app.apps.launchable() }

    /** Añade a la biblioteca una app instalada; devuelve su clave. */
    fun addInstalledApp(pkg: String, label: String): String? {
        if (repo.appByKey("a:$pkg") != null) return null
        val keys = repo.addApps(listOf(AppEntry(packageName = pkg, label = label, addedAt = System.currentTimeMillis())))
        val key = keys.firstOrNull() ?: return null
        select(key)
        materialize(keys)
        if (app.settings.autoMeta && app.credentials.anyConfigured()) engine.start(keys, force = false)
        return key
    }

    fun clearArt(key: String, kind: ArtKind) {
        val clear = {
            viewModelScope.launch {
                art.clear(key, kind)
                showToast(UiText.res(R.string.art_cleared))
            }
            Unit
        }
        // La carátula y el logo se ven como imagen suelta en pantalla, así que
        // se deshacen primero y se borran después. El fondo y el icono no
        // tienen una imagen propia que desintegrar —son parte del hero y de la
        // card—, y esperar a una animación que nadie pinta solo los haría
        // tardar más: esos se quitan al momento, como siempre.
        if (kind == ArtKind.Cover || kind == ArtKind.Logo) vanishArt(key, kind, clear) else clear()
    }

    /* ── imagen propia, elegida en la galería ─────────────────── */

    /**
     * Petición de archivo pendiente. La interfaz no puede abrir el selector
     * desde el ViewModel (hace falta un `ActivityResultLauncher`), así que el
     * ViewModel deja aquí lo que quiere y [ElyndraApp] lo lanza y devuelve el
     * resultado por [onMediaPicked].
     */
    data class MediaRequest(val mimeTypes: List<String>, val onPicked: (Uri?) -> Unit)

    var mediaRequest by mutableStateOf<MediaRequest?>(null); private set

    fun onMediaPicked(uri: Uri?) {
        val request = mediaRequest ?: return
        mediaRequest = null
        request.onPicked(uri)
    }

    /** Se cerró el selector sin elegir nada. */
    fun cancelMediaRequest() {
        mediaRequest = null
    }

    /**
     * "Elegir de la galería": el usuario pone su propia carátula, fondo, logo
     * o icono desde el carrete, sin pasar por ningún servicio ni necesitar
     * credenciales. Queda fijada igual que una descargada.
     */
    fun pickLocalArt(key: String, kind: ArtKind) {
        mediaRequest = MediaRequest(IMAGE_MIME_TYPES) { uri ->
            if (uri == null) return@MediaRequest
            viewModelScope.launch {
                val image = withContext(Dispatchers.IO) { app.localMedia.readImage(uri) }
                if (image == null) {
                    showToast(UiText.res(R.string.art_local_failed))
                    return@launch
                }
                val ok = art.applyLocal(key, kind, image)
                if (ok) materializeArt(key, kind)
                showToast(UiText.res(if (ok) R.string.art_applied else R.string.art_local_failed))
            }
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
            SheetAction(
                UiText.Raw(serviceName(service)),
                detail = problem,
                dimmed = problem != null,
                icon = SheetIcon.Service,
            ) {
                if (problem != null) showToast(problem) else searchArt(key, title, kind, service)
            }
        }
        // La galería va primero y aparte: es la única fuente que siempre
        // funciona, sin credenciales ni conexión.
        val gallery = SheetAction(
            UiText.res(R.string.art_from_gallery),
            detail = UiText.res(R.string.art_from_gallery_hint),
            icon = SheetIcon.Gallery,
        ) { pickLocalArt(key, kind) }
        showSheet(
            ActionSheetSpec(
                UiText.res(kind.label()),
                UiText.Raw(title),
                listOf(
                    SheetGroup(UiText.res(R.string.art_group_yours), listOf(gallery)),
                    SheetGroup(UiText.res(R.string.art_group_services), actions),
                ),
            ),
        )
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
            if (ok) materializeArt(state.key, state.kind)
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
        // Las carpetas no pasan por el motor: su arte se resuelve aparte.
        autoFolderArt(keys.filter { it.startsWith("f:") })
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
        input.onDialogShown()
    }

    fun dismissDialog() { dialog = null }

    fun showSheet(spec: ActionSheetSpec) {
        sheet = spec
        input.onSheetShown()
    }

    /**
     * El menú de la app, para quien juega con mando.
     *
     * Con el dedo, buscar, ordenar, añadir, Ajustes y Lucy están repartidos
     * por la pantalla —cabecera, dock, botón flotante—. Con mando no hay
     * puntero que los alcance, así que Start los junta aquí en una sola hoja.
     */
    fun mainMenu() {
        showSheet(ActionSheetSpec(UiText.res(R.string.menu_title), null, input.mainMenuActions()))
    }

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

        /**
         * Margen máximo que espera un borrado a su animación. Holgado sobre
         * los ~620 ms del efecto: es la red de seguridad para cuando nadie
         * está pintando lo que se quita, no el tiempo normal de espera.
         */
        private const val VANISH_TIMEOUT_MS = 1_200L

        /**
         * Margen que espera una marca de "móntate" a que alguien la pinte.
         * Holgado sobre lo que tarda el efecto (esperar a la imagen + ~0,7 s
         * de montaje): es limpieza, no el tiempo normal.
         */
        private const val MATERIALIZE_TIMEOUT_MS = 2_500L
        private const val MAX_SESSION_MINUTES = 12 * 60
        private const val AUTO_RESCAN_MS = 6L * 60 * 60 * 1000
        private val ART_SERVICES = listOf(Service.ScreenScraper, Service.Igdb, Service.SteamGridDb, Service.RetroAchievements)

        /**
         * Lo que acepta el selector de "Elegir de la galería": cualquier
         * imagen, más HEIC y HEIF sueltos — algunos proveedores de fotos los
         * declaran aparte y si no salen en gris al elegirlos.
         */
        private val IMAGE_MIME_TYPES = listOf("image/*", "image/heic", "image/heif")
    }
}
