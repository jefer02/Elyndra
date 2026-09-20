package com.elyndra.launcher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import kotlin.random.Random

/* ─────────────────────────────────────────────────────────────
   Materialización: el elemento se monta desde el polvo.

   Es la [DisintegratableBox] al revés, y se usa cuando algo
   entra: un juego recién añadido a la biblioteca, una carátula
   o un logo que se acaban de poner. En vez de aparecer de
   golpe, las motas llegan de fuera, se juntan en su sitio y
   cuajan en la imagen.

   Lo que cambia respecto a deshacerlo:

   1. CUÁNDO CAPTURAR. Al deshacer, lo que se captura ya está en
      pantalla. Al montar, lo que se captura es lo que *va* a
      estar: puede ser una imagen que todavía se está
      descodificando. Por eso se captura varias veces hasta que
      dos seguidas salen iguales ([CellGrid.signature]) — ahí ya
      no se mueve nada— y mientras tanto el contenido no se pinta.
   2. LA TRAYECTORIA. Cada mota tiene origen y destino, y el
      destino es su celda. Se interpola entre los dos con una
      curva acelerada: sale despacio de lejos y entra deprisa,
      que es lo que da la sensación de imán.
   3. EL RELEVO. Al final las motas están exactamente en su
      celda, así que el contenido de verdad entra con un fundido
      corto por encima y la rejilla desaparece debajo.
   ───────────────────────────────────────────────────────────── */

/**
 * Una mota que viaja hasta su sitio.
 *
 * Todo va en px del propio elemento: (0,0) es su esquina superior izquierda,
 * y el destino [targetX]/[targetY] es la celda que le toca en la rejilla.
 */
@Immutable
data class AssemblyParticle(
    /** De dónde sale: fuera del elemento, dispersa. */
    val startX: Float,
    val startY: Float,
    /** Dónde acaba: su celda. */
    val targetX: Float,
    val targetY: Float,
    /** Lado del cuadrado ya montado, px. */
    val size: Float,
    /** Cuánto más grande sale (1 = igual que al llegar). */
    val startScale: Float,
    /** Color medio de su celda, siempre opaco: la transparencia va en [alpha]. */
    val color: Color,
    /** Opacidad final, la del píxel al que pertenece. */
    val alpha: Float,
    /** Cuándo empieza a viajar, en fracción de la duración total (0…1). */
    val delay: Float,
    /**
     * Exponente de la curva de acercamiento (>1 = acelera al llegar).
     *
     * Es lo que hace que no lleguen todas a la vez aunque salgan juntas:
     * con 1.4 la mota entra casi recta, con 2.6 se queda atrás y pega el
     * acelerón al final.
     */
    val accel: Float,
)

/** Los números del efecto; las distancias en dp para que no dependan de la pantalla. */
@Immutable
data class MaterializationSpec(
    val durationMs: Int = 700,
    /** Lado mínimo de la celda: por debajo de esto no se distingue el grano. */
    val cell: Dp = 2.dp,
    /** Tope de partículas: mantiene plano el coste de dibujo. */
    val maxParticles: Int = 3200,
    /**
     * Cuánto se alejan las motas de su celda al salir. Se mide desde el
     * centro del elemento hacia fuera, así que el polvo rodea a lo que va a
     * formarse en vez de salir todo del mismo lado.
     */
    val scatter: Dp = 54.dp,
    /** Parte aleatoria de esa distancia (0 = todas igual de lejos). */
    val scatterJitter: Float = 0.75f,
    /** Componente de giro: las motas entran en espiral, no en línea recta. */
    val swirl: Float = 0.45f,
    /** Cuánto más grandes salen las motas antes de cuajar. */
    val startScale: Float = 2.1f,
    /** Parte de la duración que tarda el barrido en cruzar el elemento. */
    val sweep: Float = 0.3f,
    /** Retraso aleatorio extra: rompe la línea del barrido. */
    val jitter: Float = 0.26f,
)

