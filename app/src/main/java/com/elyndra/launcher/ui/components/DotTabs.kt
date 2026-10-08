package com.elyndra.launcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.sound.UiSound
import com.elyndra.launcher.ui.DockPalettes
import com.elyndra.launcher.ui.DotTabsLayout
import com.elyndra.launcher.ui.LocalUiSounds
import com.elyndra.launcher.ui.theme.LocalPoppins
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Swift
import com.elyndra.launcher.ui.theme.consoleFocus
import com.elyndra.launcher.ui.theme.DARK_GLASS_MIN
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.outerShadow
import com.elyndra.launcher.ui.theme.topGlint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Icono opcional de una sección: se dibuja en un cuadro de [DOT_TAB_ICON] con el color que toca. */
typealias DotTabIcon = DrawScope.(Color) -> Unit

private const val DOT_TAB_ICON = 14f
private const val DOT_TAB_ICON_GAP = 6f

val CapsuleShape = RoundedCornerShape(50)

/**
 * La lámina del dock, del botón de orden y de su menú: cristal con el color
 * del tema (perla en claro, tinta honda en oscuro), casi opaco para que lo de
 * encima se lea sobre cualquier arte (ver [DockPalettes]), con el filo de luz
 * de arriba y el canto champán de la intro.
 */
@Composable
fun Modifier.dockSurface(
    shape: Shape = CapsuleShape,
    alpha: Float = DockPalettes.surfaceAlpha(P.isDark),
    elevation: Dp = 10.dp,
): Modifier {
    val dark = P.isDark
    val base = Color(DockPalettes.surface(dark, alpha))
    val shadow = P.shade.copy(alpha = if (dark) 0.45f else 0.16f)
    val rim = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.24f else 0.95f),
            Color.White.copy(alpha = if (dark) 0.05f else 0.45f),
        ),
    )
    val haze = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = DockPalettes.hazeTop(dark)),
            Color.White.copy(alpha = 0f),
        ),
    )
    return this
        .outerShadow(elevation, shape, ambientColor = shadow, spotColor = shadow)
        .background(base, shape)
        .background(haze, shape)
        .border(1.dp, rim, shape)
        .topGlint(if (dark) 0.9f else 0.8f)
}

/**
 * Dock de secciones: una cápsula de cristal con un punto por sección y la
 * elegida abierta en píldora con su rótulo ("· [Android] ·").
 *
 * Al cambiar, la píldora se estira hacia la nueva como una gota (sus dos
 * cantos van con muelles de distinta rigidez), la vieja se recoge en punto
 * mientras el punto nuevo se abre, el rótulo entra recortado desde la
 * izquierda con un fundido corto y un brillo cruza la píldora. Todo son unos
 * pocos `Animatable` leídos al medir y al dibujar: no recompone por fotograma.
 * Con "reducir movimiento", salto directo.
 *
 * Los rótulos se miden con un [rememberTextMeasurer], así la píldora cabe en
 * cualquier idioma; por encima de [DotTabsLayout.PILL_MAX] se cortan con
 * puntos suspensivos.
 *
 * @param focused el mando está en el dock (aro de foco).
 * @param focusIndex punto señalado por el mando: lleva un aro y enseña un
 *   momento su rótulo.
 * @param feedback sonido de navegación y un toque háptico al cambiar.
 */
