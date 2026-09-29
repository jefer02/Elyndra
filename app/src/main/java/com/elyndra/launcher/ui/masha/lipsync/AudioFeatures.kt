package com.elyndra.launcher.ui.masha.lipsync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Análisis ligero del audio de la voz, en tramas de 10 ms (la trama k está
 * centrada en k·10 ms), a medida que llega el PCM del motor:
 * - [db]: energía RMS en dBFS;
 * - [hf]: proporción de energía de alta frecuencia (energía de la diferencia
 *   primera / 4·energía, 0..1: vocales ≈ 0,02–0,1, sibilantes > 0,3);
 * - [zcr]: cruces por cero por muestra;
 * - [f1]/[f2]: dos primeros formantes (Hz) por LPC (Levinson-Durbin + picos de
 *   la envolvente), 0 si la trama no es sonora o no se encuentran.
 *
 * Código propio (sin TarsosDSP, que es GPL). Corre en el hilo de la voz, no
 * en el de render; sus tablas crecen, así que no es para el bucle de fotogramas.
 */
class AudioFeatures(val sampleRate: Int) {

    val hop = (sampleRate / 100).coerceAtLeast(1)
    private val win = hop * 2
    private val order = if (sampleRate >= 16000) 12 else 10

    var count = 0
        private set
    var db = FloatArray(256); private set
    var hf = FloatArray(256); private set
    var zcr = FloatArray(256); private set
    var f1 = FloatArray(256); private set
    var f2 = FloatArray(256); private set

    /** Muestras recibidas. */
    var samples = 0L
        private set

    private val ring = FloatArray(win)
    private var ringPos = 0
    private var sinceHop = 0

    private val frame = FloatArray(win)
    private val hamming = FloatArray(win) { (0.54 - 0.46 * cos(2 * PI * it / (win - 1))).toFloat() }
    private val r = DoubleArray(order + 1)
    private val a = DoubleArray(order + 1)
    private val tmp = DoubleArray(order + 1)

    // Envolvente LPC evaluada en 150..3500 Hz, cada 50 Hz.
    private val freqs = FloatArray(68) { 150f + 50f * it }
    private val cosT = Array(freqs.size) { f -> DoubleArray(order + 1) { k -> cos(2 * PI * freqs[f] / sampleRate * k) } }
    private val sinT = Array(freqs.size) { f -> DoubleArray(order + 1) { k -> sin(2 * PI * freqs[f] / sampleRate * k) } }
    private val env = DoubleArray(freqs.size)

    /** Audio PCM de 16 bits little-endian (mono o intercalado de [channels]). */
    fun pushPcm16(bytes: ByteArray, offset: Int, length: Int, channels: Int = 1) {
        val step = 2 * channels
        var i = offset
        val end = offset + length - step + 1
        while (i < end) {
            var s = 0f
            for (c in 0 until channels) {
                val lo = bytes[i + 2 * c].toInt() and 0xFF
                val hi = bytes[i + 2 * c + 1].toInt()
                s += ((hi shl 8) or lo).toShort() / 32768f
            }
            push(s / channels)
            i += step
        }
    }

    /** Audio PCM de 8 bits sin signo. */
    fun pushPcm8(bytes: ByteArray, offset: Int, length: Int, channels: Int = 1) {
        var i = offset
        while (i + channels <= offset + length) {
            var s = 0f
            for (c in 0 until channels) s += ((bytes[i + c].toInt() and 0xFF) - 128) / 128f
            push(s / channels)
            i += channels
        }
    }

    /** Audio en coma flotante de 32 bits little-endian. */
    fun pushFloat(bytes: ByteArray, offset: Int, length: Int, channels: Int = 1) {
        val step = 4 * channels
        var i = offset
        while (i + step <= offset + length) {
            var s = 0f
            for (c in 0 until channels) {
                val o = i + 4 * c
                val bits = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
                s += Float.fromBits(bits)
            }
            push(s / channels)
            i += step
        }
    }

    /** Una muestra (−1..1). */
    fun push(s: Float) {
        ring[ringPos] = s
        ringPos = (ringPos + 1) % win
        samples++
        if (++sinceHop >= hop) {
            sinceHop = 0
            analyse()
        }
    }

