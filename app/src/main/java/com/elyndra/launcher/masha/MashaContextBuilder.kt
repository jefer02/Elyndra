package com.elyndra.launcher.masha

import com.elyndra.launcher.domain.Game
import com.elyndra.launcher.domain.device.DeviceState
import com.elyndra.launcher.domain.lists.SmartLists
import com.elyndra.launcher.domain.profile.GameProfile
import com.elyndra.launcher.domain.profile.PlayState
import com.elyndra.launcher.domain.session.ArcProgress
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Todo lo que entra en el contexto de una pregunta. */
data class ContextInput(
    val snapshot: KnowledgeSnapshot,
    val arcs: List<ArcProgress>,
    val memories: List<Memory>,
    val device: DeviceState?,
    val zone: ZoneId,
    val language: String,
    /** Qué está mirando el usuario: "library", "folder", "details", "masha"… */
    val screen: String?,
    /** El juego o la carpeta seleccionada, si la hay. */
    val focus: String?,
    /** Servicios de metadatos configurados, por nombre. */
    val services: List<String>,
    val metadataRunning: Boolean,
    /** Emuladores instalados, por nombre. */
    val installedEmulators: List<String>,
    /** Por sistema: emulador de su carpeta y si está instalado. */
    val folderEmulators: Map<String, Pair<String, Boolean>>,
    val savedLists: List<String>,
)

data class BuiltContext(val json: String, val fingerprint: String)

/**
 * El contexto que acompaña a cada pregunta: un resumen compacto y real de la
 * biblioteca, las sesiones, el dispositivo y lo que Masha recuerda.
 *
 * Tiene que caber holgado (unos pocos miles de tokens) aunque la biblioteca
 * tenga diez mil ROMs, así que el catálogo es una muestra escogida: lo que se
 * está jugando, lo reciente, lo abandonado, lo que mejor va aquí y una
 * muestra de lo nunca abierto. Para el resto, Masha tiene `find_games`.
 *
 * La huella ([BuiltContext.fingerprint]) deja fuera lo que cambia sin
 * importar para la respuesta —hora, batería—: es lo que decide si una
 * respuesta guardada sigue valiendo.
 */
object MashaContextBuilder {

    const val MAX_CATALOG = 120
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val WEEK_MS = 7 * DAY_MS

