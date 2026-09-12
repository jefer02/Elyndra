package com.elyndra.launcher.ui.components

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
                runCatching {
                    // Se respeta la proporción del drawable: rasterizarlo a un
                    // cuadrado deformaría los iconos que no lo son.
                    val d = context.packageManager.getApplicationIcon(packageName)
                    val w = d.intrinsicWidth.takeIf { it > 0 } ?: 384
                    val h = d.intrinsicHeight.takeIf { it > 0 } ?: 384
                    val k = 384f / maxOf(w, h)
                    d.toBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1)).asImageBitmap()
                }.getOrNull()
            }?.also { iconCache.put(packageName, it) }
        }
    }
    bitmap?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = contentScale) }
}
