package com.elyndra.launcher.ui.masha.voice.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Supertonic 3 (export int8 de sherpa-onnx) sobre ONNX Runtime, sin sherpa ni espeak.
 *
 * Carga de [dir]: `duration_predictor`, `text_encoder`, `vector_estimator` y `vocoder`
 * (`*.int8.onnx`), `tts.json`, `unicode_indexer.bin` y `voice.bin` (estilos de las voces).
 *
 * Tubería por frase: texto → ids ([SupertonicText]) → duración → embedding de texto →
 * ruido gaussiano en el latente → `steps` pasos de flow matching (el modelo ya devuelve el
 * latente del paso siguiente) → vocoder → PCM mono float a [sampleRate] (44,1 kHz).
 *
 * Kotlin puro (sin `android.*`): la API `ai.onnxruntime` es la misma en Android y en la JVM,
 * así que se prueba en escritorio. Confinado a un hilo: [synthesize] y [synthesizeMixed] desde
 * el hilo del motor; [close] puede llegar desde otro (espera a que acabe la síntesis en curso).
 *
 * Portado de sherpa-onnx `offline-tts-supertonic-impl.cc` (Apache-2.0, © 2026 zengyw) y de
 * `py/helper.py` de Supertone (MIT, © 2025 Supertone Inc.). El modelo es OpenRAIL-M.
 *
 * @param threads hilos intra-op de cada sesión (por defecto min(4, núcleos)).
 * @param xnnpack prueba el proveedor XNNPACK (si la build de ORT no lo trae, se sigue en CPU).
 */
