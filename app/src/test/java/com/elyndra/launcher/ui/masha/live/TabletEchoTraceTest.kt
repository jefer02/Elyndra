package com.elyndra.launcher.ui.masha.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El eco real de la tablet (trazas de LiveMic en debug, `resources/live/tablet-echo-trace*.csv`):
 * Lucy hablando por el altavoz con el AEC de la tablet, que no cancela el
 * principio de las sílabas, y el usuario interrumpiéndola a volumen normal.
 * Por la cadena real ([LiveEar]: puerta de eco + segmentador), su voz nunca
 * abre turno y las interrupciones del usuario sí.
 */
class TabletEchoTraceTest {

    private class Frame(val ms: Int, val micDb: Float, val refDb: Float, val prob: Float)

    /** Los tramos de la traza, por aperturas del micrófono (`# open`). */
    private fun segments(name: String): List<List<Frame>> {
        val out = mutableListOf(mutableListOf<Frame>())
        javaClass.classLoader!!.getResourceAsStream(name).bufferedReader().readLines().forEach { line ->
            when {
                line.startsWith("# open") -> if (out.last().isNotEmpty()) out += mutableListOf<Frame>()
                line.isBlank() || line.startsWith("#") -> Unit
                else -> line.split(',').let { f -> out.last() += Frame(f[0].toInt(), f[1].toFloat(), f[2].toFloat(), f[3].toFloat()) }
            }
        }
        return out
    }

    /** Dónde abre turno la cadena con ella sonando (ms de la traza); cada apertura del micrófono, con [gate] nueva. */
    private fun startsOverHer(name: String, gate: () -> EchoGate = { EchoGate() }): List<Int> = segments(name).flatMap { frames ->
        val ear = LiveEar(gate())
        frames.mapNotNull { f ->
            val signal = ear.accept(f.micDb, f.refDb, f.prob)
            f.ms.takeIf { signal == SpeechSegmenter.Signal.Started && f.refDb > EchoGate.REF_FLOOR }
        }
    }

    @Test
    fun firstSessionHerVoiceNeverOpensATurnAndTheInterruptionDoes() {
        val at = startsOverHer("live/tablet-echo-trace.csv")
        assertTrue("false starts during her reply: $at", at.none { it in 277_800 until 299_000 })
        assertEquals("the interruption opens a turn: $at", 1, at.count { it in 299_000..299_600 })
    }

    /** Como en la segunda prueba en la tablet: el retraso ya confirmado (lag=3, correlación 0,85) al empezar a hablar. */
    @Test
    fun firstSessionWithTheDelayAlreadyConfirmed() {
        val at = startsOverHer("live/tablet-echo-trace.csv") { EchoGate(initialCouplingDb = -24f, initialLag = 3, initialLagConfidence = 0.85f) }
        assertTrue("false starts during her reply: $at", at.none { it in 277_800 until 299_000 })
        assertEquals("the interruption opens a turn: $at", 1, at.count { it in 299_000..299_600 })
    }

    /**
     * La cuarta prueba en la tablet: con 5 dB de margen ella se pausó sola en ~236,3 s y ~238,5 s (y la
     * segunda la cortó) y en ~320,1 s y ~331,1 s; el usuario la interrumpió en ~401,1 s.
     */
    @Test
    fun secondSessionHerSyllablesNeverPauseHerAndTheInterruptionDoes() {
        val at = startsOverHer("live/tablet-echo-trace-2.csv")
        assertTrue("false starts during her replies: $at", at.none { it < 400_000 })
        assertEquals("the interruption opens a turn: $at", 1, at.count { it in 400_000..403_100 })
    }

    /** La quinta prueba en la tablet: con 6 dB de margen se pausó sola en ~107,2 s y ~108,6 s; el usuario la cortó en ~150,4 s. */
    @Test
    fun thirdSessionHerSyllablesNeverPauseHerAndTheInterruptionDoes() {
        val at = startsOverHer("live/tablet-echo-trace-3.csv")
        assertTrue("false starts during her replies: $at", at.none { it < 150_000 })
        assertEquals("the interruption opens a turn: $at", 1, at.count { it in 150_000..151_000 })
    }
}
