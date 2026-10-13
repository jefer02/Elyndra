package com.elyndra.launcher.domain

import com.elyndra.launcher.domain.insights.Insight
import com.elyndra.launcher.domain.insights.InsightPacing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightPacingTest {

    private val min = 60L * 1000
    private val hot = Insight.DeviceHot(45f)
    private val art = Insight.MissingArt(12)

    @Test
    fun theSameInsightDoesNotComeBackSoon() {
        val p = InsightPacing()
        assertTrue(p.admit(Insight.EmptyLibrary, 0))
        assertFalse(p.admit(Insight.EmptyLibrary, 30 * min))
        assertTrue(p.admit(Insight.EmptyLibrary, 7 * 60 * min))
    }

    @Test
    fun notNowKeepsMashaQuietForAWhile() {
        val p = InsightPacing()
        assertTrue(p.admit(Insight.EmptyLibrary, 0))
        p.dismissed(min)
        assertFalse(p.admit(art, 20 * min))
        assertFalse(p.admit(hot, 20 * min))
        assertTrue(p.admit(art, 50 * min))
    }

    @Test
    fun aHotDeviceSkipsTheMinimumGap() {
        val p = InsightPacing()
        assertTrue(p.admit(art, 0))
        assertTrue(p.admit(hot, 2 * min))
        assertFalse(p.admit(Insight.Unmatched(4), 3 * min))
    }
}
