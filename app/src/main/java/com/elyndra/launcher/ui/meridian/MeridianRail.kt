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
import com.elyndra.launcher.ui.theme.darkGlass
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
                                Spacer(Modifier.width(TEXT_GAP))
                                RowText(
                                    title = e.title,
                                    subline = if (selected) null else e.subline,
                                    chips = if (selected) e.chips.take(MAX_ROW_CHIPS) else emptyList(),
                                    selected = selected,
                                    tileHeight = geo.rowHeight,
                                    compact = geo.compact,
                                    wash = wash,
                                    modifier = Modifier.weight(1f).wheelDetail(wheel, i),
                                )
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
                                Spacer(Modifier.width(TEXT_GAP))
                                RowText(add.title, null, emptyList(), selected, geo.rowHeight, geo.compact, wash, Modifier.weight(1f).wheelDetail(wheel, i))
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

/** Entre la tarjeta y su nombre. */
private val TEXT_GAP = 14.dp

/** Fichas en la fila enfocada: plataforma y cantidad (el emulador va en el hero). */
private const val MAX_ROW_CHIPS = 2

private val ICON_SHAPE = RoundedCornerShape(16.dp)
private val COVER_SHAPE = RoundedCornerShape(12.dp)

/** Lo que entra la cascada desde la izquierda. */
private val CASCADE_SHIFT = 44.dp

/**
 * Una fila: su capa gráfica (la profundidad y la cascada de entrada), los
 * gestos (tocar —seleccionar o abrir lo decide quien la usa—, mantener para
 * las opciones) y lo que oye un lector de pantalla: si está seleccionada, su
 * posición y las acciones "Abrir" y "Opciones".
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
                val edge = RailEdge.fade(wheel.focusPx + lift + dy, s * row, wheel.reserve(), wheel.railPx)
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

/** El texto de una fila: se apaga con la distancia (las filas lejanas quedan solo con su arte). */
private fun Modifier.wheelDetail(wheel: WheelState, index: Int): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.ModulateAlpha
    alpha = WheelTransform.detail(wheel.distance(index))
}

/**
 * Nombre (dos líneas como mucho) y, debajo, su línea (plataforma, tiempo) o,
 * en la fila enfocada, sus fichas, que saltan de línea en vez de cortarse.
 * La enfocada va grande y sobre un velo suave del color del fondo que se
 * desvanece hacia todos sus bordes (no se lee como una tarjeta); todas llevan
 * una sombra ligera que las separa del arte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowText(
    title: String,
    subline: String?,
    chips: List<String>,
    selected: Boolean,
    tileHeight: Float,
    compact: Boolean,
    wash: WashState,
    modifier: Modifier,
) {
    val dark = P.isDark
    val shadow = Shadow(color = if (dark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.65f), blurRadius = 8f)
    Column(
        modifier
            .wrapContentHeight(unbounded = true)
            .then(if (selected) Modifier.softPlate(wash, dark).padding(vertical = 8.dp) else Modifier),
        verticalArrangement = Arrangement.Center,
    ) {
        ElyText(
            title,
            size = WheelTransform.titleSp(tileHeight, selected, compact),
            weight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = P.ink,
            letterSpacing = tracking(-0.015f),
            lineHeightRatio = 1.1f,
            shadow = shadow,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { RailChip(it) }
            }
        } else if (subline != null) {
            Spacer(Modifier.height(4.dp))
            ElyText(
                subline,
                size = 13f,
                weight = FontWeight.Medium,
                color = P.ink2,
                letterSpacing = tracking(0.02f),
                shadow = shadow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** El velo bajo el texto de la fila enfocada: un óvalo del color del fondo, lleno en su centro y que se apaga hacia fuera (nunca una tarjeta). */
private fun Modifier.softPlate(wash: WashState, dark: Boolean): Modifier = drawWithCache {
    val tone = if (wash.to.adaptive) wash.targetTone else Color(MeridianScrims.plate(dark))
    val a = MeridianScrims.plateAlpha(dark)
    val brush = Brush.radialGradient(
        0f to tone.copy(alpha = a),
        0.55f to tone.copy(alpha = a),
        0.82f to tone.copy(alpha = a * 0.45f),
        1f to tone.copy(alpha = 0f),
        center = Offset.Zero,
        radius = 1f,
    )
    val rx = size.width * 0.62f + 18.dp.toPx()
    val ry = size.height * 0.85f + 8.dp.toPx()
    val cx = size.width * 0.42f
    val cy = size.height / 2f
    onDrawBehind {
        withTransform({
            translate(cx, cy)
            scale(rx, ry, Offset.Zero)
        }) { drawCircle(brush, radius = 1f, center = Offset.Zero) }
    }
}

/** Ficha de la fila enfocada: el velo del acento con tinta encima (≥ 4,5:1 sobre el fondo); si no cabe, su texto baja de línea. */
@Composable
private fun RailChip(label: String) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(skin.a2.copy(alpha = if (P.isDark) 0.2f else 0.1f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        ElyText(label, size = 10f, weight = FontWeight.SemiBold, color = P.ink, letterSpacing = tracking(0.1f), uppercase = true, maxLines = 2)
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

/**
 * La tira A–Z junto al canto de la rueda (solo con el orden por nombre y
 * listas largas, ver [IndexStrip]). Al arrastrar el dedo por ella se salta a
 * la primera entrada de cada letra y una burbuja enseña la letra. Es un
 * atajo táctil: el lector de pantalla la salta (las filas siguen ahí).
 */
@Composable
internal fun MeridianIndexStrip(entries: List<IndexEntry>, current: String?, onJump: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val skin = LocalSkin.current
    val jump by rememberUpdatedState(onJump)
    val list by rememberUpdatedState(entries)
    var dragging by remember { mutableStateOf(false) }
    var under by remember { mutableIntStateOf(-1) }
    BoxWithConstraints(modifier.clearAndSetSemantics { }) {
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        Column(
            Modifier
                .fillMaxHeight()
                .width(STRIP_WIDTH)
                .pointerInput(heightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun touch(y: Float) {
                            val i = IndexStrip.entryAt(y / heightPx, list.size)
                            if (i >= 0 && i != under) {
                                under = i
                                jump(list[i].firstIndex)
                            }
                        }
                        dragging = true
                        down.consume()
                        touch(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero) change.consume()
                            touch(change.position.y)
                        }
                        dragging = false
                        under = -1
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            entries.forEach { e ->
                val on = e.label == current
                ElyText(
                    e.label,
                    size = 8f,
                    weight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) skin.a2 else P.ink2,
                    align = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        if (dragging && under in entries.indices) {
            val slot = heightPx / entries.size
            val bubble = with(LocalDensity.current) { BUBBLE.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((STRIP_WIDTH + 10.dp).roundToPx(), (slot * (under + 0.5f) - bubble / 2f).roundToInt()) }
                    .size(BUBBLE)
                    .darkGlass(CircleShape, minAlpha = 0.72f),
                contentAlignment = Alignment.Center,
            ) {
                ElyText(entries[under].label, size = 20f, weight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

internal val STRIP_WIDTH = 18.dp
private val BUBBLE = 48.dp