@Composable
fun DotTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<DotTabIcon?> = emptyList(),
    focused: Boolean = false,
    focusIndex: Int = -1,
    feedback: Boolean = true,
    /** Suelo de la tinta del cristal (ver [darkGlass]); más alto en la costura. */
    glassMinAlpha: Float = DARK_GLASS_MIN,
) {
    val n = labels.size
    if (n == 0) return
    val sel = selected.coerceIn(0, n - 1)
    val dens = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val skin = LocalSkin.current
    val dark = P.isDark
    val family = LocalPoppins.current
    val measurer = rememberTextMeasurer()
    val style = remember(family) {
        TextStyle(fontFamily = family, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em, color = Color.White)
    }
    val maxLabelPx = with(dens) { DotTabsLayout.LABEL_MAX.dp.roundToPx() }
    val texts = remember(labels, style, dens, maxLabelPx) {
        labels.map {
            measurer.measure(it, style, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxLabelPx), density = dens)
        }
    }
    val hasIcon = remember(icons, n) { BooleanArray(n) { icons.getOrNull(it) != null } }
    val pills = remember(texts, hasIcon) {
        FloatArray(n) { i ->
            DotTabsLayout.pillWidth(texts[i].size.width / dens.density, if (hasIcon[i]) DOT_TAB_ICON + DOT_TAB_ICON_GAP else 0f)
        }
    }

    val expansion = remember(n) { List(n) { Animatable(if (it == sel) 1f else 0f) } }
    val start = remember(n) { DotTabsLayout.pillTarget(pills, sel) }
    val left = remember(n) { Animatable(start.first) }
    val right = remember(n) { Animatable(start.second) }
    val reveal = remember(n) { Animatable(1f) }
    val sheen = remember(n) { Animatable(1f) }
    val peek = remember { Animatable(0f) }
    val press = remember { Animatable(1f) }
    val pressed = remember { mutableIntStateOf(-1) }
    // Lo que había antes del cambio en curso: no es estado (no recompone).
    val previous = remember(n) { IntArray(1) { sel } }
    val announced = remember { IntArray(1) { sel } }

    LaunchedEffect(sel, pills.contentHashCode()) {
        val (l, r) = DotTabsLayout.pillTarget(pills, sel)
        val from = previous[0]
        previous[0] = sel
        if (reduced || from == sel) {
            // Mismo hueco (cambió un rótulo, p. ej. el idioma): se recoloca sin ceremonia.
            expansion.forEachIndexed { i, a -> a.snapTo(if (i == sel) 1f else 0f) }
            left.snapTo(l)
            right.snapTo(r)
            reveal.snapTo(1f)
            sheen.snapTo(1f)
            return@LaunchedEffect
        }
        val (kl, kr) = DotTabsLayout.edgeStiffness(from, sel)
        coroutineScope {
            expansion.forEachIndexed { i, a ->
                launch { a.animateTo(if (i == sel) 1f else 0f, spring(dampingRatio = 0.86f, stiffness = 520f)) }
            }
            launch { left.animateTo(l, spring(dampingRatio = 0.8f, stiffness = kl)) }
            launch { right.animateTo(r, spring(dampingRatio = 0.8f, stiffness = kr)) }
            launch {
                reveal.snapTo(0f)
                delay(40)
                reveal.animateTo(1f, tween(240, easing = Swift))
            }
            launch {
                sheen.snapTo(0f)
                delay(70)
                sheen.animateTo(1f, tween(260, easing = LinearOutSlowInEasing))
            }
        }
    }

    val haptics = LocalHapticFeedback.current
    val sounds = LocalUiSounds.current
    LaunchedEffect(sel) {
        if (feedback && announced[0] != sel) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            sounds?.play(UiSound.Navigate)
        }
        announced[0] = sel
    }

    // El rótulo del punto señalado asoma un momento y se va.
    LaunchedEffect(focused, focusIndex, sel) {
        if (focused && focusIndex in 0 until n && focusIndex != sel) {
            if (reduced) peek.snapTo(1f) else peek.animateTo(1f, tween(140, easing = Swift))
            delay(1_100)
            if (reduced) peek.snapTo(0f) else peek.animateTo(0f, tween(220))
        } else if (reduced) {
            peek.snapTo(0f)
        } else {
            peek.animateTo(0f, tween(120))
        }
    }

    val sources = remember(n) { List(n) { MutableInteractionSource() } }
    sources.forEachIndexed { i, source ->
        LaunchedEffect(source, reduced) {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        pressed.intValue = i
                        if (reduced) press.snapTo(0.9f) else launch { press.animateTo(0.9f, spring(dampingRatio = 0.7f, stiffness = 900f)) }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        if (reduced) press.snapTo(1f) else launch { press.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 600f)) }
                    }
                }
            }
        }
    }

    val select by rememberUpdatedState(onSelect)
    val accent = skin.a1
    val pillStart = remember(accent) { Color(DockPalettes.pillStart(accent.argb())) }
    val pillEnd = remember(skin.fillEnd) { Color(DockPalettes.pillEnd(skin.fillEnd.argb())) }
    val pillRim = remember(accent) { Color(DockPalettes.pillRim(accent.argb(), true)) }
    // El dock lleva el cristal oscuro de la barra del hero en los dos temas:
    // puntos claros y el acento en su tono para fondo oscuro.
    val dot = Color(DockPalettes.GLASS_DOT)
    val ring = remember(skin.accent) { Color(DockPalettes.glassAccent(skin.accent.content(true).argb())) }
    val peekInk = Color.White
    val peekSurface = P.shade.copy(alpha = DockPalettes.GLASS_PEEK_ALPHA)
    val path = remember { Path() }
    val scratch = remember(n) { FloatArray(n + 1) }
    val e = remember(n) { FloatArray(n) }

    Layout(
        content = {
            // El cristal va en un hijo aparte: su recorte no puede comerse la
            // zona táctil de las pestañas, que sobresale de la cápsula.
            Box(Modifier.darkGlass(CapsuleShape, minAlpha = glassMinAlpha))
            for (i in 0 until n) {
                Box(
                    Modifier
                        .selectable(
                            selected = i == sel,
                            interactionSource = sources[i],
                            indication = null,
                            role = Role.Tab,
                            onClick = { select(i) },
                        )
                        .semantics { contentDescription = labels[i] },
                )
            }
        },
        modifier = modifier
            // El rótulo que asoma va por fuera de la cápsula: se dibuja antes del recorte.
            .drawWithContent {
                drawContent()
                val p = peek.value
                if (p > 0f && focusIndex in 0 until n && focusIndex != sel) {
                    for (i in 0 until n) e[i] = expansion[i].value
                    val cx = DotTabsLayout.slotCenter(pills, e, focusIndex).dp.toPx()
                    drawPeek(texts[focusIndex], cx, p, peekSurface, peekInk)
                }
            }
            .outerShadow(8.dp, CapsuleShape, ambientColor = P.shade.copy(alpha = 0.35f), spotColor = P.shade.copy(alpha = 0.35f))
            .consoleFocus(focused, CapsuleShape, color = ring)
            .selectableGroup()
            .drawWithContent {
                drawContent()
                for (i in 0 until n) e[i] = expansion[i].value
                val o = DotTabsLayout.offsets(pills, e, scratch)
                val h = size.height
                val cy = h / 2f
                val pxPerDp = dens.density

                // Puntos.
                for (i in 0 until n) {
                    val s = DotTabsLayout.dotScale(e[i]) * if (pressed.intValue == i) press.value else 1f
                    if (s <= 0f) continue
                    val cx = (o[i] + DotTabsLayout.slotWidth(pills[i], e[i]) / 2f) * pxPerDp
                    drawCircle(dot, radius = DotTabsLayout.DOT / 2f * pxPerDp * s, center = Offset(cx, cy))
                    if (focused && i == focusIndex && i != sel) {
                        drawCircle(ring, radius = 7.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
                    }
                }

                // Píldora.
                val inset = DotTabsLayout.INSET * pxPerDp
                val l = left.value * pxPerDp
                val r = right.value * pxPerDp
                if (r - l <= 1f) return@drawWithContent
                val top = inset
                val bottom = h - inset
                val radius = (bottom - top) / 2f
                val rect = RoundRect(l, top, r, bottom, CornerRadius(radius))
                path.rewind()
                path.addRoundRect(rect)
                // Halo de tres trazos, como el marco de selección (sin desenfoque).
                for ((w, a) in GLOW) {
                    drawRoundRect(
                        accent.copy(alpha = a),
                        topLeft = Offset(l - w.dp.toPx() / 2f, top - w.dp.toPx() / 2f),
                        size = Size(r - l + w.dp.toPx(), bottom - top + w.dp.toPx()),
                        cornerRadius = CornerRadius(radius + w.dp.toPx() / 2f),
                        style = Stroke(w.dp.toPx()),
                    )
                }
                drawPath(path, Brush.linearGradient(listOf(pillStart, pillEnd), start = Offset(l, top), end = Offset(r, bottom)))
                clipPath(path) {
                    // Luz que entra por arriba.
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (dark) 0.22f else 0.3f),
                            0.55f to Color.Transparent,
                            startY = top,
                            endY = bottom,
                        ),
                        topLeft = Offset(l, top),
                        size = Size(r - l, bottom - top),
                    )
                    val s = sheen.value
                    if (s < 1f) {
                        val span = r - l
                        val x = l + span * (-0.3f + 1.6f * s)
                        val band = (bottom - top) * 1.6f
                        val a = 0.5f * sin(s * PI.toFloat())
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = a), Color.Transparent),
                                start = Offset(x - band, top),
                                end = Offset(x, bottom),
                            ),
                            topLeft = Offset(l, top),
                            size = Size(span, bottom - top),
                        )
                    }
                    for (i in 0 until n) {
                        val alpha: Float
                        val slotLeft: Float
                        val slotWidth: Float
                        if (i == sel) {
                            // El rótulo nuevo ya está en su sitio final: la píldora lo destapa.
                            val (tl, _) = DotTabsLayout.pillTarget(pills, sel)
                            slotLeft = tl
                            slotWidth = pills[i]
                            alpha = (reveal.value * 1.6f).coerceAtMost(1f)
                        } else {
                            alpha = DotTabsLayout.fadingLabelAlpha(e[i])
                            if (alpha <= 0f) continue
                            slotLeft = o[i]
                            slotWidth = DotTabsLayout.slotWidth(pills[i], e[i])
                        }
                        val text = texts[i]
                        val icon = icons.getOrNull(i)
                        val content = text.size.width + if (icon != null) (DOT_TAB_ICON + DOT_TAB_ICON_GAP).dp.toPx() else 0f
                        val x0 = slotLeft * pxPerDp + (slotWidth * pxPerDp - content) / 2f
                        val clipRight = if (i == sel) x0 + reveal.value * (content + DotTabsLayout.LABEL_PAD.dp.toPx()) else r
                        clipRect(left = x0 - 1f, top = top, right = clipRight, bottom = bottom) {
                            var x = x0
                            if (icon != null) {
                                val box = DOT_TAB_ICON.dp.toPx()
                                translate(x, cy - box / 2f) {
                                    clipRect(0f, 0f, box, box) { icon(Color.White.copy(alpha = alpha)) }
                                }
                                x += box + DOT_TAB_ICON_GAP.dp.toPx()
                            }
                            drawText(text, topLeft = Offset(x, cy - text.size.height / 2f), alpha = alpha)
                        }
                    }
                }
                drawPath(
                    path,
                    Brush.verticalGradient(listOf(pillRim, pillRim.copy(alpha = pillRim.alpha * 0.25f)), startY = top, endY = bottom),
                    style = Stroke(1.dp.toPx()),
                )
                if (focused && focusIndex == sel) {
                    val g = 2.dp.toPx()
                    drawRoundRect(
                        ring,
                        topLeft = Offset(l - g, top - g),
                        size = Size(r - l + 2 * g, bottom - top + 2 * g),
                        cornerRadius = CornerRadius(radius + g),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            },
    ) { measurables, _ ->
        for (i in 0 until n) e[i] = expansion[i].value
        val o = DotTabsLayout.offsets(pills, e, scratch)
        val h = DotTabsLayout.HEIGHT.dp.roundToPx()
        val touch = DotTabsLayout.TOUCH.dp.roundToPx()
        val width = (o[n] * dens.density).roundToInt()
        val glass = measurables[0].measure(Constraints.fixed(width, h))
        val xs = IntArray(n)
        // Cada pestaña mide 48 dp de alto y sobresale de la cápsula: la zona
        // táctil es de 48 × 48 dp aunque la cápsula mida 34 de alto.
        val tabs = List(n) { i ->
            val (l, r) = DotTabsLayout.touchSpan(o, pills, e, i)
            val x0 = (l * dens.density).roundToInt()
            xs[i] = x0
            measurables[i + 1].measure(Constraints.fixed(((r * dens.density).roundToInt() - x0).coerceAtLeast(1), touch))
        }
        layout(width, h) {
            glass.place(0, 0)
            tabs.forEachIndexed { i, p -> p.place(xs[i], (h - touch) / 2) }
        }
    }
}