    fun build(input: ContextInput): BuiltContext {
        val s = input.snapshot
        val now = s.now
        val games = s.games
        val profiles = s.profiles

        val stable = buildJsonObject {
            putJsonObject("library") { library(this, input) }
            putJsonObject("playtime") { playtime(this, input) }
            list("in_progress", games.filter { profiles[it.key]?.state == PlayState.InProgress }.sortedByDescending { profiles[it.key]?.lastPlayed ?: 0 }.take(8), input)
            list("recently_played", games.filter { (profiles[it.key]?.lastPlayed ?: 0) > 0 }.sortedByDescending { profiles[it.key]?.lastPlayed ?: 0 }.take(6), input)
            list("abandoned", games.filter { profiles[it.key]?.state == PlayState.Abandoned }.sortedByDescending { profiles[it.key]?.totalMinutes ?: 0 }.take(6), input)
            list("best_on_device", games.filter { SmartLists.bestOnDevice(profiles[it.key]) }.sortedByDescending { profiles[it.key]?.totalMinutes ?: 0 }.take(6), input)
            val never = games.filter { profiles[it.key]?.state == PlayState.New }
            putJsonObject("never_opened") {
                put("count", never.size)
                putJsonArray("examples") { rotate(never, now).take(8).forEach { add(it.title) } }
            }
            putJsonObject("artwork") {
                put("complete", games.count { it.hasCompleteArt })
                put("missing_main_image", games.count { !it.hasPrimaryArt })
                put("partial", games.count { it.hasPrimaryArt && !it.hasCompleteArt })
            }
            putJsonObject("metadata") {
                putJsonArray("services") { input.services.forEach { add(it) } }
                put("never_scraped", games.count { it.meta.scrapedAt == 0L })
                put("unmatched", games.count { it.meta.scrapedAt > 0 && !it.meta.matched })
                put("matched_by_hash", games.count { it.meta.matchedBy == "hash" })
                put("updating_now", input.metadataRunning)
            }
            putJsonObject("cleanup") {
                val c = s.curation
                put("duplicates", c.duplicates.size)
                put("incomplete_sets", c.incomplete.size)
                put("poorly_named", c.naming.size)
                put("mixed_regions", c.regions.size)
                putJsonArray("series") { c.series.sortedByDescending { it.keys.size }.take(12).forEach { add("${it.name} (${it.keys.size})") } }
            }
            putJsonArray("arcs") {
                input.arcs.forEach { p ->
                    addJsonObject {
                        put("title", p.arc.title)
                        put("step", "${p.doneSteps + (if (p.isComplete) 0 else 1)}/${p.totalSteps}")
                        p.current?.let { step ->
                            put("current_game", s.game(step.gameKey)?.title ?: step.gameKey)
                            put("progress", "${p.currentMinutes}/${step.targetMinutes} min")
                            step.goal?.let { put("goal", it) }
                        }
                    }
                }
            }
            putJsonArray("emulators_installed") { input.installedEmulators.sorted().forEach { add(it) } }
            putJsonArray("saved_lists") { input.savedLists.forEach { add(it) } }
            putJsonArray("memories") { input.memories.take(15).forEach { add(it.content) } }
            put("catalog_legend", "t=title p=platform s=state m=minutes played g=genre y=year lp=last played")
            putJsonArray("catalog") { catalog(games, profiles, now).forEach { add(entry(it, profiles[it.key], now)) } }
        }

        val volatile = buildJsonObject {
            put("now", describeNow(now, input.zone, input.language))
            input.device?.let { d ->
                putJsonObject("device") {
                    d.batteryPct?.let { put("battery_pct", it) }
                    put("charging", d.charging)
                    d.batteryTempC?.let { put("battery_temp_c", it) }
                    put("thermal", d.thermal.name.lowercase())
                    put("power_save", d.powerSave)
                    put("network", d.network.name.lowercase())
                }
            }
            putJsonObject("screen") {
                put("view", input.screen ?: "library")
                input.focus?.let { put("focused", it) }
            }
        }

        val merged = JsonObject(volatile + stable)
        return BuiltContext(merged.toString(), fingerprint(stable.toString(), now))
    }

    private fun library(b: JsonObjectBuilder, input: ContextInput) {
        val games = input.snapshot.games
        val profiles = input.snapshot.profiles
        b.put("games", games.size)
        b.put("roms", games.count { !it.isApp })
        b.put("android_games", games.count { it.isApp })
        b.putJsonArray("systems") {
            games.groupBy { it.systemId ?: "android" }
                .entries
                .sortedByDescending { it.value.size }
                .forEach { (systemId, list) ->
                    addJsonObject {
                        put("system", list.first().platform)
                        put("games", list.size)
                        put("minutes", list.sumOf { profiles[it.key]?.totalMinutes ?: it.stats.minutes })
                        input.folderEmulators[systemId]?.let { (name, installed) ->
                            put("emulator", name)
                            put("emulator_installed", installed)
                        }
                    }
                }
        }
    }

    private fun playtime(b: JsonObjectBuilder, input: ContextInput) {
        val s = input.snapshot
        val now = s.now
        val sessions = s.library.sessions.filter { it.minutes > 0 }
        val week = sessions.filter { it.start >= now - WEEK_MS }
        val prev = sessions.filter { it.start in (now - 2 * WEEK_MS) until (now - WEEK_MS) }
        b.put("this_week_minutes", week.sumOf { it.minutes })
        b.put("last_week_minutes", prev.sumOf { it.minutes })
        b.put("sessions_this_week", week.size)
        b.put("total_minutes", s.games.sumOf { s.profiles[it.key]?.totalMinutes ?: it.stats.minutes })
        b.putJsonArray("top_this_week") {
            week.groupBy { it.key }
                .mapNotNull { (key, l) -> s.game(key)?.let { it.title to l.sumOf { session -> session.minutes } } }
                .sortedByDescending { it.second }
                .take(3)
                .forEach { (title, minutes) ->
                    addJsonObject {
                        put("title", title)
                        put("minutes", minutes)
                    }
                }
        }
    }

