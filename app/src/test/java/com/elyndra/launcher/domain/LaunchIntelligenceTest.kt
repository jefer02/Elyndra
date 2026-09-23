package com.elyndra.launcher.domain

import com.elyndra.launcher.data.PlayStats
import com.elyndra.launcher.domain.Fixtures.NOW
import com.elyndra.launcher.domain.Fixtures.folder
import com.elyndra.launcher.domain.Fixtures.rom
import com.elyndra.launcher.domain.Fixtures.session
import com.elyndra.launcher.domain.device.DeviceState
import com.elyndra.launcher.domain.device.DeviceWarning
import com.elyndra.launcher.domain.launch.EmulatorRanker
import com.elyndra.launcher.domain.launch.LaunchAdvisor
import com.elyndra.launcher.domain.launch.LaunchContext
import com.elyndra.launcher.domain.launch.LaunchDecision
import com.elyndra.launcher.domain.launch.LaunchNote
import com.elyndra.launcher.domain.launch.Verdict
import com.elyndra.launcher.domain.profile.EmulatorUsage
import com.elyndra.launcher.domain.profile.GameProfiles
import com.elyndra.launcher.domain.profile.LaunchOutcome
import com.elyndra.launcher.domain.profile.LaunchRecord
import com.elyndra.launcher.domain.profile.PlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Perfil vivo de cada juego, clasificación de emuladores y la decisión de lanzamiento. */
class LaunchIntelligenceTest {

    private val ps2 = folder("ps2", "ps2", emulatorId = "nethersx2")

    /* ── perfil vivo ──────────────────────────────────────────── */

    @Test
    fun playStatesFollowTheHistory() {
        assertEquals(PlayState.New, GameProfiles.state(0, 0, 0, NOW))
        assertEquals(PlayState.InProgress, GameProfiles.state(120, 3, NOW - 2 * Fixtures.DAY, NOW))
        assertEquals(PlayState.Tried, GameProfiles.state(5, 1, NOW - 2 * Fixtures.DAY, NOW))
        assertEquals(PlayState.Abandoned, GameProfiles.state(90, 4, NOW - 40 * Fixtures.DAY, NOW))
        assertEquals(PlayState.Dormant, GameProfiles.state(3000, 40, NOW - 40 * Fixtures.DAY, NOW))
    }

    @Test
    fun theProfileRemembersSessionsAndEmulators() {
        val game = rom("okami", ps2, stats = PlayStats(minutes = 71, lastPlayed = NOW - 3 * Fixtures.DAY, launches = 3))
        val sessions = listOf(
            session(game.key, 10.0, 30, "nethersx2"),
            session(game.key, 5.0, 40, "nethersx2"),
            session(game.key, 3.0, 1, "aethersx2"),
            session("r:other", 1.0, 90, "nethersx2"),
        )
        val g = Fixtures.library(listOf(ps2), listOf(game)).games().single()
        val p = GameProfiles.build(g, sessions, now = NOW)
        assertEquals(3, p.sessions)
        assertEquals(1, p.earlyExits)
        assertEquals(35, p.medianMinutes)
        assertEquals("aethersx2", p.lastEmulator)
        assertTrue(p.lastWasEarlyExit)
        val usage = p.emulators.associateBy { it.emulatorId }
        assertEquals(2, usage.getValue("nethersx2").goodSessions)
        assertEquals(1, usage.getValue("aethersx2").earlyExits)
    }

    /* ── clasificación de emuladores ──────────────────────────── */

    private fun usage(id: String, good: Int = 0, early: Int = 0, failed: Int = 0, minutes: Int = good * 40) =
        EmulatorUsage(id, good + early, good, early, minutes, good + early, failed, NOW)

    @Test
    fun withoutHistoryTheCommunityOrderWins() {
        val ranked = EmulatorRanker.rank(listOf("a", "b", "c"), { true }, emptyList(), emptyList())
        assertEquals(listOf("a", "b", "c"), ranked.map { it.emulatorId })
        assertTrue(ranked.all { it.verdict == Verdict.Untested })
    }

    @Test
    fun whatWorkedHereBeatsTheCommunityOrder() {
        val ranked = EmulatorRanker.rank(listOf("a", "b"), { true }, gameUsage = listOf(usage("b", good = 3)), systemUsage = emptyList())
        assertEquals("b", ranked.first().emulatorId)
        assertEquals(Verdict.Proven, ranked.first().verdict)
    }

