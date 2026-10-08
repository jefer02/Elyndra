package com.elyndra.launcher.ui.meridian

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.ui.selection.SelectionLook
import kotlin.math.roundToInt

/**
 * La rueda con todo lo suyo, en el hueco de la columna izquierda: una rueda
 * por sección (al cambiar de sección la nueva entra deslizando en vertical y
 * la vieja se va con su lista de antes), el dial orbital en el canto derecho
 * con su nodo y su contador, y la tira A–Z en el izquierdo si toca.
 *
 * [focused] es la fila enfocada de la sección actual ([section]); la rueda de
 * esa sección se publica en [current] (el paralaje del arte y el dial la leen).
 * [onSettle] recibe la fila en la que se para la rueda tras moverla con el dedo.
 * [bubbleBottom] es el canto de abajo (px en la ventana) del bocadillo de
 * Masha mientras se ve, o null: la rueda le deja sitio con un muelle.
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
    index: List<IndexEntry>?,
    onSettle: (Int) -> Unit,
    onJump: (Int) -> Unit,
    tile: @Composable (entry: WheelEntry, index: Int, selected: Boolean) -> Unit,
    wrap: @Composable (entry: WheelEntry, content: @Composable () -> Unit) -> Unit,
    /** Un toque en la fila [row] de la sección actual (`entries.size` = "Añadir"), en [at] de la ventana. */
    onTap: (row: Int, at: Offset, scrolling: Boolean) -> Unit,
    onOpen: (row: Int) -> Unit,
    onLongPress: (WheelEntry, Rect) -> Unit,
    onBounds: (Rect) -> Unit,
    empty: @Composable BoxScope.(focusY: Float) -> Unit,
    bubbleBottom: Float? = null,
) {
    val density = LocalDensity.current
    val rowPx = with(density) { geo.rowHeight.dp.roundToPx() }
    val focusPx = with(density) { geo.padTop.dp.roundToPx() } + rowPx / 2f
    val railPx = with(density) { geo.railHeight.dp.toPx() }
    val count = entries.size + if (add != null) 1 else 0
    val focusedRow = focused.coerceIn(0, (count - 1).coerceAtLeast(0))

    // El sitio que se deja al bocadillo de Masha: llega y se va con un muelle (al instante con "reducir movimiento").
    var railTop by remember { mutableFloatStateOf(0f) }
    val gap = with(density) { BUBBLE_GAP.toPx() }
    val target = bubbleBottom?.let { BubbleSlot.reserve(it, railTop, gap) } ?: 0f
    val reserve = remember { Animatable(target) }
    LaunchedEffect(target, reduced) {
        if (reduced) reserve.snapTo(target) else reserve.animateTo(target, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow))
    }
    Spacer(Modifier.matchParentSize().onGloballyPositioned { railTop = it.positionInWindow().y })

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
            wheel.reserve = { reserve.value }
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

    if (index != null && entries.isNotEmpty()) {
        val currentLetter = entries.getOrNull(focusedRow)?.let { IndexStrip.letterOf(it.title) }
        MeridianIndexStrip(
            entries = index,
            current = currentLetter,
            onJump = onJump,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 2.dp, top = 6.dp, bottom = 6.dp)
                .fillMaxHeight(),
        )
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
    val counterX = with(density) { (geo.axisX.dp - DIAL_INSET + COUNTER_GAP).roundToPx() }
    DialCounterChip(
        index = focusedRow,
        total = entries.size,
        onAdd = add != null && focusedRow >= entries.size,
        tint = { wash.tone() },
        enterMs = enterMs,
        reduced = reduced,
        modifier = Modifier
            .align(Alignment.TopStart)
            // Se coloca en la fase de colocación leyendo el nodo: lo sigue sin recomponer, centrado en él.
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(p.width, p.height) {
                    val w = current.value
                    val y = (w?.focusPx ?: focusPx) + (w?.lift() ?: 0f) - p.height / 2f
                    p.place(counterX, y.roundToInt())
                }
            },
    )
}

/** Entre el bocadillo de Masha y la primera fila que se ve. */
private val BUBBLE_GAP = 8.dp

/** Del nodo al contador. */
private val COUNTER_GAP = 16.dp
