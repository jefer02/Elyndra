package com.elyndra.launcher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Swift
import kotlin.math.max
import kotlin.random.Random

/* ─────────────────────────────────────────────────────────────
   Portal: la card se abre y se come la pantalla.

   Es la transición de abrir un juego. Tiene tres tiempos, y cada
   uno responde a algo distinto:

   1. APRETÓN (~110 ms). Al tocar, la card se hunde un poco y
      sale un destello en el punto exacto del dedo. Es el acuse
      de recibo: pasa antes de que nada más se mueva, que es lo
      que hace que la pulsación se sienta atendida al instante.
   2. EXPANSIÓN (~520 ms). La card crece desde donde estaba
      hasta ocupar la pantalla entera, con el fondo oscureciéndose
      detrás. Lo que se ve creciendo es un fotograma de la propia
      card (capturado al empezar), así que la imagen es la misma
      que había: no hay salto.
   3. CHISPAS. Mientras crece, los bordes sueltan un puñado de
      motas de luz que salen hacia fuera y se apagan. Van montadas
      en el borde que se mueve, así que la estela sale sola.

   Todo se pinta en un `Popup` —una ventana propia por encima de
   la app— porque la card vive dentro de una lista que recorta: sin
   salir de ahí, la expansión se cortaría en el borde del carrusel.
   ───────────────────────────────────────────────────────────── */

/** Los números del portal. Las distancias en dp: no dependen de la pantalla. */
@Immutable
data class PortalSpec(
    /** Lo que tarda el apretón antes de empezar a crecer. */
    val squeezeMs: Int = 110,
    /** Lo que tarda la card en llenar la pantalla. */
    val expandMs: Int = 520,
    /** Hasta dónde se hunde la card al tocarla. */
    val squeezeScale: Float = 0.94f,
    /** Radio al que llega el destello del dedo. */
    val pulseRadius: Dp = 130.dp,
    /** Parte de la expansión en la que el destello ya se ha apagado. */
    val pulseFade: Float = 0.45f,
    /** Cuántas chispas sueltan los bordes. */
    val sparks: Int = 150,
    /** Cuánto se alejan del borde antes de apagarse. */
    val sparkDrift: Dp = 52.dp,
    val sparkSize: Dp = 2.5.dp,
    /** Negro del fondo cuando la card ya llena la pantalla. */
    val scrim: Float = 0.78f,
    /** Radio de la card al empezar; se va a 0 según llena la pantalla. */
    val corner: Dp = 16.dp,
    /**
     * Tramo final en el que el portal se funde con lo que haya debajo (el
     * velo de carga, la pantalla nueva) en vez de desaparecer de golpe.
     *
     * A 0 se queda opaco hasta el final: es lo que hace falta cuando debajo
     * todavía no está lo que va a verse —una pantalla a la que no se ha
     * navegado aún—, porque si no se asomaría la de antes.
     */
    val tailFade: Float = 0.14f,
)

/**
 * Envuelve una card y la abre como un portal cuando [isOpening] pasa a `true`.
 *
 * [tapOffset] es el punto donde se tocó, en coordenadas del propio contenido;
 * de ahí sale el destello. Si no se pasa, el destello sale del centro.
 *
 * [onExpandStart] se llama en cuanto termina el apretón y empieza la
 * expansión: es el momento de arrancar lo que haya que arrancar (el juego, la
 * navegación) para que la carga ocurra *debajo* de la animación en vez de
 * después. [onLaunchComplete] llega al final, ya con la pantalla llena.
 */
