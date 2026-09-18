package com.elyndra.launcher.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.input.Pad
import kotlinx.coroutines.launch

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
 * Lucy— se recorren con la cruceta sin tener que escribirles navegación propia.
 */
class InputController(private val vm: ElyndraViewModel) {

    /** Fila señalada del menú, cuando se abrió con el mando. */
    var sheetFocus by mutableStateOf(-1); private set

    /** Botón señalado del diálogo: 0 aceptar, 1 descartar, 2 el tercero. */
    var dialogFocus by mutableStateOf(-1); private set

    /** El mando ha entrado en juego: la interfaz enseña lo que está señalado. */
    var active by mutableStateOf(false); private set

    /** Panel desplazable de la capa de arriba, mientras esté en pantalla. */
    private var scroll: ScrollState? = null

    fun bindScroll(state: ScrollState?) {
        scroll = state
    }

    /**
     * Menú recién abierto.
     *
     * Con mando se señala ya la primera fila: quien navega con la cruceta
     * espera encontrar algo señalado al abrir. Con el dedo no se señala nada,
     * porque ahí la marca sobra y solo confundiría sobre qué está pulsado.
     */
    fun onSheetShown() {
        sheetFocus = if (active) 0 else -1
    }

    fun onDialogShown() {
        dialogFocus = if (active) 0 else -1
    }

    fun handle(pad: Pad): Boolean {
        active = true
        return when {
            // Mientras se lanza un juego no se toca nada: el velo se va solo.
            vm.launching != null -> true
            vm.dialog != null -> dialog(pad)
            vm.sheet != null -> sheet(pad)
            vm.artPicker != null -> back(pad)
            vm.detailsKey != null -> details(pad)
            vm.screen == Screen.Library -> library(pad)
            vm.screen == Screen.Folder -> folder(pad)
            else -> back(pad)
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

    /* ── biblioteca ───────────────────────────────────────────── */

    private fun library(pad: Pad): Boolean {
        val items = vm.items()
        val index = items.indexOfFirst { it.key == vm.selected()?.key }.coerceAtLeast(0)
        return when (pad) {
            Pad.Left -> moveLibrary(items, index, -1)
            Pad.Right -> moveLibrary(items, index, 1)
            Pad.PagePrev -> moveLibrary(items, index, -PAGE)
            Pad.PageNext -> moveLibrary(items, index, PAGE)
            // Los filtros son la fila de encima del carrusel: arriba y abajo
            // se mueven por ellos, que es donde el ojo los busca.
            Pad.Up -> cycleFilter(-1)
            Pad.Down -> cycleFilter(1)
            Pad.Confirm -> vm.selected()?.let { vm.open(it); true } ?: false
            Pad.Details -> vm.selected()?.let { vm.showDetails(it.key); true } ?: false
            Pad.Options -> vm.selected()?.let { vm.itemOptions(it); true } ?: false
            Pad.Menu -> { vm.mainMenu(); true }
            Pad.Search -> { vm.toggleSearch(); true }
            Pad.Back -> if (vm.canGoBack) { vm.back(); true } else false
        }
    }

    private fun moveLibrary(items: List<LibraryItem>, from: Int, delta: Int): Boolean {
        val item = items.getOrNull((from + delta).coerceIn(0, items.lastIndex)) ?: return false
        vm.select(item.key)
        return true
    }

    private fun cycleFilter(delta: Int): Boolean {
        val all = LibraryFilter.entries
        val next = all[(all.indexOf(vm.filter) + delta + all.size) % all.size]
        vm.updateFilter(next)
        return true
    }

    /* ── dentro de una carpeta ────────────────────────────────── */

    private fun folder(pad: Pad): Boolean {
        val roms = vm.folderRoms(vm.folderId)
        val index = roms.indexOfFirst { it.key == vm.selectedRom()?.key }.coerceAtLeast(0)
        fun move(delta: Int): Boolean {
            val rom = roms.getOrNull((index + delta).coerceIn(0, roms.lastIndex)) ?: return false
            vm.selectRom(rom.key)
            return true
        }
        return when (pad) {
            Pad.Left -> move(-1)
            Pad.Right -> move(1)
            Pad.Up, Pad.PagePrev -> move(-PAGE)
            Pad.Down, Pad.PageNext -> move(PAGE)
            Pad.Confirm -> vm.selectedRom()?.let { vm.openRom(it); true } ?: false
            Pad.Details -> vm.selectedRom()?.let { vm.showDetails(it.key); true } ?: false
            Pad.Options -> vm.selectedRom()?.let { vm.romOptions(it); true } ?: false
            Pad.Menu -> { vm.currentFolder()?.let { vm.itemOptions(it) }; true }
            Pad.Search -> false
            Pad.Back -> { vm.back(); true }
        }
    }

    /* ── menú de acciones ─────────────────────────────────────── */

    private fun sheet(pad: Pad): Boolean {
        val actions = vm.sheet?.groups.orEmpty().flatMap { it.actions }
        if (actions.isEmpty()) return back(pad)
        return when (pad) {
            Pad.Up, Pad.PagePrev -> { sheetFocus = step(sheetFocus, -1, actions.size); true }
            Pad.Down, Pad.PageNext -> { sheetFocus = step(sheetFocus, 1, actions.size); true }
            Pad.Confirm -> {
                val action = actions.getOrNull(sheetFocus) ?: return true
                vm.dismissSheet()
                action.action()
                true
            }
            Pad.Back, Pad.Options -> { vm.dismissSheet(); true }
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
            Pad.Left -> { dialogFocus = step(dialogFocus, -1, buttons.size); true }
            Pad.Right -> { dialogFocus = step(dialogFocus, 1, buttons.size); true }
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
        SheetAction(UiText.res(R.string.lucy), icon = SheetIcon.Details) { vm.go(Screen.Lucy) },
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
