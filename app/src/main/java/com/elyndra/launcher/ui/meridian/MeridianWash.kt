package com.elyndra.launcher.ui.meridian

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.ui.components.appIconPixels
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.Swift
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Lo que se lee de un arte: sus colores ([ArtWash]) y una copia diminuta y desenfocada para el fondo ambiental. */
internal class ArtSample(val colors: ArtColors?, val ambient: ImageBitmap?)

/**
 * Las muestras del arte, siempre fuera del hilo principal: Coil decodifica
 * una copia de software diminuta (nunca se leen píxeles de un bitmap de
 * hardware) y aquí se reduce, se analiza y se desenfoca una vez. Se guardan
 * en memoria por ruta (las rutas llevan marca de tiempo: un arte nuevo es
 * otra clave).
 */
internal object ArtSampler {

    private val samples = LruCache<String, ArtSample>(48)

    /** Luminancia del canto de cada tarjeta (NaN = sin canto que leer). */
    private val edges = LruCache<String, Double>(320)

    private val NONE = ArtSample(null, null)

    /** Lado de la copia para leer el canto de una tarjeta (px). */
    private const val EDGE_PX = 16

    fun cached(path: String): ArtSample? = samples.get(path)

    suspend fun load(context: Context, path: String): ArtSample {
        samples.get(path)?.let { return it }
        val bitmap = decode(context, path, ArtWash.AMBIENT) ?: return NONE.also { samples.put(path, it) }
        val sample = withContext(Dispatchers.Default) { analyse(bitmap) }
        samples.put(path, sample)
        return sample
    }

    fun cachedEdge(key: String): Double? = edges.get(key)

    /** Luminancia del canto de una tarjeta: su imagen ([path]) o el icono de la app ([pkg]). */
    suspend fun edge(context: Context, path: String?, pkg: String?): Double? {
        val key = edgeKey(path, pkg) ?: return null
        edges.get(key)?.let { return it.takeUnless(Double::isNaN) }
        val px = when {
            path != null -> decode(context, path, EDGE_PX)?.let { withContext(Dispatchers.Default) { pixels(it, EDGE_PX, EDGE_PX) } }
            pkg != null -> appIconPixels(context, pkg, EDGE_PX)
            else -> null
        }
        val lum = px?.let { ArtWash.edgeLuminance(it, EDGE_PX, EDGE_PX) }
        edges.put(key, lum ?: Double.NaN)
        return lum
    }

    fun edgeKey(path: String?, pkg: String?): String? = path ?: pkg?.let { "pkg:$it" }

    private suspend fun decode(context: Context, path: String, side: Int): Bitmap? = try {
        val request = ImageRequest.Builder(context)
            .data(File(context.filesDir, path))
            .size(side)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .build()
        ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            ?.takeIf { it.config != Bitmap.Config.HARDWARE }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun pixels(src: Bitmap, w: Int, h: Int): IntArray {
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        return px
    }

    private fun analyse(src: Bitmap): ArtSample {
        val s = ArtWash.SAMPLE
        val colors = ArtWash.extract(pixels(src, s, s), s, s)
        val aw = ArtWash.AMBIENT
        val ah = (aw * src.height / src.width.coerceAtLeast(1).toFloat()).roundToInt().coerceIn(8, aw * 2)
        val px = pixels(src, aw, ah)
        repeat(2) { boxBlur(px, aw, ah, BLUR_RADIUS) }
        val ambient = Bitmap.createBitmap(px, aw, ah, Bitmap.Config.ARGB_8888).asImageBitmap()
        return ArtSample(colors, ambient)
    }

    private const val BLUR_RADIUS = 2

    /** Desenfoque de caja (horizontal y vertical) sobre la copia diminuta: una vez por arte, nunca por fotograma. */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        for (pass in 0..1) {
            val src = if (pass == 0) px else tmp
            val dst = if (pass == 0) tmp else px
            val outer = if (pass == 0) h else w
            val inner = if (pass == 0) w else h
            for (o in 0 until outer) {
                for (i in 0 until inner) {
                    var a = 0
                    var rr = 0
                    var gg = 0
                    var bb = 0
                    var n = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, inner - 1)
                        val p = if (pass == 0) src[o * w + j] else src[j * w + o]
                        a += p ushr 24
                        rr += (p shr 16) and 0xFF
                        gg += (p shr 8) and 0xFF
                        bb += p and 0xFF
                        n++
                    }
                    val v = ((a / n) shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
                    if (pass == 0) dst[o * w + i] = v else dst[i * w + o] = v
                }
            }
        }
    }
}

