package com.elyndra.launcher.ui.masha.live

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * El nivel de lo que va saliendo por la voz de Lucy, por bloques de
 * [blockMs], indexado por trama de salida (pura). La escribe el hilo de salida
 * (SpeechOutput) según escribe el PCM en la pista; la lee el hilo del
 * micrófono con la trama que suena ahora (la del reloj de la pista): es la
 * señal de referencia de [EchoGate].
 *
 * Guarda los últimos [capacity] bloques (~8 s a 16 ms); lo que es más viejo,
 * o aún no se ha escrito, es silencio.
 */
class PlaybackEnvelope(val blockMs: Int = 16, val capacity: Int = 512) {

    private val levels = FloatArray(capacity) { SILENT }
    @Volatile private var blocks = 0L
    @Volatile private var blockFrames = 0

    // Bloque a medias (hilo de salida).
    private var acc = 0.0
    private var accFrames = 0

    /** Pista nueva (otra frecuencia o tras cortar): empieza en la trama 0. */
    fun reset(sampleRate: Int) {
        blockFrames = (sampleRate * blockMs / 1000).coerceAtLeast(1)
        acc = 0.0
        accFrames = 0
        blocks = 0L
    }

    /** PCM de 16 bits little-endian, [channels] intercalados, tal como va a la pista. */
    fun push(pcm: ByteArray, channels: Int, length: Int = pcm.size) {
        val bf = blockFrames
        if (bf <= 0) return
        val frameBytes = 2 * channels
        var off = 0
        while (off + frameBytes <= length) {
            var frame = 0.0
            for (c in 0 until channels) {
                val o = off + 2 * c
                val s = ((pcm[o].toInt() and 0xFF) or (pcm[o + 1].toInt() shl 8)).toShort() / 32768.0
                frame += s * s
            }
            acc += frame / channels
            accFrames++
            off += frameBytes
            if (accFrames == bf) close()
        }
    }

    /** Mismo, con muestras ya en 16 bits (para pruebas). */
    fun push(samples: ShortArray) {
        val bf = blockFrames
        if (bf <= 0) return
        for (v in samples) {
            val s = v / 32768.0
            acc += s * s
            accFrames++
            if (accFrames == bf) close()
        }
    }

    private fun close() {
        val rms = sqrt(acc / accFrames)
        val db = if (rms <= 1e-5) SILENT else (20 * log10(rms)).toFloat().coerceAtLeast(SILENT)
        val b = blocks
        levels[(b % capacity).toInt()] = db
        blocks = b + 1
        acc = 0.0
        accFrames = 0
    }

    /** Nivel (dBFS) del bloque que contiene la trama de salida [frame]. */
    fun levelAt(frame: Long): Float {
        val bf = blockFrames
        if (bf <= 0 || frame < 0) return SILENT
        val b = frame / bf
        val done = blocks
        if (b >= done || b < done - capacity) return SILENT
        return levels[(b % capacity).toInt()]
    }

    companion object {
        const val SILENT = -100f
    }
}
