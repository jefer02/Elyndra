package com.elyndra.launcher.ui.masha.live

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Oye si el usuario empieza a hablar **mientras habla Lucy**, para cortarla
 * (barge-in). Graba con la fuente de llamadas (VOICE_COMMUNICATION), que trae
 * cancelación de eco, y le pone además el cancelador de eco y el supresor de
 * ruido del sistema si existen; el nivel de cada trozo de 20 ms va a
 * [VoiceActivity].
 *
 * Solo funciona entre [start] y [stop]: nunca a la vez que el reconocedor
 * (muchos móviles no dejan abrir el micrófono dos veces). Sin cancelación de
 * eco y por el altavoz, su propia voz la cortaría: [usable] lo dice y quien lo
 * usa decide (ver LiveVoice).
 */
class BargeInListener(context: Context, private val onSpeech: () -> Unit) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Hay cancelación de eco en este móvil. */
    val echoCancellation: Boolean = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)

    /** Se puede usar ahora: con permiso y, por el altavoz, solo con cancelación de eco. */
    fun usable(headphones: Boolean): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            (echoCancellation || headphones)

    @SuppressLint("MissingPermission") // usable() lo comprueba antes
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "lucy-barge-in").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(200)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val frame = SAMPLE_RATE / 1000 * FRAME_MS
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 2 * 4))
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "sin micrófono para el barge-in")
            record?.release()
            running = false
            return
        }
        val aec = if (echoCancellation) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val ns = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val vad = VoiceActivity(frameMs = FRAME_MS)
        val buf = ShortArray(frame)
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buf, 0, frame)
                if (n <= 0) continue
                if (vad.accept(VoiceActivity.levelDb(buf, n))) {
                    running = false
                    main.post(onSpeech)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "barge-in parado", e)
        } finally {
            runCatching { record.stop() }
            aec?.release()
            ns?.release()
            record.release()
        }
    }

    private companion object {
        const val TAG = "LucyBargeIn"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
    }
}
