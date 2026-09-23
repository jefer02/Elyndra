package com.elyndra.launcher.masha

import com.elyndra.launcher.core.device.DeviceStateMonitor
import com.elyndra.launcher.data.ArcRepository
import com.elyndra.launcher.data.SmartListRepository
import com.elyndra.launcher.domain.Game
import com.elyndra.launcher.domain.launch.Verdict
import com.elyndra.launcher.domain.lists.DynamicList
import com.elyndra.launcher.domain.lists.ListRule
import com.elyndra.launcher.domain.lists.SmartLists
import com.elyndra.launcher.domain.profile.PlayState
import com.elyndra.launcher.domain.session.ArcProgress
import com.elyndra.launcher.domain.session.Arcs
import com.elyndra.launcher.domain.session.SessionMood
import com.elyndra.launcher.domain.session.SessionPlan
import com.elyndra.launcher.domain.session.SessionPlanner
import com.elyndra.launcher.launch.LaunchOrchestrator
import com.elyndra.launcher.library.Names
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lo que Masha sabe hacer sin tocar la interfaz: buscar, perfilar, clasificar
 * emuladores, planear sesiones, crear listas y arcos, revisar la biblioteca,
 * contar estadísticas y recordar.
 *
 * Sirve igual a la IA en línea (como herramientas) y a Masha sin conexión
 * (OfflineMasha llama a estas mismas funciones): lo que se sabe hacer no
 * depende de tener red.
 */