/**
 * El fondo adaptable de un momento: el plan del arte anterior, el del nuevo
 * y el progreso del fundido entre los dos. Todo se lee al dibujar.
 */
@Stable
internal class WashState(initial: WashPlan, initialAmbient: ImageBitmap?) {

    var from by mutableStateOf(initial)
        private set
    var to by mutableStateOf(initial)
        private set
    var fromAmbient by mutableStateOf(initialAmbient)
        private set
    var ambient by mutableStateOf(initialAmbient)
        private set

    val progress = Animatable(1f)

    /** El tono del velo ahora mismo (solo al dibujar). */
    fun tone(): Color = lerp(Color(from.tone), Color(to.tone), progress.value)

    /** El tono al que va (para lo que no se funde: la luminancia con la que se reparte el filo de las tarjetas). */
    val targetTone: Color get() = Color(to.tone)

    suspend fun retarget(plan: WashPlan, image: ImageBitmap?, reduced: Boolean) {
        val t = progress.value
        from = if (t >= 1f) to else blend(from, to, t)
        fromAmbient = if (t >= 0.5f) ambient else fromAmbient
        to = plan
        ambient = image
        if (reduced) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(CROSSFADE_MS, easing = Swift))
        }
    }

    private fun blend(a: WashPlan, b: WashPlan, t: Float) = WashPlan(
        tone = ColorMath.mix(a.tone, b.tone, t),
        edges = IntArray(a.edges.size) { ColorMath.mix(a.edges[it], b.edges.getOrElse(it) { b.tone }, t) },
        coverage = a.coverage + (b.coverage - a.coverage) * t,
        top = ColorMath.mix(a.top, b.top, t),
        topAlpha = a.topAlpha + (b.topAlpha - a.topAlpha) * t,
        adaptive = b.adaptive,
    )

    companion object {
        /** El fundido de colores al cambiar de selección. */
        const val CROSSFADE_MS = 520
    }
}

/**
 * El fondo adaptable de [imagePath] (relativo a filesDir; null = sin arte):
 * en el arranque, lo que ya haya en caché; luego, al cambiar de selección,
 * lee la muestra fuera del hilo principal y funde hacia el nuevo plan.
 * [fallback] es el primario de la paleta de firma; [enabled] el ajuste
 * "Color de fondo adaptable". [preset] sustituye a la lectura (vistas previas).
 */
@Composable
internal fun rememberWash(imagePath: String?, dark: Boolean, fallback: Color, enabled: Boolean, preset: ArtColors? = null): WashState {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val fallbackArgb = fallback.argb()
    val state = remember {
        val sample = if (preset != null) ArtSample(preset, null) else imagePath?.let(ArtSampler::cached)
        WashState(ArtWash.plan(sample?.colors, dark, fallbackArgb, enabled), sample?.ambient?.takeIf { enabled })
    }
    LaunchedEffect(imagePath, dark, enabled, fallbackArgb, preset) {
        val sample = when {
            preset != null -> ArtSample(preset, null)
            imagePath == null -> null
            else -> ArtSampler.cached(imagePath) ?: ArtSampler.load(context, imagePath)
        }
        val plan = withContext(Dispatchers.Default) { ArtWash.plan(sample?.colors, dark, fallbackArgb, enabled) }
        state.retarget(plan, sample?.ambient?.takeIf { enabled }, reduced)
    }
    return state
}

/** Opacidad de la modulación vertical (los colores del canto del arte) dentro del velo. */
private const val MODULATION = 0.4f

/** El grano: una tesela de ruido gris pintada una vez, casi invisible (quita las bandas). */
private const val GRAIN_ALPHA = 0.035f
private const val GRAIN_TILE = 64

