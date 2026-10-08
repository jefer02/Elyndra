package com.elyndra.launcher.ui.meridian

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.SignaturePalettes
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.ui.theme.LocalPoppins
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Swift
import com.elyndra.launcher.ui.theme.darkGlass
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max

/**
 * Los colores de Meridian para el tema activo, sacados del acento: el par de
 * su paleta de firma (o, si el acento es de un solo tono, ese tono con el
 * secundario girado), más hondo en claro. En oscuro la luz suma.
 */
@Immutable
internal class MeridianInk(
    val dark: Boolean,
    val primary: Color,
    val secondary: Color,
    val spark: Color,
    /** El primario como texto o icono sobre el papel (4,5:1). */
    val text: Color,
) {
    val blend: BlendMode = if (dark) BlendMode.Plus else BlendMode.SrcOver

    companion object {
        fun of(accentId: String, accentA: Int, accentC: Int, dark: Boolean): MeridianInk {
            val raw = SignaturePalettes.accentPair(accentId, accentA, accentC)
            val pair = if (raw.secondary == raw.primary) SignaturePalettes.derive(raw.primary) else raw
            val themed = SignaturePalettes.forTheme(pair, dark)
            return MeridianInk(dark, Color(themed.primary), Color(themed.secondary), Color(themed.spark), Color(SignaturePalettes.text(pair, dark)))
        }
    }
}

@Composable
internal fun rememberMeridianInk(): MeridianInk {
    val skin = LocalSkin.current
    val dark = P.isDark
    return remember(skin.accent, dark) { MeridianInk.of(skin.accent.id, skin.a1.argb(), skin.secondary.argb(), dark) }
}

/** Dónde va el arco respecto al canto derecho de la columna de la rueda. */
internal val DIAL_INSET = 6.dp

/** Tramos en que se pinta el arco (cada uno con su color y su opacidad: el degradado y el desvanecido sin crear pinceles). */
private const val ARC_SEGMENTS = 28

/**
 * El dial orbital: un arco fino y luminoso del primario al secundario (con
 * un resplandor de dos trazos y sin desenfoque) que forma parte del mismo
 * círculo que siguen las filas ([WheelArc]) y se apaga hacia los extremos;
 * una marca por juego, perpendicular al arco, que rueda con la lista como un
 * dial de verdad (en listas largas, más juntas y de una en N, con marcas
 * mayores); una muesca fija en la línea de foco con el nodo de luz, que late
 * un momento con cada cambio de selección. La marca que pasa por la muesca se
 * alarga y se enciende; las lejanas siguen la caída de la rueda.
 *
 * Todo en un único lienzo que lee la rueda al dibujar ([wheel]): sin
 * recomponer, sin objetos por fotograma. El nodo va siempre en
 * `línea de foco + lift`, el mismo valor que coloca la tarjeta enfocada: con
 * la rueda parada, en su centro exacto.
 *
 * En claro, marcas de grafito y el degradado de firma en el arco; en oscuro,
 * marcas claras y el resplandor suma.
 */