@Singleton
class MashaSkills @Inject constructor(
    private val knowledge: MashaKnowledge,
    private val lists: SmartListRepository,
    private val arcs: ArcRepository,
    private val memory: MashaMemory,
    private val orchestrator: LaunchOrchestrator,
    private val device: DeviceStateMonitor,
) {
    /** Lo último propuesto en un plan: el siguiente intenta no repetirlo. */
    private val recentlyProposed = ArrayDeque<String>()

    /* ── buscar ───────────────────────────────────────────────── */

    suspend fun findGames(args: JsonObject): ToolResult {
        val s = knowledge.snapshot()
        val rule = ListRule(
            list = args.str("list"),
            systems = args.strings("systems"),
            genres = args.strings("genres"),
            query = args.str("query"),
            minMinutes = args.int("min_minutes"),
            maxMinutes = args.int("max_minutes"),
            playedWithinDays = args.int("played_within_days"),
            notPlayedForDays = args.int("not_played_for_days"),
            android = args.bool("android"),
            series = args.str("series"),
            sort = args.str("sort"),
        )
        val found = SmartLists.evaluate(rule, s.listContext())
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return gamesResult(found, limit, title = null, s)
    }

    suspend fun openList(args: JsonObject): ToolResult {
        val name = args.str("name") ?: return toolFail("missing list name")
        val s = knowledge.snapshot()
        DynamicList.byId(name.lowercase().replace(' ', '_'))?.let { dyn ->
            return gamesResult(SmartLists.evaluate(ListRule(list = dyn.id), s.listContext()), MAX_LIMIT, dyn.id, s)
        }
        val saved = lists.byName(name) ?: return toolFail("there is no saved list called '$name'")
        return gamesResult(SmartLists.evaluate(saved.rule, s.listContext()), MAX_LIMIT, saved.name, s)
    }

    private fun gamesResult(found: List<Game>, limit: Int, title: String?, s: KnowledgeSnapshot): ToolResult {
        val shown = found.take(limit)
        return toolOk(attachment = MashaAttachment.Games(title, shown.map { it.key }, found.size)) {
            put("count", found.size)
            put("shown", shown.size)
            putJsonArray("games") { shown.forEach { add(brief(it, s)) } }
        }
    }

    /* ── un juego ─────────────────────────────────────────────── */

    suspend fun gameProfile(args: JsonObject): ToolResult {
        val s = knowledge.snapshot()
        val game = args.str("title")?.let { s.resolve(it) } ?: return notFound(args.str("title"))
        val p = s.profiles[game.key]
        val meta = game.meta
        val memories = memory.about(game.key)
        return toolOk(attachment = MashaAttachment.Games(null, listOf(game.key))) {
            put("title", game.title)
            put("platform", game.platform)
            put("state", stateName(p?.state))
            put("minutes", p?.totalMinutes ?: game.stats.minutes)
            put("sessions", p?.sessions ?: 0)
            put("launches", p?.launches ?: game.stats.launches)
            p?.lastPlayed?.takeIf { it > 0 }?.let { put("last_played", MashaContextBuilder.ago(s.now - it)) }
            p?.medianMinutes?.takeIf { it > 0 }?.let { put("typical_session_minutes", it) }
            put("instant_exits", p?.earlyExits ?: 0)
            put("failed_launches", p?.failedLaunches ?: 0)
            p?.lastSession?.let { last ->
                putJsonObject("last_session") {
                    put("minutes", last.minutes)
                    put("when", MashaContextBuilder.ago(s.now - last.start))
                    last.emulatorId?.let { put("emulator", orchestrator.name(it)) }
                    put("exited_early", last.earlyExit)
                    put("measured_by", if (last.source == "usage") "system usage stats" else "launch-to-return time")
                }
            }
            putJsonArray("emulators_used") {
                p?.emulators.orEmpty().forEach { u ->
                    addJsonObject {
                        put("emulator", orchestrator.name(u.emulatorId))
                        put("sessions", u.sessions)
                        put("good_sessions", u.goodSessions)
                        put("instant_exits", u.earlyExits)
                        put("failed_launches", u.failedLaunches)
                        put("minutes", u.minutes)
                    }
                }
            }
            game.emulatorId?.let { put("configured_emulator", orchestrator.name(it)) }
            putJsonObject("metadata") {
                put("identified", meta.matched)
                meta.matchedBy?.let { put("matched_by", it) }
                meta.matchConfidence?.let { put("confidence", "%.2f".format(it)) }
                if (meta.sources.isNotEmpty()) put("sources", meta.sources.joinToString(","))
                meta.genre?.let { put("genre", it) }
                meta.developer?.let { put("developer", it) }
                meta.releaseDate?.let { put("released", it) }
                meta.description?.let { put("description", it.take(400)) }
            }
            putJsonObject("artwork") {
                put("cover", meta.cover != null)
                put("background", meta.hero != null || meta.screenshot != null)
                put("logo", meta.logo != null)
                put("icon", meta.icon != null)
                meta.artOrigins.forEach { (kind, origin) -> put("${kind}_from", origin.source) }
            }
            meta.ra?.let { ra ->
                putJsonObject("achievements") {
                    put("earned", ra.earned)
                    put("total", ra.achievements)
                    put("points", "${ra.earnedPoints}/${ra.points}")
                }
            }
            s.curation.seriesOf(game.key)?.let { put("series", it.name) }
            if (memories.isNotEmpty()) putJsonArray("you_remember") { memories.forEach { add(it.content) } }
        }
    }

    suspend fun suggestEmulator(args: JsonObject): ToolResult {
        val s = knowledge.snapshot()
        val game = args.str("title")?.let { s.resolve(it) } ?: return notFound(args.str("title"))
        val rom = game.rom ?: return toolFail("'${game.title}' is an Android game: it runs by itself, no emulator involved")
        val folder = s.library.folders.firstOrNull { it.id == rom.folderId } ?: return toolFail("folder not found")
        val ranked = orchestrator.ranking(rom, folder)
        return toolOk {
            put("title", game.title)
            put("system", game.platform)
            game.emulatorId?.let { put("currently_configured", orchestrator.name(it)) }
            putJsonArray("ranking") {
                ranked.take(6).forEach { r ->
                    addJsonObject {
                        put("emulator", orchestrator.name(r.emulatorId))
                        put("id", r.emulatorId)
                        put("installed", r.installed)
                        put("verdict", verdictName(r.verdict))
                        r.onGame?.let { u -> put("with_this_game", "${u.goodSessions} good sessions, ${u.earlyExits} instant exits, ${u.failedLaunches} failed launches") }
                        r.onSystem?.let { u -> put("with_this_system", "${u.goodSessions} good sessions, ${u.earlyExits} instant exits") }
                        if (r.preferenceIndex >= 0) put("community_rank", r.preferenceIndex + 1)
                    }
                }
            }
        }
    }

    /* ── sesiones y arcos ─────────────────────────────────────── */

    suspend fun planSession(args: JsonObject): ToolResult {
        val min = args.int("min_minutes") ?: 30
        val max = args.int("max_minutes") ?: (min + 15)
        val (plan, result) = planSession(min, max, SessionMood.byId(args.str("mood")))
        return if (plan.isEmpty) toolFail("nothing in the library fits that session") else result
    }

    suspend fun planSession(min: Int, max: Int, mood: SessionMood): Pair<SessionPlan, ToolResult> {
        val s = knowledge.snapshot()
        val arcKeys = arcProgress().mapNotNull { it.current?.gameKey }.toSet()
        val plan = SessionPlanner.plan(
            minMinutes = min,
            maxMinutes = max,
            mood = mood,
            games = s.games,
            profiles = s.profiles,
            device = device.snapshot(),
            arcKeys = arcKeys,
            avoid = synchronized(recentlyProposed) { recentlyProposed.toSet() },
            now = s.now,
        )
        synchronized(recentlyProposed) {
            plan.blocks.forEach { recentlyProposed.addLast(it.game.key) }
            while (recentlyProposed.size > 6) recentlyProposed.removeFirst()
        }
        val attachment = MashaAttachment.Plan(
            minMinutes = plan.minMinutes,
            maxMinutes = plan.maxMinutes,
            mood = plan.mood.id,
            blocks = plan.blocks.map { MashaAttachment.PlanItem(it.game.key, it.minutes, it.reason.name.lowercase()) },
        )
        return plan to toolOk(attachment = attachment) {
            put("total_minutes", plan.totalMinutes)
            if (plan.deviceLimited) put("note", "heavy systems left out: low battery, heat or power saving")
            putJsonArray("blocks") {
                plan.blocks.forEach { b ->
                    addJsonObject {
                        put("title", b.game.title)
                        put("platform", b.game.platform)
                        put("minutes", b.minutes)
                        put("why", b.reason.name.lowercase())
                        s.profiles[b.game.key]?.lastSession?.let { put("last_session", "${it.minutes} min, ${MashaContextBuilder.ago(s.now - it.start)}") }
                    }
                }
            }
        }
    }

    suspend fun arcProgress(): List<ArcProgress> {
        val s = knowledge.snapshot()
        return arcs.active().map { arc -> Arcs.progress(arc) { key -> s.profiles[key]?.totalMinutes ?: s.game(key)?.stats?.minutes ?: 0 } }
    }

    suspend fun createArc(args: JsonObject): ToolResult {
        val s = knowledge.snapshot()
        val title = args.str("title") ?: return toolFail("missing title")
        val seriesName = args.str("series")
        val keys = if (seriesName != null) {
            val series = s.curation.series.firstOrNull { it.name.equals(seriesName, ignoreCase = true) }
                ?: s.curation.series.maxByOrNull { Names.similarity(seriesName, it.name) }
                ?: return toolFail("no series called '$seriesName' in the library")
            series.keys
        } else {
            args.strings("games").mapNotNull { s.resolve(it)?.key }
        }
        if (keys.size < 2) return toolFail("an arc needs at least two games that exist in the library")
        val arc = Arcs.draft(
            id = arcs.newId(),
            title = title,
            theme = args.str("theme"),
            description = args.str("description"),
            keys = keys,
            games = s.byKey,
            profiles = s.profiles,
            targetMinutes = args.int("minutes_per_step"),
            now = s.now,
        )
        arcs.save(arc)
        return toolOk(
            detail = "arc '${arc.title}' created with ${arc.steps.size} steps",
            attachment = MashaAttachment.ArcCard(arc.id, arc.title, arc.steps.map { it.gameKey }, 0, arc.steps.size),
            mutating = true,
        ) {
            putJsonArray("steps") {
                arc.steps.forEach { step ->
                    addJsonObject {
                        put("title", s.game(step.gameKey)?.title ?: step.gameKey)
                        put("target_minutes", step.targetMinutes)
                    }
                }
            }
        }
    }

    suspend fun getArcs(): ToolResult {
        val s = knowledge.snapshot()
        val progress = arcProgress()
        if (progress.isEmpty()) return toolOk("no active arcs")
        val first = progress.first()
        return toolOk(
            attachment = MashaAttachment.ArcCard(first.arc.id, first.arc.title, first.arc.steps.map { it.gameKey }, first.doneSteps, first.totalSteps),
        ) {
            putJsonArray("arcs") {
                progress.forEach { p ->
                    addJsonObject {
                        put("title", p.arc.title)
                        put("done_steps", p.doneSteps)
                        put("total_steps", p.totalSteps)
                        p.current?.let { step ->
                            put("current_game", s.game(step.gameKey)?.title ?: step.gameKey)
                            put("current_progress", "${p.currentMinutes}/${step.targetMinutes} min")
                        }
                    }
                }
            }
        }
    }

    /* ── listas ───────────────────────────────────────────────── */

    suspend fun createList(args: JsonObject): ToolResult {
        val s = knowledge.snapshot()
        val name = args.str("name") ?: return toolFail("missing list name")
        val titles = args.strings("titles")
        val rule = if (titles.isNotEmpty()) {
            val keys = titles.mapNotNull { s.resolve(it)?.key }
            if (keys.isEmpty()) return toolFail("none of those titles are in the library")
            ListRule(keys = keys)
        } else {
            ListRule(
                list = args.str("list"),
                systems = args.strings("systems"),
                genres = args.strings("genres"),
                series = args.str("series"),
                minMinutes = args.int("min_minutes"),
                maxMinutes = args.int("max_minutes"),
                android = args.bool("android"),
            )
        }
        val saved = lists.save(name, rule, createdBy = "masha")
        val found = SmartLists.evaluate(saved.rule, s.listContext())
        return toolOk(
            detail = "list '${saved.name}' saved with ${found.size} games" + if (titles.isEmpty()) " (updates itself)" else "",
            attachment = MashaAttachment.Games(saved.name, found.take(MAX_LIMIT).map { it.key }, found.size),
            mutating = true,
        )
    }

    suspend fun savedListNames(): List<String> = lists.all().map { it.name }

    /* ── revisión, estadísticas y memoria ─────────────────────── */

    suspend fun curationReport(args: JsonObject): ToolResult {
        val s = knowledge.snapshot()
        val c = s.curation
        fun title(key: String) = s.game(key)?.title ?: key
        return when (args.str("detail") ?: "summary") {
            "duplicates" -> toolOk(attachment = MashaAttachment.Games("duplicates", c.duplicates.flatMap { it.keys }.take(MAX_LIMIT))) {
                putJsonArray("groups") {
                    c.duplicates.take(20).forEach { g ->
                        addJsonObject {
                            put("game", g.title)
                            put("kind", if (g.kind.name == "SameFile") "identical files" else "several versions")
                            putJsonArray("entries") { g.keys.forEach { add(title(it)) } }
                            put("keep_suggestion", title(g.keys.first()))
                        }
                    }
                }
            }
            "incomplete_sets" -> toolOk(attachment = MashaAttachment.Games("incomplete_sets", c.incomplete.flatMap { it.keys }.take(MAX_LIMIT))) {
                putJsonArray("sets") {
                    c.incomplete.take(20).forEach { set ->
                        addJsonObject {
                            put("game", set.title)
                            put("discs_present", set.present.joinToString(","))
                            put("discs_missing", set.missing.joinToString(","))
                        }
                    }
                }
            }
            "poorly_named" -> toolOk(attachment = MashaAttachment.Games("poorly_named", c.naming.map { it.key }.take(MAX_LIMIT))) {
                putJsonArray("files") {
                    c.naming.take(25).forEach { issue ->
                        val rom = s.game(issue.key)?.rom
                        addJsonObject {
                            put("file", rom?.fileName ?: issue.key)
                            put("problem", issue.reason.name.lowercase())
                            issue.suggestion?.let { put("probably", it) }
                        }
                    }
                }
            }
            "mixed_regions" -> toolOk(attachment = MashaAttachment.Games("mixed_regions", c.regions.flatMap { it.keys }.take(MAX_LIMIT))) {
                putJsonArray("games") {
                    c.regions.take(20).forEach { r ->
                        addJsonObject {
                            put("game", r.title)
                            put("regions", r.regions.joinToString(","))
                        }
                    }
                }
            }
            "series" -> toolOk {
                putJsonArray("series") {
                    c.series.sortedByDescending { it.keys.size }.take(20).forEach { series ->
                        addJsonObject {
                            put("name", series.name)
                            putJsonArray("games") { series.keys.forEach { add(title(it)) } }
                        }
                    }
                }
            }
            else -> toolOk {
                put("duplicates", c.duplicates.size)
                put("incomplete_sets", c.incomplete.size)
                put("poorly_named", c.naming.size)
                put("mixed_regions", c.regions.size)
                put("series", c.series.size)
                put("note", "Elyndra never renames or deletes files; the user decides what to do")
            }
        }
    }

    suspend fun stats(args: JsonObject): ToolResult {
        val s = knowledge.snapshot()
        val period = args.str("period") ?: "week"
        val span = when (period) {
            "week" -> 7L
            "month" -> 30L
            "year" -> 365L
            else -> null
        }?.let { it * DAY_MS }
        val sessions = s.library.sessions.filter { it.minutes > 0 }
        val inPeriod = if (span == null) sessions else sessions.filter { it.start >= s.now - span }
        val previous = if (span == null) emptyList() else sessions.filter { it.start in (s.now - 2 * span) until (s.now - span) }
        val byGame = inPeriod.groupBy { it.key }.mapValues { (_, l) -> l.sumOf { it.minutes } }
        return toolOk {
            put("period", period)
            put("minutes", if (span == null) s.games.sumOf { s.profiles[it.key]?.totalMinutes ?: it.stats.minutes } else inPeriod.sumOf { it.minutes })
            if (span != null) put("previous_period_minutes", previous.sumOf { it.minutes })
            put("sessions", inPeriod.size)
            if (inPeriod.isNotEmpty()) put("average_session_minutes", inPeriod.sumOf { it.minutes } / inPeriod.size)
            inPeriod.maxByOrNull { it.minutes }?.let { longest ->
                put("longest_session", "${s.game(longest.key)?.title ?: "?"}: ${longest.minutes} min")
            }
            putJsonArray("top_games") {
                byGame.entries.sortedByDescending { it.value }
                    .mapNotNull { (key, minutes) -> s.game(key)?.let { it.title to minutes } }
                    .take(5)
                    .forEach { (title, minutes) ->
                        addJsonObject {
                            put("title", title)
                            put("minutes", minutes)
                        }
                    }
            }
            putJsonArray("by_system") {
                byGame.entries.groupBy { s.game(it.key)?.platform ?: "?" }
                    .mapValues { (_, l) -> l.sumOf { it.value } }
                    .entries.sortedByDescending { it.value }.take(6)
                    .forEach { (system, minutes) ->
                        addJsonObject {
                            put("system", system)
                            put("minutes", minutes)
                        }
                    }
            }
            put("instant_exits", s.library.sessions.count { it.earlyExit && (span == null || it.start >= s.now - span) })
        }
    }

    suspend fun remember(args: JsonObject): ToolResult {
        val fact = args.str("fact") ?: return toolFail("nothing to remember")
        val s = knowledge.snapshot()
        val subject = args.str("game")?.let { s.resolve(it)?.key }
        val kind = if (args.str("kind") == MashaMemory.KIND_FACT) MashaMemory.KIND_FACT else MashaMemory.KIND_PREFERENCE
        memory.remember(fact, kind, subject)
        return toolOk("remembered", mutating = true)
    }

    suspend fun forget(args: JsonObject): ToolResult {
        val fact = args.str("fact") ?: return toolFail("what should I forget?")
        val n = memory.forget(fact)
        return if (n == 0) toolFail("nothing remembered matches that") else toolOk("forgot $n memories", mutating = true)
    }

    /* ── utilidades ───────────────────────────────────────────── */

    fun brief(g: Game, s: KnowledgeSnapshot): JsonObject = buildJsonObject {
        val p = s.profiles[g.key]
        put("title", g.title)
        put("platform", g.platform)
        put("state", stateName(p?.state))
        val minutes = p?.totalMinutes ?: g.stats.minutes
        if (minutes > 0) put("minutes", minutes)
        p?.lastPlayed?.takeIf { it > 0 }?.let { put("last_played", MashaContextBuilder.ago(s.now - it)) }
        g.meta.genre?.let { put("genre", it) }
        g.meta.releaseDate?.take(4)?.let { put("year", it) }
        put("has_cover", g.hasPrimaryArt)
    }

    private fun notFound(title: String?) = toolFail("'${title.orEmpty()}' is not in the user's library (searched the full library)")

    private fun stateName(state: PlayState?) = when (state ?: PlayState.New) {
        PlayState.New -> "new"
        PlayState.Tried -> "tried"
        PlayState.InProgress -> "in_progress"
        PlayState.Abandoned -> "abandoned"
        PlayState.Dormant -> "dormant"
    }

    private fun verdictName(v: Verdict) = when (v) {
        Verdict.Proven -> "runs well here"
        Verdict.Promising -> "some good sessions"
        Verdict.Untested -> "not tried on this device"
        Verdict.Struggling -> "instant exits or failed launches"
    }

    companion object {
        const val DEFAULT_LIMIT = 12
        const val MAX_LIMIT = 40
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
