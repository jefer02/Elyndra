package com.elyndra.launcher.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.input.Pad
import kotlinx.coroutines.launch

/**
 * Los botones de la barra superior del hero que se alcanzan con el mando.
 *
 * El carrusel se maneja con la selección (no con el foco de Compose), así que
 * la barra tampoco usa foco: se señala con [InputController.barFocus] y cada
 * botón se pinta resaltado cuando le toca.
 */
enum class BarItem { Masha, Open, Search, Settings, Back, Emulator }

/**
 * El mando, ya traducido a lo que hace Elyndra.
 *
 * Una pulsación va a parar a **la capa de arriba**: si hay un diálogo abierto
 * manda el diálogo, no la biblioteca de detrás. Ese orden es el mismo que el
 * de [ElyndraViewModel.back], para que "volver" y "aceptar" no discrepen nunca
 * sobre qué se está manejando.
 *
 * Lo que no se consume aquí ([handle] devuelve false) lo recoge el foco de
 * Compose en la Activity: así las pantallas de formulario —Ajustes, Añadir,
 * Masha— se recorren con la cruceta sin tener que escribirles navegación propia.
 *
 * Esquema de consola:
 *  - Stick izquierdo / cruceta: mover (un paso por pulsación, con repetición controlada).
 *  - A: aceptar · B: volver · X: ficha · Y: opciones del juego.
 *  - L1 / R1: cambiar de sección (filtros en la biblioteca, página en una carpeta).
 *  - Start: Ajustes · Select: buscar · L3 / R3: menú de la app.
 *  - Arriba desde el carrusel: la barra superior (Abrir, buscar, Ajustes…).
 */
class InputController(private val vm: ElyndraViewModel) {

    /** Fila señalada del menú, cuando se abrió con el mando. */
    var sheetFocus by mutableIntStateOf(-1); private set

    /** Botón señalado del diálogo: 0 aceptar, 1 descartar, 2 el tercero. */
    var dialogFocus by mutableIntStateOf(-1); private set

    /** El mando ha entrado en juego: la interfaz enseña lo que está señalado. */
    var active by mutableStateOf(false); private set

    /** Hay un mando conectado: la interfaz enseña las pistas de sus botones. */
    var gamepadPresent by mutableStateOf(false); private set

    /** Botón de la barra superior señalado; null = el mando está en el carrusel. */
    var barFocus by mutableStateOf<BarItem?>(null); private set

    /** Pantalla en la que se eligió [barFocus]: al cambiar de pantalla se olvida. */
    private var barScreen: Screen? = null

    /**
     * El mando está en la card de "Añadir", la última del carrusel. No es un
     * juego, así que no puede ser la selección: se señala aparte.
     */
    private var addFocus by mutableStateOf(false)

    /** Panel desplazable de la capa de arriba, mientras esté en pantalla. */
    private var scroll: ScrollState? = null

    fun bindScroll(state: ScrollState?) {
        scroll = state
    }

    /** ¿Se pinta resaltado [item]? Solo mientras se usa el mando. */
    fun isBarFocused(item: BarItem): Boolean = active && barFocus == item

    /** ¿Se pinta señalada la card de "Añadir"? Solo con el mando y en el carrusel. */
    fun isAddFocused(): Boolean = active && addFocus && barFocus == null && vm.screen == Screen.Library

    /**
     * Menú recién abierto.
     *
     * Con mando se señala ya la primera fila: quien navega con la cruceta
     * espera encontrar algo señalado al abrir. Con el dedo no se señala nada,
     * porque ahí la marca sobra y solo confundiría sobre qué está pulsado.
     */
    fun onSheetShown() {
        sheetFocus = if (active) 0 else -1
        sheetArmed = -1
    }

    fun onDialogShown() {
        dialogFocus = if (active) 0 else -1
    }

    /** Se ha tocado la pantalla: los resaltes del mando se apagan hasta la próxima pulsación. */
    fun onTouch() {
        if (active) active = false
        if (barFocus != null) barFocus = null
        if (addFocus) addFocus = false
    }

