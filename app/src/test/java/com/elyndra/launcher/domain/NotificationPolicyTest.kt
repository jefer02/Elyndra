package com.elyndra.launcher.domain

import com.elyndra.launcher.domain.notify.LucyNotice
import com.elyndra.launcher.domain.notify.MemoryNotificationPolicyStore
import com.elyndra.launcher.domain.notify.NoticeContext
import com.elyndra.launcher.domain.notify.NoticeVerdict
import com.elyndra.launcher.domain.notify.NotificationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class NotificationPolicyTest {

    private val zone: ZoneId = ZoneOffset.ofHours(2)
    private val hour = 60L * 60 * 1000

    /** Un reloj que se puede adelantar a mano. */
    private class TestClock(var instant: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = TestClock(instant, zone)
        override fun instant(): Instant = instant
        fun advance(ms: Long) { instant = instant.plusMillis(ms) }
    }

    private fun at(day: Int, h: Int, m: Int = 0): Instant =
        LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant()

    private val away = NoticeContext(foreground = false)
    private val playing = NoticeContext(foreground = false, inSession = true)

    private fun policy(clock: TestClock, store: MemoryNotificationPolicyStore) = NotificationPolicy(store, clock)

    /** La app se abrió por última vez hace [hoursAgo] horas. */
    private fun store(clock: Clock, hoursAgo: Long = 30) =
        MemoryNotificationPolicyStore(lastOpenedAt = clock.millis() - hoursAgo * hour)

    /* ── comunes ─────────────────────────────────────────────── */

    @Test
    fun nothingWhileElyndraIsOnScreen() {
        val clock = TestClock(at(10, 12), zone)
        val p = policy(clock, store(clock))
        val here = NoticeContext(foreground = true, inSession = true)
        LucyNotice.entries.forEach { assertEquals(it.name, NoticeVerdict.Foreground, p.decide(it, here)) }
    }

    @Test
    fun theSettingsToggleSilencesEveryKind() {
        val clock = TestClock(at(10, 12), zone)
        val p = policy(clock, store(clock))
        val off = NoticeContext(foreground = false, inSession = true, enabled = false)
        LucyNotice.entries.forEach { assertEquals(it.name, NoticeVerdict.Disabled, p.decide(it, off)) }
    }

    /* ── calor ───────────────────────────────────────────────── */

    @Test
    fun heatOnlyDuringAGameSession() {
        val clock = TestClock(at(10, 15), zone)
        val p = policy(clock, store(clock))
        assertEquals(NoticeVerdict.NotInSession, p.decide(LucyNotice.Hot, away))
        assertEquals(NoticeVerdict.Allowed, p.decide(LucyNotice.Hot, playing))
    }

    @Test
    fun heatAtMostOnceEveryTwoHoursAtAnyHour() {
        val clock = TestClock(at(10, 23, 30), zone)
        val p = policy(clock, store(clock))
        assertEquals(NoticeVerdict.Allowed, p.decide(LucyNotice.Hot, playing))
        p.markSent(LucyNotice.Hot)
        clock.advance(hour + 59 * 60_000L)
        assertEquals(NoticeVerdict.Cooldown, p.decide(LucyNotice.Hot, playing))
        clock.advance(60_000L)
        assertEquals(NoticeVerdict.Allowed, p.decide(LucyNotice.Hot, playing))
    }

    /* ── juegos nuevos y metadatos ───────────────────────────── */

    @Test
    fun eventsNeverAtNight() {
        for (h in listOf(22, 23, 0, 3, 8)) {
            val clock = TestClock(at(10, h), zone)
            val p = policy(clock, store(clock))
            assertEquals("$h h", NoticeVerdict.QuietHours, p.decide(LucyNotice.NewGames, away))
            assertEquals("$h h", NoticeVerdict.QuietHours, p.decide(LucyNotice.MetadataDone, away))
            assertEquals("$h h", NoticeVerdict.QuietHours, p.decide(LucyNotice.Daily, away))
        }
        val nine = TestClock(at(10, 9), zone)
        assertEquals(NoticeVerdict.Allowed, policy(nine, store(nine)).decide(LucyNotice.NewGames, away))
    }

    @Test
    fun eachEventKindHasItsOwnCooldown() {
        val clock = TestClock(at(10, 9), zone)
        val p = policy(clock, store(clock))
        p.markSent(LucyNotice.NewGames)
        assertEquals(NoticeVerdict.Cooldown, p.decide(LucyNotice.NewGames, away))
        assertEquals(NoticeVerdict.Allowed, p.decide(LucyNotice.MetadataDone, away))
        p.markSent(LucyNotice.MetadataDone)
        clock.advance(6 * hour)
        assertEquals(NoticeVerdict.Allowed, p.decide(LucyNotice.NewGames, away))
        assertEquals(NoticeVerdict.Cooldown, p.decide(LucyNotice.MetadataDone, away))
        clock.advance(6 * hour)
        assertEquals(NoticeVerdict.Allowed, p.decide(LucyNotice.MetadataDone, away))
    }

    @Test
    fun noMoreThanThreeADay() {
        val clock = TestClock(at(10, 9), zone)
        val p = NotificationPolicy(store(clock), clock, newGamesCooldownMs = hour, metadataCooldownMs = hour)
        p.markSent(LucyNotice.NewGames)
        clock.advance(hour)
        p.markSent(LucyNotice.MetadataDone)
        clock.advance(hour)
        p.markSent(LucyNotice.NewGames)
        clock.advance(hour)
        assertEquals(NoticeVerdict.DailyCap, p.decide(LucyNotice.NewGames, away))
        assertEquals(NoticeVerdict.DailyCap, p.decide(LucyNotice.MetadataDone, away))
        // Al día siguiente el cupo vuelve a empezar.
        clock.instant = at(11, 9)
        assertEquals(NoticeVerdict.Allowed, p.decide(LucyNotice.NewGames, away))
    }

    /* ── nota diaria ─────────────────────────────────────────── */

    @Test
    fun theDailyNoteOnlyIfTheAppWasNotOpenedForTwentyHours() {
        val clock = TestClock(at(10, 12), zone)
        assertEquals(NoticeVerdict.RecentlyOpened, policy(clock, store(clock, hoursAgo = 19)).decide(LucyNotice.Daily, away))
        assertEquals(NoticeVerdict.Allowed, policy(clock, store(clock, hoursAgo = 20)).decide(LucyNotice.Daily, away))
    }

    @Test
    fun theDailyNoteAtMostOncePerCalendarDay() {
        val clock = TestClock(at(10, 9), zone)
        val p = policy(clock, store(clock))
        p.markSent(LucyNotice.Daily)
        clock.instant = at(10, 21, 59)
        assertEquals(NoticeVerdict.AlreadyToday, p.decide(LucyNotice.Daily, away))
        // Día natural, no 24 h: a las 9:00 del día siguiente ya puede.
        clock.instant = at(11, 9)
        assertEquals(NoticeVerdict.Allowed, p.decide(LucyNotice.Daily, away))
    }

    @Test
    fun noDailyNoteOnADayAnotherLucyNotificationWentOut() {
        for (other in listOf(LucyNotice.Hot, LucyNotice.NewGames, LucyNotice.MetadataDone)) {
            val clock = TestClock(at(10, 10), zone)
            val p = policy(clock, store(clock))
            p.markSent(other)
            clock.advance(3 * hour)
            assertEquals(other.name, NoticeVerdict.AlreadyToday, p.decide(LucyNotice.Daily, away))
        }
    }

    @Test
    fun aHotAlertLateLastNightDoesNotBlockToday() {
        val clock = TestClock(at(10, 23, 50), zone)
        val p = policy(clock, store(clock))
        p.markSent(LucyNotice.Hot)
        clock.instant = at(11, 10)
        assertEquals(NoticeVerdict.Allowed, p.decide(LucyNotice.Daily, away))
    }

    @Test
    fun afterThreeIgnoredNotesSheWaitsUntilTheAppIsOpened() {
        val clock = TestClock(at(10, 10), zone)
        val s = store(clock)
        val p = policy(clock, s)
        for (day in 10..12) {
            clock.instant = at(day, 10)
            assertEquals("día $day", NoticeVerdict.Allowed, p.decide(LucyNotice.Daily, away))
            p.markSent(LucyNotice.Daily)
        }
        clock.instant = at(13, 10)
        assertEquals(NoticeVerdict.BackOff, p.decide(LucyNotice.Daily, away))
        clock.instant = at(20, 10)
        assertEquals(NoticeVerdict.BackOff, p.decide(LucyNotice.Daily, away))

        // Abre la app el día 20 a las 10:30; la racha vuelve a cero, pero hoy ya no (la acaba de abrir).
        s.lastOpenedAt = at(20, 10, 30).toEpochMilli()
        clock.instant = at(20, 12)
        assertEquals(NoticeVerdict.RecentlyOpened, p.decide(LucyNotice.Daily, away))
        clock.instant = at(21, 6, 0)
        assertEquals(NoticeVerdict.QuietHours, p.decide(LucyNotice.Daily, away))
        clock.instant = at(21, 10)
        assertEquals(NoticeVerdict.Allowed, p.decide(LucyNotice.Daily, away))
    }

    @Test
    fun openingTheAppBetweenNotesKeepsTheStreakAtOne() {
        val clock = TestClock(at(10, 10), zone)
        val s = store(clock)
        val p = policy(clock, s)
        for (day in 10..16 step 2) {
            clock.instant = at(day, 10)
            assertEquals("día $day", NoticeVerdict.Allowed, p.decide(LucyNotice.Daily, away))
            p.markSent(LucyNotice.Daily)
            // La abre esa tarde: la siguiente nota no cuenta como ignorada.
            s.lastOpenedAt = at(day, 13).toEpochMilli()
        }
        assertEquals(1, s.dailyStreak)
    }

    @Test
    fun theDailyLineChangesEachDayButNotDuringIt() {
        val clock = TestClock(at(10, 9), zone)
        val p = policy(clock, store(clock))
        val morning = p.dailyLineIndex(5)
        clock.instant = at(10, 21)
        assertEquals(morning, p.dailyLineIndex(5))
        clock.instant = at(11, 9)
        assertNotEquals(morning, p.dailyLineIndex(5))
        assertEquals(0, p.dailyLineIndex(0))
    }

    @Test
    fun quietHoursFollowTheLocalZone() {
        // 07:00 UTC son las 09:00 en UTC+2: ya es de día.
        val clock = TestClock(Instant.parse("2026-11-10T07:00:00Z"), zone)
        assertEquals(NoticeVerdict.Allowed, policy(clock, store(clock)).decide(LucyNotice.MetadataDone, away))
        val utc = TestClock(Instant.parse("2026-11-10T07:00:00Z"), ZoneOffset.UTC)
        assertEquals(NoticeVerdict.QuietHours, policy(utc, store(utc)).decide(LucyNotice.MetadataDone, away))
    }
}