@Composable
internal fun MeridianDial(
    wheel: () -> WheelState?,
    count: Int,
    selected: Int,
    geo: MeridianGeometry,
    ink: MeridianInk,
    enterMs: () -> Float,
    reduced: Boolean,
    lite: Boolean,
    modifier: Modifier = Modifier,
) {
    val dial = DialScale.of(count) ?: return
    val flare = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (reduced) return@LaunchedEffect
        flare.snapTo(1f)
        flare.animateTo(0f, tween(FLARE_MS, easing = Swift))
    }
    Spacer(
        modifier
            .clearAndSetSemantics { }
            .drawWithCache {
                val ax = size.width - DIAL_INSET.toPx()
                val radius = geo.arcRadius.dp.toPx()
                val spacing = dial.spacing.dp.toPx()
                val pad = SPAN_PAD.toPx()
                val edgePad = EDGE_PAD.toPx()
                val wide = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
                val mid = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                val hair = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
                val core = Stroke(0.75.dp.toPx(), cap = StrokeCap.Round)
                val ringStroke = Stroke(1.dp.toPx())
                val tick = 5.dp.toPx()
                val major = 8.dp.toPx()
                val notch = 15.dp.toPx()
                val tickGap = 3.dp.toPx()
                val tickWidth = 1.dp.toPx()
                val litWidth = 2.dp.toPx()
                val nodeHalo = 12.dp.toPx()
                val nodeCore = 2.6.dp.toPx()
                val ring = 5.5.dp.toPx()
                val outer = 7.dp.toPx()
                val frontR = 14.dp.toPx()
                val tickColor = Color(ArtWash.tickColor(ink.dark))
                val tickAlpha = ArtWash.tickAlpha(ink.dark)
                val wideAlpha = if (ink.dark) 0.16f else 0.10f
                val midAlpha = if (ink.dark) 0.32f else 0.22f
                val halo = Brush.radialGradient(listOf(ink.primary.copy(alpha = 0.6f), ink.primary.copy(alpha = 0f)), center = Offset.Zero, radius = nodeHalo)
                val front = Brush.radialGradient(listOf(ink.spark, ink.spark.copy(alpha = 0f)), center = Offset.Zero, radius = frontR)
                val span = FloatArray(2)
                val normal = FloatArray(2)
                val box = Size(radius * 2f, radius * 2f)
                onDrawBehind {
                    val w = wheel() ?: return@onDrawBehind
                    val ignite = MeridianMotion.ignite(enterMs())
                    if (ignite <= 0f || w.rowPx <= 0f) return@onDrawBehind
                    val fade = if (reduced) ignite else 1f
                    val reach = if (reduced) 1f else ignite
                    val p = w.position
                    val fy = w.focusPx + w.lift()
                    val top = w.reserve().coerceAtLeast(0f) + edgePad
                    val up = (fy - top).coerceAtLeast(0f) * reach
                    val down = (size.height - edgePad - fy).coerceAtLeast(0f) * reach
                    dial.span(p, spacing, pad, up, down, span)
                    val a0 = span[0]
                    val a1 = span[1]
                    val centre = (a0 + a1) / 2f
                    val half = ((a1 - a0) / 2f).coerceAtLeast(1f)
                    val topLeft = Offset(ax - radius * 2f, fy - radius)

                    // El arco: resplandor ancho, medio, el filo y el núcleo; del primario al secundario y apagándose en las puntas.
                    for (s in 0 until ARC_SEGMENTS) {
                        val y0 = a0 + (a1 - a0) * s / ARC_SEGMENTS
                        val y1 = a0 + (a1 - a0) * (s + 1) / ARC_SEGMENTS
                        val ym = (y0 + y1) / 2f
                        val ends = DialScale.endFade(abs(ym - centre) / half)
                        if (ends <= 0.01f) continue
                        val k = ((ym - a0) / (a1 - a0).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val color = lerp(ink.primary, ink.secondary, k)
                        val start = Math.toDegrees(asin((y0 / radius).coerceIn(-1f, 1f).toDouble())).toFloat()
                        val end = Math.toDegrees(asin((y1 / radius).coerceIn(-1f, 1f).toDouble())).toFloat()
                        val sweep = end - start
                        val a = ends * fade
                        if (!lite) drawArc(color, start, sweep, false, topLeft, box, alpha = wideAlpha * a, style = wide, blendMode = ink.blend)
                        drawArc(color, start, sweep, false, topLeft, box, alpha = midAlpha * a, style = mid, blendMode = ink.blend)
                        drawArc(color, start, sweep, false, topLeft, box, alpha = a, style = hair, blendMode = ink.blend)
                        if (ink.dark) drawArc(Color.White, start, sweep, false, topLeft, box, alpha = 0.5f * a, style = core)
                    }

                    // Las marcas: ruedan con la lista; la que pasa por la muesca se alarga y se enciende.
                    val first = dial.first(p, -a0, spacing)
                    val last = dial.last(p, a1, spacing)
                    var i = first
                    while (i <= last) {
                        val dy = (i - p) * spacing
                        if (dy >= a0 && dy <= a1) {
                            val falloff = WheelTransform.alpha(dy / w.rowPx, lite).coerceAtLeast(0.25f)
                            val ends = DialScale.endFade(abs(dy - centre) / half)
                            val lit = DialScale.lit(dy, spacing)
                            val base = if (dial.isMajor(i)) major else tick
                            val len = base + (notch - base) * lit
                            val x = ax + WheelArc.offset(dy, radius)
                            val y = fy + dy
                            WheelArc.inward(dy, radius, normal)
                            val sx = x + normal[0] * tickGap
                            val sy = y + normal[1] * tickGap
                            val ex = x + normal[0] * (tickGap + len)
                            val ey = y + normal[1] * (tickGap + len)
                            val a = falloff * ends * fade
                            drawLine(tickColor, Offset(sx, sy), Offset(ex, ey), strokeWidth = tickWidth + (litWidth - tickWidth) * lit, alpha = tickAlpha * a * (1f - lit))
                            if (lit > 0.01f) {
                                drawLine(ink.primary, Offset(sx, sy), Offset(ex, ey), strokeWidth = litWidth, alpha = a * lit, cap = StrokeCap.Round)
                                drawLine(ink.spark, Offset(sx, sy), Offset(ex, ey), strokeWidth = litWidth * 3f, alpha = 0.22f * a * lit, cap = StrokeCap.Round, blendMode = ink.blend)
                            }
                        }
                        i += dial.stride
                    }

                    // La muesca fija y el nodo, que late con cada selección.
                    val f = flare.value
                    drawLine(ink.primary, Offset(ax + tickGap, fy), Offset(ax + outer, fy), strokeWidth = litWidth, alpha = fade, cap = StrokeCap.Round)
                    val k = 1f + 0.7f * f
                    translate(ax, fy) {
                        scale(k, k, Offset.Zero) { drawCircle(halo, nodeHalo, Offset.Zero, alpha = (0.75f + 0.25f * f) * fade, blendMode = ink.blend) }
                        drawCircle(ink.secondary, ring * (1f + 0.25f * f), Offset.Zero, alpha = 0.85f * fade, style = ringStroke)
                        drawCircle(ink.spark, nodeCore * (1f + 0.3f * f), Offset.Zero, alpha = fade)
                    }

                    // Los frentes del encendido: un destello que corre hacia cada punta.
                    if (!reduced && ignite < 1f) {
                        val a = (1f - ignite) * 0.9f
                        translate(ax + WheelArc.offset(a0, radius), fy + a0) { drawCircle(front, frontR, Offset.Zero, alpha = a, blendMode = ink.blend) }
                        translate(ax + WheelArc.offset(a1, radius), fy + a1) { drawCircle(front, frontR, Offset.Zero, alpha = a, blendMode = ink.blend) }
                    }
                }
            },
    )
}