@Composable
fun PortalExpandContainer(
    isOpening: Boolean,
    onLaunchComplete: () -> Unit,
    modifier: Modifier = Modifier,
    tapOffset: Offset? = null,
    spec: PortalSpec = PortalSpec(),
    onExpandStart: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val density = LocalDensity.current.density
    val skin = LocalSkin.current
    val started by rememberUpdatedState(onExpandStart)
    val finished by rememberUpdatedState(onLaunchComplete)

    // Dónde está la card en la ventana. Se mide siempre: cuando haga falta no
    // hay tiempo de preguntarlo, y `onGloballyPositioned` solo cuesta cuando
    // el elemento se mueve de verdad.
    var bounds by remember { mutableStateOf(Rect.Zero) }
    // Null = en reposo; el portal no existe y esto no pinta nada.
    var portal by remember { mutableStateOf<PortalShot?>(null) }
    var recording by remember { mutableStateOf(false) }
    // La card no se esconde hasta que el overlay ha pintado su primer
    // fotograma: el `Popup` es otra ventana y tarda una pasada en aparecer,
    // así que esconderla antes dejaba el hueco vacío durante un fotograma.
    var lifted by remember { mutableStateOf(false) }
    // Los dos progresos se leen solo en fase de dibujo: la animación redibuja
    // el overlay, pero no recompone ni la card ni la pantalla de debajo.
    val squeeze = remember { mutableFloatStateOf(0f) }
    val expand = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isOpening) {
        if (!isOpening) {
            portal = null
            recording = false
            lifted = false
            squeeze.floatValue = 0f
            expand.floatValue = 0f
            return@LaunchedEffect
        }
        if (bounds.width <= 0f || bounds.height <= 0f) {
            // Sin sitio medido no hay de dónde crecer: se sigue sin animación.
            started()
            finished()
            return@LaunchedEffect
        }

        // 1. Un fotograma de la card, que es lo que se ve crecer.
        recording = true
        var frames = 0
        while (layer.size == IntSize.Zero && frames++ < CAPTURE_FRAMES) withFrameNanos { }
        val shot = runCatching { layer.toImageBitmap() }.getOrNull()
        recording = false

        val origin = tapOffset ?: Offset(bounds.width / 2f, bounds.height / 2f)
        portal = PortalShot(
            image = shot,
            bounds = bounds,
            tap = bounds.topLeft + origin,
            sparks = buildSparks(spec, density),
        )

        // El overlay ya está montado con la card en su sitio exacto: a partir
        // del siguiente fotograma el hueco puede quedarse vacío sin que se note.
        withFrameNanos { }
        lifted = true

        // 2. Apretón y expansión, con un solo reloj: el tiempo real manda, así
        //    que saltarse un fotograma no descuadra las fases.
        val squeezeNanos = spec.squeezeMs * NANOS_PER_MS
        val expandNanos = spec.expandMs * NANOS_PER_MS
        var start = 0L
        var launched = false
        while (true) {
            val elapsed = withFrameNanos { now ->
                if (start == 0L) start = now
                (now - start).toFloat()
            }
            squeeze.floatValue = (elapsed / squeezeNanos).coerceIn(0f, 1f)
            val p = ((elapsed - squeezeNanos) / expandNanos).coerceIn(0f, 1f)
            expand.floatValue = p
            // En cuanto empieza a crecer se arranca lo de debajo: la carga y
            // la animación corren a la vez, no una detrás de otra.
            if (!launched && p > 0f) {
                launched = true
                started()
            }
            if (p >= 1f) break
        }

        // 3. La pantalla ya está llena. Quien llame decide qué hay debajo; el
        //    portal se queda puesto hasta que `isOpening` vuelva a false, para
        //    que no se vea un parpadeo entre medias.
        finished()
    }

    Box(
        modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
    ) {
        Box(
            Modifier.drawWithContent {
                when {
                    // Con el portal en marcha la card ya vive en el overlay:
                    // aquí solo queda su hueco.
                    lifted -> Unit
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

    portal?.let { shot ->
        val accent = skin.a1
        // Ventana propia, pegada a la esquina de la de la app: así las
        // coordenadas de dentro son las mismas que midió `boundsInWindow`, y
        // nada de lo que recorte la lista afecta al overlay.
        Popup(
            popupPositionProvider = FullWindowPosition,
            properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawPortal(shot, squeeze.floatValue, expand.floatValue, spec, accent, density)
                    },
            )
        }
    }
}

/** El fotograma de la card y de dónde sale, congelados al empezar. */
private class PortalShot(
    val image: ImageBitmap?,
    /** Sitio de la card en la ventana, en px. */
    val bounds: Rect,
    /** Punto tocado, en coordenadas de la ventana. */
    val tap: Offset,
    val sparks: List<Spark>,
) {
    /** Se reaprovecha en cada fotograma (`rewind`) en vez de crear uno nuevo. */
    val clip = Path()
}

/**
 * Una chispa del borde.
 *
 * [edge] es su sitio en el perímetro (0…1 dando la vuelta al rectángulo), así
 * que se recalcula sobre el borde de *cada* fotograma: la chispa viaja
 * montada en el borde que crece y la estela sale sola, sin guardar posiciones.
 */
private class Spark(
    val edge: Float,
    /** Cuánto se separa del borde antes de apagarse, px. */
    val drift: Float,
    val size: Float,
    /** Cuándo sale, en fracción de la expansión. */
    val delay: Float,
    /** Las más blancas hacen de núcleo; las de acento, de halo. */
    val hot: Boolean,
)

private fun buildSparks(spec: PortalSpec, density: Float): List<Spark> {
    val random = Random(SEED)
    val drift = spec.sparkDrift.value * density
    val size = spec.sparkSize.value * density
    return List(spec.sparks) {
        Spark(
            edge = random.nextFloat(),
            drift = drift * (0.45f + random.nextFloat()),
            size = size * (0.6f + random.nextFloat()),
            // Casi todas salen pronto: es un chispazo al abrirse, no una fuente.
            delay = random.nextFloat() * 0.45f,
            hot = random.nextFloat() < 0.35f,
        )
    }
}

/**
 * Pinta el portal entero en el instante dado.
 *
 * El rectángulo de cada fotograma es la interpolación entre el sitio de la
 * card y la pantalla completa, con la curva [Swift] del resto de la interfaz.
 * De ese rectángulo cuelga todo lo demás: la imagen va dentro, las chispas en
 * su borde y el radio de las esquinas se va a cero según lo llena.
 */
private fun DrawScope.drawPortal(
    shot: PortalShot,
    squeeze: Float,
    expand: Float,
    spec: PortalSpec,
    accent: Color,
    density: Float,
) {
    val eased = Swift.transform(expand)
    val screen = Rect(0f, 0f, size.width, size.height)

    // Cola: en el último tramo todo el portal se funde sobre lo de debajo.
    val tail = if (spec.tailFade <= 0f) {
        1f
    } else {
        1f - ((eased - (1f - spec.tailFade)) / spec.tailFade).coerceIn(0f, 1f)
    }

    // Fondo: se apaga deprisa al principio, que es cuando aún se ve.
    val scrim = spec.scrim * (eased * 2f).coerceAtMost(1f) * tail
    if (scrim > 0f) drawRect(Color.Black, alpha = scrim)

    // Destello del dedo: crece y se apaga dentro del primer tramo.
    val pulse = pulseProgress(squeeze, expand, spec)
    if (pulse > 0f) {
        val radius = spec.pulseRadius.value * density * (0.35f + 0.65f * (1f - pulse))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.55f * pulse),
                    accent.copy(alpha = 0.35f * pulse),
                    Color.Transparent,
                ),
                center = shot.tap,
                radius = radius.coerceAtLeast(1f),
            ),
            radius = radius.coerceAtLeast(1f),
            center = shot.tap,
        )
    }

    // El rectángulo que crece. El apretón encoge la card antes de soltarla,
    // así que en el primer tramo el rectángulo es *menor* que el de partida.
    val shrink = 1f - (1f - spec.squeezeScale) * squeeze * (1f - eased)
    val from = shot.bounds.scaledAround(shrink)
    val rect = Rect(
        left = from.left + (screen.left - from.left) * eased,
        top = from.top + (screen.top - from.top) * eased,
        right = from.right + (screen.right - from.right) * eased,
        bottom = from.bottom + (screen.bottom - from.bottom) * eased,
    )
    val corner = spec.corner.value * density * (1f - eased)

    // La card, recortada en redondo y sin deformarse (ver [centerCrop]).
    val path = shot.clip
    path.rewind()
    path.addRoundRect(RoundRect(rect, CornerRadius(corner, corner)))
    clipPath(path) {
        val image = shot.image
        if (image == null) {
            drawRect(accent, topLeft = rect.topLeft, size = rect.size, alpha = tail)
        } else {
            val src = centerCrop(image.width, image.height, rect.width / rect.height)
            drawImage(
                image = image,
                srcOffset = src.topLeft,
                srcSize = src.size,
                dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                dstSize = IntSize(rect.width.toInt().coerceAtLeast(1), rect.height.toInt().coerceAtLeast(1)),
                alpha = tail,
            )
        }
    }

    // Filo de luz: marca el borde mientras crece y se apaga al llenar.
    val edgeGlow = (1f - eased) * 0.9f * tail
    if (edgeGlow > 0.01f) {
        drawRoundRectOutline(rect, corner, accent.copy(alpha = edgeGlow), 1.5f * density)
    }

    drawSparks(shot.sparks, rect, eased, accent)
}

