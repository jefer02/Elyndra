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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
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
 */
@Composable
fun DynamicBackdrop(imagePath: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current

    var dominant by remember { mutableStateOf(Color.Transparent) }
    LaunchedEffect(imagePath) {
        dominant = imagePath?.let { dominantColor(context, File(context.filesDir, it)) } ?: Color.Transparent
    }
    val tint by animateColorAsState(dominant, motion(Springs.fade()), label = "tint")

    Box(modifier.fillMaxSize()) {
        Crossfade(targetState = imagePath, animationSpec = motion(Springs.fade()), label = "backdrop") { path ->
            if (path == null) return@Crossfade
            // Zoom sutil 1.08 → 1.14 cada vez que llega un arte nuevo.
            val zoom = remember(path) { Animatable(if (reduced) 1.1f else 1.08f) }
            LaunchedEffect(path) {
                if (!reduced) zoom.animateTo(1.14f, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 12f))
            }
            AsyncImage(
                model = File(context.filesDir, path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                        alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.42f else 0.22f
                    }
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(48.dp) else Modifier),
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

/** Color medio del arte, reducido a un píxel. Barato y suficiente para teñir. */
private suspend fun dominantColor(context: android.content.Context, file: File): Color? {
    val request = ImageRequest.Builder(context).data(file).size(24).allowHardware(false).build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
    return withContext(Dispatchers.Default) {
        val one = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        Color(one.getPixel(0, 0)).copy(alpha = 1f)
    }
}
