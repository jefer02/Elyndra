package com.elyndra.launcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/* ─────────────────────────────────────────────────────────────
   Chispas de neón de la selección.

   Sobre el marco del icono o la carátula seleccionados caen, despacio,
   unas pocas chispas de luz: la mayoría resbalan por los laterales del
   propio marco y alguna cruza por dentro. No es una lluvia: son
   [SPARKS], cada una a su ritmo.

   Se pintan *encima* de la card, con un núcleo blanco incandescente
   y un anillo aclarado: así se leen incluso sobre un marco del mismo
   color que ellas (el marco toma el color de las chispas).

   Pensado para no pesar:
    - Las chispas no se simulan: su posición sale del reloj con una
      fórmula (caída + vaivén), así que no hay estado por chispa ni
      basura para el recolector.
    - El reloj solo corre en la card seleccionada y se lee en la fase
      de dibujo: el carrusel no recompone por él.
    - Con "reducir movimiento" se quedan quietas, encendidas.
   ───────────────────────────────────────────────────────────── */

/** Cuántas chispas caen a la vez sobre la selección. */
private const val SPARKS = 10

/** Una chispa: todo lo que la distingue de las demás, fijo desde el principio. */
private class Spark(
    /** 0 = marco izquierdo, 1 = marco derecho, 2 = por dentro de la card. */
    val lane: Int,
    /** Dentro de la card: columna en fracción del ancho. En el marco no se usa. */
    val x: Float,
    /** Segundos que tarda en caer de arriba abajo. */
    val period: Float,
    /** Desfase inicial (0..1): no salen todas a la vez. */
    val phase: Float,
    /** Amplitud del vaivén lateral, en fracción del grosor del marco. */
    val sway: Float,
    val swayRate: Float,
    /** Radio del núcleo, en dp. */
    val size: Float,
    val twinkle: Float,
)

/**
 * Siempre las mismas chispas (semilla fija): cambiar de selección no
 * reordena el efecto, solo lo traslada.
 */
private val SPARK_SET: List<Spark> = Random(0x6E0A).let { r ->
    List(SPARKS) { i ->
        // Cuatro por cada lateral del marco y dos por dentro.
        val lane = when {
            i < 8 -> i % 2
            else -> 2
        }
        Spark(
            lane = lane,
            x = 0.25f + r.nextFloat() * 0.5f,
            period = 5f + r.nextFloat() * 3.5f,
            phase = (i / 2).toFloat() / 5f + r.nextFloat() * 0.1f,
            sway = 0.35f + r.nextFloat() * 0.4f,
            swayRate = 0.7f + r.nextFloat() * 0.7f,
            size = if (lane == 2) 1.5f + r.nextFloat() * 0.8f else 1.9f + r.nextFloat() * 1.1f,
            twinkle = r.nextFloat() * 2f * PI.toFloat(),
        )
    }
}

/**
 * Chispas de neón de [color] cayendo sobre el marco del elemento mientras
 * [active]. [frame] es el grosor del marco: las chispas de los laterales
 * caen por el centro de esa franja. Se pintan *encima* de lo que venga
 * después en la cadena de modificadores; conviene ponerlo tras la escala de
 * la card (para que la acompañe) y antes de su recorte (para que el halo
 * pueda asomar por fuera).
 */
@Composable
fun Modifier.neonParticles(active: Boolean, color: Color, frame: Dp = 6.dp, sparkScale: Float = 1f): Modifier {
    val reduced = LocalReducedMotion.current
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active, reduced) {
        if (!active || reduced) return@LaunchedEffect
        val start = withFrameNanos { it } - (clock.floatValue * 1_000_000_000f).toLong()
        while (true) {
            withFrameNanos { clock.floatValue = (it - start) / 1_000_000_000f }
        }
    }
    if (!active) return this
    return drawWithContent {
        drawContent()
        drawSparks(clock.floatValue, color, frame.toPx(), sparkScale)
    }
}

private fun DrawScope.drawSparks(seconds: Float, color: Color, frame: Float, scale: Float) {
    val w = size.width
    val h = size.height
    // Anillo aclarado entre el halo y el núcleo: separa la chispa del marco.
    val bright = lerp(color, Color.White, 0.55f)
    // Caen desde justo encima de la card hasta justo debajo.
    val top = -h * 0.05f
    val travel = h * 1.1f
    for (s in SPARK_SET) {
        val p = (seconds / s.period + s.phase) % 1f
        // Aparecen y se apagan con suavidad en los extremos de la caída.
        val envelope = sin(p * PI.toFloat())
        val flicker = 0.8f + 0.2f * sin(seconds * 3.1f + s.twinkle)
        val a = (envelope * flicker).coerceIn(0f, 1f)
        if (a <= 0.01f) continue
        val swing = sin(seconds * s.swayRate + s.twinkle) * s.sway * frame
        val x = when (s.lane) {
            0 -> frame / 2f + swing
            1 -> w - frame / 2f + swing
            else -> s.x * w + swing
        }
        val y = top + p * travel
        val r = s.size * density * scale
        val c = Offset(x, y)

        // Colita de la caída: se desvanece hacia arriba.
        val tail = r * 8f
        drawLine(
            Brush.verticalGradient(listOf(Color.Transparent, bright.copy(alpha = 0.7f * a)), startY = y - tail, endY = y),
            start = Offset(x, y - tail),
            end = c,
            strokeWidth = r * 1.2f,
            cap = StrokeCap.Round,
        )
        // Halo de neón, anillo aclarado y núcleo blanco incandescente.
        val halo = r * 6f
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.75f * a), color.copy(alpha = 0f)), center = c, radius = halo),
            radius = halo,
            center = c,
        )
        drawCircle(bright, radius = r * 1.6f, center = c, alpha = a)
        drawCircle(Color.White, radius = r * 0.85f, center = c, alpha = a)
    }
}
