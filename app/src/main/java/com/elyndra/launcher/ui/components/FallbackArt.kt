package com.elyndra.launcher.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.elyndra.launcher.data.FallbackPalette
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.PixelBlur
import com.elyndra.launcher.ui.masha.MashaQuality
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/* ─────────────────────────────────────────────────────────────
   Arte de reserva: lo que se ve cuando un juego no tiene arte.

   Capas, de abajo arriba:
     1. Base: el color que manda en el icono (o el primario de
        marca), ya recortado a un tono medio (ver FallbackPalette).
     2. Si hay icono, el icono ampliado y muy desenfocado, oscurecido:
        la carátula "huele" al juego. Se desenfoca una vez y se guarda.
     3. Malla: tres blobs radiales difusos —principal, profundo y
        claro/análogo— colocados según el juego, siempre los mismos.
     4. Grano de película: una tesela de ruido generada una vez.
     5. Viñeta y un reflejo de cristal desde la esquina de arriba.
     6. En portada, el icono nítido y el título en Poppins.

   Nada se reserva por fotograma: pinceles y posiciones salen de
   drawWithCache y la deriva opcional solo desplaza los blobs.
   ───────────────────────────────────────────────────────────── */

/** Qué juego es: de aquí salen el color, la colocación de la malla y el rótulo. */
@Immutable
data class ArtFallback(
    /** Clave estable del juego (semilla y caché). */
    val key: String,
    val title: String,
    /** Icono elegido a mano (relativo a filesDir). */
    val iconPath: String? = null,
    /** App cuyo icono se usa si no hay [iconPath]. */
    val packageName: String? = null,
) {
    val hasIcon: Boolean get() = iconPath != null || packageName != null

    /** El mismo juego sin icono: solo color, sin nada que leer del disco. */
    fun colorOnly(): ArtFallback = if (hasIcon) copy(iconPath = null, packageName = null) else this
}

/** Formatos: banda apaisada (hero, fondos), portada 2:3 y cuadrado (iconos). */
enum class ArtVariant { Banner, Cover, Square }

/** Lo que se lee del icono una vez por juego: los tonos y el icono desenfocado. */
@Immutable
class FallbackLook(val tones: FallbackPalette.Tones, val blurred: ImageBitmap?)

internal object FallbackArtCache {

    private const val SIDE = 32
    private val cache = LruCache<String, FallbackLook>(256)

    @Volatile private var lite: Boolean? = null

    private fun id(f: ArtFallback) = "${f.key}|${f.iconPath}|${f.packageName}"

    /** Lo que hay sin leer nada: la caché o, si no, el color sin icono. */
    fun immediate(f: ArtFallback): FallbackLook =
        cache.get(id(f)) ?: FallbackLook(FallbackPalette.of(null, f.key), null).also { if (!f.hasIcon) cache.put(id(f), it) }

