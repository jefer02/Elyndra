package com.elyndra.launcher.ui.masha.voice

import android.content.Context
import android.media.AudioFormat
import android.os.SystemClock
import android.util.Log
import com.elyndra.launcher.ui.masha.voice.supertonic.SupertonicModel
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * La voz natural de Masha: Supertonic 3 en el propio móvil (ONNX Runtime), sin
 * red ni coste. Una voz femenina para los seis idiomas: Masha suena igual en todos.
 *
 * - Un hilo propio sintetiza las frases en orden; la siguiente se prepara mientras
 *   suena la anterior (la salida las escribe seguidas).
 * - Las frases largas se parten por comas para que la primera parte suene antes;
 *   las partes van seguidas bajo la misma frase (un solo onBegin/onEnd).
 * - Los títulos en inglés dentro de una frase en otro idioma se leen con la
 *   pronunciación inglesa de la misma voz ([LanguageSpans]).
 * - Cada parte: se recorta el silencio de cabeza (~0,5 s del modelo), se iguala la
 *   sonoridad con la voz del sistema y sale en PCM de 16 bits a 44,1 kHz.
 * - Si algo falla antes de dar audio, la frase acaba con onEnd(ok = false) y el
 *   enrutador ([VoiceRouter]) la repite con la voz del sistema.
 */
class NeuralVoiceEngine(context: Context) : VoiceEngine {

    override val id = "neural"

    private val app = context.applicationContext

    @Volatile private var lang = "en"
    @Volatile var speaker = 0
    @Volatile var steps = DEFAULT_STEPS
    @Volatile private var gen = 0
    @Volatile private var released = false

    private class Task(val req: SynthesisRequest?, val cb: SynthesisCallback?, val gen: Int, val lang: String, val warm: Boolean = false)

    private val queue = LinkedBlockingDeque<Task>()
    private var model: SupertonicModel? = null // hilo del motor

    private val thread = Thread({ loop() }, "masha-voice-neural").apply {
        priority = Thread.NORM_PRIORITY + 1
        isDaemon = true
        start()
    }

    override val ready: Boolean get() = !released && NeuralRuntime.usable(app)

    override fun setLanguage(tag: String) {
        lang = tag.substringBefore('-').lowercase()
    }

    override fun voice(): VoiceInfo? {
        if (!ready || lang !in LANGS) return null
        return VoiceInfo(id, "supertonic3:$speaker", localeFor(lang), wantsNormalizedText = true)
    }

    override fun warmUp() {
        if (ready) queue.offer(Task(null, null, gen, lang, warm = true))
    }

    override fun synthesize(req: SynthesisRequest, cb: SynthesisCallback): Boolean {
        if (released) return false
        queue.offer(Task(req, cb, gen, lang))
        return true
    }

    override fun stop() {
        gen++
        // Lo pendiente se cierra con onEnd(false) desde el hilo del motor, en orden.
    }

    override fun release() {
        stop()
        released = true
        queue.clear()
        thread.interrupt()
    }

    /* ── hilo del motor ── */

    private fun loop() {
        try {
            while (!released) {
                val t = queue.poll(1, TimeUnit.SECONDS) ?: continue
                if (t.warm) { warm(t); continue }
                val req = t.req ?: continue
                val cb = t.cb ?: continue
                if (t.gen != gen) { cb.onEnd(req.id, false); continue }
                run(t, req, cb)
            }
        } catch (_: InterruptedException) {
        } finally {
            if (model != null) NeuralRuntime.release()
            model = null
        }
    }

    private fun ensureModel(): SupertonicModel? {
        model?.let { return it }
        model = NeuralRuntime.acquire(app)
        if (model == null) NeuralRuntime.release()
        return model
    }

    /** Carga el modelo y una síntesis corta desechable: la primera frase de verdad ya no paga ese coste. */
    private fun warm(t: Task) {
        val m = ensureModel() ?: return
        // Ya espera una frase de verdad: ella hace de calentamiento (no se paga dos veces).
        if (queue.any { !it.warm }) return
        val t0 = SystemClock.elapsedRealtime()
        val x = runCatching { m.synthesize(WARM[t.lang] ?: "Hi.", t.lang, speaker, steps) }.getOrNull() ?: return
        Log.i(TAG, "voz natural: calentamiento ${SystemClock.elapsedRealtime() - t0} ms (${x.size * 1000L / m.sampleRate} ms de audio)")
    }

