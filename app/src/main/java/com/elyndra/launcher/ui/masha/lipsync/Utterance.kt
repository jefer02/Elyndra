package com.elyndra.launcher.ui.masha.lipsync

import android.media.AudioFormat

/**
 * Lo que se sabe de una frase mientras el motor la sintetiza: texto y
 * fonemas, PCM analizado y rangos de palabra (`onRangeStart`). [build] da las
 * curvas ([Track]) con lo que haya; se llama cada vez que llega algo nuevo.
 *
 * Modos:
 * - texto + rangos (primario);
 * - texto sin rangos → repuesto A (palabras sobre los tramos sonoros);
 * - sin G2P (japonés…) → repuesto B ([AudioOnly]).
 *
 * Solo lo usa el hilo de análisis de la voz (no es seguro entre hilos).
 */
class Utterance(val text: String, g2p: G2p?, val seed: Int) {

    val tokens: List<Token> = Tokenizer.tokens(text)
    val words: List<WordPhones> = if (g2p == null) emptyList() else tokens.map { t ->
        val ph = ArrayList<Phone>(t.text.length + 2)
        runCatching { g2p.word(t.text, ph) }
        if (ph.isEmpty()) G2p.generic(t.text, ph)
        WordPhones(t, ph)
    }
    val question = Tokenizer.isQuestion(text)
    val audioOnly: Boolean get() = words.isEmpty()

    var features: AudioFeatures? = null
        private set
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var channels = 1
    var done = false
        private set
    var rangesSeen = 0
        private set

    /** Último alineado (para aprender el perfil de vocales y el ritmo). */
    var segments: List<Seg> = emptyList()
        private set

    fun begin(sampleRate: Int, audioFormat: Int, channelCount: Int) {
        if (features == null || features!!.sampleRate != sampleRate) features = AudioFeatures(sampleRate)
        encoding = audioFormat
        channels = channelCount.coerceAtLeast(1)
    }

    fun audio(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        val f = features ?: return
        when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> f.pushPcm8(bytes, offset, length, channels)
            AudioFormat.ENCODING_PCM_FLOAT -> f.pushFloat(bytes, offset, length, channels)
            else -> f.pushPcm16(bytes, offset, length, channels)
        }
    }

    /** `onRangeStart(start, end, frame)`: la palabra del texto que cubre [start, end) empieza en la muestra [frame]. */
    fun range(start: Int, end: Int, frame: Int) {
        if (frame < 0) return
        val w = words.indexOfFirst { it.token.start < end && it.token.end > start }
        if (w < 0) return
        if (words[w].rangeSample < 0) {
            words[w].rangeSample = frame.toLong()
            rangesSeen++
        }
    }

    fun finish() {
        done = true
    }

    /** Curvas con lo que hay. [secPerWeight] = ritmo supuesto; [tail] = tramas extra tras el audio (cierre). */
    fun build(cfg: LipSyncConfig, profile: VowelProfile, secPerWeight: Float, tail: Int = 40): Track {
        val f = features ?: return Track.EMPTY
        val n = f.count
        if (n == 0) return Track.EMPTY
        val frames = n + if (done) tail else 0
        val data = FloatArray(frames * Ch.COUNT)
        val blinks = ArrayList<Int>()
        if (audioOnly) {
            AudioOnly.render(f, n, frames, cfg, profile, data)
        } else {
            val segs = Aligner.align(words, f, n, done, secPerWeight)
            segments = segs
            Coarticulator.render(segs, f, n, frames, cfg, words, question, seed, data, blinks)
        }
        return Track(frames, data, blinks.toIntArray(), n, done)
    }

    /** Ritmo real de esta frase (s por unidad de peso), o NaN si no se puede medir. */
    fun measuredSecPerWeight(): Float {
        val f = features ?: return Float.NaN
        if (!done || words.isEmpty()) return Float.NaN
        val thr = Aligner.voicedThreshold(f, f.count)
        var voiced = 0
        for (k in 0 until f.count) if (f.db[k] > thr) voiced++
        val w = words.sumOf { it.weight.toDouble() }.toFloat()
        return if (w > 0f && voiced > 10) voiced * Aligner.FRAME / w else Float.NaN
    }
}
