package com.elyndra.launcher.domain

import com.elyndra.launcher.domain.insights.Insight
import com.elyndra.launcher.domain.insights.InsightPacing
import com.elyndra.launcher.domain.insights.MemoryInsightPacingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightPacingTest {

    private val min = 60L * 1000
    private val hour = 60 * min
    private val day = 24 * hour
    private val hot = Insight.DeviceHot(45f)
    private val art = Insight.MissingArt(12)
    private val never = Insight.NeverOpened(4, "r:1", "Okami")
    private val added = Insight.NewGames(3, "r:2", "Celeste", newestAt = 1_000L)
    private val meta = Insight.MetadataFinished(10, 2, finishedAt = 2_000L)

    @Test
    fun eventsAreTheOnesThatHappen() {
        listOf(hot, added, meta, Insight.ArcNext("a", "Saga", "r:3", "Castlevania", 2, 5), Insight.EmulatorMissing("f", "PS2", "aethersx2"))
            .forEach { assertTrue(it.toString(), it.isEvent) }
        listOf(art, never, Insight.EmptyLibrary, Insight.ContinueGame("r:4", "Zelda", 2, 30, null), Insight.Unmatched(4))
            .forEach { assertFalse(it.toString(), it.isEvent) }
    }

    @Test
    fun anEventIsToldOnlyOnce() {
        val p = InsightPacing()
        assertTrue(p.admit(added, 0))
        assertFalse(p.admit(added, 20 * min))
        assertFalse(p.admit(added, 30 * day))
    }

    @Test
    fun atMostOneRoutineSuggestionADay() {
        val p = InsightPacing()
        assertTrue(p.admit(art, 0))
        assertFalse(p.admit(never, 2 * hour))
        assertFalse(p.admit(never, 19 * hour))
        assertTrue(p.admit(never, 20 * hour))
    }

    @Test
    fun theSameRoutineSuggestionWaitsThreeDays() {
        val p = InsightPacing()
        assertTrue(p.admit(art, 0))
        assertFalse(p.admit(art, day))
        assertFalse(p.admit(art, 2 * day))
        assertTrue(p.admit(art, 3 * day))
    }

    @Test
    fun eventsDoNotSpendTheDailySuggestion() {
        val p = InsightPacing()
        assertTrue(p.admit(meta, 0))
        assertTrue(p.admit(art, 15 * min))
    }

    @Test
    fun notNowKeepsLucyQuietForAWhile() {
        val p = InsightPacing()
        assertTrue(p.admit(Insight.EmptyLibrary, 0))
        p.dismissed(min)
        assertFalse(p.admit(added, 20 * min))
        assertFalse(p.admit(hot, 20 * min))
        assertTrue(p.admit(added, 50 * min))
    }

    @Test
    fun aHotDeviceSkipsTheMinimumGapAndCanRepeatAfterTwoHours() {
        val p = InsightPacing()
        assertTrue(p.admit(art, 0))
        assertTrue(p.admit(hot, 2 * min))
        assertFalse(p.admit(meta, 3 * min))
        assertFalse(p.admit(hot, hour))
        assertTrue(p.admit(hot, 2 * min + 2 * hour))
    }

    @Test
    fun pickTakesTheFirstThatMayShow() {
        val p = InsightPacing()
        assertTrue(p.admit(art, 0))
        // La de rutina ya salió hoy: pasa al hecho que viene detrás.
        assertEquals(meta, p.pick(listOf(never, meta), 30 * min))
        assertNull(p.pick(listOf(never, meta), 60 * min))
    }

    /* ── reiniciar el proceso (lo que hacía salir el bocadillo en cada apertura) ── */

    @Test
    fun aProcessRestartShowsNothingNew() {
        val store = MemoryInsightPacingStore()
        val candidates = listOf(added, meta, never)
        val first = InsightPacing(store)
        assertEquals(added, first.pick(candidates, 0))
        assertEquals(meta, first.pick(candidates, 11 * min))
        assertEquals(never, first.pick(candidates, 22 * min))

        // Android mata la app y se vuelve a abrir: un InsightPacing nuevo sobre lo guardado.
        for (open in 1..5) {
            val restarted = InsightPacing(store)
            assertNull("apertura $open", restarted.pick(candidates, 22 * min + open * hour))
        }
    }

    @Test
    fun afterARestartOnlyANewEventOrTheNextDaysSuggestion() {
        val store = MemoryInsightPacingStore()
        assertEquals(never, InsightPacing(store).pick(listOf(never), 0))

        val moreAdded = Insight.NewGames(5, "r:9", "Hades", newestAt = 9_000L)
        assertEquals(moreAdded, InsightPacing(store).pick(listOf(never, moreAdded), 3 * hour))
        assertNull(InsightPacing(store).pick(listOf(never, moreAdded), 6 * hour))
        assertEquals(art, InsightPacing(store).pick(listOf(never, art), day))
    }

    @Test
    fun notNowSurvivesARestart() {
        val store = MemoryInsightPacingStore()
        InsightPacing(store).dismissed(0)
        assertNull(InsightPacing(store).pick(listOf(added), 10 * min))
        assertEquals(added, InsightPacing(store).pick(listOf(added), 46 * min))
    }
}
