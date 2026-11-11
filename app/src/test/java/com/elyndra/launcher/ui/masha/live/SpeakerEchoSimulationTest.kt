package com.elyndra.launcher.ui.masha.live

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Banco de pruebas del barge-in por el altavoz, sin móvil: Lucy hablando por
 * un altavoz simulado (retraso, reverberación, ganancia y, a volumen alto,
 * distorsión) que vuelve al micrófono, y un usuario que a veces habla encima.
 * Pasa por la cadena real ([LiveEar]: VAD de energía, que también "oye" el eco,
 * + [EchoGate] + [SpeechSegmenter]) y mide:
 * - **falsos disparos**: SpeechStart con solo la voz de ella (por minuto);
 * - **barge-ins perdidos**: frases del usuario sin SpeechStart en 600 ms;
 * - **reacción**: del inicio de la voz del usuario al SpeechStart.
 *
 * Es una estimación (señales sintéticas, sin Silero, que en el móvil rechaza
 * además lo que no es voz humana); las cifras reales se miden en el móvil con
 * el log `LucyLive` (docs/MASHA_VOICE.md). Imprime una tabla para el informe.
 */
class SpeakerEchoSimulationTest {

    private val rate = 16_000
    private val frame = 256

    /** Voz sintética: sílabas con armónicos y vibrato, huecos y pausas de frase. */
    private fun voice(seconds: Double, f0: Double, rmsDb: Double, rnd: Random): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        var i = 0
        val amp = Math.pow(10.0, rmsDb / 20) * 1.9
        while (i < n) {
            val syl = (rate * (0.11 + rnd.nextDouble() * 0.14)).toInt()
            val gain = amp * Math.pow(10.0, (rnd.nextDouble() * 6 - 3) / 20)
            val f = f0 * (0.9 + rnd.nextDouble() * 0.25)
            for (k in 0 until syl) {
                if (i + k >= n) break
                val env = sin(PI * k / syl)
                val t = (i + k).toDouble() / rate
                val ph = 2 * PI * f * t + 0.3 * sin(2 * PI * 5 * t)
                out[i + k] = (gain * env * (sin(ph) + 0.6 * sin(2 * ph) + 0.35 * sin(3 * ph) + 0.2 * sin(5 * ph)) / 1.3).toFloat()
            }
            val gap = if (rnd.nextInt(7) == 0) 0.35 + rnd.nextDouble() * 0.4 else 0.03 + rnd.nextDouble() * 0.09
            i += syl + (gap * rate).toInt()
        }
        return out
    }

    /** Lo que vuelve al micrófono de lo que suena: retraso, cola, ganancia y saturación. */
    private fun speaker(ref: FloatArray, delayMs: Int, gainDb: Double, drive: Double): FloatArray {
        val d = delayMs * rate / 1000
        val g = Math.pow(10.0, gainDb / 20)
        val taps = listOf(0 to 1.0, 112 to 0.45, 304 to 0.25, 656 to 0.12, 1200 to 0.05)
        return FloatArray(ref.size) { t ->
            var s = 0.0
            for ((k, a) in taps) {
                val i = t - d - k
                if (i >= 0) s += a * ref[i]
            }
            val x = s * g
            (if (drive > 0) tanh(x * drive) / drive else x).toFloat()
        }
    }

    private class Result(val falsePerMin: Double, val missed: Int, val events: Int, val reactionMs: Double)

    private fun run(gainDb: Double, drive: Double, seed: Int, userDb: Double = -27.0): Result {
        val rnd = Random(seed)
        val minutes = 3.0
        val ref = voice(minutes * 60, 215.0, -20.0, rnd)
        val echo = speaker(ref, delayMs = 95, gainDb = gainDb, drive = drive)
        val mic = FloatArray(ref.size) { echo[it] + (rnd.nextDouble() * 2 - 1).toFloat() * 0.0006f }
        // Frases del usuario encima de ella, a partir del segundo 10, cada ~4–7 s.
        val onsets = ArrayList<Int>()
        var at = 10.0
        val user = FloatArray(ref.size)
        while (at < minutes * 60 - 3) {
            val len = 0.9 + rnd.nextDouble() * 1.2
            val u = voice(len, 125.0, userDb + rnd.nextDouble() * 6 - 3, rnd)
            val start = (at * rate).toInt()
            // El inicio real: la primera muestra audible.
            val first = u.indexOfFirst { kotlin.math.abs(it) > 0.003f }.coerceAtLeast(0)
            onsets += start + first
            for (k in u.indices) if (start + k < user.size) user[start + k] = u[k]
            at += len + 4 + rnd.nextDouble() * 3
        }
        for (i in mic.indices) mic[i] += user[i]

        val ear = LiveEar()
        val vad = VoiceActivity()
        val starts = ArrayList<Int>()
        val buf = ShortArray(frame)
        val rbuf = ShortArray(frame)
        var t = 0
        while (t + frame <= mic.size) {
            for (k in 0 until frame) {
                buf[k] = (mic[t + k].coerceIn(-1f, 1f) * 32767).toInt().toShort()
                rbuf[k] = (ref[t + k].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }
            val micDb = VoiceActivity.levelDb(buf)
            val sig = ear.accept(micDb, VoiceActivity.levelDb(rbuf), vad.prob(micDb))
            if (sig == SpeechSegmenter.Signal.Started) starts += t
            // Como en el móvil: tras un SpeechStart el turno se cierra cuando el reconocedor termina (~1,5 s después).
            t += frame
        }
        val window = rate * 600 / 1000
        val userRanges = onsets.map { it until it + rate * 3 }
        val falseStarts = starts.count { s -> userRanges.none { s in it } }
        val reactions = onsets.mapNotNull { o -> starts.firstOrNull { it in o..o + window }?.let { (it + frame - o) * 1000.0 / rate } }
        return Result(falseStarts / minutes, onsets.size - reactions.size, onsets.size, reactions.average())
    }

    @Test
    fun bargeInOnTheSpeakerAtMediumAndHighVolume() {
        // Medio: el eco llega ~22 dB por debajo de lo que suena. Alto: ~8 dB y el altavoz satura.
        val medium = (1..3).map { run(gainDb = -22.0, drive = 0.0, seed = it) }
        val high = (1..3).map { run(gainDb = -8.0, drive = 3.0, seed = it) }
        // Con el altavoz fuerte la gente sube la voz sola (efecto Lombard, ~+6 dB).
        val loud = (1..3).map { run(gainDb = -8.0, drive = 3.0, seed = it, userDb = -21.0) }
        fun row(name: String, r: List<Result>) =
            "%-7s falsos/min=%.2f  perdidos=%d/%d (%.0f %%)  reacción=%.0f ms".format(
                name, r.map { it.falsePerMin }.average(), r.sumOf { it.missed }, r.sumOf { it.events },
                100.0 * r.sumOf { it.missed } / r.sumOf { it.events }, r.map { it.reactionMs }.average(),
            )
        println("Barge-in simulado por el altavoz (3 × 3 min por volumen):")
        println(row("medio", medium))
        println(row("alto", high))
        println(row("alto+v", loud))
        val missedMedium = medium.sumOf { it.missed }.toDouble() / medium.sumOf { it.events }
        assertTrue(row("medio", medium), medium.map { it.falsePerMin }.average() <= 0.5)
        assertTrue(row("medio", medium), missedMedium <= 0.1)
        assertTrue(row("alto", high), high.map { it.falsePerMin }.average() <= 2.0)
    }
}
