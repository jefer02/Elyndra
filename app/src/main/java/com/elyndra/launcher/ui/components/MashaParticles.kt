package com.elyndra.launcher.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/* ─────────────────────────────────────────────────────────────
   Partículas fosforescentes de Masha.

   Al arrastrar el botón de Masha va soltando chispas de luz a su
   alrededor: más cuanto más rápido se mueve, y se quedan atrás
   formando una estela que se apaga sola.

   Pensado para no pesar:
    - Un fondo fijo de [MAX] partículas en arrays planos (sin un
      objeto por chispa: no hay basura para el recolector). Si se
      llena, la nueva sustituye a la más vieja.
    - El bucle de fotogramas solo corre mientras quede alguna viva;
      con la estela apagada no hay ni un fotograma de trabajo.
    - Se dibuja en la fase de dibujo leyendo un contador: avanzar
      la simulación no recompone la pantalla.
   ───────────────────────────────────────────────────────────── */

@Stable
class ParticleField {
    private val x = FloatArray(MAX)
    private val y = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val life = FloatArray(MAX)
    private val maxLife = FloatArray(MAX)
    private val radius = FloatArray(MAX)
    private var next = 0
    private var alive = 0

    /** Sube en cada paso de la simulación: es lo que invalida el dibujo. */
    internal var tick by mutableIntStateOf(0)

    /** Hay chispas vivas: el bucle de animación debe correr. */
    internal var running by mutableStateOf(false)

    /**
     * Suelta chispas alrededor de ([cx], [cy]) —el centro del botón, en px—
     * por un movimiento de ([dx], [dy]) px. El número crece con la distancia
     * recorrida, con un tope por evento para que un tirón brusco no sature.
     */
    fun emit(cx: Float, cy: Float, dx: Float, dy: Float, spread: Float, density: Float) {
        val distance = hypot(dx, dy)
        if (distance < 0.5f) return
        val count = (distance / (5f * density)).toInt().coerceIn(1, 7)
        // Las chispas salen hacia atrás respecto al movimiento: eso forma la estela.
        val back = if (distance > 0f) Offset(-dx / distance, -dy / distance) else Offset.Zero
        val speed = min(distance * 9f, 420f * density)
        repeat(count) {
            val i = next
            next = (next + 1) % MAX
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val ring = spread * (0.55f + Random.nextFloat() * 0.5f)
            x[i] = cx + cos(angle) * ring
            y[i] = cy + sin(angle) * ring
            val jitter = 40f * density
            vx[i] = back.x * speed * (0.25f + Random.nextFloat() * 0.35f) + (Random.nextFloat() - 0.5f) * jitter
            vy[i] = back.y * speed * (0.25f + Random.nextFloat() * 0.35f) + (Random.nextFloat() - 0.5f) * jitter
            maxLife[i] = 0.55f + Random.nextFloat() * 0.6f
            life[i] = maxLife[i]
            radius[i] = (1.4f + Random.nextFloat() * 2.2f) * density
        }
        alive = MAX
        running = true
    }

    /** Avanza [dt] segundos. Devuelve false cuando ya no queda ninguna viva. */
    internal fun step(dt: Float): Boolean {
        var any = false
        val drag = 1f - min(dt * 3.2f, 0.9f)
        for (i in 0 until alive) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            if (life[i] <= 0f) continue
            any = true
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            vx[i] *= drag
            vy[i] *= drag
        }
        tick++
        return any
    }

    internal fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, color: Color) {
        for (i in 0 until alive) {
            val l = life[i]
            if (l <= 0f) continue
            val f = l / maxLife[i]
            // Se apaga y se encoge a la vez; el halo, más deprisa que el núcleo.
            val r = radius[i] * (0.4f + 0.6f * f)
            val c = Offset(x[i], y[i])
            scope.drawCircle(color, radius = r * 3.2f, center = c, alpha = 0.16f * f * f)
            scope.drawCircle(color, radius = r * 1.6f, center = c, alpha = 0.35f * f)
            scope.drawCircle(Color.White, radius = r * 0.55f, center = c, alpha = 0.85f * f, blendMode = BlendMode.SrcOver)
        }
    }

    companion object {
        /** Tope de chispas vivas a la vez. */
        const val MAX = 140
    }
}

@Composable
fun rememberParticleField(): ParticleField = remember { ParticleField() }

/**
 * La capa donde se pintan las chispas: del tamaño de su contenedor, sin
 * recibir toques. Va debajo del botón de Masha, para que el botón tape el
 * nacimiento de cada chispa y la estela asome por detrás.
 */
@Composable
fun ParticleLayer(field: ParticleField, color: Color, modifier: Modifier = Modifier) {
    val running = field.running
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // Tope al paso: al volver de segundo plano no hay un salto enorme.
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 1f / 30f)
            last = now
            if (!field.step(dt)) break
        }
        field.running = false
        field.tick++
    }
    Spacer(
        modifier.drawBehind {
            // Leer `tick` aquí es lo que repinta la capa en cada paso.
            if (field.tick >= 0) field.draw(this, color)
        },
    )
}
