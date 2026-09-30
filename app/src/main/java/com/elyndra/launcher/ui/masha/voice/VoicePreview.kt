package com.elyndra.launcher.ui.masha.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prueba de la voz natural en Ajustes: sintetiza una frase y la reproduce tal
 * cual (sin labios). Una prueba nueva corta la anterior.
 */
class VoicePreview(context: Context) {

    private val app = context.applicationContext
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "masha-voice-preview").apply { isDaemon = true } }
    private val gen = AtomicInteger()
    @Volatile private var track: AudioTrack? = null

    fun play(text: String, lang: String, speaker: Int, rate: Float) {
        val g = gen.incrementAndGet()
        stopTrack()
        exec.execute {
            if (g != gen.get()) return@execute
            val m = NeuralRuntime.acquire(app)
            try {
                if (m == null) return@execute
                val l = lang.substringBefore('-')
                val t = runCatching { SpeechNormalizer.normalize(text, l, null) }.getOrDefault(text)
                val x = m.synthesize(t, l, speaker, NeuralVoiceEngine.DEFAULT_STEPS, rate, isCancelled = { g != gen.get() }) ?: return@execute
                val y = Pcm.trim(x, m.sampleRate)
                if (y.isEmpty() || g != gen.get()) return@execute
                Pcm.normalize(y, m.sampleRate)
                val pcm = Pcm.toPcm16(y)
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(m.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                at.write(pcm, 0, pcm.size)
                track = at
                at.play()
            } catch (e: Throwable) {
                Log.w("MashaVoice", "prueba de voz", e)
            } finally {
                if (m != null) NeuralRuntime.release()
            }
        }
    }

    fun stop() {
        gen.incrementAndGet()
        stopTrack()
    }

    private fun stopTrack() {
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }
}