internal val grainTile: ImageBitmap by lazy {
    val rnd = java.util.Random(7)
    val px = IntArray(GRAIN_TILE * GRAIN_TILE) {
        val v = 96 + rnd.nextInt(64)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    Bitmap.createBitmap(px, GRAIN_TILE, GRAIN_TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * El velo de la rueda, en una capa aparte del ancho justo (hasta donde se
 * vuelve transparente): el arte desenfocado alineado con el de verdad, el
 * tono del velo encima, la modulación vertical con los colores del canto
 * del arte (como luz que sangra de él), el grano y, al final, la máscara
 * de [MeridianFog]: el perfil de [MeridianScrims] curvado por el círculo de
 * la rueda ([arc]), sin canto en ningún sitio. La máscara es una imagen
 * pequeña que se calcula una vez por tamaño y se amplía con filtrado; la
 * capa solo se vuelve a pintar al fundirse los colores, y girar la rueda no la toca.
 *
 * [windowWidth] y [axisX] en dp; [artExtra] lo que el arte sobresale arriba
 * y abajo (el paralaje) para que el desenfoque caiga sobre el mismo motivo.
 */
@Composable
internal fun MeridianWashLayer(
    state: WashState,
    windowWidth: Float,
    axisX: Float,
    artExtra: Float,
    lite: Boolean,
    modifier: Modifier = Modifier,
    arc: () -> FogArc = { FogArc.NONE },
) {
    val grain = remember { ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated)) }
    Spacer(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val w = windowWidth.dp.toPx()
                val h = size.height
                val ax = axisX.dp.toPx()
                val fog = arc()
                val mask = fogMask(size, ax, w, fog.top, fog.focusY.dp.toPx(), fog.radius.dp.toPx(), MeridianFog.CELL_DP.dp.toPx())
                val maskSize = IntSize(mask.width, mask.height)
                val layerSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                val from = state.from
                val to = state.to
                val modFrom = modulation(from)
                val modTo = modulation(to)
                val fromImage = state.fromAmbient
                val image = state.ambient
                val extra = artExtra.dp.toPx()
                val fromFrame = image(fromImage, w * (1f + MeridianArtFrame.FOCAL_SHIFT), h + extra * 2f)
                val frame = image(image, w * (1f + MeridianArtFrame.FOCAL_SHIFT), h + extra * 2f)
                onDrawBehind {
                    val t = state.progress.value
                    if (fromImage != null && fromImage !== image && t < 1f) drawAmbient(fromImage, fromFrame, extra, 1f - t)
                    if (image != null) drawAmbient(image, frame, extra, if (fromImage === image) 1f else t)
                    val coverage = from.coverage + (to.coverage - from.coverage) * t
                    drawRect(state.tone(), alpha = coverage)
                    if (t < 1f) drawRect(modFrom, alpha = MODULATION * coverage * (1f - t))
                    drawRect(modTo, alpha = MODULATION * coverage * t)
                    if (!lite) drawRect(grain, alpha = GRAIN_ALPHA)
                    drawImage(
                        mask,
                        srcOffset = IntOffset.Zero,
                        srcSize = maskSize,
                        dstSize = layerSize,
                        blendMode = BlendMode.DstIn,
                        filterQuality = FilterQuality.Low,
                    )
                }
            },
    )
}

