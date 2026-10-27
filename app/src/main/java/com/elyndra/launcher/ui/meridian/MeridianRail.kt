package com.elyndra.launcher.ui.meridian

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.elyndra.launcher.R
import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.ui.TapAnchor
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.rememberUserScrolling
import com.elyndra.launcher.ui.selection.SelectionLook
import com.elyndra.launcher.ui.selection.selectionFrame
import com.elyndra.launcher.ui.theme.LocalSkin
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.elyndra.launcher.ui.TitleFit
import com.elyndra.launcher.ui.theme.LocalPoppins
import com.elyndra.launcher.ui.theme.WheelGlass
import androidx.compose.ui.semantics.contentDescription
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.lightGlass
import com.elyndra.launcher.ui.theme.liquidGlass
import com.elyndra.launcher.ui.theme.outerShadow
import kotlin.math.roundToInt

/** Una fila de la rueda: lo que se lee junto a su arte. */
@Immutable
internal data class WheelEntry(
    val key: String,
    val title: String,
    /** Plataforma y tiempo de juego, bajo el nombre de las filas que no están en la línea. */
    val subline: String,
    /** Las fichas de la fila enfocada. */
    val chips: List<String>,
    /** Juego Android que ya no está instalado: se ve apagado. */
    val dimmed: Boolean = false,
    /** Lo que representa (el elemento de la biblioteca o la ROM), para pintar su arte. */
    val payload: Any? = null,
    /** La imagen de la tarjeta (relativa a filesDir) o la app de su icono: de su canto sale la fuerza de su filo. */
    val art: String? = null,
    val pkg: String? = null,
)

/** La fila de "Añadir", al final de la rueda de la biblioteca: un rótulo corto ([title]) y el largo para el lector de pantalla ([label]). */
@Immutable
internal data class WheelAdd(val title: String, val label: String, val onClick: () -> Unit)

/** Clave de la fila de "Añadir" (no choca con las de juegos, que llevan prefijo). */
internal const val WHEEL_ADD_KEY = "meridian:add"

/**
 * La rueda: una lista vertical con una fila por juego y, al final, la de
 * "Añadir". Cada hueco mide lo que la tarjeta enfocada ([MeridianGeometry.rowHeight]);
 * el relleno de arriba y de abajo deja que la primera y la última lleguen a
 * la línea de foco, y al soltarla con el dedo se para con una fila en ella
 * ([WheelSnap]).
 *
 * La profundidad —escala, opacidad, curva, giro, el arrime que deja huecos
 * regulares y la niebla sobre el arte— se aplica en la capa y en el dibujo de
 * cada fila leyendo la posición ([WheelTransform]): al girar no se recompone
 * ninguna fila. El orden de dibujo (las lejanas por detrás) cambia solo con la
 * selección. Solo la enfocada lleva el marco de la selección y sus fichas.
 *
 * Toques: [onTap] recibe la fila (la de "Añadir" es `entries.size`), el punto
 * en la ventana y si la lista se estaba moviendo al apoyar el dedo; quien la
 * usa decide si selecciona o abre (ver `TapGate`).
 */
