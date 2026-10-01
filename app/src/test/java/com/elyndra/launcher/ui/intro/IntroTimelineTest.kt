package com.elyndra.launcher.ui.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroTimelineTest {

    private fun at(t: Float) = IntroTimeline.at(t)

    @Test
    fun startsWithOnlyTheBackground() {
        val f = at(0f)
        assertEquals(0f, f.atmosphere, 0f)
        assertEquals(0f, f.gather, 0f)
        assertEquals(0f, f.core, 0f)
        assertEquals(0f, f.reveal, 0f)
        assertEquals(1f, f.alpha, 0f)
        assertFalse(f.done)
    }

    @Test
    fun atmosphereThenDustAndCore() {
        assertEquals(1f, at(400f).atmosphere, 1e-4f)
        assertEquals(0f, at(400f).gather, 0f)
        val mid = at(900f)
        assertTrue(mid.gather in 0.1f..0.9f)
        assertTrue(mid.core > 0f)
        assertTrue(at(1400f).core > 0.99f)
        assertEquals(0f, at(1300f).burst, 0f)
    }

    @Test
    fun burstAndStreakPeakAroundOnePointFiveSeconds() {
        assertTrue(at(1480f).burst > 0.99f)
        assertTrue(at(1500f).streak > 0.99f)
        assertTrue(at(2300f).burst < 1e-4f)
        assertEquals(0f, at(2600f).streak, 1e-6f)
    }

    @Test
    fun wordmarkRevealsBetweenOnePointFiveAndTwoPointFour() {
        assertEquals(0f, at(1500f).reveal, 0f)
        assertTrue(at(1900f).reveal in 0.1f..0.99f)
        assertEquals(1f, at(2400f).reveal, 1e-4f)
        assertTrue(at(2000f).embers > 0f)
    }

    @Test
    fun sweepCrossesTheLetters() {
        assertEquals(0f, at(2400f).sweepAlpha, 1e-4f)
        assertEquals(0f, at(2400f).sweep, 1e-4f)
        assertTrue(at(2700f).sweepAlpha > 0.99f)
        assertEquals(1f, at(3000f).sweep, 1e-4f)
    }

    @Test
    fun holdsThenDispersesAndFadesOut() {
        val hold = at(3100f)
        assertEquals(1f, hold.alpha, 0f)
        assertEquals(1f, hold.reveal, 1e-4f)
        assertTrue(at(3400f).disperse > 0.5f)
        val end = at(IntroTimeline.TOTAL_MS)
        assertEquals(0f, end.alpha, 1e-4f)
        assertTrue(end.done)
        assertFalse(at(IntroTimeline.TOTAL_MS - 1f).done)
    }

    @Test
    fun phasesStayInRangeAndRevealNeverGoesBack() {
        var lastReveal = 0f
        var t = 0f
        while (t <= IntroTimeline.TOTAL_MS) {
            val f = at(t)
            for (v in listOf(f.atmosphere, f.gather, f.core, f.burst, f.streak, f.reveal, f.embers, f.sweep, f.sweepAlpha, f.disperse, f.alpha)) {
                assertTrue("t=$t valor $v", v in 0f..1f)
            }
            assertTrue(f.reveal >= lastReveal)
            lastReveal = f.reveal
            t += 10f
        }
    }

    @Test
    fun reducedMotionIsAShortFadeWithTheFinalFrame() {
        assertTrue(IntroTimeline.duration(reduced = true) <= 800f)
        val mid = IntroTimeline.at(400f, reduced = true)
        assertEquals(1f, mid.reveal, 0f)
        assertEquals(0f, mid.gather, 0f)
        assertEquals(0f, mid.burst, 0f)
        assertEquals(0f, mid.sweepAlpha, 0f)
        assertEquals(0f, IntroTimeline.at(0f, reduced = true).fadeIn, 0f)
        assertTrue(IntroTimeline.at(800f, reduced = true).done)
    }

    @Test
    fun skippingIsOnlyAllowedAfterPointEightSecondsAndFadesQuickly() {
        assertFalse(IntroTimeline.canSkip(799f))
        assertTrue(IntroTimeline.canSkip(800f))
        val skipAt = 1200f
        assertEquals(skipAt + IntroTimeline.SKIP_FADE_MS, IntroTimeline.end(false, skipAt), 0f)
        assertFalse(IntroTimeline.at(skipAt + 100f, skipAt = skipAt).done)
        val end = IntroTimeline.at(skipAt + IntroTimeline.SKIP_FADE_MS, skipAt = skipAt)
        assertTrue(end.done)
        assertEquals(0f, end.alpha, 1e-4f)
        // Un salto pedido después del final no lo alarga.
        assertEquals(IntroTimeline.TOTAL_MS, IntroTimeline.end(false, 3400f), 0f)
    }
}
