package com.elyndra.launcher.ui.meridian

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.ui.selection.SelectionLook
import kotlin.math.roundToInt

/**
 * La rueda con todo lo suyo, en el hueco de la columna izquierda: una rueda
 * por sección (al cambiar de sección la nueva entra deslizando en vertical y
 * la vieja se va con su lista de antes) y el dial orbital en el canto derecho
 * con su nodo.
 *
 * [focused] es la fila enfocada de la sección actual ([section]); la rueda de
 * esa sección se publica en [current] (el paralaje del arte y el dial la leen).
 * [onSettle] recibe la fila en la que se para la rueda tras moverla con el dedo.
 */
@Composable
internal fun BoxScope.MeridianWheelArea(
    geo: MeridianGeometry,
    section: Any,
    forward: Boolean,
    entries: List<WheelEntry>,
    add: WheelAdd?,
    focused: Int,
    selectedKey: String?,
    addFocused: Boolean,
    tileAspect: Float,
    look: SelectionLook,
    ink: MeridianInk,
    wash: WashState,
    enterMs: () -> Float,
    reduced: Boolean,
    lite: Boolean,
    current: MutableState<WheelState?>,
    onSettle: (Int) -> Unit,
    tile: @Composable (entry: WheelEntry, index: Int, selected: Boolean) -> Unit,
    wrap: @Composable (entry: WheelEntry, content: @Composable () -> Unit) -> Unit,
    /** Un toque en la fila [row] de la sección actual (`entries.size` = "Añadir"), en [at] de la ventana. */
    onTap: (row: Int, at: Offset, scrolling: Boolean) -> Unit,
    onOpen: (row: Int) -> Unit,
    onLongPress: (WheelEntry, Rect) -> Unit,
    onBounds: (Rect) -> Unit,
    empty: @Composable BoxScope.(focusY: Float) -> Unit,
) {
    val density = LocalDensity.current
    val rowPx = with(density) { geo.rowHeight.dp.roundToPx() }
    val focusPx = with(density) { geo.padTop.dp.roundToPx() } + rowPx / 2f
    val railPx = with(density) { geo.railHeight.dp.toPx() }
    val count = entries.size + if (add != null) 1 else 0
    val focusedRow = focused.coerceIn(0, (count - 1).coerceAtLeast(0))

    // Lo que tenía cada sección la última vez que se vio: la que se va sale con su lista.
    val sections = remember { HashMap<Any, Pair<List<WheelEntry>, WheelAdd?>>() }
    sections[section] = entries to add

    AnimatedContent(
        targetState = section,
        transitionSpec = { sectionSwap(forward, reduced) },
        // Al deslizar, la rueda no se sube a la cabecera ni baja a las pistas.
        modifier = Modifier.fillMaxSize().clipToBounds(),
        label = "meridianSection",
    ) { key ->
        val live = key == section
        val (list, addRow) = if (live) entries to add else sections[key] ?: (emptyList<WheelEntry>() to null)
        val rows = list.size + if (addRow != null) 1 else 0
        val restore = remember { WheelMath.restoreIndex(list.map { it.key }, selectedKey, addFocused, addRow != null) }
        val wheel = rememberWheelState(rememberLazyListState(initialFirstVisibleItemIndex = restore), restore)
        SideEffect {
            wheel.railPx = railPx
            wheel.focusPx = focusPx
            wheel.count = rows
            if (live) {
                wheel.target = focusedRow
                current.value = wheel
            }
        }
        WheelEffects(wheel, reduced, rows, active = live, onSettle = onSettle)
        Box(Modifier.fillMaxSize()) {
            if (rows == 0) {
                empty(focusPx)
            } else {
                // La fila que estaba arriba al entrar: desde ella cuenta la cascada.
                val above = (geo.focusY / geo.rowHeight - 0.5f).roundToInt().coerceAtLeast(0)
                MeridianRail(
                    wheel = wheel,
                    entries = list,
                    add = addRow,
                    focused = if (live) focusedRow else wheel.target,
                    geo = geo,
                    tileAspect = tileAspect,
                    look = look,
                    enterMs = enterMs,
                    cascadeTop = (if (live) focusedRow else wheel.target) - above,
                    reduced = reduced,
                    lite = lite,
                    wash = wash,
                    modifier = Modifier.fillMaxSize(),
                    tile = tile,
                    wrap = wrap,
                    onTap = { i, at, moving -> if (live) onTap(i, at, moving) },
                    onOpen = { i -> if (live) onOpen(i) },
                    onLongPress = { i, r -> list.getOrNull(i)?.let { onLongPress(it, r) } },
                    onBounds = onBounds,
                )
            }
        }
    }

    // Sin juegos (solo "Añadir", o nada) no hay dial.
    val dialCount = if (entries.isEmpty()) 0 else count
    MeridianDial(
        wheel = { current.value },
        count = dialCount,
        selected = focusedRow,
        geo = geo,
        ink = ink,
        enterMs = enterMs,
        reduced = reduced,
        lite = lite,
        modifier = Modifier.matchParentSize(),
    )
}
