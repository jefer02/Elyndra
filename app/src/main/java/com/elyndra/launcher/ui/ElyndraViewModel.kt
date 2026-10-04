package com.elyndra.launcher.ui

import com.elyndra.launcher.library.RomScanner
import com.elyndra.launcher.metadata.MediaFailure
import com.elyndra.launcher.metadata.MediaResult
import com.elyndra.launcher.library.NameCheck
import com.elyndra.launcher.library.Names
import com.elyndra.launcher.metadata.TranslationCache
import com.elyndra.launcher.metadata.TranslationPacks
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.elyndra.launcher.data.ArtOrigin
import com.elyndra.launcher.metadata.LocalImage
import com.elyndra.launcher.metadata.LocalMedia
import com.elyndra.launcher.launch.BachataS4
import com.elyndra.launcher.library.Ps4
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
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
import com.elyndra.launcher.data.PlaySession
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.sound.BackgroundMusic
import com.elyndra.launcher.sound.SoundManager
import com.elyndra.launcher.sound.UiSound
import com.elyndra.launcher.launch.GameLauncher
import com.elyndra.launcher.library.InstalledApp
import com.elyndra.launcher.library.PcGameIds
import com.elyndra.launcher.library.PcGames
import com.elyndra.launcher.metadata.ArtCandidate
import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.metadata.ArtSources
import com.elyndra.launcher.metadata.MetadataPriorityStore
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.domain.launch.LaunchDecision
import com.elyndra.launcher.domain.profile.LaunchOutcome
import com.elyndra.launcher.launch.EmulatorInventory
import com.elyndra.launcher.launch.LaunchOrchestrator
import com.elyndra.launcher.masha.MashaBrain
import com.elyndra.launcher.session.SessionTracker
import com.elyndra.launcher.work.ElyndraWork
import com.elyndra.launcher.ui.screens.serviceName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Estado de la app. La biblioteca vive en [LibraryRepository] (persistida);
 * aquí se proyecta para la UI y se orquestan navegación, lanzamientos y
 * avisos. Añadir, Ajustes y Masha tienen su propio controlador.
 */
