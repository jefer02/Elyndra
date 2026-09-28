package com.elyndra.launcher.ui.masha

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/* ─────────────────────────────────────────────────────────────
   Atmósfera sobre el escenario 3D: motas de datos que suben y se
   disipan, una banda de escaneo que barre la pantalla y columnas
   de datos tenues en los bordes. Es una capa 2D encima de la vista
   de Filament: no toca la GPU del holograma y no recibe toques
   (la cámara sigue orbitando por debajo).

   Como las chispas de la biblioteca, las motas no se simulan: su
   posición sale del reloj, y el reloj solo se lee en la fase de
   dibujo, así que no recompone nada.
   ───────────────────────────────────────────────────────────── */

private const val MOTES = 42

/** Actualizaciones por segundo de la atmósfera (ver el reloj en [HoloAtmosphere]). */
private const val ATMOSPHERE_HZ = 30

private class Mote(val x: Float, val period: Float, val phase: Float, val size: Float, val sway: Float, val twinkle: Float)

private val MOTE_SET = Random(0x4D41).let { r ->
    List(MOTES) {
        Mote(
            x = r.nextFloat(),
            period = 7f + r.nextFloat() * 9f,
            phase = r.nextFloat(),
            size = 0.8f + r.nextFloat() * 1.8f,
            sway = 4f + r.nextFloat() * 10f,
            twinkle = r.nextFloat() * 6.28f,
        )
    }
}

@Composable
fun HoloAtmosphere(presence: MashaPresence, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        val start = withFrameNanos { it }
        // El reloj (y con él el redibujado) avanza a ATMOSPHERE_HZ, no en cada vsync: redibujar
        // esta capa obliga a Android a recomponer toda la interfaz, que compite con el
        // holograma por la GPU (medido: 64 → 119 fps a 120 Hz). Lo que mueve es lento
        // (motas de 7–16 s, barrido cada 9 s): a 30 Hz no se nota.
        var last = 0L
        while (true) withFrameNanos {
            val debugHz = if (com.elyndra.launcher.BuildConfig.DEBUG) MashaDebugPose.atmoHz else 0
            val hz = if (debugHz > 0) debugHz else ATMOSPHERE_HZ
            val off = com.elyndra.launcher.BuildConfig.DEBUG && MashaDebugPose.noAtmo
            // Margen de 2 ms: con vsync a 60/120 Hz cae justo cada 2/4 fotogramas.
            if (!off && it - last >= 1_000_000_000L / hz - 2_000_000L) {
                last = it
                clock.floatValue = (it - start) / 1e9f
            }
        }
    }
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                val t = clock.floatValue
                val mood = presence.visibleMood
                val glow = Color(mood.glow)
                val energy = presence.energy
                drawStreams(t, glow, energy)
                drawMotes(t, glow, energy)
                drawScan(t, glow, energy)
            },
    )
}

private fun DrawScope.drawMotes(t: Float, color: Color, energy: Float) {
    // Con más energía, más motas a la vez (nunca todas: es polvo de datos, no lluvia).
    val visible = (MOTES * (0.45f + 0.55f * energy)).toInt()
    val w = size.width
    val h = size.height
    for (i in 0 until visible) {
        val m = MOTE_SET[i]
        val p = (t / m.period + m.phase) % 1f
        val env = sin(p * PI.toFloat())
        val a = env * (0.55f + 0.45f * sin(t * 2.3f + m.twinkle)) * (0.35f + 0.5f * energy)
        if (a <= 0.02f) continue
        val x = m.x * w + sin(t * 0.6f + m.twinkle) * m.sway * density
        // Suben desde el pie de la pantalla y se disipan antes de llegar arriba.
        val y = h * (1.02f - p * 0.95f)
        val r = m.size * density
        val c = Offset(x, y)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.5f * a), Color.Transparent), c, r * 6f), r * 6f, c)
        drawCircle(Color.White.copy(alpha = 0.85f * a), r * 0.7f, c)
    }
}

private fun DrawScope.drawScan(t: Float, color: Color, energy: Float) {
    // Banda de escaneo que baja cada ~9 s.
    val p = (t / 9f) % 1f
    val y = size.height * p
    val band = 70f * density
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.5f to color.copy(alpha = 0.05f + 0.05f * energy),
            1f to Color.Transparent,
            startY = y - band,
            endY = y + band,
        ),
        topLeft = Offset(0f, y - band),
        size = androidx.compose.ui.geometry.Size(size.width, band * 2),
    )
}

private fun DrawScope.drawStreams(t: Float, color: Color, energy: Float) {
    // Columnas de datos muy tenues pegadas a los bordes.
    val cols = 5
    val step = 9f * density
    for (side in 0..1) {
        for (c in 0 until cols) {
            val x = if (side == 0) (6 + c * 7) * density else size.width - (6 + c * 7) * density
            val speed = 18f + c * 7f
            val offset = (t * speed * density) % step
            var y = -step + offset
            var k = c * 31 + side * 7
            while (y < size.height) {
                k = (k * 1103515245 + 12345) and 0x7fffffff
                if (k % 5 < 2) {
                    val a = (0.05f + 0.07f * energy) * (1f - kotlin.math.abs(y / size.height - 0.5f) * 1.6f).coerceAtLeast(0f)
                    drawRect(color.copy(alpha = a), Offset(x, y), androidx.compose.ui.geometry.Size(2f * density, 4f * density))
                }
                y += step
            }
        }
    }
}

/**
 * Si el 3D no está disponible (modelo que no carga, GPU sin soporte), Masha
 * sigue ahí: su avatar dentro de un núcleo holográfico que late con la voz.
 */
@Composable
fun HoloFallback(presence: MashaPresence, modifier: Modifier = Modifier) {
    val glow = Color(presence.visibleMood.glow)
    val transition = rememberInfiniteTransition(label = "core")
    val spin = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val beat = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "beat")
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(230.dp)
                .graphicsLayer { rotationZ = spin.value }
                .drawBehind {
                    val c = center
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.35f), Color.Transparent), c, size.minDimension / 2), size.minDimension / 2, c)
                    for (i in 0 until 3) {
                        drawArc(glow.copy(alpha = 0.5f), i * 120f, 70f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    }
                },
        )
        Image(
            painterResource(R.drawable.masha),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(120.dp)
                .graphicsLayer {
                    val k = 1f + 0.04f * beat.value + if (presence.speaking) 0.03f else 0f
                    scaleX = k
                    scaleY = k
                }
                .clip(CircleShape)
                .border(2.dp, glow, CircleShape),
        )
    }
}
