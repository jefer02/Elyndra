package com.elyndra.launcher.domain.lists

import com.elyndra.launcher.domain.Game
import com.elyndra.launcher.domain.curation.CurationReport
import com.elyndra.launcher.domain.profile.GameProfile
import com.elyndra.launcher.domain.profile.PlayState
import com.elyndra.launcher.library.Names
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Las listas que Elyndra sabe calcular sola, siempre al día. */
enum class DynamicList(val id: String) {
    NeverOpened("never_opened"),
    Abandoned("abandoned"),
    InProgress("in_progress"),
    RecentlyPlayed("recently_played"),
    RecentlyAdded("recently_added"),
    IncompleteSets("incomplete_sets"),
    BestOnDevice("best_on_device"),
    ArtworkComplete("artwork_complete"),
    ArtworkMissing("artwork_missing"),
    Unmatched("unmatched"),
    Duplicates("duplicates"),
    PoorlyNamed("poorly_named"),
    MixedRegions("mixed_regions"),
    ;

    companion object {
        fun byId(id: String?): DynamicList? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Regla de una lista inteligente. Se guarda en JSON (tabla `smart_lists`) y es
 * también lo que Masha rellena cuando el usuario le pide una lista a medida:
 * "mis RPG de PS2 sin empezar", "lo que jugué este mes en portátil"…
 *
 * Todas las condiciones se suman (Y). Una lista manual es la que tiene [keys].
 */
@Serializable
data class ListRule(
    /** Lista dinámica de base (ver [DynamicList]); null = toda la biblioteca. */
    val list: String? = null,
    val systems: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val query: String? = null,
    @SerialName("min_minutes") val minMinutes: Int? = null,
    @SerialName("max_minutes") val maxMinutes: Int? = null,
    @SerialName("played_within_days") val playedWithinDays: Int? = null,
    @SerialName("not_played_for_days") val notPlayedForDays: Int? = null,
    /** true = solo juegos Android, false = solo ROMs, null = todo. */
    val android: Boolean? = null,
    @SerialName("series") val series: String? = null,
    /** Lista manual: estos juegos, en este orden. */
    val keys: List<String> = emptyList(),
    val sort: String? = null,
    val limit: Int? = null,
)

/** Todo lo que puede necesitar una regla para evaluarse. */
data class ListContext(
    val games: List<Game>,
    val profiles: Map<String, GameProfile>,
    val curation: CurationReport,
    val now: Long,
)

object SmartLists {

    const val DAY_MS = 24L * 60 * 60 * 1000

    fun evaluate(rule: ListRule, ctx: ListContext): List<Game> {
        val byKey = ctx.games.associateBy { it.key }
        var list: List<Game> = if (rule.keys.isNotEmpty()) rule.keys.mapNotNull { byKey[it] } else base(DynamicList.byId(rule.list), ctx)

        if (rule.systems.isNotEmpty()) {
            val wanted = rule.systems.map { it.lowercase() }.toSet()
            list = list.filter { g ->
                val id = g.systemId ?: "android"
                id in wanted || g.platform.lowercase() in wanted || g.system?.short?.lowercase() in wanted
            }
        }
        if (rule.genres.isNotEmpty()) {
            val wanted = rule.genres.map { it.lowercase() }
            list = list.filter { g -> g.meta.genre?.lowercase()?.let { genre -> wanted.any { genre.contains(it) } } == true }
        }
        rule.query?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            list = list.filter { it.title.contains(q, ignoreCase = true) || Names.similarity(q, it.title) >= 0.8 }
        }
        rule.minMinutes?.let { min -> list = list.filter { minutes(it, ctx) >= min } }
        rule.maxMinutes?.let { max -> list = list.filter { minutes(it, ctx) <= max } }
        rule.playedWithinDays?.let { d -> list = list.filter { lastPlayed(it, ctx) >= ctx.now - d * DAY_MS } }
        rule.notPlayedForDays?.let { d -> list = list.filter { lastPlayed(it, ctx).let { lp -> lp in 1 until ctx.now - d * DAY_MS } } }
        rule.android?.let { a -> list = list.filter { it.isApp == a } }
        rule.series?.takeIf { it.isNotBlank() }?.let { s ->
            val series = ctx.curation.series.firstOrNull { it.name.equals(s, ignoreCase = true) }
                ?: ctx.curation.series.maxByOrNull { Names.similarity(s, it.name) }?.takeIf { Names.similarity(s, it.name) >= 0.7 }
            val keys = series?.keys.orEmpty()
            list = keys.mapNotNull { k -> list.firstOrNull { it.key == k } }
        }
        list = sort(list, rule.sort, ctx)
        return rule.limit?.let { list.take(it.coerceAtLeast(1)) } ?: list
    }

    private fun base(list: DynamicList?, ctx: ListContext): List<Game> {
        val games = ctx.games
        fun state(g: Game) = ctx.profiles[g.key]?.state
        return when (list) {
            null -> games.sortedBy { it.title.lowercase() }
            DynamicList.NeverOpened -> games.filter { state(it) == PlayState.New }.sortedByDescending { it.addedAt }
            DynamicList.Abandoned -> games.filter { state(it) == PlayState.Abandoned }.sortedByDescending { lastPlayed(it, ctx) }
            DynamicList.InProgress -> games.filter { state(it) == PlayState.InProgress }.sortedByDescending { lastPlayed(it, ctx) }
            DynamicList.RecentlyPlayed -> games.filter { lastPlayed(it, ctx) > 0 }.sortedByDescending { lastPlayed(it, ctx) }
            DynamicList.RecentlyAdded -> games.filter { it.addedAt > 0 }.sortedByDescending { it.addedAt }
            DynamicList.IncompleteSets -> keysIn(ctx.curation.incomplete.flatMap { it.keys }, games)
            DynamicList.BestOnDevice -> games.filter { bestOnDevice(ctx.profiles[it.key]) }
                .sortedByDescending { ctx.profiles[it.key]?.totalMinutes ?: 0 }
            DynamicList.ArtworkComplete -> games.filter { it.hasCompleteArt }.sortedBy { it.title.lowercase() }
            DynamicList.ArtworkMissing -> games.filterNot { it.hasPrimaryArt }.sortedBy { it.title.lowercase() }
            DynamicList.Unmatched -> games.filter { it.meta.scrapedAt > 0 && !it.meta.matched }.sortedBy { it.title.lowercase() }
            DynamicList.Duplicates -> keysIn(ctx.curation.duplicates.flatMap { it.keys }, games)
            DynamicList.PoorlyNamed -> keysIn(ctx.curation.naming.map { it.key }, games)
            DynamicList.MixedRegions -> keysIn(ctx.curation.regions.flatMap { it.keys }, games)
        }
    }

    /**
     * "Los que mejor van en este dispositivo": varias sesiones, de las que
     * duran, sin salidas inmediatas recientes ni lanzamientos fallidos. Es la
     * mejor medida de rendimiento a la que llega un lanzador que no emula.
     */
    fun bestOnDevice(p: GameProfile?): Boolean {
        if (p == null || p.sessions < 2 || p.failedLaunches > 0) return false
        return p.medianMinutes >= 15 && p.earlyExits * 4 <= p.sessions && !p.lastWasEarlyExit
    }

    private fun keysIn(keys: List<String>, games: List<Game>): List<Game> {
        val byKey = games.associateBy { it.key }
        return keys.distinct().mapNotNull { byKey[it] }
    }

    private fun minutes(g: Game, ctx: ListContext) = ctx.profiles[g.key]?.totalMinutes ?: g.stats.minutes
    private fun lastPlayed(g: Game, ctx: ListContext) = ctx.profiles[g.key]?.lastPlayed ?: g.stats.lastPlayed

    private fun sort(list: List<Game>, sort: String?, ctx: ListContext): List<Game> = when (sort) {
        "title" -> list.sortedBy { it.title.lowercase() }
        "minutes", "playtime" -> list.sortedByDescending { minutes(it, ctx) }
        "last_played", "recent" -> list.sortedByDescending { lastPlayed(it, ctx) }
        "added" -> list.sortedByDescending { it.addedAt }
        "rating" -> list.sortedByDescending { it.meta.rating ?: -1f }
        "release" -> list.sortedBy { it.meta.releaseDate ?: "9999" }
        else -> list
    }
}
