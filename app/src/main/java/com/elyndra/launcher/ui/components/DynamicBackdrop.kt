package com.elyndra.launcher.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.elyndra.launcher.ui.theme.LocalPerformance
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fondo vivo de la selección: el arte del juego elegido, muy desenfocado y
 * con un acercamiento lento, teñido con su color dominante. Cambiar de juego
 * funde un fondo en otro con muelle; todo va en `graphicsLayer`, así que el
 * zoom no recompone nada.
 *
 * El desenfoque de Compose necesita Android 12; en versiones anteriores el
 * arte se ve más apagado en su lugar.
 *
 * En Rendimiento ([LocalPerformance]) no hay RenderEffect: un desenfoque de
 * 48 dp a pantalla completa se vuelve a calcular en cada fotograma que pinta
 * la ventana (y la flotación o el halo de la selección piden uno tras otro).
 * En su lugar el arte se decodifica diminuto (32 px) y se amplía con filtrado
 * bilineal: a ese tamaño ya es una mancha de color suave, el mismo fondo, sin
 * coste por fotograma. Tampoco hay acercamiento lento (segundos de fotogramas).
 */
@Composable
fun DynamicBackdrop(imagePath: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val perf = LocalPerformance.current
    val liveBlur = perf.liveBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val decodePx = perf.backdropDecodePx

    var dominant by remember { mutableStateOf(Color.Transparent) }
    LaunchedEffect(imagePath) {
        dominant = imagePath?.let { dominantColor(context, File(context.filesDir, it)) } ?: Color.Transparent
    }
    val tint by animateColorAsState(dominant, motion(Springs.fade()), label = "tint")

    Box(modifier.fillMaxSize()) {
        Crossfade(targetState = imagePath, animationSpec = motion(Springs.fade()), label = "backdrop") { path ->
            if (path == null) return@Crossfade
            // Zoom sutil 1.08 → 1.14 cada vez que llega un arte nuevo.
            val still = reduced || !perf.ambientMotion
            val zoom = remember(path) { Animatable(if (still) 1.1f else 1.08f) }
            LaunchedEffect(path) {
                if (!still) zoom.animateTo(1.14f, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 12f))
            }
            AsyncImage(
                // Tan desenfocado no hace falta el arte a tamaño de pantalla: se
                // decodifica pequeño (menos memoria y menos trabajo al desenfocar).
                // Clave propia en la caché: esta versión diminuta no puede
                // acabar sirviéndose al hero, que pide el mismo fichero en grande.
                model = remember(path, decodePx) {
                    ImageRequest.Builder(context)
                        .data(File(context.filesDir, path))
                        .size(decodePx)
                        .memoryCacheKey("backdrop:$decodePx:$path")
                        .build()
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Sin RenderEffect, el bilineal es el desenfoque: que no sea el vecino más próximo.
                filterQuality = FilterQuality.Low,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                        alpha = if (liveBlur || decodePx < BLURRY_DECODE_PX) 0.42f else 0.22f
                    }
                    .then(if (liveBlur) Modifier.blur(48.dp) else Modifier),
            )
        }
        // El color dominante baña la parte baja y funde con el fondo oscuro.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.10f), tint.copy(alpha = 0.32f)))),
        )
    }
}

/**
 * Por debajo de este lado (px) el arte decodificado ya se ve desenfocado al
 * ampliarlo: lleva el mismo alfa que el desenfoque de verdad. Por encima y sin
 * desenfoque (Android 8–11 en Calidad), más apagado, como siempre.
 */
private const val BLURRY_DECODE_PX = 64

/** Color medio del arte, reducido a un píxel. Barato y suficiente para teñir. */
private suspend fun dominantColor(context: android.content.Context, file: File): Color? {
    // Clave propia: la versión de 24 px no debe quedar en caché bajo la clave del fichero.
    val request = ImageRequest.Builder(context).data(file).size(24).allowHardware(false).memoryCacheKey("tint:${file.path}").build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
    return withContext(Dispatchers.Default) {
        val one = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        Color(one.getPixel(0, 0)).copy(alpha = 1f)
    }
}