/**
 * Envuelve [content] y lo monta desde el polvo cuando [isMaterializing] pasa
 * a `true`. Al terminar llama a [onAnimationEnd], donde el llamante suele
 * limpiar la marca que disparó el efecto.
 *
 * Mientras dura, el contenido está compuesto y medido pero no se pinta: la
 * caja conserva su tamaño —nada se mueve de sitio alrededor— y lo que se ve
 * es la rejilla de motas llegando. Si no hay nada que capturar (contenido
 * transparente, elemento aún sin medir) se enseña el contenido tal cual y se
 * avisa: el efecto nunca puede dejar algo invisible.
 */
@Composable
fun MaterializableBox(
    isMaterializing: Boolean,
    onAnimationEnd: () -> Unit,
    modifier: Modifier = Modifier,
    spec: MaterializationSpec = MaterializationSpec(),
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val density = LocalDensity.current.density
    val finished by rememberUpdatedState(onAnimationEnd)

    // Null = en reposo: ni se graba la capa ni se dibuja ninguna partícula.
    var particles by remember { mutableStateOf<List<AssemblyParticle>?>(null) }
    // Se graba mientras dure la espera a que el contenido esté quieto.
    var recording by remember { mutableStateOf(false) }
    // El contenido se esconde desde el primer fotograma: si no, se le vería
    // aparecer entero un instante antes de empezar a montarse.
    var hidden by remember { mutableStateOf(isMaterializing) }
    // Progreso 0→1, leído solo en fase de dibujo: la animación no recompone.
    val progress = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isMaterializing) {
        if (!isMaterializing) {
            particles = null
            recording = false
            hidden = false
            progress.floatValue = 0f
            return@LaunchedEffect
        }

        // 1. Esperar a que lo que se va a montar esté completo y quieto.
        hidden = true
        recording = true
        progress.floatValue = 0f
        val grid = awaitStableGrid(layer, spec, density)
        if (grid == null) {
            // Nada que montar: se enseña el contenido y se sigue.
            recording = false
            hidden = false
            finished()
            return@LaunchedEffect
        }

        // 2. Repartir las motas alrededor y darle a cada una su trayectoria.
        val field = withContext(Dispatchers.Default) { grid.toAssembly(spec, density) }
        if (field.isEmpty()) {
            recording = false
            hidden = false
            finished()
            return@LaunchedEffect
        }
        recording = false
        particles = field

        // 3. Moverlas con el reloj de fotogramas.
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

        // 4. El contenido de verdad ya está por encima (entró con el fundido
        //    de [HANDOVER]), así que soltar las motas no se nota.
        particles = null
        hidden = false
        finished()
    }

    // Opacidad del contenido real. Entra en el último tramo, cuando las motas
    // ya están prácticamente en su celda: así la rejilla cuaja en la imagen
    // nítida en vez de cambiarse por ella de golpe.
    fun handover(): Float = when {
        particles != null -> ((progress.floatValue - (1f - HANDOVER)) / HANDOVER).coerceIn(0f, 1f)
        hidden -> 0f
        else -> 1f
    }

    Box(modifier) {
        // Las motas van detrás; el contenido se funde por encima al final.
        particles?.let { field ->
            Spacer(
                Modifier
                    .matchParentSize()
                    .drawBehind { drawAssembly(field, progress.floatValue) },
            )
        }

        Box(
            Modifier
                // La capa del fundido solo existe mientras dura el efecto: en
                // reposo este envoltorio no añade ni una capa de más.
                .then(
                    if (hidden || particles != null) {
                        Modifier.graphicsLayer { alpha = handover() }
                    } else {
                        Modifier
                    },
                )
                .drawWithContent {
                    when {
                        // Grabando: hay que pintar para poder capturar, pero
                        // la capa de arriba lo mantiene invisible (alpha 0).
                        recording -> {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        }
                        // Escondido y sin grabar: ni se dibuja.
                        handover() <= 0f -> Unit
                        else -> drawContent()
                    }
                },
        ) {
            content()
        }
    }
}

