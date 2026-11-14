package com.elyndra.launcher.domain.relationship

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La barra del panel "Lucy y tú": cuánto falta y cuánto se lleva del tramo, en cada borde. */
class AffinityProgressTest {

    @Test
    fun pointsToTheNextLevelAtEveryBoundary() {
        // Último punto de un nivel → falta 1; primero del siguiente → falta todo su tramo.
        assertEquals(1, AffinityProgress.of(5).pointsToNext)
        assertEquals(AffinityLevel.Curious, AffinityProgress.of(5).next)
        assertEquals(30, AffinityProgress.of(6).pointsToNext) // 6 → 36
        assertEquals(1, AffinityProgress.of(35).pointsToNext)
        assertEquals(25, AffinityProgress.of(36).pointsToNext) // 36 → 61
        assertEquals(1, AffinityProgress.of(60).pointsToNext)
        assertEquals(15, AffinityProgress.of(61).pointsToNext) // 61 → 76
        assertEquals(1, AffinityProgress.of(75).pointsToNext)
        assertEquals(AffinityLevel.Devoted, AffinityProgress.of(75).next)
    }

    @Test
    fun theFractionStartsAtZeroInEachBand() {
        assertEquals(0f, AffinityProgress.of(0).fraction, 0.001f)
        assertEquals(0f, AffinityProgress.of(6).fraction, 0.001f)
        assertEquals(29f / 30f, AffinityProgress.of(35).fraction, 0.001f)
        assertEquals(0f, AffinityProgress.of(36).fraction, 0.001f)
        assertEquals(0.5f, AffinityProgress.of(21).fraction, 0.001f) // 6..35: 15 de 30
        assertEquals(10f / 15f, AffinityProgress.of(71).fraction, 0.001f)
    }

    @Test
    fun theTopLevelIsFullWithNothingNext() {
        listOf(76, 90, 100).forEach {
            val p = AffinityProgress.of(it)
            assertEquals(AffinityLevel.Devoted, p.level)
            assertNull(p.next)
            assertEquals(0, p.pointsToNext)
            assertEquals(1f, p.fraction, 0.001f)
        }
    }

    @Test
    fun outOfRangeScoresAreClamped() {
        assertEquals(AffinityProgress.of(0), AffinityProgress.of(-20))
        assertEquals(AffinityProgress.of(100), AffinityProgress.of(250))
        assertEquals(0, AffinityProgress.of(-20).score)
    }

    @Test
    fun firstScoreOfEachLevel() {
        assertEquals(listOf(0, 6, 36, 61, 76), AffinityLevel.entries.map { AffinityProgress.firstScore(it) })
    }
}