    /* ── conexión de mandos ───────────────────────────────────── */

    fun onGamepadConnected(name: String, announce: Boolean) {
        gamepadPresent = true
        if (announce) vm.showToast(UiText.res(R.string.gamepad_connected, name))
    }

    fun onGamepadDisconnected(anyLeft: Boolean) {
        gamepadPresent = anyLeft
        if (!anyLeft) {
            // Sin mando no hay a quién enseñarle la marca del foco.
            active = false
            barFocus = null
            addFocus = false
        }
        vm.showToast(UiText.res(R.string.gamepad_disconnected))
    }

    fun handle(pad: Pad): Boolean {
        active = true
        if (vm.screen != barScreen) {
            barScreen = vm.screen
            barFocus = null
            addFocus = false
        }
        return when {
            // La intro tapa todo: cualquier botón la salta y no llega a lo de debajo.
            vm.intro.visible -> { vm.intro.skip(); true }
            // Mientras se lanza un juego no se toca nada: el velo se va solo.
            vm.dialog != null -> dialog(pad)
            vm.sheet != null -> sheet(pad)
            vm.artPicker != null -> back(pad)
            // El diálogo de nombre se maneja con el foco de Compose (campo, resultados, botones).
            vm.identify.state != null -> back(pad)
            vm.detailsKey != null -> details(pad)
            vm.screen == Screen.Library -> library(pad)
            vm.screen == Screen.Folder -> folder(pad)
            else -> form(pad)
        }
    }

    /**
     * La ficha del juego: se lee de arriba abajo y se cierra.
     *
     * Es un panel de lectura, así que la cruceta lo desplaza en vez de saltar
     * de botón en botón — que es lo que haría el foco, dejando media ficha sin
     * ver. El desplazamiento lo cede el propio panel con [PadScrollBinding].
     */
    private fun details(pad: Pad): Boolean = when (pad) {
        Pad.Up -> scrollBy(-SCROLL_STEP)
        Pad.Down -> scrollBy(SCROLL_STEP)
        Pad.Back, Pad.Details -> { vm.closeDetails(); true }
        else -> false
    }

    private fun scrollBy(dy: Float): Boolean {
        val state = scroll ?: return false
        vm.viewModelScope.launch { state.animateScrollBy(dy) }
        return true
    }

    /** En las capas que no navegan con el mando, al menos volver funciona. */
    private fun back(pad: Pad): Boolean {
        if (pad != Pad.Back) return false
        vm.back()
        return true
    }

    /**
     * Pantallas de formulario (Ajustes, Añadir, Masha): las direcciones y A
     * las mueve el foco de Compose; aquí solo volver y Start.
     */
    private fun form(pad: Pad): Boolean = when (pad) {
        Pad.Back -> { vm.back(); true }
        // L1/R1: en Ajustes, categoría (o mover la fuente cogida); en Añadir, pestaña.
        Pad.PagePrev, Pad.PageNext -> {
            val delta = if (pad == Pad.PagePrev) -1 else 1
            when (vm.screen) {
                Screen.Settings -> { vm.settings.onBumper(delta); true }
                Screen.Add -> { vm.add.updateTab(if (delta < 0) AddTab.Android else AddTab.Roms); true }
                else -> false
            }
        }
        // Start abre y cierra Ajustes, como el botón de pausa de una consola.
        Pad.Menu -> {
            vm.go(if (vm.screen == Screen.Settings) Screen.Library else Screen.Settings)
            true
        }
        else -> false
    }

    /* ── barra superior ───────────────────────────────────────── */

    private fun libraryBar(): List<BarItem> =
        listOfNotNull(BarItem.Masha, BarItem.Open.takeIf { !vm.searchOpen }, BarItem.Search, BarItem.Settings)

    private fun folderBar(): List<BarItem> = listOf(BarItem.Back, BarItem.Open, BarItem.Emulator)

