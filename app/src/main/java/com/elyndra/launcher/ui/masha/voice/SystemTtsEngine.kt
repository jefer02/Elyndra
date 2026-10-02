package com.elyndra.launcher.ui.masha.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * La voz del sistema (Google TTS, Samsung…): el repuesto de la voz natural y la
 * única en móviles sin ella.
 *
 * - **Sintetiza** a un fichero desechable (`synthesizeToFile`) y el PCM llega por
 *   `onAudioAvailable` a trozos, sin esperar al fichero. Si el motor no da trozos,
 *   se lee el WAV al terminar; si ni sintetiza a fichero, habla con `speak()` y la
 *   boca va con el reloj de pared ([SynthesisCallback.onSelfPlayback]).
 * - **Tono 1.0:** con otro tono Google abandona su voz neuronal ("seanet") por la
 *   antigua ("lstm"), que suena bastante peor (comprobado en logcat).
 * - **Voz femenina por nombre:** la API no dice el género y todas las voces de Google
 *   declaran la misma calidad; se prefieren las femeninas conocidas ([FEMALE]).
 */
class SystemTtsEngine(context: Context) : VoiceEngine {

    override val id = "system"

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = HandlerThread("masha-tts-io").apply { start() }
    private val ioHandler = Handler(io.looper)

    @Volatile private var ok = false
    @Volatile private var released = false
    private var lang: String? = null
    @Volatile private var info: VoiceInfo? = null
    @Volatile private var rate = 1f

    /** Aviso (hilo principal) cuando el motor queda listo o cambia de voz. */
    var onChanged: (() -> Unit)? = null

    private class Req(val req: SynthesisRequest, val cb: SynthesisCallback) {
        var file: File? = null
        var pfd: ParcelFileDescriptor? = null
        @Volatile var chunks = 0
        @Volatile var bytes = 0L
        @Volatile var ended = false
        @Volatile var speakMode = false
    }

