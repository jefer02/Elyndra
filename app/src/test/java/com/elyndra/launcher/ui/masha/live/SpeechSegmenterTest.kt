package com.elyndra.launcher.ui.masha.live

import com.elyndra.launcher.ui.masha.live.SpeechSegmenter.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El fin de turno por silencio: empieza a los 250 ms de voz, termina a los 700 ms callado. */
class SpeechSegmenterTest {

    private fun feed(s: SpeechSegmenter, voiced: Boolean, ms: Int): List<Signal> =
        (0 until ms / s.frameMs).mapNotNull { s.accept(voiced) }

    @Test
    fun aTurnStartsAfterAQuarterSecondOfVoice() {
        val s = SpeechSegmenter(frameMs = 16)
        assertEquals(emptyList<Signal>(), feed(s, true, 224))
        assertEquals(listOf(Signal.Started), feed(s, true, 32))
        assertTrue(s.inTurn)
    }

    @Test
    fun aShortNoiseNeverOpensATurn() {
        val s = SpeechSegmenter(frameMs = 16)
        repeat(20) {
            assertEquals(emptyList<Signal>(), feed(s, true, 160))
            assertEquals(emptyList<Signal>(), feed(s, false, 320))
        }
        assertFalse(s.inTurn)
    }

    @Test
    fun gapsBetweenSyllablesDontResetTheOnset() {
        val s = SpeechSegmenter(frameMs = 16)
        feed(s, true, 128)
        feed(s, false, 144) // menos de 160 ms
        assertEquals(listOf(Signal.Started), feed(s, true, 16))
    }

    @Test
    fun seventyHundredMillisecondsOfSilenceEndTheTurnAndSixFiftyDont() {
        val s = SpeechSegmenter(frameMs = 10)
        feed(s, true, 400)
        val short = feed(s, false, 650)
        assertEquals(listOf(Signal.Paused), short)
        assertTrue(s.inTurn)
        // Vuelve a hablar: el silencio se cuenta de cero.
        feed(s, true, 200)
        assertEquals(listOf(Signal.Paused, Signal.Ended), feed(s, false, 700))
        assertFalse(s.inTurn)
    }

    @Test
    fun pausedComesOnceAtAQuarterSecondOfSilence() {
        val s = SpeechSegmenter(frameMs = 10)
        feed(s, true, 300)
        val out = (1..30).map { s.accept(false) }
        assertEquals(Signal.Paused, out[24])
        assertEquals(1, out.count { it == Signal.Paused })
    }

    @Test
    fun speechIsCountedForTheBackchannelDecision() {
        val s = SpeechSegmenter(frameMs = 10)
        feed(s, true, 400)
        feed(s, false, 100)
        feed(s, true, 200)
        assertEquals(600, s.speechMs)
    }

    @Test
    fun aVeryLongTurnIsCapped() {
        val s = SpeechSegmenter(frameMs = 20, maxTurnMs = 2_000)
        val out = feed(s, true, 2_200)
        assertEquals(listOf(Signal.Started, Signal.Capped), out)
    }

    @Test
    fun clearDropsTheTurn() {
        val s = SpeechSegmenter(frameMs = 16)
        feed(s, true, 300)
        s.clear()
        assertFalse(s.inTurn)
        assertEquals(0, s.speechMs)
        assertNull(s.accept(false))
    }
}