/** Ancho (trazo, alfa) de los tres trazos del halo de la píldora en oscuro. */
private val GLOW = listOf(2f to 0.22f, 5f to 0.1f, 9f to 0.04f)

/** El rótulo del punto señalado: una ficha bajo el dock, centrada en el punto. */
private fun DrawScope.drawPeek(text: TextLayoutResult, cx: Float, p: Float, surface: Color, ink: Color) {
    val padX = 9.dp.toPx()
    val h = 22.dp.toPx()
    val w = text.size.width + padX * 2
    val y = size.height + 6.dp.toPx() - (1f - p) * 4.dp.toPx()
    val x = cx - w / 2f
    val radius = CornerRadius(h / 2f)
    drawRoundRect(P.shade.copy(alpha = 0.18f * p), topLeft = Offset(x, y + 1.dp.toPx()), size = Size(w, h), cornerRadius = radius)
    drawRoundRect(surface.copy(alpha = surface.alpha * p), topLeft = Offset(x, y), size = Size(w, h), cornerRadius = radius)
    drawRoundRect(
        Color.White.copy(alpha = 0.24f * p),
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = radius,
        style = Stroke(1.dp.toPx()),
    )
    drawText(text, color = ink, topLeft = Offset(x + padX, y + (h - text.size.height) / 2f), alpha = p)
}