/**
 * Captura el contenido hasta que dos capturas seguidas salen iguales.
 *
 * Lo que se monta puede no estar listo en el primer fotograma —una carátula
 * recién asignada sigue descodificándose, una card recién añadida todavía
 * está midiéndose—, y trocear eso daría polvo de una imagen a medias. Se
 * capta cada pocos fotogramas hasta que la firma se repite; si en
 * [STABLE_TIMEOUT_FRAMES] no se estabiliza, se usa lo último que haya, que
 * siempre es mejor que no animar nada.
 */
private suspend fun awaitStableGrid(
    layer: GraphicsLayer,
    spec: MaterializationSpec,
    density: Float,
): CellGrid? {
    var previous: Int? = null
    var last: CellGrid? = null
    var frames = 0
    while (frames < STABLE_TIMEOUT_FRAMES) {
        withFrameNanos { }
        frames++
        if (layer.size == IntSize.Zero) continue
        val shot: ImageBitmap = runCatching { layer.toImageBitmap() }.getOrNull() ?: continue
        val grid = withContext(Dispatchers.Default) {
            shot.toCellGrid(spec.cell, spec.maxParticles, density)
        } ?: continue
        // Sin nada pintado todavía no hay con qué comparar: se sigue esperando.
        if (grid.coverage() <= MIN_COVERAGE) {
            previous = null
            continue
        }
        val signature = grid.signature()
        if (previous == signature) return grid
        previous = signature
        last = grid
    }
    return last
}

/**
 * Reparte las motas alrededor del elemento y les da su trayectoria.
 *
 * Cada celda sale despedida hacia fuera desde el centro —así el polvo rodea
 * lo que va a formarse en vez de venir todo del mismo lado— con dos
 * añadidos: un giro perpendicular ([MaterializationSpec.swirl]), para que
 * entren en espiral y no en línea recta, y una distancia aleatoria, para que
 * la nube no sea un anillo perfecto.
 */
private fun CellGrid.toAssembly(spec: MaterializationSpec, density: Float): List<AssemblyParticle> {
    val random = Random(SEED)
    val scatter = spec.scatter.value * density
    val centerX = cols * cellW / 2f
    val centerY = rows * cellH / 2f

    val particles = ArrayList<AssemblyParticle>(cols * rows)
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val argb = pixels[row * cols + col]
            val alpha = (argb ushr 24 and 0xFF) / 255f
            // Lo transparente no se monta: el hueco de un logo sigue siendo hueco.
            if (alpha <= ALPHA_FLOOR) continue

            val targetX = col * cellW
            val targetY = row * cellH
            // Dirección desde el centro hasta la celda, normalizada. En el
            // centro exacto no hay dirección: se le da una cualquiera.
            var dx = targetX - centerX
            var dy = targetY - centerY
            val length = sqrt(dx * dx + dy * dy)
            if (length < 1f) {
                dx = random.nextFloat() - 0.5f
                dy = random.nextFloat() - 0.5f
            } else {
                dx /= length
                dy /= length
            }
            // Giro: la perpendicular (-dy, dx) desplaza el origen de lado, y
            // al interpolar en línea recta hacia el destino la entrada se lee
            // como una curva.
            val swirl = spec.swirl * (random.nextFloat() - 0.5f) * 2f
            val distance = scatter * (1f + (random.nextFloat() - 0.5f) * spec.scatterJitter)

            // El barrido reparte las llegadas de izquierda a derecha; el
            // jitter rompe la línea para que no parezca una persiana.
            val sweepAt = if (cols > 1) col.toFloat() / (cols - 1) else 0f
            val delay = (sweepAt * spec.sweep + random.nextFloat() * spec.jitter)
                .coerceIn(0f, MAX_DELAY)

            particles += AssemblyParticle(
                startX = targetX + (dx - dy * swirl) * distance,
                startY = targetY + (dy + dx * swirl) * distance,
                targetX = targetX,
                targetY = targetY,
                size = side,
                startScale = 1f + (spec.startScale - 1f) * (0.5f + random.nextFloat() * 0.5f),
                // El alfa se guarda aparte, así que el color va opaco: si no,
                // se aplicaría dos veces y el polvo saldría lavado.
                color = Color(argb or OPAQUE),
                alpha = alpha,
                delay = delay,
                accel = MIN_ACCEL + random.nextFloat() * ACCEL_RANGE,
            )
        }
    }
    return particles
}