    /**
     * El mando está en la barra superior. Izquierda/derecha la recorren,
     * abajo (o B) vuelve al carrusel y A pulsa el botón señalado. Lo que no es
     * de la barra (L1/R1, Start…) sigue funcionando como en el carrusel.
     */
    private fun bar(pad: Pad, items: List<BarItem>, fallback: (Pad) -> Boolean): Boolean {
        val current = barFocus ?: return fallback(pad)
        val index = items.indexOf(current).coerceAtLeast(0)
        return when (pad) {
            Pad.Left -> { barFocus = items[(index - 1).coerceAtLeast(0)]; true }
            Pad.Right -> { barFocus = items[(index + 1).coerceAtMost(items.lastIndex)]; true }
            Pad.Up -> true
            Pad.Down, Pad.Back -> { barFocus = null; true }
            Pad.Confirm -> { activate(current); true }
            else -> fallback(pad)
        }
    }

    private fun activate(item: BarItem) {
        when (item) {
            BarItem.Masha -> vm.go(Screen.Masha)
            BarItem.Open -> when (vm.screen) {
                Screen.Folder -> vm.selectedRom()?.let { vm.openRom(it) }
                else -> vm.selected()?.let { vm.requestOpen(it) }
            }
            BarItem.Search -> vm.toggleSearch()
            BarItem.Settings -> vm.go(Screen.Settings)
            BarItem.Back -> vm.go(Screen.Library)
            BarItem.Emulator -> vm.currentFolder()?.let { vm.pickFolderEmulator(it.folder) }
        }
    }

    /* ── biblioteca ───────────────────────────────────────────── */

    private fun library(pad: Pad): Boolean = bar(pad, libraryBar()) { p ->
        val items = vm.items()
        val index = items.indexOfFirst { it.key == vm.selected()?.key }.coerceAtLeast(0)
        // Sin juegos en la sección, "Añadir" es lo único que hay: ya está señalada.
        // Una búsqueda sin resultados no enseña la card, así que ahí no.
        if (items.isEmpty()) addFocus = vm.loaded && vm.query.isBlank()
        if (addFocus) {
            when (p) {
                Pad.Left -> {
                    if (items.isNotEmpty()) {
                        addFocus = false
                        vm.select(items.last().key)
                    }
                    return@bar true
                }
                Pad.Right, Pad.Down -> return@bar true
                Pad.Confirm -> { vm.go(Screen.Add); return@bar true }
                // No es un juego: ni ficha ni opciones.
                Pad.Details, Pad.Options -> return@bar true
                else -> Unit
            }
        }
        when (p) {
            Pad.Left -> moveLibrary(items, index, -1)
            // Pasado el último juego, la card de "Añadir".
            Pad.Right -> if (items.isNotEmpty() && index >= items.lastIndex && vm.loaded) { addFocus = true; true } else moveLibrary(items, index, 1)
            // L1/R1 son el atajo estándar de mando para cambiar de sección:
            // Todos, Android, Consolas.
            Pad.PagePrev -> cycleFilter(-1)
            Pad.PageNext -> cycleFilter(1)
            // Arriba sube a la barra del hero, donde está "Abrir".
            Pad.Up -> { barFocus = if (vm.searchOpen) BarItem.Search else BarItem.Open; true }
            Pad.Down -> true
            // Con mando se abre igual que con el dedo.
            Pad.Confirm -> vm.selected()?.let { vm.requestOpen(it); true } ?: false
            Pad.Details -> vm.selected()?.let { vm.showDetails(it.key); true } ?: false
            Pad.Options -> vm.selected()?.let { vm.itemOptions(it); true } ?: false
            Pad.Menu -> { vm.go(Screen.Settings); true }
            Pad.Search -> { vm.toggleSearch(); true }
            Pad.AppMenu -> { vm.mainMenu(); true }
            Pad.Back -> if (vm.canGoBack) { vm.back(); true } else false
        }
    }

    private fun moveLibrary(items: List<LibraryItem>, from: Int, delta: Int): Boolean {
        // Con la lista vacía `coerceIn(0, -1)` lanzaría una excepción.
        if (items.isEmpty()) return false
        val item = items[(from + delta).coerceIn(0, items.lastIndex)]
        vm.select(item.key)
        return true
    }