@HiltViewModel
class ElyndraViewModel @Inject constructor(
    application: Application,
    /** Masha: la IA, lo que sabe de la biblioteca, su memoria y el dispositivo. */
    val brain: MashaBrain,
    /** Decide con qué emulador va cada ROM y aprende de cómo acaba cada lanzamiento. */
    val orchestrator: LaunchOrchestrator,
    val sessions: SessionTracker,
    private val inventory: EmulatorInventory,
    private val work: ElyndraWork,
    val metadataPriority: MetadataPriorityStore,
    /** Los sonidos de la interfaz (ver [SoundManager]). */
    val sound: SoundManager,
    /** La música de fondo de la interfaz (ver [BackgroundMusic]). */
    val music: BackgroundMusic,
    /** Traducciones de descripciones en el dispositivo (ver [TranslationCache]). */
    val translations: TranslationCache,
    /** Paquetes de idioma para traducir y su descarga (ver [TranslationPacks]). */
    val packs: TranslationPacks,
) : AndroidViewModel(application) {

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

    /* ── datos ────────────────────────────────────────────────── */
    var library by mutableStateOf(repo.current); private set
    var loaded by mutableStateOf(false); private set
    var installedPackages by mutableStateOf<Set<String>>(emptySet()); private set
    var metaProgress by mutableStateOf(engine.progress.value); private set

    /* ── capas superpuestas ───────────────────────────────────── */
    var dialog by mutableStateOf<DialogSpec?>(null); private set

    /** Lo escrito en el campo del diálogo (si lo lleva): aceptar con el dedo, con A o con Intro lee esto. */
    var dialogText by mutableStateOf(""); private set
    var sheet by mutableStateOf<ActionSheetSpec?>(null); private set

    /**
     * La card que abrió el menú (su rectángulo en coordenadas de ventana).
     *
     * El overlay se transforma desde ahí, así que el menú sale literalmente de
     * lo que se mantuvo pulsado (o de la card señalada, con mando). Null = no
     * viene de una card (menú de la app, ordenar…): aparece en su sitio.
     */
    var sheetOrigin by mutableStateOf<Rect?>(null); private set

    /** Rectángulo de la card seleccionada: de ahí sale el menú cuando se abre con el mando. */
    private var selectedCardBounds: Rect? = null

    /** Rectángulo de la card que se acaba de mantener pulsada (lo consume el siguiente menú). */
    private var pendingOrigin: Rect? = null
    var detailsKey by mutableStateOf<String?>(null); private set

    /**
     * La card de la que sale la ficha (como [sheetOrigin]): la seleccionada,
     * si la ficha es de ella. Null = aparece en su sitio (desde Masha o un atajo).
     */
    var detailsOrigin by mutableStateOf<Rect?>(null); private set
    var achievements by mutableStateOf<AchievementsState>(AchievementsState.Idle); private set
    var toast by mutableStateOf<UiText?>(null); private set
    var artPicker by mutableStateOf<ArtPickerState?>(null); private set

    /** De dónde sale el id con el que arranca un juego de PC (ver [PcGameIds]). */
    private val pcGameIds = PcGameIds(app.scanner)

    private val art = ArtSources(engine, repo, app.media)
    private var artJob: Job? = null

    /** El mando: traduce sus botones a lo que hace cada capa (ver [InputController]). */
    val input = InputController(this)

    /** La intro de arranque, encima de todo mientras se ve. */
    val intro = IntroController()

    val add = AddController(this)
    val settings = SettingsController(this)
    val sounds = SoundsController(this)
    val descriptions = DescriptionsController(this)
    /** "Editar nombre / Identificar juego" (ver [IdentifyController]). */
    val identify = IdentifyController(this)
    val masha = MashaController(this, brain)

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

    private class ItemsKey(
        val derived: Derived,
        val apps: List<AppEntry>,
        val sort: SortMode,
        val filter: LibraryFilter,
        val query: String,
    ) {
        fun sameAs(o: ItemsKey) =
            derived === o.derived && apps === o.apps && sort == o.sort && filter == o.filter && query == o.query
    }

    private var itemsCache: Pair<ItemsKey, List<LibraryItem>>? = null

    /**
     * Carpetas y apps mezcladas por nombre, con filtro y búsqueda (también dentro de las carpetas).
     *
     * Se llama varias veces por recomposición; con las mismas entradas devuelve
     * la misma lista (y los mismos elementos), así las cards pueden saltarse la
     * recomposición. Las entradas se leen siempre, para que Compose las siga.
     */
    fun items(): List<LibraryItem> {
        val key = ItemsKey(derived(), library.apps, settings.sortMode, filter, query)
        itemsCache?.let { (k, list) -> if (k.sameAs(key)) return list }
        return buildItems(key.derived).also { itemsCache = key to it }
    }

    private fun buildItems(d: Derived): List<LibraryItem> {
        val q = query.trim().lowercase()
        var list: List<LibraryItem> = d.folders + library.apps.map {
            LibraryItem.App(it, installedPackages.isEmpty() || it.packageName in installedPackages)
        }
        list = sorted(list)
        if (filter == LibraryFilter.Android) list = list.filterIsInstance<LibraryItem.App>()
        if (filter == LibraryFilter.Consoles) list = list.filterIsInstance<LibraryItem.Folder>()
        if (filter == LibraryFilter.Unnamed) list = list.filter { item ->
            when (item) {
                is LibraryItem.App -> needsName(item.app.displayTitle)
                is LibraryItem.Folder -> d.roms[item.folder.id].orEmpty().any { needsName(it.displayTitle) }
            }
        }
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

    /** El arte de reserva de una card del carrusel: su color sale del icono. */
    fun fallbackOf(item: LibraryItem): ArtFallback = when (item) {
        is LibraryItem.Folder -> ArtFallback(item.key, item.system.name, item.iconPath, item.emulatorPackage)
        is LibraryItem.App -> ArtFallback(item.key, item.app.displayTitle, item.app.meta.icon, item.app.packageName)
    }

    /** El arte de reserva de un juego sin carátula: su icono, si tiene, y su título. */
    fun romFallback(rom: RomEntry): ArtFallback = ArtFallback(rom.key, rom.displayTitle, rom.meta.icon)

    /** El arte de reserva de cualquier juego de la biblioteca por su clave. */
    fun fallbackForKey(key: String, title: String): ArtFallback {
        library.roms.firstOrNull { it.key == key }?.let { return romFallback(it) }
        library.apps.firstOrNull { it.key == key }?.let { return ArtFallback(it.key, it.displayTitle, it.meta.icon, it.packageName) }
        return ArtFallback(key, title)
    }

    fun currentFolder(): LibraryItem.Folder? = derived().folders.firstOrNull { it.folder.id == folderId }

    fun folderRoms(id: String?): List<RomEntry> {
        val all = id?.let { derived().roms[it] }.orEmpty()
        // Con el filtro "Sin nombre", dentro de la carpeta solo salen esos.
        return if (filter == LibraryFilter.Unnamed) all.filter { needsName(it.displayTitle) }.ifEmpty { all } else all
    }

    /** ¿A este juego le falta un nombre que sirva para buscarlo? (ver [NameCheck]). */
    fun needsName(title: String): Boolean = !NameCheck.isNameUsable(title)

    /** Hay algún juego sin nombre: solo entonces sale el filtro "Sin nombre". */
    val anyUnnamed: Boolean
        get() = library.apps.any { needsName(it.displayTitle) } || library.roms.any { needsName(it.displayTitle) }

    /** Los filtros que se enseñan (y que recorren L1/R1). */
    fun availableFilters(): List<LibraryFilter> =
        LibraryFilter.entries.filter { it != LibraryFilter.Unnamed || anyUnnamed || filter == LibraryFilter.Unnamed }

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
        if (target == Screen.Settings) settings.onOpen(fromPage = screen.isSettingsPage)
        screen = target
    }

    val canGoBack: Boolean
        get() = intro.visible || dialog != null || sheet != null || artPicker != null || identify.state != null || detailsKey != null || screen != Screen.Library || searchOpen

    /** Lo que hay abierto, para [BackPriority] ([keyboard]: el teclado en pantalla, que lo cierra el mando). */
    fun backState(keyboard: Boolean = false) = BackPriority.State(
        keyboard = keyboard,
        intro = intro.visible,
        dialog = dialog != null,
        sheet = sheet != null,
        artPicker = artPicker != null,
        identify = identify.state != null,
        details = detailsKey != null,
        screen = screen,
        priorityGrab = settings.priorityGrab != null,
        settingsPageOpen = settings.compact && settings.detailOpen,
        searchOpen = searchOpen,
    )

    fun back() {
        when (BackPriority.next(backState())) {
            BackPriority.Target.Intro -> intro.skip()
            BackPriority.Target.Dialog -> dialog = null
            BackPriority.Target.Sheet -> sheet = null
            BackPriority.Target.ArtPicker -> closeArtPicker()
            BackPriority.Target.Identify -> identify.close()
            BackPriority.Target.Details -> closeDetails()
            // En Ajustes, atrás suelta la fuente cogida y, en ventana estrecha, vuelve a la lista.
            BackPriority.Target.PriorityGrab -> settings.releaseGrab()
            BackPriority.Target.SettingsCategory -> settings.closeCategory()
            BackPriority.Target.SettingsPage -> go(Screen.Settings)
            BackPriority.Target.Screen -> go(Screen.Library)
            BackPriority.Target.Search -> toggleSearch()
            // El teclado lo cierra el mando (InputController) o el propio sistema.
            BackPriority.Target.Keyboard, BackPriority.Target.None -> Unit
        }
    }

    /**
     * Cambiar la selección suena a "moverse", se haga con el dedo o con el
     * mando (el paso del mando que no cambia nada ya lo filtra el reloj de
     * [SoundManager]: dos avisos seguidos en menos de 60 ms son uno).
     */
    fun select(key: String) {
        if (selected()?.key != key) sound.play(UiSound.Navigate)
        selectedKey = key
    }

    fun selectRom(key: String) {
        if (selectedRom()?.key != key) sound.play(UiSound.Navigate)
        selectedRomKey = key
    }

    fun updateFilter(f: LibraryFilter) { filter = f }

    /** Con el buscador cerrado se ignora: el campo sigue compuesto (ancho 0) y podría recibir teclas tardías. */
    fun updateQuery(q: String) { if (searchOpen) query = q }

    fun toggleSearch() {
        if (searchOpen) query = ""
        searchOpen = !searchOpen
    }

    /* ── abrir ────────────────────────────────────────────────── */

    /**
     * Juego Android que se está lanzando. Mientras no es null la pantalla sale
     * con la misma transición que al abrir una carpeta (ver `ElyndraApp`), y
     * el lanzamiento corre a la vez. Se vuelve a null al rato o si falla, y la
     * pantalla regresa sola.
     */
    var opening by mutableStateOf<LibraryItem?>(null); private set

    fun requestOpen(item: LibraryItem) {
        if (opening != null) return
        if (item is LibraryItem.Folder) {
            open(item)
            return
        }
        opening = item
        open(item)
        viewModelScope.launch {
            delay(OPENING_TIMEOUT_MS)
            if (opening === item) opening = null
        }
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
        launchJob?.cancel()
        launchJob = viewModelScope.launch {
            val outcome = launcher.launchApp(entry.packageName)
            orchestrator.record(entry.key, null, null, entry.packageName, outcomeId(outcome))
            when (outcome) {
                GameLauncher.Outcome.Started -> {
                    sound.play(UiSound.Launch)
                    startSession(entry.key, null, entry.packageName)
                }
                else -> {
                    opening = null
                    showDialog(
                        DialogSpec(
                            error = true,
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

    /**
     * Lanza una ROM.
     *
     * Con qué emulador lo decide el orquestador ([LaunchOrchestrator]): lo que
     * eligió el usuario manda, pero si no está instalado y otro compatible sí,
     * o si con este juego viene fallando y otro le ha ido bien, Masha lo dice
     * antes de lanzar y deja elegir. Nunca cambia de emulador por su cuenta.
     */
    fun openRom(rom: RomEntry) {
        val folder = repo.folder(rom.folderId) ?: return
        if (!app.files.hasPermission(folder.treeUri)) {
            showDialog(
                DialogSpec(
                    error = true,
                    title = UiText.res(R.string.dialog_permission_lost_title),
                    message = UiText.res(R.string.dialog_permission_lost_msg, folder.displayPath),
                    confirm = DialogButton(UiText.res(R.string.ok)) {},
                ),
            )
            return
        }
        launchJob?.cancel()
        launchJob = viewModelScope.launch {
            when (val decision = orchestrator.decide(rom, folder)) {
                is LaunchDecision.Go -> launchRomWith(rom, folder, decision.emulatorId)
                is LaunchDecision.UseInstead -> offerInstalledEmulator(rom, folder, decision)
                is LaunchDecision.SuggestSwitch -> offerSwitch(rom, folder, decision)
                is LaunchDecision.NoneInstalled -> {
                    val emuId = decision.configured ?: decision.recommended
                    if (emuId == null) {
                        showDialog(
                            DialogSpec(
                                error = true,
                                title = UiText.res(R.string.dialog_no_emulator_title),
                                message = UiText.res(R.string.dialog_no_emulator_msg),
                                confirm = DialogButton(UiText.res(R.string.change_emulator)) { pickFolderEmulator(folder) },
                                dismiss = DialogButton(UiText.res(R.string.close)) {},
                            ),
                        )
                    } else {
                        emulatorMissing(emuId, emulatorName(emuId), folder, rom)
                    }
                }
            }
        }
    }

    /** El lanzamiento en sí, ya decidido el emulador: directo al juego, sin velo. */
    private suspend fun launchRomWith(rom: RomEntry, folder: RomFolder, emuId: String) {
        val emuName = emulatorName(emuId)
        val vitaTitle = if (folder.systemId == "psvita") {
            withContext(Dispatchers.IO) {
                app.scanner.readSmallText(Uri.parse(folder.treeUri), rom.docId, 256)?.lineSequence()?.firstOrNull()?.trim()
            }
        } else null
        val pcId = pcGameIds.resolve(folder, rom)
        val ref = launcher.romRef(folder, rom, vitaTitle, pcId.id, pcId.assigned)
        val outcome = launcher.launchRom(emuId, ref)
        val pkg = orchestrator.packageFor(emuId)
        orchestrator.record(rom.key, rom.systemId, emuId, pkg, outcomeId(outcome))
        if (outcome == GameLauncher.Outcome.Started) {
            sound.play(UiSound.Launch)
            startSession(rom.key, emuId, pkg)
            return
        }
        // No se pudo arrancar el juego directamente: el emulador está abierto y
        // el usuario lo elige dentro. La sesión se mide igual.
        if (outcome == GameLauncher.Outcome.OpenedApp) {
            sound.play(UiSound.Launch)
            startSession(rom.key, emuId, pkg)
            showToast(UiText.res(R.string.toast_pick_game_in_app, emuName))
            return
        }
        when (outcome) {
            GameLauncher.Outcome.NotInstalled -> emulatorMissing(emuId, emuName, folder, rom)
            GameLauncher.Outcome.NeedsPath -> showDialog(
                DialogSpec(
                    error = true,
                    title = UiText.res(R.string.dialog_needs_path_title),
                    message = UiText.res(R.string.dialog_needs_path_msg, emuName),
                    confirm = DialogButton(UiText.res(R.string.choose_other)) { pickFolderEmulator(folder) },
                    dismiss = DialogButton(UiText.res(R.string.close)) {},
                ),
            )
            GameLauncher.Outcome.NeedsVitaTitle -> showDialog(
                DialogSpec(
                    error = true,
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
                    error = true,
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
                    error = true,
                    title = UiText.res(R.string.dialog_launch_failed_title),
                    message = UiText.res(R.string.dialog_launch_failed_msg, emuName, outcome.reason),
                    confirm = DialogButton(UiText.res(R.string.choose_other)) { pickFolderEmulator(folder) },
                    dismiss = DialogButton(UiText.res(R.string.close)) {},
                ),
            )
            GameLauncher.Outcome.Started, GameLauncher.Outcome.OpenedApp -> Unit
        }
    }

    /**
     * El emulador elegido no está instalado y otro compatible sí: Masha lo
     * ofrece. Aceptar lo guarda donde estaba la elección (el juego o su
     * carpeta) y lanza; "Instalar" abre la tienda del que falta.
     */
    private fun offerInstalledEmulator(rom: RomEntry, folder: RomFolder, d: LaunchDecision.UseInstead) {
        val missing = emulatorName(d.configured)
        val alternative = d.alternative.emulatorId
        val altName = emulatorName(alternative)
        showDialog(
            DialogSpec(
                title = UiText.res(R.string.masha_dialog_missing_title, missing),
                message = UiText.res(R.string.masha_dialog_missing_msg, missing, altName),
                confirm = DialogButton(UiText.res(R.string.masha_use_emulator, altName)) {
                    if (rom.emulatorId != null) repo.setRomEmulator(rom.id, alternative) else repo.setFolderEmulator(folder.id, alternative)
                    launchJob = viewModelScope.launch { launchRomWith(rom, folder, alternative) }
                },
                dismiss = DialogButton(UiText.res(R.string.close)) {},
                extra = Emulators.byId(d.configured)?.let { p ->
                    DialogButton(UiText.res(R.string.install)) { openExternal(launcher.storeIntent(p)) }
                },
            ),
        )
    }

    /**
     * Con este juego, el emulador elegido viene saliéndose al instante y otro
     * instalado le ha ido bien. Masha lo propone para *este* juego; seguir con
     * el de siempre también lanza, sin más preguntas.
     */
    private fun offerSwitch(rom: RomEntry, folder: RomFolder, d: LaunchDecision.SuggestSwitch) {
        val current = emulatorName(d.configured)
        val alternative = d.alternative.emulatorId
        val altName = emulatorName(alternative)
        val bad = d.struggling.earlyExits + d.struggling.failedLaunches
        showDialog(
            DialogSpec(
                title = UiText.res(R.string.masha_dialog_switch_title),
                message = UiText.res(R.string.masha_dialog_switch_msg, bad, current, altName),
                confirm = DialogButton(UiText.res(R.string.masha_use_emulator, altName)) {
                    repo.setRomEmulator(rom.id, alternative)
                    launchJob = viewModelScope.launch { launchRomWith(rom, folder, alternative) }
                },
                dismiss = DialogButton(UiText.res(R.string.masha_keep_emulator, current)) {
                    launchJob = viewModelScope.launch { launchRomWith(rom, folder, d.configured) }
                },
            ),
        )
    }

    /** La línea de Masha en el velo, en palabras. */
    private fun outcomeId(outcome: GameLauncher.Outcome): String = when (outcome) {
        GameLauncher.Outcome.Started, GameLauncher.Outcome.OpenedApp -> LaunchOutcome.STARTED
        GameLauncher.Outcome.NotInstalled -> LaunchOutcome.NOT_INSTALLED
        GameLauncher.Outcome.NeedsPath -> LaunchOutcome.NEEDS_PATH
        GameLauncher.Outcome.NeedsVitaTitle -> LaunchOutcome.NEEDS_VITA_TITLE
        GameLauncher.Outcome.NeedsPcLauncher -> LaunchOutcome.NEEDS_PC_LAUNCHER
        is GameLauncher.Outcome.Failed -> LaunchOutcome.FAILED
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
        if (launcher.launchApp(pkg) == GameLauncher.Outcome.Started) sound.play(UiSound.Launch)
    }

    private fun emulatorMissing(emuId: String, emuName: String, folder: RomFolder, rom: RomEntry) {
        val profile = Emulators.byId(emuId)
        showDialog(
            DialogSpec(
                error = true,
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

    private suspend fun startSession(key: String, emulatorId: String?, packageName: String?) {
        repo.recordLaunch(key)
        sessions.begin(key, emulatorId, packageName)
    }

    fun openExternal(intent: Intent) {
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openUrl(url: String) = openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /* ── ciclo de vida ────────────────────────────────────────── */

    /**
     * La app vuelve a primer plano: cierra la sesión de juego medida, refresca
     * lo instalado y le da a Masha la ocasión de decir algo (un paso de arco
     * cumplido, una salida sospechosamente rápida, la sugerencia del momento).
     */
    fun onForeground() {
        settings.refreshLanguage()
        app.settings.lastOpenedAt = System.currentTimeMillis()
        refreshInventory()
        viewModelScope.launch {
            repo.awaitLoaded()
            // Si cambió el idioma de la app (o hay descripciones sin idioma de antes),
            // se piden las del idioma de ahora en segundo plano; no hay nada que esperar.
            engine.refreshDescriptions()
            // Si el idioma cambió y falta un paquete de traducción, se ofrece (sin bajar nada aún).
            descriptions.onAppLanguage(settings.lang)
            val session = sessions.finish()
            if (session != null) afterSession(session)
            // Lo que Masha tenga que decir sale ya, con la sesión recién cerrada:
            // no espera al inventario de emuladores, que no lo necesita.
            masha.refreshInsight()
            refreshInstalled()
            if (library.folders.isNotEmpty() && System.currentTimeMillis() - library.lastAutoScan > AUTO_RESCAN_MS) {
                repo.markAutoScan(System.currentTimeMillis())
                lastPs4Scan = System.currentTimeMillis()
                library.folders.forEach { rescan(it, silent = true) }
            } else {
                // Lo que se instaló en Bachata mientras tanto aparece al volver.
                rescanPs4Folders()
            }
        }
    }

    /**
     * Inventario de emuladores instalados, aparte: son un par de cientos de
     * consultas al PackageManager y nada de la pantalla depende de ellas.
     */
    private fun refreshInventory() {
        viewModelScope.launch { runCatching { inventory.refresh() } }
    }

    /** La app pasa a segundo plano (normalmente, porque arrancó el juego). */
    fun onBackground() {
        launchJob?.cancel()
    }

    /**
     * Lo que Masha comenta al volver de jugar, en una línea y solo si vale la
     * pena: un paso de arco cumplido, o una segunda salida seguida en segundos
     * con el mismo emulador (a la primera puede ser cualquier cosa).
     */
    private suspend fun afterSession(session: PlaySession) {
        val closed = runCatching { brain.syncArcs() }.getOrDefault(emptyList())
        closed.lastOrNull()?.let { (arcTitle, _) -> showToast(UiText.res(R.string.masha_toast_arc_step, arcTitle)) }
        val emulator = session.emulatorId
        if (closed.isEmpty() && session.earlyExit && emulator != null) {
            val previous = library.sessions.lastOrNull { it.key == session.key && it.start < session.start }
            if (previous?.earlyExit == true && previous.emulatorId == emulator) {
                val title = repo.romByKey(session.key)?.displayTitle
                if (title != null) showToast(UiText.res(R.string.masha_toast_early_exit, emulatorName(emulator), title))
            }
        }
        work.refreshWidgetNow()
    }

    /* ── lo que Masha necesita saber de la interfaz ───────────── */

    /** Qué pantalla está mirando el usuario, para el contexto de Masha. */
    fun screenName(): String = when {
        detailsKey != null -> "details"
        screen == Screen.Folder -> "folder:" + (currentFolder()?.system?.name ?: "")
        else -> screen.name.lowercase()
    }

    /** Lo seleccionado: la ficha abierta, la ROM de la carpeta o la card del carrusel. */
    fun focusName(): String? {
        detailsKey?.let { key -> return repo.romByKey(key)?.displayTitle ?: repo.appByKey(key)?.displayTitle }
        return when (screen) {
            Screen.Folder -> selectedRom()?.displayTitle
            Screen.Library -> selected()?.name
            else -> null
        }
    }

    /** "Enséñame mis juegos de X": vuelve a la biblioteca con ese filtro y esa búsqueda. */
    fun showLibraryFiltered(text: String, category: LibraryFilter) {
        go(Screen.Library)
        filter = category
        if (text.isBlank()) {
            query = ""
            searchOpen = false
        } else {
            searchOpen = true
            query = text
        }
    }

    /** Desde el widget: lanzar ese juego en cuanto la biblioteca esté cargada (arranque en frío incluido). */
    fun launchFromShortcut(key: String) {
        viewModelScope.launch {
            repo.awaitLoaded()
            openByKey(key)
        }
    }

    /** Desde un aviso de Masha: la ficha del juego, para decidir con calma. */
    fun showFromShortcut(key: String) {
        viewModelScope.launch {
            repo.awaitLoaded()
            go(Screen.Library)
            if (repo.romByKey(key) != null || repo.appByKey(key) != null) showDetails(key)
        }
    }

    fun setRomEmulatorByKey(key: String, emulatorId: String?) {
        repo.romByKey(key)?.let { repo.setRomEmulator(it.id, emulatorId) }
    }

    /**
     * Metadatos a demanda (Masha, sugerencias): [keys] null = toda la
     * biblioteca. Sin [force] solo se completa lo que falta.
     */
    fun updateMetadata(keys: List<String>?, force: Boolean) {
        if (!app.credentials.anyConfigured()) {
            showToast(UiText.res(R.string.configure_a_service))
            return
        }
        if (keys != null && keys.isEmpty()) return
        engine.start(keys, force)
        showToast(UiText.res(R.string.toast_metadata_started))
    }

    /** Servicios de imágenes en el orden de prioridad del usuario (Ajustes → Metadatos). */
    private fun artServices(): List<Service> = metadataPriority.get().art


    /* ── carpetas ─────────────────────────────────────────────── */

    /** Reescanea una carpeta. En modo silencioso no avisa ni borra nada si la carpeta no responde. */
    suspend fun rescan(folder: RomFolder, silent: Boolean): LibraryRepository.ScanDiff? {
        if (!app.files.hasPermission(folder.treeUri)) return null
        val system = Systems.byId(folder.systemId) ?: return null
        val tree = Uri.parse(folder.treeUri)
        val found = if (system.id == Ps4.SYSTEM_ID) {
            val ps4 = runCatching { app.scanner.scanPs4(tree, folder.rootDocId) }.getOrNull() ?: return null
            ps4NotInstalled = ps4NotInstalled + (folder.id to ps4.notInstalled.size)
            ps4.found
        } else {
            runCatching { app.scanner.scan(tree, folder.rootDocId, system) }.getOrNull() ?: return null
        }
        val before = library.roms.count { it.folderId == folder.id }
        if (found.isEmpty() && before > 0) {
            if (!silent) showToast(UiText.res(R.string.toast_folder_unreachable, folder.displayPath))
            return null
        }
        val diff = repo.mergeScan(folder.id, found)
        if (system.id == Ps4.SYSTEM_ID) importPs4Art(folder)
        if (diff.added.isNotEmpty() && app.settings.autoMeta) engine.start(diff.added, force = false)
        if (!silent) showToast(UiText.res(R.string.toast_rescan, diff.added.size, diff.removed))
        return diff
    }

    /* ── lo que hay en el dispositivo y no está en la biblioteca (Masha) ── */

    /** Un juego de una carpeta con acceso que no está en la biblioteca. */
    data class AddableRom(val folder: RomFolder, val found: RomScanner.Found, val title: String, val removedBefore: Boolean)

    /** Apps instaladas que no están en la biblioteca (los juegos primero). */
    suspend fun addableApps(): List<InstalledApp> {
        val inLibrary = library.apps.map { it.packageName }.toSet()
        return installedApps().filter { it.packageName !in inLibrary && it.packageName != app.packageName }
            .sortedWith(compareByDescending<InstalledApp> { it.isGame }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /**
     * ROMs y juegos de PC en carpetas a las que Elyndra ya tiene acceso pero
     * que no están en la biblioteca: los nuevos (descargados después del
     * último análisis) y los que el usuario quitó. Solo lee: no añade nada.
     */
    suspend fun addableRoms(): List<AddableRom> = withContext(Dispatchers.IO) {
        library.folders.flatMap { folder ->
            if (!app.files.hasPermission(folder.treeUri)) return@flatMap emptyList()
            val system = Systems.byId(folder.systemId) ?: return@flatMap emptyList()
            val tree = Uri.parse(folder.treeUri)
            val found = if (system.id == Ps4.SYSTEM_ID) {
                runCatching { app.scanner.scanPs4(tree, folder.rootDocId).found }.getOrNull()
            } else {
                runCatching { app.scanner.scan(tree, folder.rootDocId, system) }.getOrNull()
            }.orEmpty()
            val present = library.roms.filter { it.folderId == folder.id }.map { it.docId }.toSet()
            found.filter { it.docId !in present }.map { f ->
                AddableRom(folder, f, f.title ?: Names.cleanTitle(f.name, stripExtension = !f.isDir), removedBefore = f.docId in folder.excluded)
            }
        }
    }

    /** Añade juegos de carpetas (ver [addableRoms]); devuelve las claves nuevas. */
    fun addRomsToLibrary(items: List<AddableRom>): List<String> {
        val keys = items.groupBy { it.folder.id }.flatMap { (folderId, list) -> repo.addRoms(folderId, list.map { it.found }) }
        if (keys.isNotEmpty() && app.settings.autoMeta) engine.start(keys, force = false)
        return keys
    }

    /** La pantalla Añadir en la pestaña de ROMs (las carpetas las elige el usuario en el selector del sistema). */
    fun openAddRoms() {
        go(Screen.Add)
        add.updateTab(AddTab.Roms)
    }

    /**
     * Pone [emulatorId] en todas las carpetas de [systemId]. Devuelve lo que
     * tenía cada carpeta, para poder deshacerlo.
     */
    fun setSystemEmulator(systemId: String, emulatorId: String): Map<String, String?> {
        val folders = library.folders.filter { it.systemId == systemId }
        val before = folders.associate { it.id to it.emulatorId }
        folders.forEach { repo.setFolderEmulator(it.id, emulatorId) }
        return before
    }

    fun restoreFolderEmulators(before: Map<String, String?>) {
        before.forEach { (id, emu) -> repo.setFolderEmulator(id, emu) }
    }

    /* ── PlayStation 4 (Bachata S4) ───────────────────────────── */

    /**
     * Paquetes de juego (.pkg) sin extraer en cada carpeta de PS4, por el id de
     * la carpeta. Se recalcula en cada análisis; lo enseña la cabecera de la carpeta.
     */
    var ps4NotInstalled by mutableStateOf<Map<String, Int>>(emptyMap()); private set

    private var lastPs4Scan = 0L

    /**
     * Carpetas de PS4 al volver a Elyndra (de Bachata, normalmente): los juegos
     * que se acaban de instalar aparecen solos y los que desaparecieron se van.
     * Solo esas carpetas, y como mucho cada [PS4_RESCAN_MS]; el análisis lee
     * pocas carpetas y cabeceras, y mergeScan + LibraryDiff solo escriben lo que cambia.
     */
    private suspend fun rescanPs4Folders() {
        val now = System.currentTimeMillis()
        if (now - lastPs4Scan < PS4_RESCAN_MS) return
        val folders = library.folders.filter { it.systemId == Ps4.SYSTEM_ID }
        if (folders.isEmpty()) return
        lastPs4Scan = now
        folders.forEach { rescan(it, silent = true) }
    }

    /**
     * Icono y fondo del propio juego (`sce_sys/icon0.png` y `pic1.png`) para
     * los que aún no tienen. Van como imagen de origen "local", sin fijar: si
     * un servicio de metadatos trae la suya, la sustituye; si ninguno trae
     * nada, se queda esta.
     */
    private suspend fun importPs4Art(folder: RomFolder) = withContext(Dispatchers.IO) {
        val tree = Uri.parse(folder.treeUri)
        val local = LocalMedia(app.contentResolver)
        for (rom in library.roms.filter { it.folderId == folder.id && it.serial != null }) {
            val needIcon = rom.meta.icon == null
            val needHero = rom.meta.hero == null
            if (!needIcon && !needHero) continue
            fun image(vararg names: String): LocalImage? = names.firstNotNullOfOrNull { name ->
                app.scanner.ps4SystemFile(tree, rom.docId, name)
                    ?.let { local.readImage(DocumentsContract.buildDocumentUriUsingTree(tree, it)) }
            }
            val icon = if (needIcon) image("icon0.png")?.let { app.media.save(it.bytes, rom.key, "icon", it.extension) } else null
            val hero = if (needHero) image("pic1.png", "pic0.png")?.let { app.media.save(it.bytes, rom.key, "hero", it.extension) } else null
            if (icon == null && hero == null) continue
            repo.updateMeta(rom.key) { old ->
                val origins = old.artOrigins.toMutableMap()
                if (icon != null && old.icon == null) origins["icon"] = ArtOrigin(LOCAL_SOURCE)
                if (hero != null && old.hero == null) origins["hero"] = ArtOrigin(LOCAL_SOURCE)
                old.copy(icon = old.icon ?: icon, hero = old.hero ?: hero, artOrigins = origins)
            }
        }
    }

    /**
     * Una carpeta recién añadida: si es de PS4, se completa ya lo que el alta
     * no hace (arte local y paquetes sin instalar) con un análisis silencioso.
     */
    fun onFolderAdded(folder: RomFolder) {
        if (folder.systemId != Ps4.SYSTEM_ID) return
        viewModelScope.launch {
            lastPs4Scan = System.currentTimeMillis()
            rescan(folder, silent = true)
        }
    }

    /** Abre Bachata S4 (desde la fila de paquetes sin instalar o el menú de la carpeta). */
    fun openBachata() {
        val pkg = BachataS4.PACKAGES.firstOrNull { launcher.isPackageInstalled(it) }
        if (pkg == null) {
            Emulators.byId(BachataS4.PROFILE_ID)?.let { openExternal(launcher.storeIntent(it)) }
            return
        }
        if (launcher.launchApp(pkg) == GameLauncher.Outcome.Started) {
            sound.play(UiSound.Launch)
        } else {
            sound.play(UiSound.Error)
            showToast(UiText.res(R.string.dialog_launch_failed_title))
        }
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
     * La card que coincide se desintegra (ver `DisintegratingContainer`) y el
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
                destructive = true,
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
                destructive = true,
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
                destructive = true,
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
    /** Deja apuntado de qué card sale el menú antes de abrirlo (pulsación larga). */
    fun markSheetOrigin(bounds: Rect?) {
        pendingOrigin = bounds
    }

    /**
     * La card seleccionada se ha colocado en [bounds]: si el menú se abre con
     * el mando, sale de aquí. No es estado de Compose (cambia al desplazar el
     * carrusel y nadie lo pinta).
     */
    fun noteSelectedCard(bounds: Rect) {
        selectedCardBounds = bounds
    }

    /** El origen del menú de una card: la que se mantuvo pulsada o, con mando, la señalada. */
    private fun cardOrigin(): Rect? = (pendingOrigin ?: selectedCardBounds).also { pendingOrigin = null }

    fun itemOptions(item: LibraryItem) {
        val groups = when (item) {
            is LibraryItem.Folder -> listOf(
                SheetGroup(
                    UiText.res(R.string.sheet_group_play),
                    listOf(
                        SheetAction(UiText.res(R.string.open), icon = SheetIcon.Play, primary = true) { open(item) },
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
                    ) + restoreAction(item.folder) + listOfNotNull(
                        // PS4: los .pkg se instalan en Bachata, no aquí.
                        SheetAction(
                            UiText.res(R.string.open_bachata),
                            detail = ps4NotInstalled[item.folder.id]?.takeIf { it > 0 }
                                ?.let { UiText.plural(R.plurals.ps4_pkgs_not_installed, it, it) },
                            icon = SheetIcon.App,
                        ) { openBachata() }.takeIf { item.folder.systemId == Ps4.SYSTEM_ID },
                    ),
                ),
                removalGroup(UiText.res(R.string.remove_folder)) { removeFolder(item.folder) },
            )

            is LibraryItem.App -> listOf(
                SheetGroup(
                    UiText.res(R.string.sheet_group_play),
                    listOf(
                        SheetAction(UiText.res(R.string.sheet_play), icon = SheetIcon.Play, primary = true) { open(item) },
                        SheetAction(UiText.res(R.string.details), icon = SheetIcon.Details, opensSheet = true) { showDetails(item.key) },
                    ),
                ),
                artworkGroup(item.key, item.app.displayTitle),
                SheetGroup(
                    UiText.res(R.string.sheet_group_manage),
                    listOf(
                        SheetAction(UiText.res(R.string.identify_action), icon = SheetIcon.Search, opensSheet = true) { identify.open(item.key) },
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
        showSheet(ActionSheetSpec(UiText.Raw(item.name), subtitle, groups, thumbOf(item), heroOf(item)), cardOrigin())
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
                            SheetAction(UiText.res(R.string.sheet_play), icon = SheetIcon.Play, primary = true) { openRom(rom) },
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
                            SheetAction(UiText.res(R.string.identify_action), icon = SheetIcon.Search, opensSheet = true) { identify.open(rom.key) },
                            SheetAction(UiText.res(R.string.refresh_metadata), icon = SheetIcon.Refresh) { refreshMetadata(listOf(rom.key)) },
                        ),
                    ),
                    removalGroup(UiText.res(R.string.remove_game)) { removeRom(rom) },
                ),
                SheetThumb(coverPath = rom.meta.cover, fallback = romFallback(rom)),
                heroOf(rom),
            ),
            cardOrigin(),
        )
    }

    /* ── cabecera de juego del menú ───────────────────────────── */

    /** La cabecera de la ficha: el mismo arte, logo y fondo que el menú de acciones. */
    fun detailsHero(key: String): SheetHero? {
        repo.romByKey(key)?.let { return heroOf(it) }
        return repo.appByKey(key)?.let { heroOf(LibraryItem.App(it, installed = true)) }
    }

    private fun heroOf(item: LibraryItem): SheetHero = when (item) {
        is LibraryItem.Folder -> SheetHero(
            backgroundPath = item.heroPath,
            logoPath = item.logoPath,
            coverPath = item.coverPath,
            iconPath = item.iconPath,
            packageName = item.emulatorPackage,
            fallback = fallbackOf(item),
            info = listOfNotNull(
                UiText.Raw(item.system.name),
                item.emulatorName?.let { UiText.Raw(it) },
                item.minutes.takeIf { it > 0 }?.let { UiText.Raw(fmtMinutes(it)) },
            ),
        )
        is LibraryItem.App -> SheetHero(
            backgroundPath = item.app.meta.hero ?: item.app.meta.screenshot,
            logoPath = item.app.meta.logo,
            coverPath = item.app.meta.cover,
            iconPath = item.app.meta.icon,
            packageName = item.app.packageName,
            fallback = fallbackOf(item),
            info = playInfo(item.app.stats.minutes, item.app.stats.lastPlayed) + UiText.Raw("Android"),
        )
    }

    private fun heroOf(rom: RomEntry): SheetHero {
        val folder = repo.folder(rom.folderId)
        val emulator = (rom.emulatorId ?: folder?.emulatorId)?.let { emulatorName(it) }
        return SheetHero(
            backgroundPath = rom.meta.hero ?: rom.meta.screenshot,
            logoPath = rom.meta.logo,
            coverPath = rom.meta.cover,
            iconPath = rom.meta.icon,
            fallback = romFallback(rom),
            info = playInfo(rom.stats.minutes, rom.stats.lastPlayed) +
                listOfNotNull(Systems.byId(rom.systemId)?.name?.let { UiText.Raw(it) }, emulator?.let { UiText.Raw(it) }),
        )
    }

    /** Tiempo jugado y última partida ("hoy", "ayer", "hace 3 días"); sin jugar, eso. */
    private fun playInfo(minutes: Int, lastPlayed: Long): List<UiText> {
        if (minutes <= 0 && lastPlayed <= 0) return listOf(UiText.res(R.string.never_played))
        val out = ArrayList<UiText>(2)
        if (minutes > 0) out += UiText.Raw(fmtMinutes(minutes))
        if (lastPlayed > 0) {
            val days = ((System.currentTimeMillis() - lastPlayed) / 86_400_000L).toInt().coerceAtLeast(0)
            out += when (days) {
                0 -> UiText.res(R.string.masha_today)
                1 -> UiText.res(R.string.masha_yesterday)
                else -> UiText.plural(R.plurals.masha_days_ago, days, days)
            }
        }
        return out
    }

    /** La carátula (o el icono) que se enseña en la cabecera de la hoja. */
    private fun thumbOf(item: LibraryItem): SheetThumb = when (item) {
        is LibraryItem.Folder -> SheetThumb(
            coverPath = item.coverPath,
            iconPath = item.iconPath ?: item.logoPath,
            packageName = item.emulatorPackage,
            fallback = fallbackOf(item),
        )
        is LibraryItem.App -> SheetThumb(
            coverPath = item.app.meta.cover,
            iconPath = item.app.meta.icon,
            packageName = item.app.packageName,
            fallback = fallbackOf(item),
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
                    preview = art.path(key, kind),
                    opensSheet = true,
                ) { chooseArtSource(key, title, kind) }
            }
            if (has) {
                clear += SheetAction(
                    UiText.res(kind.removeLabel()),
                    destructive = true,
                    icon = SheetIcon.Remove,
                    // Quitar una imagen no pasa por un diálogo: el menú lo protege.
                    holdToConfirm = true,
                ) { clearArt(key, kind) }
            }
        }
        return SheetGroup(UiText.res(R.string.sheet_group_artwork), set + clear, GroupStyle.Thumbnails)
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
                    for (service in artServices()) {
                        if (!app.credentials.isConfigured(service) || !art.supports(key, service)) continue
                        val url = runCatching { art.candidates(key, kind, service) }
                            .getOrNull()?.firstOrNull()?.url ?: continue
                        if (runCatching { art.apply(key, kind, url, service) is MediaResult.Saved }.getOrDefault(false)) {
                            // Si es la carpeta que se está mirando, la imagen
                            // se monta desde el polvo en cuanto llega; si no,
                            // entra sin más, que no hay nadie delante.
                            if (key == selectedKey) materializeArt(key, kind)
                            break
                        }
                    }
                }
            }
        }
    }

    /**
     * Pone automáticamente una imagen a un elemento, con el primer candidato
     * que dé algún servicio configurado. Es lo que usan tanto el arte
     * automático de las carpetas como las acciones de Masha.
     */
    suspend fun applyArtAuto(key: String, kind: ArtKind): Boolean {
        if (!app.credentials.anyConfigured()) return false
        for (service in artServices()) {
            if (!app.credentials.isConfigured(service) || !art.supports(key, service)) continue
            val url = runCatching { art.candidates(key, kind, service) }
                .getOrNull()?.firstOrNull()?.url ?: continue
            if (runCatching { art.apply(key, kind, url, service) is MediaResult.Saved }.getOrDefault(false)) return true
        }
        return false
    }

    /* ── acciones que puede ejecutar Masha ────────────────── */

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

    /** Apps instaladas en el teléfono, para que Masha pueda añadir una. */
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
        // Carátula, logo y fondo se ven como imagen suelta en pantalla, así
        // que se deshacen primero y se borran después. El icono no: vive
        // dentro de la card y no tiene una imagen propia que desintegrar, así
        // que esperar a una animación que nadie pinta solo lo haría tardar
        // más. Ese se quita al momento, como siempre.
        if (kind == ArtKind.Icon) clear() else vanishArt(key, kind, clear)
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

    /**
     * "Elegir de la galería": el usuario pone su propia carátula, fondo, logo
     * o icono desde el carrete, sin pasar por ningún servicio ni necesitar
     * credenciales. Queda fijada igual que una descargada.
     */
    fun pickLocalArt(key: String, kind: ArtKind) {
        mediaRequest = MediaRequest(IMAGE_MIME_TYPES) { uri ->
            if (uri == null) return@MediaRequest
            viewModelScope.launch {
                repo.flush()
                val image = withContext(Dispatchers.IO) { app.localMedia.readImage(uri) }
                if (image == null) {
                    showToast(UiText.res(R.string.art_local_failed))
                    return@launch
                }
                val ok = try {
                    art.applyLocal(key, kind, image)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (ok) materializeArt(key, kind)
                showToast(UiText.res(if (ok) R.string.art_applied else R.string.art_local_failed))
            }
        }
    }

    /** Hoja con los cuatro servicios; los que no están configurados (o no cubren el juego) salen atenuados. */
    private fun chooseArtSource(key: String, title: String, kind: ArtKind) {
        val actions = artServices().map { service ->
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
        // Si la imagen es una elegida a mano, se puede volver a la automática.
        val reset = SheetAction(UiText.res(R.string.art_reset), icon = SheetIcon.Refresh) { resetArt(key, kind) }
            .takeIf { art.isPinned(key, kind) }
        showSheet(
            ActionSheetSpec(
                UiText.res(kind.label()),
                UiText.Raw(title),
                listOf(
                    SheetGroup(UiText.res(R.string.art_group_yours), listOfNotNull(gallery, reset)),
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
            // Lo pendiente de la biblioteca se escribe antes de descargar nada:
            // pase lo que pase después, lo hecho hasta aquí ya está en disco.
            repo.flush()
            val result = try {
                art.apply(state.key, state.kind, candidate.url, state.service)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MediaResult.Failed(MediaFailure.Storage)
            }
            if (result is MediaResult.Saved) {
                materializeArt(state.key, state.kind)
                artPicker = null
                showToast(UiText.res(R.string.art_applied))
            } else {
                // Se queda la imagen que había y el selector abierto: se puede elegir otra.
                artPicker = artPicker?.takeIf { it.key == state.key }?.copy(applying = null)
                showToast(UiText.res(artFailureText((result as MediaResult.Failed).reason)))
            }
        }
    }

    private fun artFailureText(reason: MediaFailure): Int = when (reason) {
        MediaFailure.Network -> R.string.art_failed_network
        MediaFailure.Http, MediaFailure.Storage -> R.string.art_apply_failed
        MediaFailure.TooBig, MediaFailure.TooSmall, MediaFailure.Unsupported, MediaFailure.Undecodable -> R.string.art_failed_image
    }

    /** "Restablecer automático": fuera la imagen elegida a mano y se vuelve a pedir la de los metadatos. */
    fun resetArt(key: String, kind: ArtKind) {
        viewModelScope.launch {
            art.clear(key, kind)
            if (key.startsWith("f:")) autoFolderArt(listOf(key)) else engine.refresh(key)
            showToast(UiText.res(R.string.art_reset_done))
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
        // De la card seleccionada (con X o desde su menú de acciones, que
        // vuelve a ella mientras la ficha sale), como el menú.
        val fromCard = when (screen) {
            Screen.Library -> selected()?.key == key
            Screen.Folder -> selectedRom()?.key == key
            else -> false
        }
        detailsOrigin = if (fromCard) selectedCardBounds else null
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
        dialogText = spec.input?.initial.orEmpty()
        dialog = spec
        input.onDialogShown()
    }

    fun dismissDialog() { dialog = null }

    fun updateDialogText(text: String) { dialogText = text }

    /** [origin]: la card de la que sale el menú (ver [sheetOrigin]); null = de ninguna. */
    fun showSheet(spec: ActionSheetSpec, origin: Rect? = null) {
        sheetOrigin = origin
        sheet = spec
        input.onSheetShown()
    }

    /**
     * El menú de la app, para quien juega con mando.
     *
     * Con el dedo, buscar, ordenar, añadir, Ajustes y Masha están repartidos
     * por la pantalla —cabecera, carrusel, botón flotante—. Con mando no hay
     * puntero que los alcance, así que L3/R3 los junta aquí en una sola hoja.
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
        // `viewModelScope` ya está cancelado cuando se llama a onCleared: el
        // guardado pendiente va en el ámbito de la aplicación.
        app.scope.launch { repo.flush() }
        super.onCleared()
    }

    companion object {
        /**
         * Margen máximo que espera un borrado a su animación. Holgado sobre
         * los ~620 ms del efecto: es la red de seguridad para cuando nadie
         * está pintando lo que se quita, no el tiempo normal de espera.
         */
        private const val VANISH_TIMEOUT_MS = 1_200L

        /** Lo que dura la pantalla fuera tras lanzar un juego, antes de volver sola. */
        private const val OPENING_TIMEOUT_MS = 1_100L

        /**
         * Margen que espera una marca de "móntate" a que alguien la pinte.
         * Holgado sobre lo que tarda el efecto (esperar a la imagen + ~0,7 s
         * de montaje): es limpieza, no el tiempo normal.
         */
        private const val MATERIALIZE_TIMEOUT_MS = 2_500L
        private const val AUTO_RESCAN_MS = 6L * 60 * 60 * 1000

        /** Tope entre dos análisis de las carpetas de PS4 al volver a primer plano. */
        private const val PS4_RESCAN_MS = 10_000L

        /** Origen de las imágenes que salen del propio juego (ver [ArtOrigin]). */
        private const val LOCAL_SOURCE = "local"

        /**
         * Lo que acepta el selector de "Elegir de la galería": cualquier
         * imagen, más HEIC y HEIF sueltos — algunos proveedores de fotos los
         * declaran aparte y si no salen en gris al elegirlos.
         */
        private val IMAGE_MIME_TYPES = listOf("image/*", "image/heic", "image/heif")
    }
}