/**
 * Pinta la nube en el instante [t] (0…1 de la animación).
 *
 * Para cada mota se calcula su tiempo propio —descontando su retraso— y de
 * ahí sale todo: posición interpolada entre origen y destino con la curva
 * acelerada `u^accel`, tamaño que encoge hasta su celda y opacidad que sube.
 * Es aritmética pura sobre la lista ya creada: ni se reserva memoria ni se
 * guarda estado entre fotogramas.
 */
private fun DrawScope.drawAssembly(particles: List<AssemblyParticle>, t: Float) {
    for (i in particles.indices) {
        val p = particles[i]
        // Todavía no ha salido: no se pinta. Al principio del efecto el
        // elemento está vacío y se va llenando, que es justo lo que se busca.
        if (t < p.delay) continue

        val life = 1f - p.delay
        val local = if (life <= 0f) 1f else ((t - p.delay) / life).coerceIn(0f, 1f)
        // `u^accel` con accel > 1: arranca despacio y entra deprisa.
        val eased = fastPow(local, p.accel)

        val x = p.startX + (p.targetX - p.startX) * eased
        val y = p.startY + (p.targetY - p.startY) * eased
        // Encoge desde su tamaño de salida hasta el de la celda…
        val scale = p.startScale + (1f - p.startScale) * eased
        val side = p.size * scale
        // …y se enciende antes de llegar, para que la nube no aparezca de golpe.
        val fade = (local * FADE_IN_SPEED).coerceAtMost(1f)
        // El cuadrado crece desde su centro: sin esto, las motas grandes
        // quedarían descolgadas hacia abajo y a la derecha de su celda.
        val offset = (side - p.size) / 2f
        drawRect(
            p.color,
            Offset(x - offset, y - offset),
            Size(side, side),
            alpha = p.alpha * fade,
        )
    }
}

/**
 * `x^exponent` para exponentes pequeños y positivos.
 *
 * Se llama una vez por partícula y fotograma —varios miles de veces por
 * segundo—, así que se evita `Math.pow`: con dos multiplicaciones y una raíz
 * se cubre el rango de [AssemblyParticle.accel] con de sobra precisión para
 * mover un cuadrado de cuatro píxeles.
 */
private fun fastPow(x: Float, exponent: Float): Float {
    val squared = x * x
    return when {
        exponent <= 1.5f -> x * sqrt(x)
        exponent <= 2f -> squared
        exponent <= 2.5f -> squared * sqrt(x)
        else -> squared * x
    }
}

/** Parte final del efecto en la que el contenido real se funde por encima. */
private const val HANDOVER = 0.18f

/** Lo que tarda una mota en encenderse, en múltiplos de su vida. */
private const val FADE_IN_SPEED = 3.5f

/** Ninguna mota sale más tarde que esto: todas tienen que llegar. */
private const val MAX_DELAY = 0.72f

private const val MIN_ACCEL = 1.4f
private const val ACCEL_RANGE = 1.2f

/** Cobertura mínima para dar por bueno un fotograma capturado. */
private const val MIN_COVERAGE = 0.02f

/** Tope de fotogramas esperando a que el contenido deje de cambiar (~0,5 s). */
private const val STABLE_TIMEOUT_FRAMES = 30

private const val NANOS_PER_MS = 1_000_000f

/** Fija: el grano sale igual cada vez, que es más fácil de mirar y de depurar. */
private const val SEED = 20260921
