package com.elyndra.launcher.domain

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.PlayStats
import com.elyndra.launcher.domain.Fixtures.DAY
import com.elyndra.launcher.domain.Fixtures.NOW
import com.elyndra.launcher.domain.Fixtures.folder
import com.elyndra.launcher.domain.Fixtures.rom
import com.elyndra.launcher.domain.Fixtures.session
import com.elyndra.launcher.domain.Fixtures.withArt
import com.elyndra.launcher.domain.curation.CurationReport
import com.elyndra.launcher.domain.curation.LibraryCurator
import com.elyndra.launcher.domain.device.DeviceState
import com.elyndra.launcher.domain.insights.Insight
import com.elyndra.launcher.domain.insights.InsightInput
import com.elyndra.launcher.domain.insights.MashaInsights
import com.elyndra.launcher.domain.lists.DynamicList
import com.elyndra.launcher.domain.lists.ListContext
import com.elyndra.launcher.domain.lists.ListRule
import com.elyndra.launcher.domain.lists.SmartLists
import com.elyndra.launcher.domain.profile.GameProfiles
import com.elyndra.launcher.domain.session.ArcStatus
import com.elyndra.launcher.domain.session.Arcs
import com.elyndra.launcher.domain.session.PlanReason
import com.elyndra.launcher.domain.session.SessionMood
import com.elyndra.launcher.domain.session.SessionPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Listas inteligentes, minisesiones, arcos y lo que Masha dice por su cuenta. */
class PlanningTest {

    private val ps2 = folder("ps2", "ps2", "nethersx2")
    private val gba = folder("gba", "gba", "ra_mgba")

    // Okami: en curso (jugado hace 2 días, sesiones de ~45 min).
    private val okami = rom("okami", ps2, meta = withArt(genre = "Action-Adventure"), stats = PlayStats(135, NOW - 2 * DAY, 3))
    // Kingdom Hearts: abandonado (70 min y 40 días sin tocarlo).
    private val kh = rom("kh", ps2, meta = withArt(), stats = PlayStats(70, NOW - 40 * DAY, 2))
    // Tetris: ligero, jugado mucho y en sesiones cortas.
    private val tetris = rom("tetris", gba, meta = withArt(genre = "Puzzle", logo = true, hero = true), stats = PlayStats(100, NOW - DAY, 5))
    // Dos juegos nunca abiertos, uno con carátula.
    private val sotc = rom("sotc", ps2, meta = withArt(), addedAt = NOW - 3 * DAY)
    private val pokemon = rom("pokemon", gba, addedAt = NOW - 10 * DAY)

    private val sessions = listOf(
        session("r:okami", 9.0, 45, "nethersx2"),
        session("r:okami", 5.0, 50, "nethersx2"),
        session("r:okami", 2.0, 40, "nethersx2"),
        session("r:kh", 45.0, 60, "nethersx2"),
        session("r:kh", 40.0, 10, "nethersx2"),
        session("r:tetris", 8.0, 20),
        session("r:tetris", 6.0, 18),
        session("r:tetris", 4.0, 22),
        session("r:tetris", 3.0, 20),
        session("r:tetris", 1.0, 20),
    )

    private val library = Fixtures.library(listOf(ps2, gba), listOf(okami, kh, tetris, sotc, pokemon), sessions = sessions)
    private val games = library.games()
    private val profiles = GameProfiles.buildAll(games, library.sessions, now = NOW)
    private val curation = LibraryCurator.analyze(games)
    private val ctx = ListContext(games, profiles, curation, NOW)

    private fun keys(rule: ListRule) = SmartLists.evaluate(rule, ctx).map { it.key }

    /* ── listas ───────────────────────────────────────────────── */

    @Test
    fun dynamicListsSortTheLibrary() {
        assertEquals(listOf("r:sotc", "r:pokemon"), keys(ListRule(list = DynamicList.NeverOpened.id)))
        assertEquals(listOf("r:kh"), keys(ListRule(list = DynamicList.Abandoned.id)))
        assertEquals(setOf("r:okami", "r:tetris"), keys(ListRule(list = DynamicList.InProgress.id)).toSet())
        assertEquals(listOf("r:pokemon"), keys(ListRule(list = DynamicList.ArtworkMissing.id)))
        assertEquals(listOf("r:tetris"), keys(ListRule(list = DynamicList.ArtworkComplete.id)))
    }

    @Test
    fun bestOnDeviceNeedsSeveralCleanSessions() {
        // Kingdom Hearts está abandonado, pero cuando se jugó fue bien (mediana 35 min): cuenta.
        assertEquals(setOf("r:okami", "r:tetris", "r:kh"), keys(ListRule(list = DynamicList.BestOnDevice.id)).toSet())
        // Una salida al instante en la última sesión lo saca de la lista.
        val bailed = GameProfiles.buildAll(games, library.sessions + session("r:tetris", 0.5, 1), now = NOW)
        assertTrue("r:tetris" !in SmartLists.evaluate(ListRule(list = DynamicList.BestOnDevice.id), ctx.copy(profiles = bailed)).map { it.key })
    }

    @Test
    fun rulesCombine() {
        assertEquals(listOf("r:okami"), keys(ListRule(systems = listOf("ps2"), genres = listOf("adventure"))))
        assertEquals(listOf("r:okami", "r:tetris"), keys(ListRule(minMinutes = 100, sort = "minutes")))
        assertEquals(listOf("r:kh"), keys(ListRule(notPlayedForDays = 30)))
        assertEquals(listOf("r:tetris", "r:okami"), keys(ListRule(keys = listOf("r:tetris", "missing", "r:okami"))))
        assertEquals(1, keys(ListRule(limit = 1)).size)
    }

