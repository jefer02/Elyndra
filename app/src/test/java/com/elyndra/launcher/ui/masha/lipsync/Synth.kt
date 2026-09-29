package com.elyndra.launcher.ui.masha.lipsync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.random.Random

/**
 * Voz sintética para las pruebas: tren de pulsos glotales (F0) por dos
 * resonadores (F1, F2) para las vocales, ruido para las fricativas y casi
 * silencio para los cierres. 24 kHz como la voz local de Google.
 */
object Synth {
    const val SR = 24000

    sealed class Part(val ms: Int)
    class Vowel(ms: Int, val f1: Float, val f2: Float, val amp: Float = 0.3f) : Part(ms)
    class Noise(ms: Int, val amp: Float = 0.08f) : Part(ms)
    class Gap(ms: Int, val amp: Float = 0.002f) : Part(ms)
    class Sil(ms: Int) : Part(ms)
    /** Nasal: sonora, más débil y sin agudos. */
    class Nasal(ms: Int, val amp: Float = 0.06f) : Part(ms)

    val A = { ms: Int -> Vowel(ms, 850f, 1450f) }
    val E = { ms: Int -> Vowel(ms, 520f, 2150f) }
    val I = { ms: Int -> Vowel(ms, 330f, 2650f) }
    val O = { ms: Int -> Vowel(ms, 540f, 1050f) }
    val U = { ms: Int -> Vowel(ms, 360f, 900f, 0.25f) }

    fun render(vararg parts: Part, seed: Int = 1): FloatArray {
        val rnd = Random(seed)
        val total = parts.sumOf { it.ms } * SR / 1000
        val out = FloatArray(total)
        var pos = 0
        var phase = 0.0
        val f0 = 210.0
        for (p in parts) {
            val n = p.ms * SR / 1000
            when (p) {
                is Vowel, is Nasal -> {
                    val (f1, f2, amp) = if (p is Vowel) Triple(p.f1, p.f2, p.amp) else Triple(280f, 1300f, (p as Nasal).amp)
                    val r1 = Resonator(f1.toDouble(), 90.0)
                    val r2 = Resonator(f2.toDouble(), 130.0)
                    val buf = DoubleArray(n)
                    for (i in 0 until n) {
                        phase += f0 / SR
                        val pulse = if (phase >= 1.0) { phase -= 1.0; 1.0 } else 0.0
                        val y1 = r1.step(pulse)
                        buf[i] = y1 + 0.6 * r2.step(y1 + 0.3 * pulse)
                    }
                    var peak = 1e-9
                    for (v in buf) peak = maxOf(peak, kotlin.math.abs(v))
                    for (i in 0 until n) {
                        // Rampas de 10 ms: sin chasquidos.
                        val env = minOf(1.0, i / (0.01 * SR), (n - i) / (0.01 * SR))
                        out[pos + i] = (buf[i] / peak * amp * env).toFloat()
                    }
                }
                // Ruido blanco: mucha energía aguda (como /s/).
                is Noise -> for (i in 0 until n) out[pos + i] = (rnd.nextFloat() * 2 - 1) * p.amp
                is Gap -> for (i in 0 until n) out[pos + i] = (rnd.nextFloat() * 2 - 1) * p.amp
                is Sil -> for (i in 0 until n) out[pos + i] = (rnd.nextFloat() * 2 - 1) * 0.0003f
            }
            pos += n
        }
        return out
    }

    /** PCM 16 bits little-endian. */
    fun pcm16(x: FloatArray): ByteArray {
        val b = ByteArray(x.size * 2)
        for (i in x.indices) {
            val s = (x[i].coerceIn(-1f, 1f) * 32767f).toInt()
            b[2 * i] = s.toByte()
            b[2 * i + 1] = (s shr 8).toByte()
        }
        return b
    }

    fun features(x: FloatArray): AudioFeatures = AudioFeatures(SR).also { it.pushAll(x) }

    private class Resonator(f: Double, bw: Double) {
        private val r = exp(-PI * bw / SR)
        private val a1 = 2 * r * cos(2 * PI * f / SR)
        private val a2 = -r * r
        private var y1 = 0.0
        private var y2 = 0.0
        fun step(x: Double): Double {
            val y = x + a1 * y1 + a2 * y2
            y2 = y1; y1 = y
            return y
        }
    }
}
