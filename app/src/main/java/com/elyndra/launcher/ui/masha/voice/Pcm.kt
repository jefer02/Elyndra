package com.elyndra.launcher.ui.masha.voice

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Utilidades de PCM para la voz natural (puro Kotlin: se prueba en la JVM). */
object Pcm {

    /**
     * Recorta el silencio del principio y del final de una frase sintetizada.
     * Supertonic deja ~0,5 s antes de hablar: sumado al margen de la salida, se
     * notaría como retraso. Se dejan [keepLeadMs] antes de la voz (la primera
     * consonante sorda empieza flojita) y [keepTailMs] después (la pausa natural
     * entre frases: la salida no añade ninguna).
     */
    fun trim(x: FloatArray, sampleRate: Int, keepLeadMs: Int = 30, keepTailMs: Int = 110): FloatArray {
        val hop = max(1, sampleRate / 100)
        val frames = x.size / hop
        if (frames == 0) return x
        val rms = FloatArray(frames) { f ->
            var s = 0.0
            for (i in f * hop until (f + 1) * hop) s += x[i] * x[i]
            sqrt(s / hop).toFloat()
        }
        val peak = rms.max()
        if (peak <= 1e-5f) return FloatArray(0)
        // Voz: por encima de -40 dB del pico (y de un suelo absoluto de -66 dBFS).
        val thr = max(peak * 0.01f, 5e-4f)
        val first = rms.indexOfFirst { it > thr }
        val last = rms.indexOfLast { it > thr }
        val from = max(0, first * hop - keepLeadMs * sampleRate / 1000)
        val to = min(x.size, (last + 1) * hop + keepTailMs * sampleRate / 1000)
        return x.copyOfRange(from, to)
    }

    /**
     * Sonoridad pareja con la voz del sistema: lleva el RMS de los tramos con voz a
     * [targetDbfs] sin pasar de [peakDbfs] de pico. Importa para los labios: el canal
     * de energía (gestos, brillo) y la puerta de formantes van en dBFS absolutos.
     */
    fun normalize(x: FloatArray, sampleRate: Int, targetDbfs: Float = -16f, peakDbfs: Float = -1f): Float {
        val hop = max(1, sampleRate / 100)
        val frames = x.size / hop
        if (frames == 0) return 1f
        val rms = FloatArray(frames) { f ->
            var s = 0.0
            for (i in f * hop until (f + 1) * hop) s += x[i] * x[i]
            sqrt(s / hop).toFloat()
        }
        val top = rms.max()
        if (top <= 1e-5f) return 1f
        var sum = 0.0
        var n = 0
        for (r in rms) if (r > top * 0.03f) { sum += r * r; n++ }
        val active = sqrt(sum / max(1, n)).toFloat()
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        val want = db2lin(targetDbfs) / active
        val gain = min(want, db2lin(peakDbfs) / max(peak, 1e-6f)).coerceIn(0.25f, 8f)
        for (i in x.indices) x[i] *= gain
        return gain
    }

    /** Float [-1, 1] → PCM 16 bits little-endian. */
    fun toPcm16(x: FloatArray, from: Int = 0, to: Int = x.size): ByteArray {
        val out = ByteArray((to - from) * 2)
        var o = 0
        for (i in from until to) {
            val s = (x[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[o++] = s.toByte()
            out[o++] = (s shr 8).toByte()
        }
        return out
    }

    fun db2lin(db: Float) = Math.pow(10.0, db / 20.0).toFloat()

    fun lin2db(v: Float) = if (v <= 0f) -120f else 20f * log10(v)
}

/**
 * Diezma PCM 16 bits mono a la mitad de frecuencia (44,1 → 22,05 kHz) con un
 * filtro de media banda, conservando el estado entre trozos.
 *
 * Solo para la copia que analiza la sincronía de labios: su análisis (LPC de
 * orden 12, rejilla de formantes, umbral de agudos) está afinado a ~24 kHz. Lo
 * que suena sigue a 44,1 kHz.
 */
class HalfbandDecimator {
    private val hist = FloatArray(TAPS.size)
    private var pos = 0
    private var phase = 0

    fun process(pcm16: ByteArray): ByteArray {
        val n = pcm16.size / 2
        val out = ByteArray((n + 1) / 2 * 2 + 2)
        var o = 0
        for (i in 0 until n) {
            val s = ((pcm16[2 * i].toInt() and 0xFF) or (pcm16[2 * i + 1].toInt() shl 8)) / 32768f
            hist[pos] = s
            pos = (pos + 1) % hist.size
            phase = phase xor 1
            if (phase == 0) continue
            var acc = 0f
            var k = pos
            for (t in TAPS) {
                acc += t * hist[k]
                k = (k + 1) % hist.size
            }
            val v = (acc.coerceIn(-1f, 1f) * 32767f).toInt()
            out[o++] = v.toByte()
            out[o++] = (v shr 8).toByte()
        }
        return out.copyOf(o)
    }

    private companion object {
        /** Media banda de 23 coeficientes (ventana de Blackman, corte en fs/4): >60 dB fuera de banda. */
        val TAPS: FloatArray = run {
            val n = 23
            val m = (n - 1) / 2
            val h = FloatArray(n) { i ->
                val k = i - m
                val sinc = if (k == 0) 0.5 else Math.sin(Math.PI * k / 2) / (Math.PI * k)
                val w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (n - 1))
                (sinc * w).toFloat()
            }
            val s = h.sum()
            FloatArray(n) { h[it] / s }
        }
    }
}