    private fun JsonObjectBuilder.list(name: String, games: List<Game>, input: ContextInput) {
        if (games.isEmpty()) return
        val profiles = input.snapshot.profiles
        val now = input.snapshot.now
        putJsonArray(name) {
            games.forEach { g ->
                val p = profiles[g.key]
                addJsonObject {
                    put("title", g.title)
                    put("platform", g.platform)
                    put("minutes", p?.totalMinutes ?: g.stats.minutes)
                    p?.lastPlayed?.takeIf { it > 0 }?.let { put("last_played", ago(now - it)) }
                    p?.lastSession?.let { last ->
                        put("last_session_minutes", last.minutes)
                        last.emulatorId?.let { put("last_emulator", it) }
                        if (last.earlyExit) put("last_session_exited_early", true)
                    }
                }
            }
        }
    }

    /**
     * La muestra del catálogo: primero lo que tiene historia (se está jugando,
     * reciente, abandonado, lo que mejor va), después lo nunca abierto (con
     * arte y reconocido antes: son mejores sugerencias) y el resto hasta llenar.
     */
    fun catalog(games: List<Game>, profiles: Map<String, GameProfile>, now: Long): List<Game> {
        val out = LinkedHashSet<Game>()
        fun take(list: List<Game>, n: Int) = list.take(n).forEach { if (out.size < MAX_CATALOG) out += it }
        val lastPlayed = { g: Game -> profiles[g.key]?.lastPlayed ?: 0L }
        take(games.filter { profiles[it.key]?.state == PlayState.InProgress }.sortedByDescending(lastPlayed), 25)
        take(games.filter { lastPlayed(it) > 0 }.sortedByDescending(lastPlayed), 15)
        take(games.filter { profiles[it.key]?.state == PlayState.Abandoned }.sortedByDescending(lastPlayed), 15)
        take(games.filter { SmartLists.bestOnDevice(profiles[it.key]) }, 10)
        val never = games.filter { profiles[it.key]?.state == PlayState.New }
            .sortedWith(compareByDescending<Game> { it.hasPrimaryArt }.thenByDescending { it.meta.matched }.thenByDescending { it.meta.rating ?: 0f })
        take(rotate(never.take(90), now), 30)
        take(games.filter { it.meta.rating != null }.sortedByDescending { it.meta.rating }, 15)
        take(rotate(games, now), MAX_CATALOG)
        return out.toList()
    }

    private fun entry(g: Game, p: GameProfile?, now: Long): JsonObject = buildJsonObject {
        put("t", g.title)
        put("p", g.system?.short ?: "Android")
        put("s", (p?.state ?: PlayState.New).name.lowercase().let { if (it == "inprogress") "in_progress" else it })
        val minutes = p?.totalMinutes ?: g.stats.minutes
        if (minutes > 0) put("m", minutes)
        g.meta.genre?.let { put("g", it.split(',').first().trim()) }
        g.meta.releaseDate?.take(4)?.let { put("y", it) }
        p?.lastPlayed?.takeIf { it > 0 }?.let { put("lp", ago(now - it)) }
    }

    /** Orden estable que cambia cada día: la muestra no es siempre la misma. */
    private fun rotate(list: List<Game>, now: Long): List<Game> {
        val day = (now / DAY_MS).toInt()
        return list.sortedBy { abs((it.key.hashCode() xor (day * 0x9E3779B1.toInt())) % 100_003) }
    }

    fun ago(ms: Long): String {
        val minutes = ms / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 60 -> "${minutes.coerceAtLeast(0)}min ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "yesterday"
            days < 14 -> "${days}d ago"
            days < 60 -> "${days / 7}w ago"
            days < 730 -> "${days / 30}mo ago"
            else -> "${days / 365}y ago"
        }
    }

    private fun describeNow(now: Long, zone: ZoneId, language: String): String {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val part = when (t.hour) {
            in 5..11 -> "morning"
            in 12..17 -> "afternoon"
            in 18..22 -> "evening"
            else -> "night"
        }
        val day = t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "%s %02d:%02d (%s %s), ui=%s".format(t.toLocalDate(), t.hour, t.minute, day, part, language)
    }

    private fun fingerprint(stable: String, now: Long): String {
        // Por día: "ayer" y "hace 3 días" cambian solos al cambiar de fecha.
        val digest = MessageDigest.getInstance("SHA-256").digest("$stable|${now / DAY_MS}".toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