/** El pulso del nodo al cambiar de selección. */
private const val FLARE_MS = 480

/** Margen del arco más allá de la primera y la última marca. */
private val SPAN_PAD = 22.dp

/** Margen del arco con los cantos de la rueda. */
private val EDGE_PAD = 8.dp

/**
 * El contador del dial: una cápsula de cristal pegada al nodo por el lado
 * del hero, con cifras tabulares —la posición grande, el total apagado— que
 * ruedan en vertical como un cuentakilómetros al cambiar (con "reducir
 * movimiento", un fundido). Siempre las mismas cifras ([DialCounter]): no
 * cambia de ancho. En la fila de "Añadir" ([onAdd]) enseña un "+". Lleva un
 * matiz del color del fondo ([tint], ≤ 12 %). Quien la pone la coloca leyendo
 * el nodo en la fase de colocación.
 */
@Composable
internal fun DialCounterChip(index: Int, total: Int, onAdd: Boolean, tint: () -> Color, enterMs: () -> Float, reduced: Boolean, modifier: Modifier = Modifier) {
    if (total <= 0) return
    val measurer = rememberTextMeasurer()
    val family = LocalPoppins.current
    val density = LocalDensity.current
    val digits = DialCounter.digits(total)
    val glyphs = remember(measurer, family, density) {
        val big = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White)
        val small = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 9.5.sp, color = Color.White)
        CounterGlyphs(
            big = Array(10) { measurer.measure(it.toString(), big, density = density) },
            small = Array(10) { measurer.measure(it.toString(), small, density = density) },
            plus = measurer.measure("+", big, density = density),
            slash = measurer.measure("/", small, density = density),
        )
    }
    val value = if (onAdd) -1 else index + 1
    val shown = remember { mutableIntStateOf(value) }
    val previous = remember { mutableIntStateOf(value) }
    val roll = remember { Animatable(1f) }
    LaunchedEffect(value) {
        if (value == shown.intValue) return@LaunchedEffect
        previous.intValue = shown.intValue
        shown.intValue = value
        roll.snapTo(0f)
        roll.animateTo(1f, tween(ROLL_MS, easing = Swift))
    }
    val padH = with(density) { 10.dp.toPx() }
    val gap = with(density) { 4.dp.toPx() }
    val width = padH * 2f + digits * glyphs.bigAdvance + gap + glyphs.slash.size.width + gap + digits * glyphs.smallAdvance
    val height = max(glyphs.bigHeight + with(density) { 8.dp.toPx() }, with(density) { COUNTER_HEIGHT.toPx() })
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clearAndSetSemantics { }
            .graphicsLayer { alpha = MeridianMotion.ignite(enterMs()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .darkGlass(shape, minAlpha = MeridianGlass.FLOOR)
            .drawBehind {
                drawRect(tint(), alpha = COUNTER_TINT)
                val t = roll.value
                val cy = size.height / 2f
                clipRect {
                    var x = padH
                    for (c in 0 until digits) {
                        drawRolling(glyphs, previous.intValue, shown.intValue, digits, c, x, cy, t, reduced)
                        x += glyphs.bigAdvance
                    }
                    x += gap
                    drawText(glyphs.slash, topLeft = Offset(x, cy - glyphs.slash.size.height / 2f + glyphs.bigBaselineShift), alpha = 0.55f)
                    x += glyphs.slash.size.width + gap
                    for (c in 0 until digits) {
                        val g = glyphs.small[DialCounter.digitAt(total, digits, c)]
                        drawText(g, topLeft = Offset(x + (glyphs.smallAdvance - g.size.width) / 2f, cy - g.size.height / 2f + glyphs.bigBaselineShift), alpha = 0.55f)
                        x += glyphs.smallAdvance
                    }
                }
            },
    )
}