    private fun cycleFilter(delta: Int): Boolean {
        val all = vm.availableFilters()
        val next = all[(all.indexOf(vm.filter) + delta + all.size) % all.size]
        addFocus = false
        vm.updateFilter(next)
        return true
    }

    /* ── dentro de una carpeta ────────────────────────────────── */

    private fun folder(pad: Pad): Boolean = bar(pad, folderBar()) { p ->
        val roms = vm.folderRoms(vm.folderId)
        val index = roms.indexOfFirst { it.key == vm.selectedRom()?.key }.coerceAtLeast(0)
        fun move(delta: Int): Boolean {
            if (roms.isEmpty()) return false
            val rom = roms[(index + delta).coerceIn(0, roms.lastIndex)]
            vm.selectRom(rom.key)
            return true
        }
        when (p) {
            Pad.Left -> move(-1)
            Pad.Right -> move(1)
            Pad.PagePrev -> move(-PAGE)
            Pad.PageNext -> move(PAGE)
            // Arriba sube a la barra: volver, "Abrir" y el selector de emulador.
            Pad.Up -> { barFocus = BarItem.Open; true }
            Pad.Down -> true
            Pad.Confirm -> vm.selectedRom()?.let { vm.openRom(it); true } ?: false
            Pad.Details -> vm.selectedRom()?.let { vm.showDetails(it.key); true } ?: false
            Pad.Options -> vm.selectedRom()?.let { vm.romOptions(it); true } ?: false
            Pad.Menu -> { vm.go(Screen.Settings); true }
            Pad.AppMenu -> { vm.currentFolder()?.let { vm.itemOptions(it) }; true }
            Pad.Search -> false
            Pad.Back -> { vm.back(); true }
        }
    }

    /* ── menú de acciones ─────────────────────────────────────── */

    /**
     * Cómo está repartido el menú en pantalla: lo publica el propio panel
     * ([SheetLayoutBinding]), que es quien sabe si va en una o dos columnas.
     */
    private var sheetLayout: SheetLayout? = null

    fun bindSheetLayout(layout: SheetLayout?) {
        sheetLayout = layout
    }

    /**
     * Acción del menú a la espera de confirmación (índice en el orden del
     * foco; -1 = ninguna). Las que borran al momento piden una segunda
     * pulsación, con el mando o con el dedo.
     */
    var sheetArmed by mutableIntStateOf(-1); private set

    fun armSheet(index: Int) {
        sheetArmed = index
    }

    fun disarmSheet() {
        sheetArmed = -1
    }

    /**
     * Ejecuta una acción del menú: cierra y la lanza, salvo que pida
     * confirmación y no esté ya armada (entonces solo se arma).
     */
    fun runSheetAction(index: Int, action: SheetAction) {
        if (action.holdToConfirm && sheetArmed != index) {
            sheetArmed = index
            return
        }
        sheetArmed = -1
        vm.dismissSheet()
        action.action()
    }

    private fun sheet(pad: Pad): Boolean {
        val spec = vm.sheet ?: return back(pad)
        val layout = sheetLayout ?: SheetLayout.of(spec, landscape = false)
        val actions = layout.actions
        if (actions.isEmpty()) return back(pad)
        fun go(move: SheetLayout.Move): Boolean {
            val next = layout.move(sheetFocus, move)
            if (next != sheetFocus) sheetArmed = -1
            sheetFocus = next
            return true
        }
        return when (pad) {
            Pad.Up -> go(SheetLayout.Move.Up)
            Pad.Down -> go(SheetLayout.Move.Down)
            Pad.Left -> go(SheetLayout.Move.Left)
            Pad.Right -> go(SheetLayout.Move.Right)
            // L1/R1: al principio y al final del menú.
            Pad.PagePrev -> { sheetFocus = 0; sheetArmed = -1; true }
            Pad.PageNext -> { sheetFocus = actions.lastIndex; sheetArmed = -1; true }
            Pad.Confirm -> {
                val action = actions.getOrNull(sheetFocus) ?: return true
                runSheetAction(sheetFocus, action)
                true
            }
            Pad.Back -> {
                // Con una acción armada, B la desarma antes de cerrar.
                if (sheetArmed >= 0) sheetArmed = -1 else vm.dismissSheet()
                true
            }
            Pad.Options, Pad.AppMenu -> { vm.dismissSheet(); true }
            else -> true
        }
    }