    private fun run(t: Task, req: SynthesisRequest, cb: SynthesisCallback) {
        val m = ensureModel()
        if (m == null) { cb.onEnd(req.id, false); return }
        val sr = m.sampleRate
        val cancelled = { t.gen != gen || released }
        var begun = false
        var synthMs = 0L
        var audioSamples = 0L
        val t0 = SystemClock.elapsedRealtime()
        try {
            for ((i, part) in parts(req.text, growth()).withIndex()) {
                val ts = SystemClock.elapsedRealtime()
                val x = synthPart(m, part, t.lang, req.rate, cancelled) ?: break
                synthMs += SystemClock.elapsedRealtime() - ts
                if (cancelled()) break
                val y = Pcm.trim(x, sr, keepTailMs = tailMs(part))
                if (y.isEmpty()) continue
                Pcm.normalize(y, sr)
                if (!begun) {
                    cb.onBegin(req.id, sr, AudioFormat.ENCODING_PCM_16BIT, 1)
                    begun = true
                    Log.i(TAG, "${req.id}: primer audio neuronal a los ${SystemClock.elapsedRealtime() - t0} ms")
                }
                val step = sr / 10
                var o = 0
                while (o < y.size) {
                    if (cancelled()) break
                    val e = minOf(y.size, o + step)
                    cb.onAudio(req.id, Pcm.toPcm16(y, o, e))
                    o = e
                }
                audioSamples += y.size
                if (i == 0 && cancelled()) break
            }
        } catch (e: Throwable) {
            Log.w(TAG, "${req.id}: la voz natural falló", e)
        }
        val ok = begun && !cancelled()
        if (audioSamples > 0) {
            val rtf = synthMs / 1000f / (audioSamples.toFloat() / sr)
            Log.i(TAG, "synth ${req.id}: audio=${audioSamples * 1000 / sr} ms, síntesis=$synthMs ms, RTF=${"%.2f".format(rtf)}")
            // Solo frases de verdad (≥ 1 s): las cortas exageran el coste fijo.
            if (audioSamples >= sr) NeuralRuntime.recordRtf(app, rtf)
        }
        cb.onEnd(req.id, ok)
    }

    /**
     * Cuánto puede crecer cada parte respecto a la anterior sin atascarse: la siguiente se
     * sintetiza mientras suena la actual, así que ~1/RTF (con margen). Sin medida, 2,2.
     */
    private fun growth(): Float {
        val rtf = NeuralRuntime.measuredRtf(app)
        return if (rtf.isNaN()) GROWTH else (0.7f / rtf).coerceIn(1.6f, 3.0f)
    }

    /** Una parte, con los tramos en inglés leídos en inglés (misma voz). */
    private fun synthPart(m: SupertonicModel, part: String, lang: String, rate: Float, cancelled: () -> Boolean): FloatArray? {
        val speed = (rate * SPEED).coerceIn(0.7f, 1.5f)
        val text = NAME[lang]?.let { part.replace(MASHA, it) } ?: part
        val spans = if (lang in SPAN_LANGS) {
            val dict = NeuralRuntime.englishDict(app)
            if (dict != null) LanguageSpans.split(text, lang) { w -> dict.lookup(w.lowercase()) != null } else null
        } else {
            null
        }
        if (spans == null || spans.size <= 1) {
            return m.synthesize(text, lang, speaker, steps, speed, isCancelled = cancelled)
        }
        return m.synthesizeMixed(spans.map { it.text to it.lang }, speaker, steps, speed, isCancelled = cancelled)
    }

