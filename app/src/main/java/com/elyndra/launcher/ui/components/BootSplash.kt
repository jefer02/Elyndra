package com.elyndra.launcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.elyndra.launcher.ui.theme.LocalPoppins
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/* ─────────────────────────────────────────────────────────────
   Arranque de consola.

   Sustituye a la "L" del splash del sistema. Es una secuencia de
   encendido de consola moderna, de ~2,3 s:

     0.00–0.40 s  la pantalla se enciende como un CRT: una línea
                  que se abre en horizontal y luego en vertical,
                  con un destello tintado de acento;
     0.35–1.85 s  emblema y rótulo ELYNDRA con separación RGB, un
                  resplandor que respira y la barra de carga por
                  segmentos, con porcentaje;
     1.85–2.30 s  salida: fundido con un leve acercamiento, y la
                  biblioteca (que ya está compuesta debajo) aparece.

   Todo se dibuja en la fase de dibujo leyendo un único reloj: el
   splash no recompone nada mientras dura, así que no roba tiempo
   de fotograma a la biblioteca que se está montando detrás. Las
   scanlines son un sombreador repetido (un rectángulo por
   fotograma, no cientos de líneas).
   ───────────────────────────────────────────────────────────── */

private const val TOTAL_MS = 2300
private const val EXIT_START = 1850f
private const val REDUCED_MS = 650

/** El negro de la consola apagada: el mismo que el splash del sistema (themes.xml). */
private val BootBlack = Color(0xFF05070A)

@Composable
fun BootSplash(onFinished: () -> Unit) {
    val skin = LocalSkin.current
    val reduced = LocalReducedMotion.current
    val finished = rememberUpdatedState(onFinished)
    val clock = remember { Animatable(0f) }
    val total = if (reduced) REDUCED_MS else TOTAL_MS
    LaunchedEffect(Unit) {
        clock.animateTo(total.toFloat(), tween(total, easing = LinearEasing))
        finished.value()
    }

    val measurer = rememberTextMeasurer()
    val family = LocalPoppins.current
    val a1 = skin.a1
    val a2 = skin.a2

    Box(
        Modifier
            .fillMaxSize()
            // Salida: se funde con un acercamiento corto, como una consola que
            // "entra" en su menú. Se lee en la capa: sin recomposición.
            .graphicsLayer {
                val t = clock.value
                val exitStart = if (reduced) total * 0.5f else EXIT_START
                val e = ((t - exitStart) / (total - exitStart)).coerceIn(0f, 1f)
                alpha = 1f - e * e
                val k = if (reduced) 1f else 1f + 0.06f * e
                scaleX = k
                scaleY = k
            }
            // Mientras se ve, los toques no atraviesan a la biblioteca de detrás.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .drawWithCache {
                val minDim = min(size.width, size.height)
                val tablet = minDim > 600.dp.toPx()
                val wordStyle = TextStyle(
                    fontFamily = family,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (tablet) 38.sp else 26.sp,
                    letterSpacing = 0.38.em,
                    color = Color.White,
                )
                val word = measurer.measure("ELYNDRA", wordStyle)
                // Cifras precalculadas: el porcentaje cambia en cada fotograma y
                // medir texto nuevo cada vez generaría basura para el recolector.
                val digitStyle = TextStyle(
                    fontFamily = family,
                    fontWeight = FontWeight.Medium,
                    fontSize = if (tablet) 12.sp else 10.sp,
                    letterSpacing = 0.12.em,
                    color = Color.White,
                )
                val digits = Array(10) { measurer.measure(it.toString(), digitStyle) }
                val percent = measurer.measure("%", digitStyle)
                val scan = scanlineBrush(3.dp.toPx().coerceAtLeast(2f))
                val vignette = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.65f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.6f),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = maxOf(size.width, size.height) * 0.75f,
                )
                val emblem = Path()
                onDrawBehind {
                    // Con "reducir movimiento" se enseña el fotograma final quieto.
                    val t = if (reduced) 1_700f else clock.value
                    drawRect(BootBlack)
                    drawPowerOn(t, a2)
                    drawContent(t, a1, a2, minDim, word, digits, percent, emblem)
                    drawRect(scan, alpha = 0.5f)
                    if (!reduced) drawScanBand(t)
                    drawRect(vignette)
                }
            },
    )
}