/** Las chispas del borde, montadas en el rectángulo de este fotograma. */
private fun DrawScope.drawSparks(sparks: List<Spark>, rect: Rect, eased: Float, accent: Color) {
    // Al final ya no queda borde que mirar: se dejan de pintar.
    if (eased >= 0.98f) return
    for (i in sparks.indices) {
        val spark = sparks[i]
        if (eased < spark.delay) continue
        val life = 1f - spark.delay
        val local = if (life <= 0f) 1f else ((eased - spark.delay) / life).coerceIn(0f, 1f)
        if (local >= 1f) continue

        val (point, normal) = rect.perimeterPoint(spark.edge)
        // Sale hacia fuera perpendicular al borde, frenando (raíz cuadrada).
        val out = spark.drift * kotlin.math.sqrt(local)
        val alpha = (1f - local) * (1f - local)
        drawCircle(
            color = if (spark.hot) Color.White else accent,
            radius = spark.size * (1f - 0.4f * local),
            center = Offset(point.x + normal.x * out, point.y + normal.y * out),
            alpha = alpha,
        )
    }
}

/** Opacidad del destello del dedo: sube con el apretón y se apaga al crecer. */
private fun pulseProgress(squeeze: Float, expand: Float, spec: PortalSpec): Float {
    if (expand >= spec.pulseFade) return 0f
    val fade = 1f - (expand / spec.pulseFade)
    return squeeze * fade
}