    /* ── minisesiones ─────────────────────────────────────────── */

    @Test
    fun continuePicksWhatIsInProgressAndFitsTheTime() {
        val plan = SessionPlanner.plan(30, 40, SessionMood.Continue, games, profiles, now = NOW)
        // Lo último que se jugó de verdad: Tetris, ayer.
        assertEquals("r:tetris", plan.blocks.first().game.key)
        assertEquals(PlanReason.Continue, plan.blocks.first().reason)
        assertTrue(plan.totalMinutes in 30..40)
        // Lo nunca abierto no es "seguir".
        assertTrue(plan.blocks.none { it.game.key == "r:sotc" || it.game.key == "r:pokemon" })
    }

    @Test
    fun somethingLightAvoidsHeavySystems() {
        val plan = SessionPlanner.plan(15, 30, SessionMood.Light, games, profiles, now = NOW)
        assertTrue(plan.blocks.isNotEmpty())
        assertTrue(plan.blocks.all { SystemWeight.of(it.game.systemId) != SystemWeight.Heavy })
    }

    @Test
    fun aHotPhoneLeavesHeavyConsolesOut() {
        val hot = DeviceState(thermal = DeviceState.Thermal.Severe)
        val plan = SessionPlanner.plan(30, 45, SessionMood.Continue, games, profiles, device = hot, now = NOW)
        assertTrue(plan.deviceLimited)
        assertTrue(plan.blocks.none { it.game.systemId == "ps2" })
    }

    @Test
    fun somethingNewPrefersUnopenedGamesWithArt() {
        val plan = SessionPlanner.plan(30, 45, SessionMood.Fresh, games, profiles, now = NOW)
        assertEquals("r:sotc", plan.blocks.first().game.key)
    }

    @Test
    fun ownSessionsDecideHowLongAGameUsuallyLasts() {
        assertEquals(45, SessionPlanner.typicalMinutes(games.first { it.key == "r:okami" }, profiles["r:okami"]))
        // Sin historial: por sistema y género (GBA ligero, puzle más corto aún).
        val puzzle = rom("p", gba, meta = GameMeta(genre = "Puzzle"))
        val g = Fixtures.library(listOf(gba), listOf(puzzle)).games().single()
        assertEquals(14, SessionPlanner.typicalMinutes(g, null))
    }

    /* ── arcos ────────────────────────────────────────────────── */

    @Test
    fun anArcCountsFromWhereEachGameWas() {
        val arc = Arcs.draft("arc1", "PS2 week", "ps2", null, listOf("r:okami", "r:kh", "r:nope"), games.associateBy { it.key }, profiles, targetMinutes = 60, now = NOW)
        assertEquals(2, arc.steps.size)
        assertEquals(135, arc.steps[0].baselineMinutes)
        assertEquals(ArcStatus.Active, arc.status)

        var okamiMinutes = 135
        val progress = Arcs.progress(arc) { if (it == "r:okami") okamiMinutes else 70 }
        assertEquals("r:okami", progress.current?.gameKey)
        assertEquals(0, progress.currentMinutes)

        okamiMinutes = 200
        assertEquals(listOf(0), Arcs.reachedSteps(arc) { if (it == "r:okami") okamiMinutes else 70 }.map { it.position })
    }

    @Test
    fun defaultStepTargetsStayReasonable() {
        val target = Arcs.defaultTarget(games.first { it.key == "r:tetris" }, profiles["r:tetris"])
        assertTrue(target in Arcs.MIN_STEP_MINUTES..Arcs.MAX_STEP_MINUTES)
    }

    /* ── lo que Masha dice por su cuenta ──────────────────────── */

    private fun insights(dismissed: Set<String> = emptySet(), device: DeviceState? = null, configured: Boolean = true) =
        MashaInsights.compute(
            InsightInput(
                games = games,
                profiles = profiles,
                curation = curation,
                arcs = emptyList(),
                device = device,
                servicesConfigured = configured,
                missingEmulators = emptyList(),
                lastMetadataPass = null,
                dismissed = dismissed,
                now = NOW,
            ),
        )

    @Test
    fun mashaRemindsYouOfWhatYouWerePlaying() {
        val top = insights().first()
        assertTrue(top is Insight.ContinueGame)
        top as Insight.ContinueGame
        // Tetris se jugó ayer; Okami hace dos días: gana lo más reciente.
        assertEquals("r:tetris", top.gameKey)
    }

    @Test
    fun dismissedInsightsStayQuiet() {
        val first = insights().first()
        assertTrue(insights(dismissed = setOf(first.id)).none { it.id == first.id })
    }

    @Test
    fun aHotPhoneComesFirst() {
        assertTrue(insights(device = DeviceState(thermal = DeviceState.Thermal.Critical)).first() is Insight.DeviceHot)
    }

    @Test
    fun abandonedAndUnopenedGamesAreMentioned() {
        val all = insights()
        assertTrue(all.any { it is Insight.Abandoned && it.gameKey == "r:kh" })
        assertTrue(all.any { it is Insight.NeverOpened && it.count == 2 })
    }

    @Test
    fun anEmptyLibraryOnlyAsksForGames() {
        val empty = MashaInsights.compute(
            InsightInput(emptyList(), emptyMap(), CurationReport(), emptyList(), null, false, emptyList(), null, emptySet(), NOW),
        )
        assertEquals(listOf(Insight.EmptyLibrary), empty)
    }
}