    companion object {
        private const val TAG = "MashaVoice"
        const val DEFAULT_STEPS = 5

        /** Velocidad base: Supertonic va algo deprisa para el tono tranquilo de Masha. */
        private const val SPEED = 1.0f

        /** Idiomas de la app que habla la voz natural. */
        val LANGS = setOf("es", "en", "pt", "fr", "de", "ja")
        private val SPAN_LANGS = setOf("es", "pt", "fr", "de")

        private val WARM = mapOf("es" to "Hola.", "en" to "Hi.", "pt" to "Olá.", "fr" to "Salut.", "de" to "Hallo.", "ja" to "こんにちは。")

        /** El G2P de los labios distingue variantes (seseo, etc.): las de la voz natural. */
        fun localeFor(lang: String): Locale = when (lang) {
            "es" -> Locale.forLanguageTag("es-MX")
            "en" -> Locale.US
            "pt" -> Locale.forLanguageTag("pt-BR")
            "fr" -> Locale.FRANCE
            "de" -> Locale.GERMANY
            "ja" -> Locale.JAPAN
            else -> Locale.forLanguageTag(lang)
        }

        /** "Masha" escrito como se pronuncia en cada idioma (en francés se oía "Machin"). */
        private val MASHA = Regex("\\bMasha\\b")
        private val NAME = mapOf("fr" to "Macha", "de" to "Mascha", "pt" to "Macha")

        /**
         * Silencio que se deja tras cada parte: la pausa natural según la puntuación (la
         * salida escribe las frases seguidas). Al final de frase da tiempo a que la boca
         * se cierre (una pausa ≥ 80 ms ya la cierra) y suena menos atropellado.
         */
        fun tailMs(part: String): Int {
            val c = part.trimEnd().trimEnd('"', '\'', '»', '”', ')', '」', '』').lastOrNull() ?: return 110
            return when (c) {
                '.', '!', '?', '…', '。', '！', '？' -> 300
                ',', ';', ':', '、', '，', '；', '：' -> 150
                else -> 110
            }
        }

        /**
         * Parte una frase para sintetizarla por trozos (van seguidos bajo la misma frase).
         *
         * - Solo por signos (coma, punto y coma, dos puntos, y también ¡!¿? y punto dentro
         *   del texto): nunca a mitad de una cláusula.
         * - La primera parte, corta (≤ [FIRST] caracteres): el primer audio llega antes.
         * - Cada parte, como mucho [growth] veces la anterior: se sintetiza mientras suena
         *   la anterior sin atascos (0,7/RTF del móvil, con margen para la carga). Si no hay corte así, no se parte.
         * - Frases de hasta [SHORT] caracteres, enteras.
         */
        fun parts(text: String, growth: Float = GROWTH): List<String> {
            val t = text.trim()
            if (t.length <= SHORT) return listOf(t)
            // Tras un signo de final de frase ("¡Claro que sí! …") vale una parte corta: se
            // entona completa. Tras una coma, no (se cortaría la cláusula a medias).
            val bounds = CUT.findAll(t)
                .filter { m -> m.range.last + 1 >= (if (m.value.trim().last() in ENDS) MIN_SENTENCE else MIN_PART) }
                .map { it.range.last + 1 }
                .filter { t.length - it >= MIN_TAIL }
                .toMutableList().apply { add(t.length) }
            fun next(start: Int, prev: Int): Int {
                val fit = bounds.filter { it > start && it - start <= minOf(REST, (prev * growth).toInt()) }
                return fit.lastOrNull() ?: bounds.first { it > start }
            }
            // Primera parte: el corte más corto cuyo trozo siguiente cabe en GROWTH veces.
            val first = bounds.firstOrNull { c ->
                c < t.length && c <= FIRST && run {
                    val nb = bounds.first { it > c }
                    nb - c <= c * growth
                }
            }
            val out = ArrayList<String>()
            var start = 0
            var prev: Int
            if (first != null) {
                out += t.substring(0, first)
                start = first
                prev = first
            } else {
                prev = REST
            }
            while (start < t.length) {
                val end = next(start, prev)
                out += t.substring(start, end)
                prev = end - start
                start = end
            }
            return out.map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }.ifEmpty { listOf(t) }
        }

        private const val SHORT = 45
        private const val FIRST = 70
        private const val REST = 200
        private const val MIN_PART = 20
        private const val MIN_SENTENCE = 8
        private const val MIN_TAIL = 25
        private const val ENDS = ".!?…。！？\"'»”)"
        private const val GROWTH = 2.2f
        private val CUT = Regex("[,;:—–.!?…]+[\"'»”)]*\\s+|[、，；：。！？]")
    }
}