    /**
     * Primer movimiento: la fila de arriba. A partir de ahí no da la vuelta —
     * en un menú corto, pasar de la última a la primera se lee como un salto.
     */
    private fun step(current: Int, delta: Int, size: Int): Int =
        if (current < 0) (if (delta > 0) 0 else size - 1) else (current + delta).coerceIn(0, size - 1)

    /* ── diálogo ──────────────────────────────────────────────── */

    private fun dialog(pad: Pad): Boolean {
        val spec = vm.dialog ?: return false
        val buttons = dialogButtons(spec)
        return when (pad) {
            Pad.Left, Pad.Up -> { dialogFocus = step(dialogFocus, -1, buttons.size); true }
            Pad.Right, Pad.Down -> { dialogFocus = step(dialogFocus, 1, buttons.size); true }
            Pad.Confirm -> {
                // Sin nada señalado manda el botón principal, que es el que ya
                // está resaltado en pantalla.
                val button = buttons.getOrNull(dialogFocus) ?: buttons.first()
                vm.dismissDialog()
                if (spec.input != null && button === spec.confirm) spec.input.onConfirm(spec.input.initial) else button.action()
                true
            }
            Pad.Back -> { vm.dismissDialog(); spec.dismiss?.action?.invoke(); true }
            else -> true
        }
    }

    /** Los botones del diálogo en el orden en que se leen: el principal, primero. */
    fun dialogButtons(spec: DialogSpec): List<DialogButton> =
        listOfNotNull(spec.confirm, spec.dismiss, spec.extra)

    /** El menú de la app: todo lo que no cabe en un botón del mando. */
    fun mainMenuActions(): List<SheetAction> = listOf(
        SheetAction(UiText.res(R.string.search_hint), icon = SheetIcon.Search) { vm.toggleSearch() },
        SheetAction(UiText.res(R.string.sort_by), icon = SheetIcon.Sort, opensSheet = true) { vm.sortOptions() },
        SheetAction(UiText.res(R.string.add_title), icon = SheetIcon.App) { vm.go(Screen.Add) },
        SheetAction(UiText.res(R.string.settings_title), icon = SheetIcon.Service) { vm.go(Screen.Settings) },
        SheetAction(UiText.res(R.string.masha), icon = SheetIcon.Details) { vm.go(Screen.Masha) },
    )

    private companion object {
        /** Cuánto salta el carrusel con L1/R1: una pantalla larga de carátulas. */
        const val PAGE = 6

        /** Lo que baja la ficha por pulsación: un tercio de pantalla, en píxeles. */
        const val SCROLL_STEP = 420f
    }
}

/**
 * Cede al mando el desplazamiento del panel que está en pantalla.
 *
 * Lo llama el propio panel con su estado de desplazamiento; mientras esté
 * compuesto, la cruceta lo mueve. Al desaparecer lo suelta, para que no quede
 * moviendo algo que ya no se ve.
 */
@Composable
fun PadScrollBinding(vm: ElyndraViewModel, state: ScrollState) {
    DisposableEffect(state) {
        vm.input.bindScroll(state)
        onDispose { vm.input.bindScroll(null) }
    }
}

/** Cede al mando el reparto del menú que está en pantalla (ver [SheetLayout]). */
@Composable
fun SheetLayoutBinding(input: InputController, layout: SheetLayout) {
    DisposableEffect(layout) {
        input.bindSheetLayout(layout)
        onDispose { input.bindSheetLayout(null) }
    }
}
