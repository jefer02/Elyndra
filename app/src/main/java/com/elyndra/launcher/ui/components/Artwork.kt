package com.elyndra.launcher.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.elyndra.launcher.ui.theme.art
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Carátula: la procedural del diseño siempre debajo y, encima, la imagen
 * descargada si existe. Mientras carga (o si falla) se ve la procedural.
 */
@Composable
fun ArtImage(
    path: String?,
    pairIndex: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    scale: Float = 1f,
) {
    Box(modifier.art(pairIndex)) {
        if (path != null) {
            val context = LocalContext.current
            val file = remember(path) { File(context.filesDir, path) }
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (scale != 1f) Modifier.graphicsLayer { scaleX = scale; scaleY = scale } else Modifier),
            )
        }
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
@Composable
fun AppIconImage(
    packageName: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    val bitmap by produceState(initialValue = iconCache.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { loadAppIcon(context, packageName) }.getOrNull()
            }?.also { iconCache.put(packageName, it) }
        }
    }
    bitmap?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = contentScale) }
}

private const val ICON_PX = 384

/**
 * Icono de una app listo para llenar una card.
 *
 * Se rasteriza respetando su proporción (cuadrarlo deformaría los que no lo
 * son) y se le quita el margen que no se ve: un icono adaptativo reserva un
 * tercio de zona de seguridad y muchos PNG traen borde transparente, y sin
 * recortarlo el dibujo queda pequeño en medio de la card.
 */
private fun loadAppIcon(context: Context, packageName: String): ImageBitmap {
    val d = context.packageManager.getApplicationIcon(packageName)
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
