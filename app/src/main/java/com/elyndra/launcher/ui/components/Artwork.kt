package com.elyndra.launcher.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import com.elyndra.launcher.library.AppCatalog
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Carátula: la imagen descargada si existe y, si no hay (o no se puede
 * leer), el arte de reserva del juego ([FallbackArt]). Mientras la imagen
 * carga, y en los márgenes de una imagen encajada con `Fit`, se ve la
 * superficie de reserva sin icono ni título.
 */
@Composable
fun ArtImage(
    path: String?,
    fallback: ArtFallback,
    modifier: Modifier = Modifier,
    variant: ArtVariant = ArtVariant.Cover,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    scale: Float = 1f,
    /** El título solo cuando no hay imagen (en portada). */
    showTitle: Boolean = variant == ArtVariant.Cover,
    iconModifier: Modifier = Modifier,
) {
    if (path == null) {
        FallbackArt(fallback, variant, modifier, showTitle = showTitle, iconModifier = iconModifier)
        return
    }
    var failed by remember(path) { mutableStateOf(false) }
    if (failed) {
        FallbackArt(fallback, variant, modifier, showTitle = showTitle, iconModifier = iconModifier)
        return
    }
    Box(modifier) {
        // Debajo, solo el color del juego: no hace falta leer su icono.
        FallbackArt(fallback.colorOnly(), variant, Modifier.fillMaxSize(), showTitle = false, showIcon = false)
        val context = LocalContext.current
        val file = remember(path) { File(context.filesDir, path) }
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = contentScale,
            alignment = alignment,
            onError = { failed = true },
            modifier = Modifier
                .fillMaxSize()
                .then(if (scale != 1f) Modifier.graphicsLayer { scaleX = scale; scaleY = scale } else Modifier),
        )
    }
}

/** Logo con transparencia (wheel de ScreenScraper o logo de SteamGridDB). */
@Composable
fun LogoImage(
    path: String,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.CenterStart,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    val file = remember(path) { File(context.filesDir, path) }
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = contentScale,
        alignment = alignment,
        modifier = modifier,
    )
}

/** Icono del juego: el elegido en "Personalizar icono" si lo hay; si no, el de la app instalada. */
@Composable
fun GameIcon(
    iconPath: String?,
    packageName: String?,
    modifier: Modifier = Modifier,
    /** `Crop` cuando el icono tiene que llenar la card; `Fit` para verlo entero. */
    contentScale: ContentScale = ContentScale.Fit,
) {
    when {
        iconPath != null -> LogoImage(iconPath, modifier, Alignment.Center, contentScale)
        packageName != null -> AppIconImage(packageName, modifier, contentScale)
    }
}

private val iconCache = LruCache<String, ImageBitmap>(96)

/** Icono real de una app instalada (PackageManager), cacheado en memoria. */
// Falso positivo del detector: `value` sí se asigna en el productor.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun AppIconImage(
    packageName: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    // La caché se consulta dentro del productor: `initialValue` solo vale en la
    // primera composición, y al cambiar de paquete se quedaría el icono anterior.
    val bitmap by produceState<ImageBitmap?>(iconCache.get(packageName), packageName) {
        val icon = iconCache.get(packageName) ?: withContext(Dispatchers.IO) {
            runCatching { loadAppIcon(context, packageName) }.getOrNull()
        }?.also { iconCache.put(packageName, it) }
        value = icon
    }
    bitmap?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = contentScale) }
}

/** Acento del icono de una app instalada (ver [ArtPalette]); null si es gris o no está. */
internal suspend fun appIconAccent(context: Context, packageName: String): Int? =
    appIconPixels(context, packageName)?.let { withContext(Dispatchers.Default) { ArtPalette.accentOf(it) } }

/** El icono de una app instalada reducido a [side]×[side] píxeles ARGB; null si no está. */
internal suspend fun appIconPixels(context: Context, packageName: String, side: Int = 32): IntArray? = withContext(Dispatchers.Default) {
    val icon = iconCache.get(packageName) ?: runCatching { loadAppIcon(context, packageName) }.getOrNull()?.also { iconCache.put(packageName, it) }
    icon?.let { bitmap ->
        val small = Bitmap.createScaledBitmap(bitmap.asAndroidBitmap(), side, side, true)
        val px = IntArray(side * side)
        small.getPixels(px, 0, side, 0, 0, side, side)
        px
    }
}

private const val ICON_PX = 512

/**
 * Icono de una app listo para llenar una card.
 *
 * Se rasteriza respetando su proporción (cuadrarlo deformaría los que no lo
 * son) y se le quita el margen que no se ve: un icono adaptativo reserva un
 * tercio de zona de seguridad y muchos PNG traen borde transparente, y sin
 * recortarlo el dibujo queda pequeño en medio de la card.
 */
private fun loadAppIcon(context: Context, packageName: String): ImageBitmap {
    // A la densidad más alta que traiga la app: se pinta en cards grandes.
    val d = AppCatalog(context).highResIcon(packageName) ?: context.packageManager.getApplicationIcon(packageName)
    val w = d.intrinsicWidth.takeIf { it > 0 } ?: ICON_PX
    val h = d.intrinsicHeight.takeIf { it > 0 } ?: ICON_PX
    val k = ICON_PX.toFloat() / maxOf(w, h)
    var bmp = d.toBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
    if (d is AdaptiveIconDrawable) {
        // El launcher solo enseña el 66 % central del lienzo de 108dp.
        val inset = minOf(bmp.width, bmp.height) / 6
        if (inset > 0) {
            bmp = Bitmap.createBitmap(bmp, inset, inset, bmp.width - inset * 2, bmp.height - inset * 2)
        }
    }
    return trimTransparent(bmp).asImageBitmap()
}

/** Recorta el borde completamente transparente del bitmap. */
private fun trimTransparent(src: Bitmap): Bitmap {
    if (!src.hasAlpha()) return src
    val w = src.width
    val h = src.height
    if (w < 2 || h < 2) return src
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    fun rowEmpty(y: Int) = (0 until w).all { px[y * w + it] ushr 24 == 0 }
    fun colEmpty(x: Int) = (0 until h).all { px[it * w + x] ushr 24 == 0 }
    var top = 0
    var bottom = h - 1
    var left = 0
    var right = w - 1
    while (top < bottom && rowEmpty(top)) top++
    while (bottom > top && rowEmpty(bottom)) bottom--
    while (left < right && colEmpty(left)) left++
    while (right > left && colEmpty(right)) right--
    if (top == 0 && left == 0 && bottom == h - 1 && right == w - 1) return src
    return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
}