/** La máscara de la niebla para una capa de [layer] px (ver [MeridianFog.mask]); [top] es donde empieza la rueda. */
private fun fogMask(layer: Size, axisX: Float, width: Float, top: Float, focusY: Float, radius: Float, cell: Float): ImageBitmap {
    val cols = MeridianFog.samples(layer.width, cell)
    val rows = MeridianFog.samples(layer.height, cell)
    val px = MeridianFog.mask(cols, rows, layer.width, layer.height, axisX, width, top + focusY, radius)
    return Bitmap.createBitmap(px, cols, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun modulation(plan: WashPlan): Brush {
    val n = plan.edges.size
    return Brush.verticalGradient(*Array(n) { i -> (if (n == 1) 0f else i / (n - 1f)) to Color(plan.edges[i]) })
}

/** Dónde cae una imagen recortada como el arte (llena el marco, anclada arriba y centrada): origen y tamaño en el lienzo. */
private class AmbientFrame(val offset: IntOffset, val size: IntSize)

private fun image(image: ImageBitmap?, frameW: Float, frameH: Float): AmbientFrame? {
    if (image == null || image.width <= 0 || image.height <= 0) return null
    val s = max(frameW / image.width, frameH / image.height)
    val dw = image.width * s
    val dh = image.height * s
    return AmbientFrame(IntOffset(((frameW - dw) / 2f).roundToInt(), 0), IntSize(dw.roundToInt(), dh.roundToInt()))
}

private fun DrawScope.drawAmbient(image: ImageBitmap, frame: AmbientFrame?, extra: Float, alpha: Float) {
    frame ?: return
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(frame.offset.x, (frame.offset.y - extra).roundToInt()),
        dstSize = frame.size,
        alpha = alpha,
        filterQuality = FilterQuality.High,
    )
}

/**
 * Lo que va detrás de todo menos el velo: viñeta suave arriba y abajo, el
 * velo de arriba (la tinta honda del arte, para el texto blanco de la barra:
 * se mantiene hasta [barHeight] y se apaga en 24 dp más) y el óvalo oscuro
 * bajo el bloque del hero. Mezcla normal, sin capas aparte. La franja y el
 * óvalo van por [artShown] (0 sin arte: sobre el escenario vacío, el óvalo
 * negro sobre un fondo claro eran las manchas grises).
 */
internal fun Modifier.meridianScrims(heroLeft: Float, scrim: Float, wash: WashState, barHeight: Float, artShown: () -> Float = { 1f }): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val shade = P.shade
    val vh = h * MeridianScrims.VIGNETTE_HEIGHT
    val vignetteTop = Brush.verticalGradient(0f to shade.copy(alpha = MeridianScrims.VIGNETTE_ALPHA), 1f to Color.Transparent, endY = vh)
    val vignetteBottom = Brush.verticalGradient(0f to Color.Transparent, 1f to shade.copy(alpha = MeridianScrims.VIGNETTE_ALPHA), startY = h - vh, endY = h)
    val hold = barHeight.dp.toPx()
    val end = hold + TOP_FADE.toPx()
    val from = wash.from
    val to = wash.to
    fun top(plan: WashPlan): Brush {
        val c = Color(plan.top)
        val a = maxOf(plan.topAlpha, scrim * 0.55f).coerceAtMost(ArtWash.TOP_MAX_ALPHA)
        return Brush.verticalGradient(
            0f to c.copy(alpha = a),
            hold / end to c.copy(alpha = a),
            (hold + (end - hold) * 0.5f) / end to c.copy(alpha = a * 0.4f),
            1f to Color.Transparent,
            endY = end,
        )
    }
    val topFrom = top(from)
    val topTo = top(to)
    val title = maxOf(MeridianScrims.titleAlpha(), scrim * 0.85f)
    val hl = heroLeft.dp.toPx().coerceAtMost(w)
    val heroW = (w - hl).coerceAtLeast(1f)
    val center = Offset(hl + heroW * 0.3f, h * 0.84f)
    val rx = heroW * 0.95f
    val ry = h * 0.72f
    val oval = Brush.radialGradient(
        0f to Color.Black.copy(alpha = title),
        0.55f to Color.Black.copy(alpha = title),
        0.8f to Color.Black.copy(alpha = title * 0.4f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = 1f,
    )
    val band = Size(w, end)
    onDrawBehind {
        val t = wash.progress.value
        val shown = artShown()
        drawRect(vignetteTop)
        drawRect(vignetteBottom)
        if (shown <= 0f) return@onDrawBehind
        if (t < 1f) drawRect(topFrom, size = band, alpha = (1f - t) * shown)
        drawRect(topTo, size = band, alpha = t * shown)
        withTransform({
            translate(center.x, center.y)
            scale(rx, ry, Offset.Zero)
        }) { drawCircle(oval, radius = 1f, center = Offset.Zero, alpha = shown) }
    }
}

/** Lo que se apaga el velo de arriba por debajo de la barra. */
private val TOP_FADE = 24.dp
