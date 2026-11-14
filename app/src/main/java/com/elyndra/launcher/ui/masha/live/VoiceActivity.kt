package com.elyndra.launcher.ui.masha.live

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * VAD de repuesto por energía (puro), cuando no hay Silero (procesos de 32
 * bits, sin ONNX Runtime): devuelve una "probabilidad" de voz por tramo, con
 * la misma escala que [SileroVad] para que [LiveEar] no sepa cuál tiene.
 *
 * Sigue el fondo con un suelo adaptativo (baja enseguida, sube despacio) y
 * mide cuánto lo supera el tramo: [MARGIN_DB] por encima = 0,5. Nada por
 * debajo de [MIN_SPEECH_DB] cuenta como voz. Es más basto que Silero: un ruido
 * fuerte también es "voz" (la puerta de eco y los 250 ms del segmentador
 * filtran el resto).
 */
class VoiceActivity(
    private val marginDb: Float = MARGIN_DB,
    private val minSpeechDb: Float = MIN_SPEECH_DB,
) {
    private var floorDb = Float.NaN

    fun prob(levelDb: Float): Float {
        floorDb = when {
            floorDb.isNaN() -> levelDb
            levelDb < floorDb -> floorDb + (levelDb - floorDb) * 0.3f
            else -> floorDb + (levelDb - floorDb) * 0.005f
        }
        if (levelDb < minSpeechDb) return 0f
        val over = levelDb - floorDb - marginDb
        return (1f / (1f + exp(-over / 2f)))
    }

    companion object {
        const val MARGIN_DB = 12f
        const val MIN_SPEECH_DB = -42f

        /** Nivel RMS de [n] muestras en dBFS (−100 = silencio). */
        fun levelDb(samples: ShortArray, n: Int = samples.size, from: Int = 0): Float {
            if (n <= 0) return -100f
            var sum = 0.0
            for (i in from until from + n) {
                val s = samples[i] / 32768.0
                sum += s * s
            }
            val rms = sqrt(sum / n)
            return if (rms <= 1e-5) -100f else (20 * log10(rms)).toFloat().coerceAtLeast(-100f)
        }
    }
}
