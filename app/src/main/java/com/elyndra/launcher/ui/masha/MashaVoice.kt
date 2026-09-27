package com.elyndra.launcher.ui.masha

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * La voz de Masha (texto a voz del sistema).
 *
 * - Habla **mientras** llega la respuesta: cada frase completa se pone en cola
 *   en cuanto aparece, sin esperar al final del streaming.
 * - Elige la mejor voz instalada del idioma de la app (local antes que en
 *   red, más calidad antes que menos) con un tono algo más alto.
 * - Avisa del inicio de cada palabra a [LipSync] y del estado de habla a
 *   [MashaPresence] — siempre desde el hilo principal.
 *
 * La voz es opcional: sin motor TTS, o con la voz apagada en su panel, Masha
 * sigue escribiendo igual.
 */
class MashaVoice(context: Context, private val presence: MashaPresence) {

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var released = false
    private var lang: String? = null
    private val texts = HashMap<String, String>()
    private var pending = 0
    private var seq = 0

    /** Mensaje que se está leyendo y hasta dónde se ha puesto en cola. */
    private var feedingId: Long = -1
    private var queuedUpTo = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            if (released) return@post
            ready = status == TextToSpeech.SUCCESS
            if (ready) lang?.let { applyLanguage(it) }
        }
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                main.post {
                    if (!presence.speaking) presence.lipSync.onSpeechStart(System.nanoTime())
                    presence.speaking = true
                }
            }

            override fun onDone(utteranceId: String) {
                main.post { finished(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                main.post { finished(utteranceId) }
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                main.post { finished(utteranceId) }
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                main.post { finished(utteranceId) }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                val now = System.nanoTime()
                main.post {
                    val text = texts[utteranceId] ?: return@post
                    if (start in 0..end && end <= text.length) presence.lipSync.onWord(text.substring(start, end), now)
                }
            }
        })
    }

    fun setLanguage(tag: String) {
        lang = tag
        if (ready) applyLanguage(tag)
    }

    private fun applyLanguage(tag: String) {
        val locale = Locale.forLanguageTag(tag)
        runCatching { tts.language = locale }
        pickVoice(locale)?.let { runCatching { tts.voice = it } }
        tts.setPitch(PITCH)
        tts.setSpeechRate(RATE)
        presence.lipSync.setRate(RATE)
        presence.lipSync.setLanguage(tag)
    }

    /**
     * La mejor voz del idioma: instalada, local, de más calidad y, si el motor
     * lo dice en el nombre, femenina (Masha es ella). Null = la del motor.
     */
    private fun pickVoice(locale: Locale): Voice? = runCatching {
        tts.voices.orEmpty()
            .filter { it.locale.language == locale.language }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
            .maxByOrNull { v ->
                var score = v.quality
                if (!v.isNetworkConnectionRequired) score += 150
                if (v.locale.country == locale.country) score += 60
                if ("female" in v.name.lowercase() || "#female" in v.features.orEmpty().joinToString()) score += 220
                score - v.latency / 10
            }
    }.getOrNull()

    /**
     * Lee lo nuevo del mensaje [id] (texto completo hasta ahora). Mientras
     * llega ([final] = false) solo pone en cola frases terminadas; al final,
     * el resto.
     */
    fun feed(id: Long, text: String, final: Boolean) {
        if (id != feedingId) {
            feedingId = id
            queuedUpTo = 0
        }
        if (queuedUpTo >= text.length) return
        val end = if (final) text.length else lastSentenceEnd(text, queuedUpTo)
        if (end <= queuedUpTo) return
        val chunk = speakable(text.substring(queuedUpTo, end))
        queuedUpTo = end
        if (chunk.isNotBlank()) say(chunk)
    }

    private fun say(text: String) {
        if (!ready || released) return
        val id = "masha-${seq++}"
        texts[id] = text
        pending++
        tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
    }

    /** Calla ya (y olvida lo que quedaba en cola). */
    fun stop() {
        if (released) return
        tts.stop()
        texts.clear()
        pending = 0
        feedingId = -1
        speakingOff()
    }

    /** El mensaje ya leído no se vuelve a leer (p. ej. al volver a la pantalla). */
    fun skip(id: Long, length: Int) {
        feedingId = id
        queuedUpTo = length
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        speakingOff()
    }

    private fun finished(id: String) {
        if (texts.remove(id) == null) return
        pending = (pending - 1).coerceAtLeast(0)
        if (pending == 0) speakingOff()
    }

    private fun speakingOff() {
        presence.speaking = false
        presence.lipSync.onSpeechEnd()
    }

    companion object {
        const val PITCH = 1.06f
        const val RATE = 1.0f

        private val SENTENCE_END = Regex("[.!?…。！？]+[\"'»”)]*(\\s|$)|\\n+")
        private val URL = Regex("https?://\\S+")
        private val MARKUP = Regex("[*_#`>|~▍•]+")
        private val EMOJI = Regex("[\\p{So}\\p{Sk}\\x{FE0F}\\x{200D}\\x{20E3}]")
        private val SPACES = Regex("\\s+")

        /** Hasta dónde hay frases terminadas a partir de [from] (0 = ninguna). */
        fun lastSentenceEnd(text: String, from: Int): Int {
            var end = from
            for (m in SENTENCE_END.findAll(text, from)) {
                // Frases muy cortas ("Vale.") se juntan con la siguiente: suena más natural.
                if (m.range.last + 1 - from >= MIN_CHUNK || end > from) end = m.range.last + 1
            }
            return end
        }

        /** Lo que se lee: sin enlaces, marcas de formato ni emojis. */
        fun speakable(s: String): String =
            s.replace(URL, " ").replace(MARKUP, " ").replace(EMOJI, "").replace(SPACES, " ").trim()

        private const val MIN_CHUNK = 18
    }
}

/**
 * La voz de Masha mientras la pantalla está en composición: se crea al entrar,
 * sigue el idioma de la app y se apaga (liberando el motor) al salir.
 */
@Composable
fun rememberMashaVoice(presence: MashaPresence, language: String, enabled: Boolean): MashaVoice {
    val context = LocalContext.current
    val voice = remember(presence) { MashaVoice(context, presence) }
    DisposableEffect(voice) {
        onDispose { voice.release() }
    }
    LaunchedEffect(voice, language) { voice.setLanguage(language) }
    LaunchedEffect(voice, enabled) { if (!enabled) voice.stop() }
    return voice
}
