package com.elyndra.launcher.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/* ─────────────────────────────────────────────────────────────
   Desintegración: el elemento se deshace en polvo y se va.

   Se usa al quitar algo —un juego de la biblioteca, una carátula
   o un logo—: en vez de desaparecer de golpe, lo que se borra se
   deshace en una nube de partículas que se dispersa y se apaga.
   El borrado de verdad va detrás de la animación, así que lo que
   el usuario ve es exactamente lo que acaba de pasar.

   Cómo funciona, de arriba abajo:

   1. CAPTURA. El contenido se graba en una `GraphicsLayer` (la
      forma que da Compose de rasterizar un composable) y de ahí
      sale un `ImageBitmap`. Solo se graba cuando hace falta: en
      reposo el envoltorio no cuesta nada.
   2. REJILLA. El bitmap se reduce al tamaño de la rejilla de
      partículas, y cada píxel de esa reducción es el color medio
      de su celda. Lo hace el escalador nativo, fuera del hilo
      principal.
   3. FÍSICA. Cada partícula sale con su velocidad, su retraso y
      su encogimiento. La posición en cada fotograma es una
      fórmula cerrada (tiro parabólico), no una integración paso a
      paso: sin estado que actualizar, sin reservar memoria y sin
      acumular error.
   4. DIBUJO. Un solo `drawBehind` recorre las partículas y pinta
      un cuadrado por cada una. El progreso se lee únicamente en
      fase de dibujo, así que la animación no recompone nada.
   ───────────────────────────────────────────────────────────── */

/**
 * Una mota de polvo del elemento que se deshace.
 *
 * Posición y tamaño van en px del propio elemento (0,0 es su esquina
 * superior izquierda) y las velocidades en px/s, así que la física no
 * necesita saber nada de la densidad de la pantalla: se convirtió al
 * generarlas.
 */
@Immutable
data class Particle(
    /** Esquina de la celda que le tocó en la rejilla. */
    val x: Float,
    val y: Float,
    /** Velocidad inicial, px/s. */
    val vx: Float,
    val vy: Float,
    /** Lado del cuadrado que la representa, px. */
    val size: Float,
    /** Color medio de su celda, siempre opaco: la transparencia va en [alpha]. */
    val color: Color,
    /** Opacidad de partida, la del píxel: un borde a medias no sale opaco. */
    val alpha: Float,
    /** Cuándo empieza a moverse, en fracción de la duración total (0…1). */
    val delay: Float,
    /** Cuánto encoge mientras se va (0…1 del lado). */
    val shrink: Float,
)

/**
 * Los números del efecto. Las velocidades van en dp/s y la gravedad en
 * dp/s², para que se vea igual en cualquier pantalla.
 */
@Immutable
data class DisintegrationSpec(
    val durationMs: Int = 620,
    /** Lado mínimo de la celda: por debajo de esto no se distingue el grano. */
    val cell: Dp = 2.dp,
    /**
     * Tope de partículas. Es lo que mantiene el coste plano: una card
     * pequeña y un fondo enorme dibujan lo mismo, con el grano más fino o
     * más grueso según lo que quepa.
     */
    val maxParticles: Int = 3200,
    /** Arrastre lateral, en el sentido del barrido. */
    val drift: Dp = 44.dp,
    /** Subida (negativo = hacia arriba): el polvo se levanta antes de caer. */
    val rise: Dp = (-56).dp,
    /**
     * Cuánto se abre el abanico de velocidades. Es el número que más manda en
     * si aquello parece polvo o parece la misma imagen moviéndose entera:
     * cuanto más ancho, menos se reconoce de dónde salió cada mota.
     */
    val spread: Dp = 72.dp,
    /** Gravedad: curva la estela hacia abajo al final. */
    val gravity: Dp = 150.dp,
    /** Parte de la duración que tarda el barrido en cruzar el elemento. */
    val sweep: Float = 0.38f,
    /** Retraso aleatorio extra: rompe la línea recta del barrido. */
    val jitter: Float = 0.28f,
)

/**
 * Envuelve [content] y lo deshace en partículas cuando [isDisintegrating]
 * pasa a `true`.
 *
 * Al terminar llama a [onAnimationEnd], que es donde el llamante hace el
 * borrado de verdad (quitar el elemento de la lista, borrar la imagen…).
 * Mientras dura el efecto el contenido sigue compuesto y medido —solo deja
 * de pintarse—, así que la caja conserva su tamaño y nada se mueve de sitio
 * alrededor.
 *
 * [onAnimationEnd] se llama también si no hubo nada que capturar (elemento
 * sin medir todavía, contenido transparente): el efecto nunca puede impedir
 * el borrado que lo disparó.
 *
 * Dos límites que conviene tener presentes:
 *
 * - Lo que se captura son los límites de la caja. Si el contenido se sale de
 *   ellos (una escala que desborda, una sombra ancha), ese sobrante no entra
 *   en el polvo.
 * - Las partículas se dispersan fuera de la caja, así que un ancestro que
 *   recorte —una `LazyRow`, por ejemplo— les corta la estela. Por eso el
 *   arrastre de [DisintegrationSpec] es corto: dentro de una lista se ve
 *   entero.
 */