    private val reqs = ConcurrentHashMap<String, Req>()

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        main.post {
            if (released) return@post
            ok = status == TextToSpeech.SUCCESS
            if (ok) lang?.let { apply(it) }
            onChanged?.invoke()
        }
    }

    init {
        // Solo cuenta para speak() (repuesto); la pista propia lleva sus atributos en SpeechOutput.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                val r = reqs[utteranceId] ?: return
                if (r.speakMode) r.cb.onPlaybackStart(utteranceId)
            }

            override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                val r = reqs[utteranceId] ?: return
                r.cb.onBegin(utteranceId, sampleRateInHz, audioFormat, channelCount)
            }

            override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                val r = reqs[utteranceId] ?: return
                r.chunks++
                r.bytes += audio.size
                r.cb.onAudio(utteranceId, audio)
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                reqs[utteranceId]?.cb?.onRange(utteranceId, start, end, frame)
            }

            override fun onDone(utteranceId: String) = ended(utteranceId, true)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = ended(utteranceId, false)

            override fun onError(utteranceId: String, errorCode: Int) = ended(utteranceId, false)

            override fun onStop(utteranceId: String, interrupted: Boolean) = ended(utteranceId, false)
        })
    }

    override val ready: Boolean get() = ok && !released

    override fun setLanguage(tag: String) {
        lang = tag
        if (ok) apply(tag)
    }

    override fun voice(): VoiceInfo? = if (ready) info else null

    /** Velocidad de habla (1 = normal): se aplica a las frases siguientes. */
    fun setRate(r: Float) {
        rate = r
        if (ok) tts.setSpeechRate(r)
    }

    private fun apply(tag: String) {
        val locale = Locale.forLanguageTag(tag)
        runCatching { tts.language = locale }
        pickVoice(tts.voices.orEmpty(), locale)?.let { runCatching { tts.voice = it } }
        tts.setPitch(PITCH)
        tts.setSpeechRate(rate)
        val v = runCatching { tts.voice }.getOrNull()
        info = VoiceInfo(id, "system:${v?.name ?: tag}", v?.locale ?: locale, wantsNormalizedText = false)
        warmUp()
    }

    /**
     * El motor carga la voz la primera vez que sintetiza (~1 s medido en un Motorola
     * edge 50 fusion): una palabra a un fichero desechable al elegir idioma, para que
     * la primera frase de Masha no pague esa espera. El listener ignora este id.
     */
    override fun warmUp() {
        if (!ok) return
        val f = File(dir(), "warm.wav")
        @Suppress("DEPRECATION")
        runCatching { tts.synthesizeToFile("a", Bundle(), f, "warm") }
    }

    /**
     * Una sola síntesis a fichero en marcha: con varias en cola, las que Google ya tiene en
     * caché llegan de golpe, el binder se satura y se pierden trozos de PCM sin aviso (visto en
     * el emulador: `FAILED BINDER TRANSACTION`). Sintetizar una frase le lleva ~100 ms, así que
     * la siguiente sigue preparándose mientras suena la anterior.
     */
    private val pending = java.util.ArrayDeque<Req>()
    private var inFlight: Req? = null
    private val queueLock = Any()

    override fun synthesize(req: SynthesisRequest, cb: SynthesisCallback): Boolean {
        if (!ready) return false
        val r = Req(req, cb)
        reqs[req.id] = r
        synchronized(queueLock) {
            if (inFlight != null) {
                pending.addLast(r)
                return true
            }
            inFlight = r
        }
        return start(r).also { ok -> if (!ok) next(r) }
    }

    /** Manda [r] al motor: a fichero o, si no lo acepta, con `speak()` (el motor reproduce). */
    private fun start(r: Req): Boolean {
        val req = r.req
        if (req.rate != 1f) tts.setSpeechRate(rate * req.rate)
        val params = Bundle()
        val result = runCatching { toFile(r, params) }.getOrDefault(TextToSpeech.ERROR)
        var ok = true
        if (result != TextToSpeech.SUCCESS) {
            // Repuesto: el motor reproduce (como antes).
            closeFile(r)
            r.speakMode = true
            r.cb.onSelfPlayback(req.id)
            if (tts.speak(req.text, TextToSpeech.QUEUE_ADD, params, req.id) != TextToSpeech.SUCCESS) {
                reqs.remove(req.id)
                ok = false
            }
        }
        if (req.rate != 1f) tts.setSpeechRate(rate)
        return ok
    }

    /** [done] terminó (o no pudo empezar): la siguiente de la cola. */
    private fun next(done: Req) {
        var cur = done
        while (true) {
            val n = synchronized(queueLock) {
                if (inFlight !== cur) return
                pending.pollFirst().also { inFlight = it }
            } ?: return
            cur = n
            if (!reqs.containsKey(n.req.id)) continue // parada entretanto
            if (start(n)) return
            n.cb.onEnd(n.req.id, false)
        }
    }

    private fun dir() = File(app.cacheDir, "masha_tts").apply { mkdirs() }

    private fun toFile(r: Req, params: Bundle): Int {
        val f = File(dir(), "${r.req.id}.wav")
        r.file = f
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
            r.pfd = pfd
            tts.synthesizeToFile(r.req.text, params, pfd, r.req.id)
        } else {
            @Suppress("DEPRECATION")
            tts.synthesizeToFile(r.req.text, params, f, r.req.id)
        }
    }

    /** Fin de la síntesis (cualquier hilo del motor). */
    private fun ended(id: String, success: Boolean) {
        val r = reqs[id] ?: return
        if (r.ended) return
        r.ended = true
        if (r.speakMode) {
            reqs.remove(id)
            r.cb.onEnd(id, success)
            next(r)
            return
        }
        // El fichero ya está escrito: la siguiente frase puede empezar mientras se cierra este.
        next(r)
        ioHandler.post {
            closeFile(r)
            // Motores que no dan onAudioAvailable al sintetizar a fichero: se lee el WAV.
            if (success && r.chunks == 0 && reqs.containsKey(id)) readWav(r)
            else if (success) checkLoss(r)
            r.file?.delete()
            if (reqs.remove(id) != null) r.cb.onEnd(id, success)
        }
    }

    private fun closeFile(r: Req) {
        runCatching { r.pfd?.close() }
        r.pfd = null
    }

    /** Lee el PCM de un WAV (cabecera RIFF) y lo pasa como si hubiera llegado a trozos. */
    private fun readWav(r: Req) {
        val f = r.file ?: return
        val id = r.req.id
        runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val head = ByteArray(12)
                raf.readFully(head)
                if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return
                var sr = 0; var ch = 1; var bits = 16
                val hdr = ByteArray(8)
                while (raf.filePointer + 8 <= raf.length()) {
                    raf.readFully(hdr)
                    val tag = String(hdr, 0, 4)
                    val size = le32(hdr, 4)
                    if (tag == "fmt ") {
                        val fmt = ByteArray(size)
                        raf.readFully(fmt)
                        ch = le16(fmt, 2); sr = le32(fmt, 4); bits = le16(fmt, 14)
                    } else if (tag == "data") {
                        val len = minOf(size.toLong().takeIf { it > 0 } ?: Long.MAX_VALUE, raf.length() - raf.filePointer).toInt()
                        val data = ByteArray(len)
                        raf.readFully(data)
                        val enc = when (bits) { 8 -> AudioFormat.ENCODING_PCM_8BIT; 32 -> AudioFormat.ENCODING_PCM_FLOAT; else -> AudioFormat.ENCODING_PCM_16BIT }
                        r.cb.onBegin(id, sr, enc, ch)
                        val step = sr / 10 * ch * (bits / 8)
                        var o = 0
                        while (o < data.size) {
                            r.cb.onAudio(id, data.copyOfRange(o, minOf(data.size, o + step)))
                            o += step
                        }
                        r.chunks = 1
                        Log.i(TAG, "$id: el motor no dio PCM al sintetizar; leído del WAV ($len B)")
                        return
                    } else {
                        raf.seek(raf.filePointer + size)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "WAV $id", it) }
    }

    /**
     * ¿Llegó todo el PCM? Con frases ya en la caché de Google, el motor lo manda de golpe y,
     * si el binder se satura, pierde trozos sin avisar (visto en el emulador). El fichero sí
     * está completo: aquí solo se registra (con una síntesis a la vez ya no debería pasar).
     */
    private fun checkLoss(r: Req) {
        val f = r.file ?: return
        val data = f.length() - 44
        if (data > 0 && r.bytes < data * 98 / 100) {
            Log.w(TAG, "${r.req.id}: el motor perdió PCM por el camino (${r.bytes} de ~$data B)")
        }
    }

    override fun stop() {
        if (released) return
        synchronized(queueLock) {
            pending.clear()
            inFlight = null
        }
        reqs.clear()
        runCatching { tts.stop() }
        ioHandler.post { runCatching { dir().listFiles()?.forEach { if (it.name != "warm.wav") it.delete() } } }
    }

    override fun release() {
        stop()
        released = true
        runCatching { tts.shutdown() }
        io.quitSafely()
        runCatching { dir().listFiles()?.forEach { it.delete() } }
    }

    companion object {
        private const val TAG = "MashaVoice"

        /** Tono neutro: cualquier otro hace que Google use su voz antigua (ver arriba). */
        const val PITCH = 1.0f

        /**
         * Voces femeninas conocidas de Google TTS, por orden de preferencia. es/en
         * comprobadas por su tono medio en el emulador (API 35); el resto, por
         * referencia de la comunidad: pendiente de confirmar en un móvil real.
         */
        val FEMALE = listOf(
            "es-us-x-sfb", "es-us-x-esc", "es-es-x-eea", "es-es-x-eec", "es-es-x-eee",
            "en-us-x-iog", "en-us-x-iob", "en-us-x-tpf", "en-us-x-sfg", "en-gb-x-gba", "en-gb-x-gbc", "en-gb-x-gbg",
            "pt-br-x-afs", "pt-br-x-pte", "pt-pt-x-jfb", "fr-fr-x-frc", "fr-fr-x-fra", "de-de-x-dea", "de-de-x-nfh", "ja-jp-x-jab", "ja-jp-x-htm",
        )

        /**
         * La mejor voz del idioma: instalada, local, femenina conocida y, a igualdad,
         * de más calidad. Null = la del motor.
         */
        fun pickVoice(voices: Collection<Voice>, locale: Locale): Voice? = runCatching {
            voices
                .filter { it.locale.language == locale.language }
                .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
                .maxByOrNull { v -> score(v.name, v.quality, v.latency, v.isNetworkConnectionRequired, v.locale, locale, v.features.orEmpty()) }
        }.getOrNull()

        internal fun score(name: String, quality: Int, latency: Int, network: Boolean, voiceLocale: Locale, locale: Locale, features: Set<String>): Int {
            var score = quality
            if (!network) score += 150
            if (locale.country.isNotEmpty() && voiceLocale.country == locale.country) score += 60
            val n = name.lowercase()
            val known = FEMALE.indexOfFirst { n.startsWith(it) }
            if (known >= 0) score += 300 - known
            else if ("female" in n || features.any { "female" in it.lowercase() }) score += 220
            return score - latency / 10
        }

        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
    }
}
