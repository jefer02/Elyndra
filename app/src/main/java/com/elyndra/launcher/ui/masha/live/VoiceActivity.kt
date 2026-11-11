package com.elyndra.launcher.ui.masha.live

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ¿Empezó a hablar el usuario? Detector de voz por energía, sin Android, para
 * el barge-in mientras habla Lucy.
 *
 * Sigue el ruido de fondo (que incluye lo que se cuele de la propia voz de
 * Lucy tras la cancelación de eco): baja rápido y sube despacio. Hay voz cuando
 * el nivel supera ese fondo en [marginDb] (y un mínimo absoluto) durante
 * [minSpeechMs] seguidos. Avisa una vez por arranque de voz.
 */
class VoiceActivity(
    private val frameMs: Int = 20,
    private val minSpeechMs: Int = 260,
    private val marginDb: Float = 12f,
    private val minLevelDb: Float = -42f,
    private val riseRate: Float = 0.02f,
    private val fallRate: Float = 0.3f,
) {
    private var floor = Float.NaN
    private var voicedMs = 0
    private var fired = false

    /** Un trozo de [frameMs] con nivel [levelDb] (dBFS). True la primera vez que se confirma voz. */
    fun accept(levelDb: Float): Boolean {
        if (floor.isNaN()) floor = levelDb
        val loud = levelDb > floor + marginDb && levelDb > minLevelDb
        if (loud) {
            voicedMs += frameMs
        } else {
            voicedMs = 0
            fired = false
            // El fondo solo se adapta fuera de la voz: si no, una frase larga se lo comería.
            floor += (levelDb - floor) * (if (levelDb < floor) fallRate else riseRate)
        }
        if (!fired && voicedMs >= minSpeechMs) {
            fired = true
            return true
        }
        return false
    }

    fun reset() {
        floor = Float.NaN
        voicedMs = 0
        fired = false
    }

    companion object {
        /** Nivel RMS en dBFS de [n] muestras PCM de 16 bits (silencio absoluto = -100). */
        fun levelDb(pcm: ShortArray, n: Int = pcm.size): Float {
            if (n <= 0) return -100f
            var sum = 0.0
            for (i in 0 until n) {
                val v = pcm[i] / 32768.0
                sum += v * v
            }
            val rms = sqrt(sum / n)
            return if (rms <= 1e-5) -100f else (20 * log10(rms)).toFloat()
        }
    }
}
