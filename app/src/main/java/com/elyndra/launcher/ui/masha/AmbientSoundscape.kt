package com.elyndra.launcher.ui.masha

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.elyndra.launcher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * El ambiente sonoro del holotanque: un lazo de 96 s (res/raw/masha_ambient.ogg,
 * generado con tools/masha/generate_ambient.py) que se repite sin costura.
 *
 * - Media3/ExoPlayer con REPEAT_MODE_ONE: el Ogg Vorbis lleva su duración
 *   exacta, así que el salto de vuelta es de muestra a muestra.
 * - Pide el foco de audio (como un juego) y lo suelta al pausar; baja solo si
 *   otra app pide "duck" y se pausa si se desconectan los auriculares.
 * - Volumen propio, siempre por debajo de la voz: el máximo del deslizador
 *   es [MAX_GAIN] de la escala del reproductor.
 * - Ducking: mientras Masha habla (o escucha) baja a [DUCK] del volumen y
 *   vuelve con una rampa suave al terminar.
 * - Con el volumen a cero, o la app en segundo plano, se pausa de verdad
 *   (no gasta CPU decodificando silencio).
 */
class AmbientSoundscape(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ramp: Job? = null
    private var level = 0.45f
    private var enabled = true
    private var ducked = false
    private var foreground = true
    private var released = false

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_GAME)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        setHandleAudioBecomingNoisy(true)
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 0f
        setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://${context.packageName}/${R.raw.masha_ambient}")))
        prepare()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        update()
    }

    /** 0..1 (el deslizador); se escala a [MAX_GAIN]. */
    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
        update(fast = true)
    }

    fun setDucked(on: Boolean) {
        if (ducked == on) return
        ducked = on
        update()
    }

    fun onForeground(on: Boolean) {
        foreground = on
        update(fast = !on)
    }

    private fun target(): Float =
        if (!enabled || !foreground) 0f else level * MAX_GAIN * (if (ducked) DUCK else 1f)

    /** Lleva el volumen a su objetivo con una rampa (entra despacio, se aparta rápido). */
    private fun update(fast: Boolean = false) {
        if (released) return
        val goal = target()
        if (goal > 0f && !player.playWhenReady) player.play()
        ramp?.cancel()
        ramp = scope.launch {
            val from = player.volume
            val down = goal < from
            val ms = when {
                fast -> 180f
                down -> 350f       // al empezar a hablar: que se aparte ya
                else -> 1400f      // al callar: que vuelva sin notarse
            }
            val steps = (ms / 16f).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                val e = t * t * (3 - 2 * t)
                player.volume = from + (goal - from) * e
                delay(16)
            }
            player.volume = goal
            if (abs(goal) < 1e-4f) player.pause()
        }
    }

    fun release() {
        released = true
        scope.cancel()
        player.release()
    }

    companion object {
        /** El 100 % del deslizador: bien por debajo de la voz. */
        const val MAX_GAIN = 0.55f
        /** Cuánto queda del ambiente mientras habla Masha. */
        const val DUCK = 0.28f
    }
}

/**
 * El ambiente mientras la pantalla de Masha está compuesta. Sigue el ciclo de
 * vida (se pausa en segundo plano y vuelve al volver) y se libera al salir.
 */
@Composable
fun rememberAmbientSoundscape(enabled: Boolean, level: Float, ducked: Boolean): AmbientSoundscape {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sound = remember { AmbientSoundscape(context) }
    DisposableEffect(sound, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> sound.onForeground(true)
                Lifecycle.Event.ON_STOP -> sound.onForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sound.release()
        }
    }
    LaunchedEffect(sound, enabled) { sound.setEnabled(enabled) }
    LaunchedEffect(sound, level) { sound.setLevel(level) }
    LaunchedEffect(sound, ducked) { sound.setDucked(ducked) }
    return sound
}