    fun pushAll(s: FloatArray) = s.forEach { push(it) }

    private fun ensure() {
        if (count < db.size) return
        val n = db.size * 2
        db = db.copyOf(n); hf = hf.copyOf(n); zcr = zcr.copyOf(n); f1 = f1.copyOf(n); f2 = f2.copyOf(n)
    }

    private fun analyse() {
        ensure()
        // Ventana: las últimas win muestras, en orden.
        for (i in 0 until win) frame[i] = ring[(ringPos + i) % win]
        var e = 0.0
        var ed = 0.0
        var z = 0
        var prev = frame[0]
        for (i in 0 until win) {
            val x = frame[i]
            e += x * x
            if (i > 0) {
                val d = x - prev
                ed += d * d
                if ((x >= 0f) != (prev >= 0f)) z++
            }
            prev = x
        }
        val rms = sqrt(e / win)
        val k = count
        db[k] = (20 * log10(rms + 1e-9)).toFloat().coerceAtLeast(-120f)
        hf[k] = if (e > 1e-12) (ed / (4 * e)).toFloat().coerceIn(0f, 1f) else 0f
        zcr[k] = z.toFloat() / win
        f1[k] = 0f
        f2[k] = 0f
        if (db[k] > -50f && hf[k] < 0.25f) formants(k)
        count++
    }

    private fun formants(k: Int) {
        // Preénfasis + Hamming, autocorrelación, Levinson-Durbin.
        var last = 0f
        for (i in 0 until win) {
            val x = frame[i]
            frame[i] = (x - 0.94f * last) * hamming[i]
            last = x
        }
        for (lag in 0..order) {
            var s = 0.0
            for (i in lag until win) s += frame[i] * frame[i - lag]
            r[lag] = s
        }
        if (r[0] <= 1e-10) return
        r[0] *= 1.0001 // ruido blanco mínimo: estabilidad
        a.fill(0.0)
        a[0] = 1.0
        var err = r[0]
        for (i in 1..order) {
            var acc = r[i]
            for (j in 1 until i) acc += a[j] * r[i - j]
            val kk = -acc / err
            for (j in 0..i) tmp[j] = a[j]
            for (j in 1 until i) a[j] = tmp[j] + kk * tmp[i - j]
            a[i] = kk
            err *= (1 - kk * kk)
            if (err <= 0) return
        }
        // Envolvente 1/|A(e^jw)|² en la rejilla.
        for (f in freqs.indices) {
            var re = 0.0
            var im = 0.0
            val ct = cosT[f]
            val st = sinT[f]
            for (j in 0..order) {
                re += a[j] * ct[j]
                im -= a[j] * st[j]
            }
            env[f] = 1.0 / (re * re + im * im + 1e-12)
        }
        // Picos: F1 en 200–1100 Hz, F2 al menos 250 Hz por encima, hasta 3200 Hz.
        var p1 = -1
        for (f in 1 until freqs.size - 1) {
            if (freqs[f] < 200f || freqs[f] > 1100f) continue
            if (env[f] >= env[f - 1] && env[f] > env[f + 1]) { p1 = f; break }
        }
        if (p1 < 0) return
        var p2 = -1
        for (f in p1 + 1 until freqs.size - 1) {
            if (freqs[f] < freqs[p1] + 250f || freqs[f] > 3200f) continue
            if (env[f] >= env[f - 1] && env[f] > env[f + 1]) { p2 = f; break }
        }
        f1[k] = interp(p1)
        f2[k] = if (p2 >= 0) interp(p2) else 0f
    }

    /** Pico refinado por parábola sobre tres puntos (en log). */
    private fun interp(p: Int): Float {
        val y0 = kotlin.math.ln(env[p - 1]); val y1 = kotlin.math.ln(env[p]); val y2 = kotlin.math.ln(env[p + 1])
        val d = y0 - 2 * y1 + y2
        val off = if (d < 0) (0.5 * (y0 - y2) / d).coerceIn(-0.5, 0.5) else 0.0
        return (freqs[p] + off * 50.0).toFloat()
    }
}