    @Test
    fun instantExitsSinkAnEmulator() {
        val ranked = EmulatorRanker.rank(listOf("a", "b"), { true }, gameUsage = listOf(usage("a", early = 3)), systemUsage = emptyList())
        assertEquals("b", ranked.first().emulatorId)
        assertEquals(Verdict.Struggling, ranked.first { it.emulatorId == "a" }.verdict)
    }

    @Test
    fun notInstalledGoesLast() {
        val ranked = EmulatorRanker.rank(listOf("a", "b"), { it == "b" }, emptyList(), emptyList())
        assertEquals(listOf("b", "a"), ranked.map { it.emulatorId })
    }

    /* ── decisión de lanzamiento ──────────────────────────────── */

    private fun ctx(
        romEmulator: String? = null,
        folderEmulator: String? = "a",
        installed: Set<String> = setOf("a", "b"),
        gameUsage: List<EmulatorUsage> = emptyList(),
        device: DeviceState? = null,
        systemId: String = "psx",
    ) = LaunchContext(
        gameKey = "r:x",
        systemId = systemId,
        romEmulator = romEmulator,
        folderEmulator = folderEmulator,
        curated = listOf("a", "b", "c"),
        isInstalled = { it in installed },
        gameUsage = gameUsage,
        systemUsage = emptyList(),
        profile = null,
        device = device,
        now = NOW,
    )

    @Test
    fun theUsersChoiceAlwaysLaunches() {
        // Aunque la clasificación prefiera otro, se lanza con el elegido.
        val d = LaunchAdvisor.decide(ctx(folderEmulator = "b", gameUsage = listOf(usage("a", good = 5))))
        assertEquals(LaunchDecision.Go("b", chosenByUser = true, note = null, ranked = d.ranked), d)
    }

    @Test
    fun aMissingEmulatorGetsAnInstalledAlternative() {
        val d = LaunchAdvisor.decide(ctx(folderEmulator = "c"))
        assertTrue(d is LaunchDecision.UseInstead)
        assertEquals("a", (d as LaunchDecision.UseInstead).alternative.emulatorId)
    }

    @Test
    fun aStrugglingEmulatorTriggersASuggestionNotASwitch() {
        val d = LaunchAdvisor.decide(
            ctx(folderEmulator = "a", gameUsage = listOf(usage("a", early = 2), usage("b", good = 1))),
        )
        assertTrue(d is LaunchDecision.SuggestSwitch)
        d as LaunchDecision.SuggestSwitch
        assertEquals("a", d.configured)
        assertEquals("b", d.alternative.emulatorId)
    }

    @Test
    fun withNothingInstalledItOffersToInstall() {
        val d = LaunchAdvisor.decide(ctx(folderEmulator = null, installed = emptySet()))
        assertEquals("a", (d as LaunchDecision.NoneInstalled).recommended)
    }

    @Test
    fun withoutAChoiceTheBestInstalledGoes() {
        val d = LaunchAdvisor.decide(ctx(folderEmulator = null, gameUsage = listOf(usage("b", good = 2))))
        assertEquals("b", (d as LaunchDecision.Go).emulatorId)
        assertTrue(!d.chosenByUser)
        assertEquals(LaunchNote.Proven("b", 2), d.note)
    }

    @Test
    fun heavySystemsOnLowBatteryGetAWarning() {
        val low = DeviceState(batteryPct = 12, charging = false)
        val heavy = LaunchAdvisor.decide(ctx(systemId = "ps2", device = low)) as LaunchDecision.Go
        assertEquals(LaunchNote.Device(listOf(DeviceWarning.LowBattery)), heavy.note)
        // Un juego de Game Boy con la misma batería no merece el aviso.
        val light = LaunchAdvisor.decide(ctx(systemId = "gb", device = low)) as LaunchDecision.Go
        assertTrue(light.note !is LaunchNote.Device)
    }

    @Test
    fun failedLaunchesCountAgainstTheEmulator() {
        val launches = listOf(
            LaunchRecord("r:x", "psx", "a", NOW - 10, LaunchOutcome.FAILED),
            LaunchRecord("r:x", "psx", "a", NOW - 5, LaunchOutcome.FAILED),
        )
        val u = GameProfiles.usage(emptyList(), launches).single()
        assertEquals(2, u.failedLaunches)
        assertEquals(Verdict.Struggling, EmulatorRanker.verdict(u, null))
    }
}