@Composable
fun DisintegratableBox(
    isDisintegrating: Boolean,
    onAnimationEnd: () -> Unit,
    modifier: Modifier = Modifier,
    spec: DisintegrationSpec = DisintegrationSpec(),
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val density = LocalDensity.current.density
    // Así un lambda nuevo en cada recomposición no reinicia el efecto.
    val finished by rememberUpdatedState(onAnimationEnd)

    // Null = en reposo: ni se graba la capa ni se dibuja ninguna partícula.
    var particles by remember { mutableStateOf<List<Particle>?>(null) }
    // Grabar cuesta (rehace el display list del contenido en cada pasada de
    // dibujo), así que solo se graba en el fotograma anterior al chasquido.
    var recording by remember { mutableStateOf(false) }
    // El progreso es `FloatState` y se lee solo dentro de `drawBehind`: cambia
    // 60 veces por segundo sin recomponer nada, únicamente redibujando.
    val progress = remember { mutableFloatStateOf(0f) }
    // Hasta cuándo hay que seguir enseñando el hueco vacío (ver más abajo).
    var holdUntil by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isDisintegrating) {
        if (!isDisintegrating) {
            // Si se acaba de deshacer, el hueco aguanta vacío lo que quede de
            // margen: el borrado de verdad puede tardar un fotograma o dos en
            // llegar al estado y sin esta pausa el contenido reaparecería un
            // instante justo antes de desaparecer.
            val left = holdUntil - SystemClock.uptimeMillis()
            if (left > 0) delay(left)
            particles = null
            recording = false
            progress.floatValue = 0f
            return@LaunchedEffect
        }

        // 1. Grabar el contenido tal y como está ahora mismo en pantalla y
        //    esperar a la pasada de dibujo que rellena la capa.
        recording = true
        var frames = 0
        while (layer.size == IntSize.Zero && frames++ < CAPTURE_FRAMES) withFrameNanos { }
        val shot = runCatching { layer.toImageBitmap() }.getOrNull()

        // 2. Trocearlo en partículas fuera del hilo principal: leer píxeles y
        //    escalar un bitmap es justo lo que no puede ir en el hilo de UI.
        val field = shot?.let { withContext(Dispatchers.Default) { it.toParticles(spec, density) } }
        if (field.isNullOrEmpty()) {
            recording = false
            finished()
            return@LaunchedEffect
        }

        // 3. Moverlo con el reloj de fotogramas: un `withFrameNanos` por
        //    fotograma, sin `Animatable` de por medio, para que el progreso
        //    sea exactamente el tiempo real transcurrido aunque se salte
        //    algún fotograma.
        progress.floatValue = 0f
        particles = field
        val total = spec.durationMs * NANOS_PER_MS
        var start = 0L
        while (true) {
            val t = withFrameNanos { now ->
                if (start == 0L) start = now
                (now - start) / total
            }
            progress.floatValue = t.coerceAtMost(1f)
            if (t >= 1f) break
        }

        // 4. Avisar —aquí es donde el llamante borra de verdad— y soltar las
        //    partículas (unos miles de objetos). El margen de [HOLD_MS] deja
        //    el hueco vacío mientras ese borrado llega al estado: si se
        //    soltase en el mismo fotograma, lo que se acaba de deshacer
        //    reaparecería un momento.
        holdUntil = SystemClock.uptimeMillis() + HOLD_MS
        finished()
        delay(HOLD_MS)
        particles = null
        recording = false
    }

    // Opacidad del original mientras el polvo toma el relevo. La rejilla tiene
    // menos resolución que el contenido, así que al aparecer se notaría el
    // salto a "pixelado"; este fundido corto lo tapa, y para cuando termina
    // las motas ya están en movimiento y nadie mira la rejilla.
    fun handover(): Float =
        if (particles == null) 1f else (1f - progress.floatValue / HANDOVER).coerceIn(0f, 1f)

    Box(modifier) {
        // El polvo va detrás; el original se funde por encima.
        particles?.let { field ->
            val gravity = spec.gravity.value * density
            val seconds = spec.durationMs / MS_PER_SECOND
            Spacer(
                Modifier
                    .matchParentSize()
                    .drawBehind { drawParticles(field, progress.floatValue, gravity, seconds) },
            )
        }

        Box(
            Modifier
                // La capa del fundido solo existe mientras dura el efecto: en
                // reposo este envoltorio no añade ni una capa de más, que es
                // lo que permite ponerlo en cada elemento de una lista.
                .then(if (particles != null) Modifier.graphicsLayer { alpha = handover() } else Modifier)
                .drawWithContent {
                    when {
                        // Ya invisible: ni se graba ni se pinta.
                        handover() <= 0f -> Unit
                        recording -> {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        }
                        else -> drawContent()
                    }
                },
        ) {
            content()
        }
    }
}

