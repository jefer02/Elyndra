package com.elyndra.launcher.sound

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.elyndra.launcher.R

/**
 * Música de fondo de la interfaz: el ambiente de Elyndra
 * (tools/gen_ui_music.py) o un audio que el usuario elija, en bucle sin
 * costura (ExoPlayer).
 *
 * Buena vecina:
 *  · No pide el foco de audio. Si al volver a la app ya suena música de otra
 *    (Spotify, YouTube…), Elyndra no arranca la suya: cede hasta la próxima vez.
 *  · Se para al salir de la app (al lanzar un juego) y vuelve con un fundido.
 *  · Se baja mientras Masha habla o escucha, y se calla en la calibración de voz.
 *
 * Solo desde el hilo principal.
 */
@OptIn(UnstableApi::class)
class BackgroundMusic(private val context: Context, private val settings: SoundSettings) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var loadedUri: Uri? = null

    private var foreground = false
    private var mashaBusy = false
    private var held = false
    /** Al volver a la app ya sonaba otra música: esta vez no se arranca. */
    private var yielded = false

    private var level = 0f
    private var target = 0f

    /** El archivo propio no se pudo reproducir (se usa el de Elyndra). */
    var onCustomFailed: (() -> Unit)? = null

    private val fadeStep = object : Runnable {
        override fun run() {
            val p = player ?: return
            val step = FADE_STEP
            level = if (level < target) minOf(target, level + step) else maxOf(target, level - step)
            p.volume = level
            if (level == target) {
                if (target == 0f) p.pause()
            } else {
                main.postDelayed(this, FADE_TICK_MS)
            }
        }
    }

    fun setForeground(value: Boolean) {
        if (value == foreground) return
        foreground = value
        if (value) {
            // Si ya suena música de otra app, se le deja el sitio.
            yielded = player?.isPlaying != true && audio.isMusicActive
        } else {
            // Se sale de la app: nada de fundido, se para ya.
            main.removeCallbacks(fadeStep)
            player?.pause()
            level = 0f
            player?.volume = 0f
        }
        update()
    }

    fun setMashaBusy(value: Boolean) {
        mashaBusy = value
        update()
    }

    /** Pantallas que necesitan silencio (calibración de voz). */
    fun setHeld(value: Boolean) {
        held = value
        update()
    }

    /** Ajustes cambiados: fuente, interruptor o volumen. */
    fun reload() {
        val uri = sourceUri()
        if (!settings.musicEnabled) {
            release()
            return
        }
        if (uri != loadedUri) {
            release()
        }
        update()
    }

    fun updateVolume() = update()

    private fun gain(): Float = SoundManager.gainFor(settings.musicVolume) * MAX_GAIN

    private fun update() {
        val wanted = settings.musicEnabled && foreground && !yielded && !held
        if (!wanted) {
            fadeTo(0f)
            return
        }
        val p = player ?: create() ?: return
        if (!p.isPlaying) {
            p.volume = level
            p.play()
        }
        fadeTo(if (mashaBusy) 0f else gain())
    }

    private fun fadeTo(value: Float) {
        target = value
        if (player == null) return
        main.removeCallbacks(fadeStep)
        if (value > 0f && player?.isPlaying == false) player?.play()
        main.post(fadeStep)
    }

    private fun sourceUri(): Uri =
        settings.musicUri?.let(Uri::parse) ?: Uri.parse("android.resource://${context.packageName}/${R.raw.ui_music_ambient}")

    private fun create(): ExoPlayer? {
        val uri = sourceUri()
        val p = runCatching {
            ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_GAME).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                    /* handleAudioFocus = */ false,
                )
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                setMediaItem(MediaItem.fromUri(uri))
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) = onError()
                })
                prepare()
            }
        }.getOrNull() ?: return null
        player = p
        loadedUri = uri
        level = 0f
        return p
    }

    /** El archivo propio falló (permiso perdido, formato raro): vuelve el de Elyndra. */
    private fun onError() {
        val custom = settings.musicUri != null && loadedUri == Uri.parse(settings.musicUri)
        release()
        if (custom) {
            settings.musicUri = null
            onCustomFailed?.invoke()
            update()
        }
    }

    private fun release() {
        main.removeCallbacks(fadeStep)
        player?.release()
        player = null
        loadedUri = null
        level = 0f
    }

    private companion object {
        /** La música va por debajo de los sonidos: al 100 % del deslizador, algo menos que plena. */
        const val MAX_GAIN = 0.8f
        const val FADE_TICK_MS = 30L
        const val FADE_STEP = 0.04f
    }
}
