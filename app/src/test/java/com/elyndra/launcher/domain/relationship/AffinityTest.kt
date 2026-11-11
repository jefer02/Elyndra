package com.elyndra.launcher.domain.relationship

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** La relación con Lucy: tramos, recortes, tope diario y que nada baje solo. */
class AffinityTest {

    private val zone = ZoneOffset.UTC
    private var now = at(2026, 11, 10, 12)

    private fun at(y: Int, m: Int, d: Int, h: Int): Long = LocalDateTime.of(y, m, d, h, 0).toInstant(zone).toEpochMilli()

    private fun affinity(store: AffinityStore = MemoryAffinityStore()) = Affinity(store, { now }, { zone as ZoneId })

    @Test
    fun levelsCoverTheirRanges() {
        assertEquals(AffinityLevel.Distant, AffinityLevel.of(0))
        assertEquals(AffinityLevel.Distant, AffinityLevel.of(5))
        assertEquals(AffinityLevel.Curious, AffinityLevel.of(6))
        assertEquals(AffinityLevel.Curious, AffinityLevel.of(35))
        assertEquals(AffinityLevel.Friendly, AffinityLevel.of(36))
        assertEquals(AffinityLevel.Friendly, AffinityLevel.of(60))
        assertEquals(AffinityLevel.Close, AffinityLevel.of(61))
        assertEquals(AffinityLevel.Close, AffinityLevel.of(75))
        assertEquals(AffinityLevel.Devoted, AffinityLevel.of(76))
        assertEquals(AffinityLevel.Devoted, AffinityLevel.of(100))
        assertEquals(AffinityLevel.Distant, AffinityLevel.of(-20))
        assertEquals(AffinityLevel.Devoted, AffinityLevel.of(400))
        assertEquals("devoted", AffinityLevel.Devoted.id)
    }

    @Test
    fun itStartsAtTenCurious() {
        val a = affinity()
        assertEquals(10, a.score)
        assertEquals(AffinityLevel.Curious, a.level)
    }

    @Test
    fun eachDeltaIsClampedToTen() {
        val a = affinity(MemoryAffinityStore(score = 50))
        assertEquals(60, a.apply(+40).after)
        assertEquals(50, a.apply(-99).after)
    }

    @Test
    fun gainsAreCappedAtFifteenADayAndLossesAreNot() {
        val a = affinity()
        a.apply(10)
        assertEquals(25, a.apply(10).after) // solo caben 5 más hoy
        assertEquals(25, a.apply(10).after)
        assertEquals(15, a.apply(-10).after) // bajar no tiene tope…
        assertEquals(15, a.apply(5).after) // …y no devuelve cupo
    }

    @Test
    fun theDailyCapResetsTheNextLocalDay() {
        val a = affinity()
        a.apply(10)
        a.apply(10)
        assertEquals(25, a.score)
        now = at(2026, 11, 11, 0)
        assertEquals(35, a.apply(10).after)
        assertEquals(40, a.apply(10).after)
    }

    @Test
    fun askingForPlusTenForeverCannotFarmTheLevel() {
        val a = affinity()
        repeat(200) { a.apply(10) }
        assertEquals(25, a.score)
        assertEquals(AffinityLevel.Curious, a.level)
    }

    @Test
    fun theScoreStaysWithinZeroAndOneHundred() {
        val low = affinity(MemoryAffinityStore(score = 3))
        assertEquals(0, low.apply(-10).after)
        val high = affinity(MemoryAffinityStore(score = 98))
        assertEquals(100, high.apply(10).after)
    }

    @Test
    fun beingAwayNeverLowersIt() {
        val store = MemoryAffinityStore(score = 64)
        val a = affinity(store)
        now = at(2027, 6, 1, 9) // medio año sin abrir la app
        assertEquals(64, a.score)
        assertEquals(64, a.apply(0).after)
    }

    @Test
    fun levelUpIsReportedOnlyWhenTheLevelRises() {
        val a = affinity(MemoryAffinityStore(score = 33))
        assertTrue(a.apply(4).levelUp)
        assertFalse(a.apply(2).levelUp)
        val down = a.apply(-10)
        assertFalse(down.levelUp)
        assertEquals(AffinityLevel.Curious, down.newLevel)
    }

    @Test
    fun resetGoesBackToTheStart() {
        val store = MemoryAffinityStore(score = 80, gainedToday = 12)
        affinity(store).reset()
        assertEquals(Affinity.START, store.score)
        assertEquals(0, store.gainedToday)
    }

    @Test
    fun gainsAreRememberedWithTheirReasonButLossesAreNot() {
        val a = affinity()
        a.apply(3, GainReason.CuriousAboutHer)
        a.apply(-4)
        a.apply(2, GainReason.Humour)
        assertEquals(listOf(2 to GainReason.Humour, 3 to GainReason.CuriousAboutHer), a.recentGains.map { it.points to it.reason })
        assertEquals(now, a.recentGains.first().at)
    }

    @Test
    fun gainsRecordWhatTheDailyCapLetThrough() {
        val a = affinity()
        a.apply(10, GainReason.Compliment)
        a.apply(10, GainReason.Compliment) // solo entran 5 (tope 15)
        a.apply(10, GainReason.Compliment) // nada: no se apunta
        assertEquals(listOf(5, 10), a.recentGains.map { it.points })
    }

    @Test
    fun onlyTheLastEightGainsAreKept() {
        val a = affinity()
        repeat(12) { day ->
            now = at(2026, 11, 1 + day, 12)
            a.apply(1, GainReason.Greeting)
        }
        assertEquals(Affinity.MAX_GAINS, a.recentGains.size)
    }

    @Test
    fun eachLevelIsCelebratedOnce() {
        val store = MemoryAffinityStore(score = 33)
        val a = affinity(store)
        val up = a.apply(5) // 33 → 38: Friendly por primera vez
        assertEquals(true, up.levelUp)
        assertEquals(true, up.celebrate)
        a.apply(-5) // vuelve a Curious
        now = at(2026, 11, 11, 12)
        val again = a.apply(5) // otra vez Friendly: sube, pero ya se celebró
        assertEquals(true, again.levelUp)
        assertEquals(false, again.celebrate)
    }

    @Test
    fun aStoreFromBeforeV3StartsCelebratingFromItsCurrentLevel() {
        // Sin "celebrated" guardado: el nivel actual cuenta como visto, no se celebra lo ya alcanzado.
        val store = MemoryAffinityStore(score = 70, celebrated = -1)
        val a = affinity(store)
        assertEquals(false, a.apply(1).celebrate)
        assertEquals(AffinityLevel.Close.ordinal, store.celebrated)
    }

    @Test
    fun resetForgetsGainsAndCelebrations() {
        val store = MemoryAffinityStore(score = 70, celebrated = 4, gains = listOf(AffinityGain(3, GainReason.Chat, 1)))
        affinity(store).reset()
        assertEquals(emptyList<AffinityGain>(), store.gains)
        assertEquals(AffinityLevel.Curious.ordinal, store.celebrated)
    }
}