class SupertonicModel(
    dir: File,
    threads: Int = defaultThreads(),
    xnnpack: Boolean = false,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val lock = Any()
    @Volatile private var closed = false

    /** Frecuencia de muestreo del PCM (44100). */
    val sampleRate: Int
    private val baseChunk: Int
    private val chunkCompress: Int
    private val latentDim: Int

    /** Número de voces de `voice.bin` (10 en el export de sherpa). */
    val speakers: Int

    private val indexer: IntArray = SupertonicText.loadIndexer(File(dir, "unicode_indexer.bin"))

    // Estilos de todas las voces, planos: ttl [n, 50, 256] y dp [n, 8, 16].
    private val ttlStyle: FloatArray
    private val dpStyle: FloatArray
    private val ttlShape: LongArray
    private val dpShape: LongArray

    private val dp: OrtSession
    private val te: OrtSession
    private val ve: OrtSession
    private val vo: OrtSession

    init {
        val cfg = Json.parseToJsonElement(File(dir, "tts.json").readText()).jsonObject
        val ae = cfg.getValue("ae").jsonObject
        val ttl = cfg.getValue("ttl").jsonObject
        sampleRate = ae.getValue("sample_rate").jsonPrimitive.int
        baseChunk = ae.getValue("base_chunk_size").jsonPrimitive.int
        chunkCompress = ttl.getValue("chunk_compress_factor").jsonPrimitive.int
        latentDim = ttl.getValue("latent_dim").jsonPrimitive.int * chunkCompress

        val vb = ByteBuffer.wrap(File(dir, "voice.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val d = LongArray(6) { vb.getLong() }
        require(d.all { it > 0 } && d[0] == d[3]) { "voice.bin inválido" }
        val nTtl = (d[0] * d[1] * d[2]).toInt()
        val nDp = (d[3] * d[4] * d[5]).toInt()
        require(vb.remaining() == (nTtl + nDp) * 4) { "voice.bin: tamaño inesperado" }
        ttlStyle = FloatArray(nTtl).also { vb.asFloatBuffer().get(it) }
        vb.position(vb.position() + nTtl * 4)
        dpStyle = FloatArray(nDp).also { vb.asFloatBuffer().get(it) }
        speakers = d[0].toInt()
        ttlShape = longArrayOf(1, d[1], d[2])
        dpShape = longArrayOf(1, d[4], d[5])

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads.coerceAtLeast(1))
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
            if (xnnpack) {
                try { addXnnpack(mapOf("intra_op_num_threads" to threads.toString())) } catch (_: OrtException) {}
            }
        }
        val opened = ArrayList<OrtSession>(4)
        try {
            for (name in listOf("duration_predictor", "text_encoder", "vector_estimator", "vocoder")) {
                opened += env.createSession(File(dir, "$name.int8.onnx").path, opts)
            }
        } catch (e: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw e
        } finally {
            opts.close()
        }
        dp = opened[0]; te = opened[1]; ve = opened[2]; vo = opened[3]
    }

    /**
     * Sintetiza UNA frase en [lang] con la voz [sid]. Devuelve PCM mono float a [sampleRate]
     * (sin normalizar), vacío si no hay nada que decir, o null si [isCancelled] cortó (se mira
     * entre pasos de flow matching y antes del vocoder). [seed] fija el ruido (pruebas); sin él,
     * ruido aleatorio como la referencia. Si el texto pasa de [SupertonicText.maxLen] se parte en
     * trozos unidos con 0,3 s de silencio, como sherpa. Las frases muy cortas (≤ [SHORT_LETTERS]
     * letras, p. ej. "Okay, done.") se alargan un 10 %: a velocidad 1 el modelo se come palabras.
     */
    fun synthesize(
        text: String,
        lang: String,
        sid: Int,
        steps: Int = 5,
        speed: Float = 1f,
        seed: Long? = null,
        isCancelled: () -> Boolean = { false },
    ): FloatArray? = synchronized(lock) {
        check(!closed) { "SupertonicModel cerrado" }
        val noise = Noise(seed ?: randomSeed())
        val chunks = SupertonicText.chunk(text, SupertonicText.maxLen(lang))
        val parts = ArrayList<FloatArray>(chunks.size)
        for (c in chunks) {
            val ids = SupertonicText.encode(c, lang, indexer)
            if (ids.isEmpty()) continue
            parts += run(ids, sid, steps, shortSpeed(c, speed), noise, isCancelled) ?: return null
        }
        join(parts, (0.3f * sampleRate).toInt())
    }

    /**
     * Frase con tramos en varios idiomas, en orden: `[("Si te gustó ", "es"), ("Hollow Knight",
     * "en"), (", tienes que probar ", "es"), …]`. Las etiquetas mezcladas en una sola llamada no
     * funcionan (el modelo balbucea), así que cada tramo se sintetiza aparte y se pega recortando
     * su silencio a ~20 ms en las juntas (el principio del primero y el final del último, intactos).
     * Tramos seguidos del mismo idioma se juntan; los que son solo puntuación van al anterior.
     */
    fun synthesizeMixed(
        segments: List<Pair<String, String>>,
        sid: Int,
        steps: Int = 5,
        speed: Float = 1f,
        seed: Long? = null,
        isCancelled: () -> Boolean = { false },
    ): FloatArray? = synchronized(lock) {
        check(!closed) { "SupertonicModel cerrado" }
        val merged = mergeSegments(segments)
        if (merged.isEmpty()) return FloatArray(0)
        val noise = Noise(seed ?: randomSeed())
        val sp = shortSpeed(merged.joinToString(" ") { it.first }, speed)
        val parts = ArrayList<FloatArray>(merged.size)
        for ((i, seg) in merged.withIndex()) {
            val last = i == merged.lastIndex
            val ids = SupertonicText.encode(seg.first, seg.second, indexer, addPeriod = last)
            if (ids.isEmpty()) continue
            parts += run(ids, sid, steps, sp, noise, isCancelled) ?: return null
        }
        if (parts.size <= 1) return parts.firstOrNull() ?: FloatArray(0)
        val keep = (0.02f * sampleRate).toInt()
        for (i in parts.indices) parts[i] = trimEdges(parts[i], keep, lead = i > 0, tail = i < parts.lastIndex)
        join(parts, 0)
    }

    /** Ids ya codificados → PCM (para pruebas de paridad y para quien quiera su propio texto). */
    internal fun synthesizeIds(
        ids: LongArray, sid: Int, steps: Int = 5, speed: Float = 1f, seed: Long,
        isCancelled: () -> Boolean = { false },
    ): FloatArray? = synchronized(lock) {
        check(!closed) { "SupertonicModel cerrado" }
        run(ids, sid, steps, speed, Noise(seed), isCancelled)
    }

    /** Duración (s) que predice el modelo para unos ids, sin sintetizar. */
    internal fun predictDuration(ids: LongArray, sid: Int): Float = synchronized(lock) {
        check(!closed) { "SupertonicModel cerrado" }
        val s = sid.coerceIn(0, speakers - 1)
        OnnxTensor.createTensor(env, direct(ids), longArrayOf(1, ids.size.toLong())).use { idsT ->
            OnnxTensor.createTensor(env, ones(ids.size), longArrayOf(1, 1, ids.size.toLong())).use { maskT ->
                OnnxTensor.createTensor(env, slice(dpStyle, s), dpShape).use { dpT ->
                    dp.run(mapOf("text_ids" to idsT, "style_dp" to dpT, "text_mask" to maskT)).use {
                        (it[0] as OnnxTensor).floatBuffer.get(0)
                    }
                }
            }
        }
    }

    private fun run(
        ids: LongArray, sid: Int, steps: Int, speed: Float, noise: Noise, isCancelled: () -> Boolean,
    ): FloatArray? {
        require(steps > 0 && speed > 0f)
        if (isCancelled()) return null
        val s = sid.coerceIn(0, speakers - 1)
        val t = ids.size.toLong()
        val open = ArrayList<AutoCloseable>(12)
        try {
            val idsT = OnnxTensor.createTensor(env, direct(ids), longArrayOf(1, t)).also { open += it }
            val maskT = OnnxTensor.createTensor(env, ones(ids.size), longArrayOf(1, 1, t)).also { open += it }
            val dpT = OnnxTensor.createTensor(env, slice(dpStyle, s), dpShape).also { open += it }
            val ttlT = OnnxTensor.createTensor(env, slice(ttlStyle, s), ttlShape).also { open += it }

            // Duración (s) → muestras → longitud del latente (aritmética float como la referencia).
            var dur = dp.run(mapOf("text_ids" to idsT, "style_dp" to dpT, "text_mask" to maskT))
                .use { (it[0] as OnnxTensor).floatBuffer.get(0) }
            if (speed != 1f) dur = max(dur / speed, MIN_DURATION)
            val wavLenF = dur * sampleRate
            val wavLen = max(wavLenF.toLong(), 1L)
            val chunk = baseChunk * chunkCompress
            val latentLen = min(((wavLenF + chunk - 1) / chunk).toInt(), MAX_LATENT_LEN).coerceAtLeast(1)
            val validLen = min(((wavLen + chunk - 1) / chunk).toInt(), latentLen)

            val textEmb = te.run(mapOf("text_ids" to idsT, "style_ttl" to ttlT, "text_mask" to maskT)).also { open += it }
            val embT = textEmb[0] as OnnxTensor
            if (isCancelled()) return null

            // Ruido N(0,1) enmascarado más allá de la duración.
            val n = latentDim * latentLen
            val xt = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (dIdx in 0 until latentDim) for (k in 0 until latentLen) {
                val z = noise.next()
                xt.put(dIdx * latentLen + k, if (k < validLen) z else 0f)
            }
            val lmask = ByteBuffer.allocateDirect(latentLen * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (k in 0 until latentLen) lmask.put(k, if (k < validLen) 1f else 0f)
            val latentShape = longArrayOf(1, latentDim.toLong(), latentLen.toLong())
            val lmaskT = OnnxTensor.createTensor(env, lmask, longArrayOf(1, 1, latentLen.toLong())).also { open += it }
            val totalT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(steps.toFloat())), longArrayOf(1)).also { open += it }
            var cur: OnnxTensor = OnnxTensor.createTensor(env, xt, latentShape).also { open += it }
            var prev: OrtSession.Result? = null
            try {
                for (step in 0 until steps) {
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1)).use { stepT ->
                        val r = ve.run(mapOf(
                            "noisy_latent" to cur, "text_emb" to embT, "style_ttl" to ttlT,
                            "latent_mask" to lmaskT, "text_mask" to maskT,
                            "current_step" to stepT, "total_step" to totalT,
                        ))
                        prev?.close()
                        prev = r
                        cur = r[0] as OnnxTensor // la salida del paso es la entrada del siguiente, sin copiar
                    }
                    if (isCancelled()) return null
                }
                val wav = vo.run(mapOf("latent" to cur)).use { (it[0] as OnnxTensor).floatBuffer }
                val len = min(wavLen.toInt(), wav.remaining())
                return FloatArray(len).also { wav.get(it, 0, len) }
            } finally {
                prev?.close()
            }
        } finally {
            for (i in open.indices.reversed()) runCatching { open[i].close() }
        }
    }

    private fun slice(src: FloatArray, sid: Int): FloatBuffer {
        val per = src.size / speakers
        return FloatBuffer.wrap(src, sid * per, per).slice()
    }

    private fun direct(v: LongArray): LongBuffer =
        ByteBuffer.allocateDirect(v.size * 8).order(ByteOrder.nativeOrder()).asLongBuffer().apply { put(v); rewind() }

    private fun ones(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            for (i in 0 until n) put(i, 1f)
        }

    override fun close() {
        if (closed) return
        synchronized(lock) {
            if (closed) return
            closed = true
            for (s in listOf(vo, ve, te, dp)) runCatching { s.close() }
        }
    }

    /**
     * Ruido gaussiano reproducible: mt19937 + método polar de `std::normal_distribution<float>`
     * de MSVC (lo que usa sherpa con semilla), para comparar con la referencia.
     */
    internal class Noise(seed: Long) {
        private val mt = IntArray(624)
        private var idx = 624
        private var spare = 0f
        private var hasSpare = false

        init {
            mt[0] = seed.toInt()
            for (i in 1 until 624) mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
        }

        private fun nextInt(): Int {
            if (idx >= 624) {
                for (i in 0 until 624) {
                    val y = (mt[i] and 0x80000000.toInt()) or (mt[(i + 1) % 624] and 0x7fffffff)
                    var v = mt[(i + 397) % 624] xor (y ushr 1)
                    if (y and 1 != 0) v = v xor 0x9908b0df.toInt()
                    mt[i] = v
                }
                idx = 0
            }
            var y = mt[idx++]
            y = y xor (y ushr 11)
            y = y xor ((y shl 7) and 0x9d2c5680.toInt())
            y = y xor ((y shl 15) and 0xefc60000.toInt())
            return y xor (y ushr 18)
        }

        /** Uniforme [0,1) con los 24 bits altos (generate_canonical<float> de MSVC). */
        private fun canonical(): Float = (nextInt() ushr 8) * (1f / 16777216f)

        fun next(): Float {
            if (hasSpare) { hasSpare = false; return spare }
            var v1: Float
            var v2: Float
            var sx: Float
            while (true) {
                v1 = 2f * canonical() - 1f
                v2 = 2f * canonical() - 1f
                sx = v1 * v1 + v2 * v2
                if (sx < 1f && v1 != 0f && v2 != 0f) break
            }
            val logSx: Float
            if (sx > 1e-4f) {
                logSx = kotlin.math.ln(sx.toDouble()).toFloat()
            } else {
                val e = Math.getExponent(max(abs(v1), abs(v2)))
                v1 = Math.scalb(v1, -e); v2 = Math.scalb(v2, -e)
                sx = v1 * v1 + v2 * v2
                logSx = kotlin.math.ln(sx.toDouble()).toFloat() + e.toFloat() * (LN2 * 2f)
            }
            val f = sqrt((-2f * logSx / sx).toDouble()).toFloat()
            spare = f * v2; hasSpare = true
            return f * v1
        }
    }

    companion object {
        /** Duración mínima (s) al acelerar, como sherpa. */
        private const val MIN_DURATION = 0.1f
        /** Tope del latente (≈ 12 min de audio): evita reservas enormes. */
        private const val MAX_LATENT_LEN = 10000
        private const val LN2 = 0.6931472f

        /** Frases con como mucho estas letras se dicen un 10 % más despacio (ver [synthesize]). */
        const val SHORT_LETTERS = 10

        internal fun shortSpeed(text: String, speed: Float): Float =
            if (text.count { it.isLetterOrDigit() } <= SHORT_LETTERS) speed * 0.9f else speed

        fun defaultThreads(): Int = min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)

        private fun randomSeed(): Long = (System.nanoTime() xor Thread.currentThread().id * 0x9E3779B97F4A7C15uL.toLong()) and 0xffffffffL

        /** Tramos: se quitan los vacíos, se juntan los del mismo idioma y la puntuación suelta va al anterior. */
        internal fun mergeSegments(segments: List<Pair<String, String>>): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            for ((text, lang) in segments) {
                if (text.isBlank()) continue
                val wordy = text.any { it.isLetterOrDigit() }
                val prev = out.lastOrNull()
                if (prev != null && (!wordy || prev.second == lang)) {
                    out[out.lastIndex] = (prev.first + text) to prev.second
                } else if (wordy) {
                    out += text to lang
                }
            }
            return out.map { it.first.trim() to it.second }.filter { it.first.isNotEmpty() }
        }

        /** Recorta el silencio de los bordes pedidos dejando [keep] muestras, con 5 ms de fundido. */
        internal fun trimEdges(w: FloatArray, keep: Int, lead: Boolean, tail: Boolean): FloatArray {
            if (w.isEmpty()) return w
            var peak = 0f
            for (x in w) peak = max(peak, abs(x))
            val thr = max(0.005f, 0.03f * peak)
            var a = 0
            var b = w.size
            if (lead) { var i = 0; while (i < w.size && abs(w[i]) < thr) i++; a = max(0, i - keep) }
            if (tail) { var i = w.size - 1; while (i >= 0 && abs(w[i]) < thr) i--; b = min(w.size, i + 1 + keep) }
            if (a >= b) return w
            val out = w.copyOfRange(a, b)
            val fade = min(out.size / 4, 220)
            for (i in 0 until fade) {
                val g = i / fade.toFloat()
                if (lead) out[i] *= g
                if (tail) out[out.size - 1 - i] *= g
            }
            return out
        }

        private fun join(parts: List<FloatArray>, gap: Int): FloatArray {
            if (parts.isEmpty()) return FloatArray(0)
            if (parts.size == 1) return parts[0]
            val out = FloatArray(parts.sumOf { it.size } + gap * (parts.size - 1))
            var p = 0
            for ((i, part) in parts.withIndex()) {
                if (i > 0) p += gap
                part.copyInto(out, p); p += part.size
            }
            return out
        }
    }
}