    suspend fun load(context: Context, f: ArtFallback): FallbackLook {
        cache.get(id(f))?.let { return it }
        if (!f.hasIcon) return immediate(f)
        val px = runCatching { iconPixels(context, f) }.getOrNull()
        val look = withContext(Dispatchers.Default) {
            val tones = FallbackPalette.of(px?.let(FallbackPalette::dominant), f.key)
            val blurred = px?.let {
                val copy = PixelBlur.blur(it.copyOf(), SIDE, SIDE, radius = 3)
                Bitmap.createBitmap(copy, SIDE, SIDE, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
            FallbackLook(tones, blurred)
        }
        cache.put(id(f), look)
        return look
    }

    private suspend fun iconPixels(context: Context, f: ArtFallback): IntArray? {
        f.iconPath?.let { path ->
            val request = ImageRequest.Builder(context).data(File(context.filesDir, path)).size(SIDE * 2).allowHardware(false).build()
            val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) {
                return withContext(Dispatchers.Default) {
                    val small = Bitmap.createScaledBitmap(bitmap, SIDE, SIDE, true)
                    IntArray(SIDE * SIDE).also { small.getPixels(it, 0, SIDE, 0, 0, SIDE, SIDE) }
                }
            }
        }
        return f.packageName?.let { appIconPixels(context, it, SIDE) }
    }

    /** Equipo modesto (el mismo criterio que la calidad ligera de Masha): sin deriva. */
    fun lite(context: Context): Boolean =
        lite ?: (MashaQuality.detect(context) == MashaQuality.Lite).also { lite = it }
}

/** Tesela de grano: ruido blanco y negro muy tenue, generado una sola vez. */
private val Grain: ShaderBrush by lazy {
    val side = 96
    val rnd = java.util.Random(0x5EED)
    val px = IntArray(side * side) {
        val a = 5 + rnd.nextInt(17)
        if (rnd.nextBoolean()) (a shl 24) or 0xFFFFFF else a shl 24
    }
    val bitmap = Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
    ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

/** Periodo de la deriva de los blobs: tan lenta que no se ve moverse, solo cambiar. */
private const val DRIFT_MS = 28_000

/**
 * El arte de reserva de [fallback]. En [ArtVariant.Cover] lleva el icono y el
 * título; en [ArtVariant.Square], el icono; en [ArtVariant.Banner], solo la
 * superficie (el hero ya pone logo y título encima). [drift] mueve la malla
 * muy despacio, salvo con animaciones reducidas o en equipos modestos.
 */
@Composable
fun FallbackArt(
    fallback: ArtFallback,
    variant: ArtVariant,
    modifier: Modifier = Modifier,
    showTitle: Boolean = variant == ArtVariant.Cover,
    showIcon: Boolean = variant != ArtVariant.Banner,
    drift: Boolean = false,
    /** Se aplica al icono nítido (p. ej. la flotación del carrusel). */
    iconModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val look by produceState(FallbackArtCache.immediate(fallback), fallback) {
        value = FallbackArtCache.load(context, fallback)
    }
    val moving = drift && !LocalReducedMotion.current && !FallbackArtCache.lite(context)
    val phase: State<Float>? = if (moving) {
        rememberInfiniteTransition(label = "fallbackDrift").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(DRIFT_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "fallbackDriftPhase",
        )
    } else {
        null
    }
    Box(modifier.clipToBounds().fallbackSurface(look, fallback.key, phase, titleScrim = showTitle)) {
        val icon = showIcon && fallback.hasIcon
        when (variant) {
            ArtVariant.Cover -> if (icon || showTitle) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val w = maxWidth
                    if (icon) {
                        GameIcon(
                            fallback.iconPath,
                            fallback.packageName,
                            Modifier
                                .align(Alignment.Center)
                                .padding(bottom = if (showTitle) maxHeight * 0.2f else 0.dp)
                                .fillMaxWidth(0.5f)
                                .aspectRatio(1f)
                                .then(iconModifier),
                            ContentScale.Fit,
                        )
                    }
                    if (showTitle) {
                        // Sin icono el título es la carátula: más grande.
                        val size = (w.value * if (icon) 0.095f else 0.125f).coerceIn(9f, 26f)
                        ElyText(
                            fallback.title,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = w * 0.09f, vertical = w * 0.08f),
                            size = size,
                            weight = FontWeight.SemiBold,
                            color = Color.White,
                            letterSpacing = tracking(0.005f),
                            lineHeightRatio = 1.16f,
                            shadow = TitleShadow,
                            maxLines = if (icon) 3 else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            ArtVariant.Square -> if (icon) {
                GameIcon(
                    fallback.iconPath,
                    fallback.packageName,
                    Modifier.align(Alignment.Center).fillMaxWidth(0.58f).aspectRatio(1f).then(iconModifier),
                    ContentScale.Fit,
                )
            }
            ArtVariant.Banner -> Unit
        }
    }
}

private val TitleShadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 1.5f), 8f)

/** Las capas de superficie (1–5 del esquema de arriba). */
private fun Modifier.fallbackSurface(
    look: FallbackLook,
    seed: String,
    phase: State<Float>?,
    titleScrim: Boolean,
): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val t = look.tones
    val base = Color(t.base)
    val deep = Color(t.deep)
    val light = Color(t.light)
    val side = max(w, h)
    fun u(slot: Int) = FallbackPalette.unit(seed, slot)

