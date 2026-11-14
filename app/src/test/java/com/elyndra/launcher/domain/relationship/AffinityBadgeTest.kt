package com.elyndra.launcher.domain.relationship

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La insignia de confianza: nivel N de 5, aro hacia el siguiente y puntos que faltan. */
class AffinityBadgeTest {

    @Test
    fun theBadgeSaysTheLevelNumberAndWhatIsLeft() {
        val p = AffinityProgress.of(AffinityProgress.firstScore(AffinityLevel.Curious) + 3)
        val b = AffinityBadge.of(p)
        assertEquals(AffinityLevel.Curious, b.level)
        assertEquals(2, b.number)
        assertEquals(5, b.total)
        assertEquals(AffinityLevel.Friendly, b.next)
        assertEquals(p.pointsToNext, b.pointsToNext)
        assertEquals(p.fraction, b.ring, 0.0001f)
    }

    @Test
    fun theLastLevelHasAFullRing() {
        val b = AffinityBadge.of(AffinityProgress.of(Affinity.MAX))
        assertEquals(5, b.number)
        assertEquals(1f, b.ring, 0f)
        assertNull(b.next)
    }

    @Test
    fun levellingUpFillsTheRingThenStartsAgain() {
        val before = AffinityBadge.of(AffinityProgress.of(AffinityLevel.Curious.max))
        val after = AffinityBadge.of(AffinityProgress.of(AffinityLevel.Curious.max + 2))
        assertEquals(listOf(1f, 0f, after.ring), AffinityBadge.ringSteps(before, after))
        val same = AffinityBadge.of(AffinityProgress.of(AffinityLevel.Curious.max - 1))
        assertEquals(listOf(before.ring), AffinityBadge.ringSteps(same, before))
    }
}