/** Una cifra del número grande: la vieja sale por arriba (o por abajo) mientras la nueva entra. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRolling(
    glyphs: CounterGlyphs,
    from: Int,
    to: Int,
    digits: Int,
    column: Int,
    x: Float,
    cy: Float,
    t: Float,
    reduced: Boolean,
) {
    if (to < 0 || from < 0) {
        // "+" en la fila de "Añadir": fundido, y el "+" va en la última columna.
        val last = column == digits - 1
        val newGlyph = if (to < 0) glyphs.plus.takeIf { last } else glyphs.big[DialCounter.digitAt(to, digits, column)]
        val oldGlyph = if (from < 0) glyphs.plus.takeIf { last } else glyphs.big[DialCounter.digitAt(from, digits, column)]
        if (oldGlyph != null && t < 1f) drawText(oldGlyph, topLeft = Offset(x + (glyphs.bigAdvance - oldGlyph.size.width) / 2f, cy - oldGlyph.size.height / 2f), alpha = 1f - t)
        if (newGlyph != null) drawText(newGlyph, topLeft = Offset(x + (glyphs.bigAdvance - newGlyph.size.width) / 2f, cy - newGlyph.size.height / 2f), alpha = t)
        return
    }
    val dir = DialCounter.direction(from, to, digits, column)
    val newG = glyphs.big[DialCounter.digitAt(to, digits, column)]
    val nx = x + (glyphs.bigAdvance - newG.size.width) / 2f
    val ny = cy - newG.size.height / 2f
    if (dir == 0 || t >= 1f) {
        drawText(newG, topLeft = Offset(nx, ny))
        return
    }
    val oldG = glyphs.big[DialCounter.digitAt(from, digits, column)]
    val ox = x + (glyphs.bigAdvance - oldG.size.width) / 2f
    val oy = cy - oldG.size.height / 2f
    if (reduced) {
        drawText(oldG, topLeft = Offset(ox, oy), alpha = 1f - t)
        drawText(newG, topLeft = Offset(nx, ny), alpha = t)
        return
    }
    val h = glyphs.bigHeight
    drawText(oldG, topLeft = Offset(ox, oy - dir * t * h), alpha = 1f - t)
    drawText(newG, topLeft = Offset(nx, ny + dir * (1f - t) * h), alpha = t)
}

/** Las cifras medidas una vez (y su avance tabular: el de la más ancha). */
private class CounterGlyphs(
    val big: Array<androidx.compose.ui.text.TextLayoutResult>,
    val small: Array<androidx.compose.ui.text.TextLayoutResult>,
    val plus: androidx.compose.ui.text.TextLayoutResult,
    val slash: androidx.compose.ui.text.TextLayoutResult,
) {
    val bigAdvance: Float = big.maxOf { it.size.width }.toFloat()
    val smallAdvance: Float = small.maxOf { it.size.width }.toFloat()
    val bigHeight: Float = big[0].size.height.toFloat()

    /** Lo que baja el total para compartir la línea de base con el número grande. */
    val bigBaselineShift: Float = (big[0].firstBaseline - big[0].size.height / 2f) - (small[0].firstBaseline - small[0].size.height / 2f)
}

internal val COUNTER_HEIGHT = 24.dp

/** Lo que tarda una cifra en rodar. */
private const val ROLL_MS = 300

/** El matiz del fondo en la cápsula (como mucho un 12 %). */
private const val COUNTER_TINT = 0.10f