/**
 * Pinta la nube en el instante [t] (0…1 de la animación).
 *
 * Las que todavía no les ha llegado el barrido se pintan quietas en su sitio:
 * entre todas rehacen el elemento intacto, y por eso no hace falta seguir
 * dibujando el contenido original debajo. Las que ya se apagaron del todo no
 * se dibujan: según avanza el efecto quedan menos cuadrados por pintar, no
 * más.
 */
private fun DrawScope.drawParticles(
    particles: List<Particle>,
    t: Float,
    gravity: Float,
    durationSeconds: Float,
) {
    // Bucle por índice y con `drawRect`: ni iteradores ni objetos nuevos por
    // fotograma. `Offset` y `Size` son value classes, así que no reservan
    // memoria.
    for (i in particles.indices) {
        val p = particles[i]
        if (t < p.delay) {
            drawRect(p.color, Offset(p.x, p.y), Size(p.size, p.size), alpha = p.alpha)
            continue
        }
        // Vida propia de la partícula: todas acaban a la vez (en t = 1), las
        // que salen tarde simplemente viven menos.
        val life = 1f - p.delay
        val local = if (life <= 0f) 1f else ((t - p.delay) / life).coerceIn(0f, 1f)
        if (local >= 1f) continue

        // Tiro parabólico: posición = origen + v·t + ½·g·t².
        val elapsed = local * life * durationSeconds
        val x = p.x + p.vx * elapsed
        val y = p.y + p.vy * elapsed + 0.5f * gravity * elapsed * elapsed
        // Cuadrática: se mantiene visible al principio y se apaga deprisa al
        // final, que es lo que da la sensación de "se deshace".
        val fade = 1f - local
        val side = p.size * (1f - p.shrink * local)
        drawRect(p.color, Offset(x, y), Size(side, side), alpha = p.alpha * fade * fade)
    }
}

/**
 * Convierte el fotograma capturado en la nube de partículas.
 *
 * La rejilla se elige con dos topes: la celda nunca baja de [DisintegrationSpec.cell]
 * (más fino no se aprecia y solo cuesta) y el total nunca pasa de
 * [DisintegrationSpec.maxParticles] (lo que se puede dibujar de sobra dentro de un
 * fotograma). El color de cada celda sale de reducir el bitmap al tamaño de la
 * rejilla: el escalador nativo ya hace la media de cada celda, que es mucho más
 * rápido que recorrer a mano el millón de píxeles del original.
 *
 * Corre en [Dispatchers.Default]; no toca estado de Compose.
 */
private fun ImageBitmap.toParticles(spec: DisintegrationSpec, density: Float): List<Particle> {
    val grid = toCellGrid(spec.cell, spec.maxParticles, density) ?: return emptyList()

    val drift = spec.drift.value * density
    val rise = spec.rise.value * density
    val spread = spec.spread.value * density
    val random = Random(SEED)

    val particles = ArrayList<Particle>(grid.cols * grid.rows)
    for (row in 0 until grid.rows) {
        for (col in 0 until grid.cols) {
            val argb = grid.pixels[row * grid.cols + col]
            val alpha = (argb ushr 24 and 0xFF) / 255f
            // Lo transparente no emite polvo: las esquinas redondeadas y los
            // huecos de un logo se deshacen con la forma que tienen.
            if (alpha <= ALPHA_FLOOR) continue

            // El barrido cruza el elemento de izquierda a derecha: cada
            // columna arranca un poco más tarde que la anterior. El jitter le
            // quita la rectitud, para que no parezca una persiana bajando.
            val sweepAt = if (grid.cols > 1) col.toFloat() / (grid.cols - 1) else 0f
            val delay = (sweepAt * spec.sweep + random.nextFloat() * spec.jitter)
                .coerceIn(0f, MAX_DELAY)

            particles += Particle(
                x = col * grid.cellW,
                y = row * grid.cellH,
                vx = drift + (random.nextFloat() - 0.5f) * spread,
                vy = rise + (random.nextFloat() - 0.5f) * spread,
                size = grid.side,
                // El alfa se guarda aparte, así que el color va opaco: si no,
                // se aplicaría dos veces y el polvo saldría lavado.
                color = Color(argb or OPAQUE),
                alpha = alpha,
                delay = delay,
                shrink = MIN_SHRINK + random.nextFloat() * SHRINK_RANGE,
            )
        }
    }
    return particles
}

/** Parte del efecto en la que el original se funde y deja paso al polvo. */
private const val HANDOVER = 0.16f

/**
 * Margen en el que el hueco se queda vacío después del efecto, mientras el
 * borrado que lo disparó llega al estado.
 */
private const val HOLD_MS = 140L

/** Fotogramas que se esperan como mucho a que la capa tenga contenido grabado. */
private const val CAPTURE_FRAMES = 4

/** Ninguna partícula arranca más tarde que esto: todas tienen que poder irse. */
private const val MAX_DELAY = 0.9f

private const val MIN_SHRINK = 0.35f
private const val SHRINK_RANGE = 0.45f
private const val NANOS_PER_MS = 1_000_000f
private const val MS_PER_SECOND = 1000f

/** Fija: el grano sale igual cada vez, que es más fácil de mirar y de depurar. */
private const val SEED = 20260920