/* ── fases ─────────────────────────────────────────────────────── */

/** Encendido CRT: línea horizontal que se abre en vertical, y el destello. */
private fun DrawScope.drawPowerOn(t: Float, accent: Color) {
    val glow = lerp(Color.White, accent, 0.35f)
    if (t < 400f) {
        val w = size.width * easeOutCubic((t / 200f).coerceIn(0f, 1f))
        val line = 2.dp.toPx()
        val h = line + (size.height - line) * easeInCubic(((t - 190f) / 210f).coerceIn(0f, 1f))
        drawRect(
            glow,
            topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
            size = Size(w, h),
            alpha = 0.95f,
        )
    }
    // El destello se apaga despacio sobre el contenido que ya aparece.
    val flash = when {
        t < 400f -> 0f
        t < 760f -> 0.75f * (1f - easeOutCubic((t - 400f) / 360f))
        else -> 0f
    }
    if (flash > 0f) drawRect(glow, alpha = flash)
}

private fun DrawScope.drawContent(
    t: Float,
    a1: Color,
    a2: Color,
    minDim: Float,
    word: TextLayoutResult,
    digits: Array<TextLayoutResult>,
    percent: TextLayoutResult,
    emblem: Path,
) {
    val ca = smoothstep(350f, 720f, t)
    if (ca <= 0f) return
    val cx = size.width / 2f
    val emblemSize = (minDim * 0.15f).coerceIn(56.dp.toPx(), 132.dp.toPx())
    val block = emblemSize + 18.dp.toPx() + word.size.height + 26.dp.toPx() + 30.dp.toPx()
    val cy = (size.height - block) / 2f + emblemSize / 2f

    // Resplandor que respira detrás del emblema.
    val breathe = 0.8f + 0.2f * sin(t / 1000f * 2f * PI.toFloat())
    drawCircle(
        Brush.radialGradient(
            listOf(a1.copy(alpha = 0.42f * ca * breathe), a2.copy(alpha = 0.12f * ca), Color.Transparent),
            center = Offset(cx, cy),
            radius = minDim * 0.5f,
        ),
        radius = minDim * 0.5f,
        center = Offset(cx, cy),
    )

    // Emblema: dos rombos que giran en sentidos opuestos y un núcleo de luz.
    val pop = easeOutBack(((t - 350f) / 520f).coerceIn(0f, 1f))
    val k = 0.6f + 0.4f * pop
    val stroke = 2.dp.toPx()
    scale(k, pivot = Offset(cx, cy)) {
        rotate(45f + t * 0.03f, pivot = Offset(cx, cy)) {
            diamond(emblem, cx, cy, emblemSize / 2f)
            drawPath(emblem, Brush.linearGradient(listOf(a1, a2), Offset(cx - emblemSize, cy), Offset(cx + emblemSize, cy)), alpha = ca, style = Stroke(stroke))
        }
        rotate(-t * 0.06f, pivot = Offset(cx, cy)) {
            diamond(emblem, cx, cy, emblemSize * 0.3f)
            drawPath(emblem, Color.White, alpha = 0.85f * ca, style = Stroke(stroke * 0.75f))
        }
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = ca), a2.copy(alpha = 0.5f * ca), Color.Transparent), center = Offset(cx, cy), radius = emblemSize * 0.22f),
            radius = emblemSize * 0.22f,
            center = Offset(cx, cy),
        )
    }

    // Rótulo con separación RGB (la marca de los menús de consola), que se
    // cierra según aparece.
    val wa = smoothstep(480f, 900f, t)
    val split = (1f - wa) * 6.dp.toPx() + 1.2.dp.toPx()
    val wordTop = cy + emblemSize / 2f + 18.dp.toPx()
    val wordLeft = cx - word.size.width / 2f
    drawText(word, color = a1, topLeft = Offset(wordLeft - split, wordTop), alpha = 0.55f * wa)
    drawText(word, color = a2, topLeft = Offset(wordLeft + split, wordTop), alpha = 0.55f * wa)
    drawText(word, color = Color.White, topLeft = Offset(wordLeft, wordTop), alpha = wa)

    // Barra de carga por segmentos.
    val progress = easeInOutCubic(((t - 560f) / 1180f).coerceIn(0f, 1f))
    val barW = min(size.width * 0.56f, 300.dp.toPx())
    val barH = 5.dp.toPx()
    val barTop = wordTop + word.size.height + 26.dp.toPx()
    val barLeft = cx - barW / 2f
    val pad = 3.dp.toPx()
    drawRoundRect(
        Color.White,
        topLeft = Offset(barLeft - pad, barTop - pad),
        size = Size(barW + pad * 2, barH + pad * 2),
        cornerRadius = CornerRadius(pad * 2),
        alpha = 0.2f * ca,
        style = Stroke(1.dp.toPx()),
    )
    val segments = 24
    val gap = 2.dp.toPx()
    val segW = (barW - gap * (segments - 1)) / segments
    val filled = progress * segments
    for (i in 0 until segments) {
        val f = (filled - i).coerceIn(0f, 1f)
        if (f <= 0f) break
        drawRect(
            lerp(a1, a2, i / (segments - 1f)),
            topLeft = Offset(barLeft + i * (segW + gap), barTop),
            size = Size(segW, barH),
            alpha = f * ca,
        )
    }
    // Cabeza de la barra: un punto de luz que avanza.
    if (progress in 0.001f..0.999f) {
        val head = Offset(barLeft + barW * progress, barTop + barH / 2f)
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f * ca), a2.copy(alpha = 0.4f * ca), Color.Transparent), center = head, radius = barH * 3f),
            radius = barH * 3f,
            center = head,
        )
    }

    // Porcentaje, con las cifras ya medidas.
    val value = (progress * 100f).toInt().coerceIn(0, 100)
    val text = if (value == 100) intArrayOf(1, 0, 0) else intArrayOf(value / 10, value % 10)
    var x = cx - (text.sumOf { digits[it].size.width } + percent.size.width) / 2f
    val y = barTop + barH + 10.dp.toPx()
    val da = 0.7f * smoothstep(600f, 900f, t)
    text.forEach { d ->
        drawText(digits[d], topLeft = Offset(x, y), alpha = da)
        x += digits[d].size.width
    }
    drawText(percent, topLeft = Offset(x, y), alpha = da)
}