@Composable
internal fun MeridianRail(
    wheel: WheelState,
    entries: List<WheelEntry>,
    add: WheelAdd?,
    focused: Int,
    geo: MeridianGeometry,
    tileAspect: Float,
    look: SelectionLook,
    enterMs: () -> Float,
    cascadeTop: Int,
    reduced: Boolean,
    lite: Boolean,
    wash: WashState,
    modifier: Modifier = Modifier,
    tile: @Composable (entry: WheelEntry, index: Int, selected: Boolean) -> Unit,
    wrap: @Composable (entry: WheelEntry, content: @Composable () -> Unit) -> Unit = { _, content -> content() },
    onTap: (index: Int, at: Offset, scrolling: Boolean) -> Unit,
    onOpen: (Int) -> Unit,
    onLongPress: (Int, Rect) -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    val rowDp = geo.rowHeight.dp
    val rowPx = with(density) { rowDp.roundToPx().toFloat() }
    SideEffect { wheel.rowPx = rowPx }
    val count = entries.size + if (add != null) 1 else 0
    val total by rememberUpdatedState(count)
    val minVelocity = with(density) { SNAP_MIN_VELOCITY.toPx() }
    val snap = remember(wheel, minVelocity) { WheelSnap(wheel, { total }, minVelocity) }
    val fling = rememberSnapFlingBehavior(snap)
    val scrolling = rememberUserScrolling(wheel.list)
    val tileH = rowDp
    val tileW = rowDp * tileAspect
    val startPad = geo.inset.dp
    val radiusPx = with(density) { geo.arcRadius.dp.toPx() }
    val openLabel = stringResource(R.string.open)
    val optionsLabel = stringResource(R.string.hint_options)
    val dark = P.isDark

    BoxWithConstraints(modifier) {
        // El relleno de abajo sale del alto real en px: así la última fila llega justo a la línea.
        val topPx = with(density) { geo.padTop.dp.roundToPx() }
        val bottomPx = (constraints.maxHeight - topPx - rowPx.toInt()).coerceAtLeast(0)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = wheel.list,
            contentPadding = with(density) { PaddingValues(top = topPx.toDp(), bottom = bottomPx.toDp()) },
            flingBehavior = fling,
        ) {
            itemsIndexed(entries, key = { _, e -> e.key }) { i, e ->
                val selected = i == focused
                // Las cercanas por encima de las lejanas (en la raíz del elemento).
                Box(Modifier.zIndex(WheelTransform.zOrder(i, focused))) {
                    wrap(e) {
                        WheelRow(
                            index = i,
                            selected = selected,
                            wheel = wheel,
                            rowDp = rowDp,
                            radiusPx = radiusPx,
                            reduced = reduced,
                            lite = lite,
                            enterMs = enterMs,
                            cascadeTop = cascadeTop,
                            position = stringResource(R.string.meridian_position, i + 1, count),
                            label = if (e.subline.isBlank()) e.title else "${e.title}, ${e.subline}",
                            openLabel = openLabel,
                            optionsLabel = optionsLabel,
                            scrolling = scrolling,
                            onTap = { at, moving -> onTap(i, at, moving) },
                            onOpen = { onOpen(i) },
                            onLongPress = { onLongPress(i, it) },
                            onBounds = onBounds,
                        ) { bounds ->
                            Row(Modifier.fillMaxSize().padding(start = startPad, end = RAIL_END), verticalAlignment = Alignment.CenterVertically) {
                                val shape = if (tileAspect < 1f) COVER_SHAPE else ICON_SHAPE
                                val edge by rememberEdgeLuminance(e.art, e.pkg)
                                Box(
                                    Modifier
                                        .size(tileW, tileH)
                                        .onGloballyPositioned { bounds(it.boundsInWindow()) }
                                        // Atenuada sin capa aparte: una capa recortaría el halo de la selección.
                                        .graphicsLayer {
                                            alpha = if (e.dimmed) 0.5f else 1f
                                            compositingStrategy = CompositingStrategy.ModulateAlpha
                                        }
                                        .contactShadow(shape, dark) { tileStrength(edge, wash) }
                                        .outerShadow(if (selected) 14.dp else 6.dp, shape, ambientColor = P.shade.copy(alpha = 0.26f), spotColor = P.shade.copy(alpha = 0.26f))
                                        .selectionFrame(selected, look, shape)
                                        .clip(shape)
                                        .fogged(wheel, i, lite, wash)
                                        .hairline(shape, dark) { tileStrength(edge, wash) },
                                ) {
                                    tile(e, i, selected)
                                }
                            }
                        }
                    }
                }
            }
            if (add != null) {
                item(key = WHEEL_ADD_KEY) {
                    val i = entries.size
                    val selected = i == focused
                    Box(Modifier.zIndex(WheelTransform.zOrder(i, focused))) {
                        WheelRow(
                            index = i,
                            selected = selected,
                            wheel = wheel,
                            rowDp = rowDp,
                            radiusPx = radiusPx,
                            reduced = reduced,
                            lite = lite,
                            enterMs = enterMs,
                            cascadeTop = cascadeTop,
                            position = stringResource(R.string.meridian_position, i + 1, count),
                            label = add.label,
                            openLabel = add.label,
                            optionsLabel = null,
                            scrolling = scrolling,
                            onTap = { at, moving -> onTap(i, at, moving) },
                            onOpen = add.onClick,
                            onLongPress = null,
                            onBounds = {},
                        ) { _ ->
                            Row(Modifier.fillMaxSize().padding(start = startPad, end = RAIL_END), verticalAlignment = Alignment.CenterVertically) {
                                AddTile(tileH, selected, look)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Velocidad (dp/s) por debajo de la cual soltar la rueda la deja en la fila más cercana. */
private val SNAP_MIN_VELOCITY = 400.dp

/** Margen derecho de las filas, antes del dial (sus marcas y la muesca no tocan el nombre). */
private val RAIL_END = 34.dp

private val ICON_SHAPE = RoundedCornerShape(16.dp)
private val COVER_SHAPE = RoundedCornerShape(12.dp)

/** Lo que entra la cascada desde la izquierda. */
private val CASCADE_SHIFT = 44.dp

/**
 * Una fila: su capa gráfica (la profundidad y la cascada de entrada), los
 * gestos (tocar —seleccionar o abrir lo decide quien la usa—, mantener para
 * las opciones) y lo que oye un lector de pantalla: su nombre ([label]; en la
 * rueda solo se ve el icono), si está seleccionada, su posición y las
 * acciones "Abrir" y "Opciones".
 */
@Composable
private fun WheelRow(
    index: Int,
    selected: Boolean,
    wheel: WheelState,
    rowDp: Dp,
    radiusPx: Float,
    reduced: Boolean,
    lite: Boolean,
    enterMs: () -> Float,
    cascadeTop: Int,
    position: String,
    label: String,
    openLabel: String,
    optionsLabel: String?,
    scrolling: () -> Boolean,
    onTap: (Offset, Boolean) -> Unit,
    onOpen: () -> Unit,
    onLongPress: ((Rect) -> Unit)?,
    onBounds: (Rect) -> Unit,
    content: @Composable (bounds: (Rect) -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val shift = with(density) { CASCADE_SHIFT.toPx() }
    val camera = 12f * density.density
    val tap by rememberUpdatedState(onTap)
    val open by rememberUpdatedState(onOpen)
    val long by rememberUpdatedState(onLongPress)
    val bounds = remember { arrayOf(Rect.Zero) }
    val anchor = remember { TapAnchor() }
    val selectedNow by rememberUpdatedState(selected)
    Box(
        Modifier
            .fillMaxWidth()
            .height(rowDp)
            .graphicsLayer {
                // Sin capa aparte: una capa recortaría el halo de la selección y apagaría su luz.
                compositingStrategy = CompositingStrategy.ModulateAlpha
                val d = wheel.distance(index)
                val ms = enterMs()
                val enter = if (reduced) MeridianMotion.heroFade(ms) else MeridianMotion.cascade(ms, index - cascadeTop)
                val s = WheelTransform.scale(d)
                val row = size.height
                val lift = wheel.lift()
                val dy = WheelTransform.center(d) * row
                val edge = RailEdge.fade(wheel.focusPx + lift + dy, s * row, 0f, wheel.railPx)
                scaleX = s
                scaleY = s
                alpha = WheelTransform.alpha(d, lite) * enter * edge
                translationX = WheelArc.offset(dy, radiusPx) - if (reduced) 0f else (1f - enter) * shift
                translationY = WheelTransform.shift(d) * row + lift
                rotationY = WheelTransform.rotationY(d, reduced || lite)
                cameraDistance = camera
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .onGloballyPositioned { anchor.coordinates = it }
            .pointerInput(Unit) {
                var moving = false
                detectTapGestures(
                    onPress = { moving = scrolling() },
                    onTap = { tap(anchor.toWindow(it), moving) },
                    onLongPress = { if (!moving) long?.invoke(bounds[0]) },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
                contentDescription = label
                stateDescription = position
                // Doble toque de TalkBack: en la seleccionada abre; en las demás, la selecciona.
                onClick(label = if (selected) openLabel else null) { if (selectedNow) open() else tap(Offset.Unspecified, false); true }
                customActions = listOfNotNull(
                    CustomAccessibilityAction(openLabel) { open(); true },
                    optionsLabel?.let { CustomAccessibilityAction(it) { long?.invoke(bounds[0]); true } },
                )
            },
    ) {
        content { r ->
            bounds[0] = r
            if (selectedNow) onBounds(r)
        }
    }
}

/** La niebla sobre el arte: el color del fondo según la distancia, que lo empuja hacia atrás y le quita color. Sin ella en calidad ligera. */
private fun Modifier.fogged(wheel: WheelState, index: Int, lite: Boolean, wash: WashState): Modifier = drawWithContent {
    drawContent()
    val f = WheelTransform.fog(wheel.distance(index), lite)
    if (f > 0.005f) drawRect(wash.tone(), alpha = f)
}

/** Fuerza del filo y la sombra de contacto de una tarjeta con este canto sobre el fondo de ahora. */
private fun tileStrength(edge: Double?, wash: WashState): Float =
    ArtWash.tileEdgeStrength(edge, ColorMath.luminance(wash.targetTone.argb()))

/** El filo de 1 dp de la tarjeta, del tema: más marcado si su canto se confunde con el fondo (un icono claro sobre el velo claro). */
private fun Modifier.hairline(shape: Shape, dark: Boolean, strength: () -> Float): Modifier = drawWithContent {
    drawContent()
    val k = strength()
    val stroke = 1.dp.toPx()
    val color = if (dark) Color.White else Color(BrandTokens.LIGHT.ink)
    val alpha = if (dark) 0.10f + 0.28f * k else 0.08f + 0.30f * k
    inset(stroke / 2f) {
        drawOutline(shape.createOutline(size, layoutDirection, this), color, alpha = alpha, style = Stroke(stroke))
    }
}

/** Una sombra de contacto suave bajo la tarjeta (tres capas, sin desenfoque), más fuerte con [strength]. */
private fun Modifier.contactShadow(shape: Shape, dark: Boolean, strength: () -> Float): Modifier = drawBehind {
    val k = strength()
    val step = 1.5.dp.toPx()
    val base = if (dark) 0.10f else 0.05f
    for (n in 1..3) {
        val grow = step * n
        val outline = shape.createOutline(Size(size.width + grow, size.height + grow), layoutDirection, this)
        translate(-grow / 2f, step * n) { drawOutline(outline, Color.Black, alpha = (base + 0.05f * k) * (1f - n * 0.22f)) }
    }
}

/** La luminancia del canto de la tarjeta (de la caché o leída fuera del hilo principal). */
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberEdgeLuminance(art: String?, pkg: String?): State<Double?> {
    val context = LocalContext.current
    val key = ArtSampler.edgeKey(art, pkg)
    return produceState(key?.let(ArtSampler::cachedEdge)?.takeUnless(Double::isNaN), key) {
        if (key != null) value = ArtSampler.edge(context, art, pkg)
    }
}

/** El arte de la fila de "Añadir": cristal con un "+" del acento. */
@Composable
private fun AddTile(side: Dp, selected: Boolean, look: SelectionLook) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .size(side)
            .selectionFrame(selected, look, ICON_SHAPE)
            .liquidGlass(ICON_SHAPE, skin.a1.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        ElyText("+", size = (side.value * 0.42f).coerceIn(18f, 34f), weight = FontWeight.SemiBold, color = skin.a2)
    }
}