    // Con icono desenfocado debajo, la malla se aclara para dejarlo ver.
    val k = if (look.blurred != null) 0.6f else 1f
    val lightC = Offset(w * (0.10f + 0.30f * u(0)), h * (0.05f + 0.25f * u(1)))
    val lightR = side * (0.70f + 0.20f * u(2))
    val deepC = Offset(w * (0.62f + 0.33f * u(3)), h * (0.70f + 0.30f * u(4)))
    val deepR = side * (0.80f + 0.20f * u(5))
    val mainC = Offset(w * (0.55f + 0.35f * u(6)), h * (0.15f + 0.35f * u(7)))
    val mainR = side * 0.55f
    val lightBrush = Brush.radialGradient(
        0f to light.copy(alpha = 0.85f * k), 0.5f to light.copy(alpha = 0.30f * k), 1f to Color.Transparent,
        center = Offset.Zero, radius = lightR,
    )
    val deepBrush = Brush.radialGradient(
        0f to deep.copy(alpha = 0.95f * k), 0.55f to deep.copy(alpha = 0.45f * k), 1f to Color.Transparent,
        center = Offset.Zero, radius = deepR,
    )
    val mainBrush = Brush.radialGradient(
        0f to base.copy(alpha = 0.9f * k), 1f to Color.Transparent,
        center = Offset.Zero, radius = mainR,
    )
    val vignette = Brush.radialGradient(
        0.55f to Color.Transparent, 1f to P.shade.copy(alpha = 0.5f),
        center = Offset(w / 2f, h / 2f), radius = hypot(w, h) / 2f * 1.05f,
    )
    val sheen = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.16f), 0.45f to Color.Transparent,
        start = Offset.Zero, end = Offset(w * 0.7f, h * 0.7f),
    )
    val scrim = if (titleScrim) Brush.verticalGradient(0.45f to Color.Transparent, 1f to deep.copy(alpha = 0.75f)) else null
    val blurredDst = look.blurred?.let {
        // Ampliado un 35 % y centrado: sin bordes duros a la vista.
        val s = side * 1.35f
        IntOffset(((w - s) / 2f).toInt(), ((h - s) / 2f).toInt()) to IntSize(s.toInt(), s.toInt())
    }
    val mesh = Color(com.elyndra.launcher.data.ColorMath.mix(t.base, t.deep, 0.35f))
    val amp = side * 0.05f

    onDrawBehind {
        drawRect(mesh)
        look.blurred?.let { img ->
            val (o, s) = blurredDst!!
            drawImage(img, dstOffset = o, dstSize = s, alpha = 0.6f, filterQuality = FilterQuality.Low)
            drawRect(deep.copy(alpha = 0.28f))
        }
        val p = (phase?.value ?: 0f) * (2f * PI.toFloat())
        val dx = sin(p) * amp
        val dy = cos(p) * amp
        translate(deepC.x - dx, deepC.y - dy) { drawCircle(deepBrush, deepR, Offset.Zero) }
        translate(mainC.x + dy, mainC.y + dx) { drawCircle(mainBrush, mainR, Offset.Zero) }
        translate(lightC.x + dx, lightC.y + dy) { drawCircle(lightBrush, lightR, Offset.Zero) }
        drawRect(Grain)
        drawRect(vignette)
        drawRect(sheen)
        scrim?.let { drawRect(it) }
    }
}
