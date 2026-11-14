package com.elyndra.launcher.ui.masha.live

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** El modelo de Silero que va en el APK carga en el ONNX Runtime y responde con su forma. */
class SileroVadTest {

    private fun load(): SileroVad {
        val vad = SileroVad.load(File("src/main/assets/${SileroVad.ASSET}").readBytes())
        assertNotNull("el modelo no carga", vad)
        return vad!!
    }

    @Test
    fun silenceIsNotSpeech() {
        load().use { vad ->
            val probs = (0 until 20).map { vad.prob(ShortArray(SileroVad.WINDOW)) }
            assertTrue("silencio: $probs", probs.all { it < 0.2f })
        }
    }

    @Test
    fun steadyNoiseIsNotSpeech() {
        load().use { vad ->
            val rnd = Random(7)
            val probs = (0 until 30).map { vad.prob(ShortArray(SileroVad.WINDOW) { (rnd.nextGaussian() * 300).toInt().toShort() }) }
            assertTrue("ruido: ${probs.takeLast(10)}", probs.takeLast(10).all { it < 0.5f })
        }
    }

    @Test
    fun probabilitiesAreInRangeAndStateCarriesOver() {
        load().use { vad ->
            // Un tono con armónicos y sílabas: no es voz real, pero el modelo tiene que responder sin fallar.
            var phase = 0.0
            val probs = (0 until 40).map { w ->
                val gain = if (w % 6 < 4) 9000.0 else 300.0
                vad.prob(
                    ShortArray(SileroVad.WINDOW) {
                        phase += 2 * PI * 140 / SileroVad.SAMPLE_RATE
                        (gain * (sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase)) / 1.8).toInt().toShort()
                    },
                )
            }
            assertTrue(probs.all { it in 0f..1f })
            vad.reset()
            assertTrue(vad.prob(ShortArray(SileroVad.WINDOW)) < 0.2f)
        }
    }

    private fun Random.nextGaussian(): Double {
        var s: Double
        var u: Double
        do {
            u = nextDouble(-1.0, 1.0)
            val v = nextDouble(-1.0, 1.0)
            s = u * u + v * v
        } while (s >= 1 || s == 0.0)
        return u * kotlin.math.sqrt(-2 * kotlin.math.ln(s) / s)
    }
}
