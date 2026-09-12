package com.elyndra.launcher.ui.components

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Fondo animado de la interfaz: el vídeo que el usuario elige en Ajustes,
 * en bucle, mudo y recortado para llenar la pantalla.
 *
 * Es solo el fondo *de Elyndra* — no toca el fondo de pantalla del sistema.
 *
 * Dos detalles que importan en un lanzador:
 *   · Va siempre mudo: un fondo no debe sonar por encima de nada.
 *   · Se pausa al pasar a segundo plano. Como Elyndra cede el primer plano
 *     cada vez que arranca un juego, dejarlo corriendo gastaría batería
 *     durante toda la partida.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoBackdrop(uri: String, opacity: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exo = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, exo) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> exo.play()
                Lifecycle.Event.ON_STOP -> exo.pause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            exo.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                // Recorta para llenar, como un fondo de pantalla.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                player = exo
            }
        },
        update = { it.player = exo },
        modifier = modifier.alpha(opacity),
    )
}