/** Banda de barrido que baja por la pantalla, como el refresco de un tubo. */
private fun DrawScope.drawScanBand(t: Float) {
    val band = size.height * 0.18f
    val y = ((t / 1600f) % 1f) * (size.height + band) - band
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.05f), Color.Transparent),
            startY = y,
            endY = y + band,
        ),
        topLeft = Offset(0f, y),
        size = Size(size.width, band),
    )
}

/** Líneas de barrido: un mosaico de 1 px de ancho repetido por toda la pantalla. */
private fun scanlineBrush(period: Float): ShaderBrush {
    val h = period.toInt().coerceAtLeast(2)
    val bitmap = ImageBitmap(1, h)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    canvas.drawRect(0f, 0f, 1f, 1f, androidx.compose.ui.graphics.Paint().apply { color = Color.Black.copy(alpha = 0.55f) })
    return ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

private fun diamond(path: Path, cx: Float, cy: Float, r: Float) {
    path.reset()
    path.moveTo(cx, cy - r)
    path.lineTo(cx + r, cy)
    path.lineTo(cx, cy + r)
    path.lineTo(cx - r, cy)
    path.close()
}

/* ── curvas ────────────────────────────────────────────────────── */

private fun smoothstep(from: Float, to: Float, t: Float): Float {
    val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun easeOutCubic(x: Float): Float = 1f - (1f - x).pow(3)
private fun easeInCubic(x: Float): Float = x * x * x
private fun easeInOutCubic(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
}