/** El mismo rectángulo, encogido hacia su centro. */
private fun Rect.scaledAround(scale: Float): Rect {
    val dx = width * (1f - scale) / 2f
    val dy = height * (1f - scale) / 2f
    return Rect(left + dx, top + dy, right - dx, bottom - dy)
}

/**
 * Punto del perímetro en [t] (0…1) y su normal hacia fuera.
 *
 * Se recorre el rectángulo por lados: arriba, derecha, abajo e izquierda, en
 * proporción a lo que mide cada uno, para que las chispas se repartan por
 * igual por todo el borde y no se amontonen en los lados cortos.
 */
private fun Rect.perimeterPoint(t: Float): Pair<Offset, Offset> {
    val w = width
    val h = height
    val perimeter = 2f * (w + h)
    if (perimeter <= 0f) return center to Offset(0f, -1f)
    var walk = (t % 1f) * perimeter
    if (walk < w) return Offset(left + walk, top) to Offset(0f, -1f)
    walk -= w
    if (walk < h) return Offset(right, top + walk) to Offset(1f, 0f)
    walk -= h
    if (walk < w) return Offset(right - walk, bottom) to Offset(0f, 1f)
    walk -= w
    return Offset(left, bottom - walk) to Offset(-1f, 0f)
}

/**
 * Recorte centrado del original para llenar un destino de otra proporción.
 *
 * La card es casi cuadrada y la pantalla no, así que estirar la imagen para
 * que quepa la deformaría justo cuando más se ve. En vez de eso se recorta:
 * al principio cabe entera (misma proporción que la card) y según el destino
 * se ensancha, se van quedando fuera los bordes de arriba y abajo.
 */
private fun centerCrop(srcWidth: Int, srcHeight: Int, dstAspect: Float): IntRect {
    if (srcWidth <= 0 || srcHeight <= 0 || dstAspect <= 0f) {
        return IntRect(0, 0, max(srcWidth, 1), max(srcHeight, 1))
    }
    val srcAspect = srcWidth.toFloat() / srcHeight
    return if (dstAspect > srcAspect) {
        // El destino es más ancho: sobra alto.
        val h = (srcWidth / dstAspect).toInt().coerceIn(1, srcHeight)
        val y = (srcHeight - h) / 2
        IntRect(0, y, srcWidth, y + h)
    } else {
        val w = (srcHeight * dstAspect).toInt().coerceIn(1, srcWidth)
        val x = (srcWidth - w) / 2
        IntRect(x, 0, x + w, srcHeight)
    }
}

/** Contorno redondeado de un grosor dado, sin crear un Path por fotograma. */
private fun DrawScope.drawRoundRectOutline(rect: Rect, corner: Float, color: Color, width: Float) {
    drawRoundRect(
        color = color,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(width = width),
    )
}

/** Pega el popup a la esquina de la ventana: dentro, las coordenadas son las de la ventana. */
private object FullWindowPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

private const val CAPTURE_FRAMES = 4
private const val NANOS_PER_MS = 1_000_000f
private const val SEED = 20260922
