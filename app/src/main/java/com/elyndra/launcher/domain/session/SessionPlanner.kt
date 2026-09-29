package com.elyndra.launcher.domain.session

import com.elyndra.launcher.domain.Game
import com.elyndra.launcher.domain.SystemWeight
import com.elyndra.launcher.domain.device.DeviceState
import com.elyndra.launcher.domain.profile.GameProfile
import com.elyndra.launcher.domain.profile.PlayState
import kotlin.math.abs

/** Qué tipo de rato quiere el usuario. */
enum class SessionMood(val id: String) {
    /** "Quiero seguir con algo." */
    Continue("continue"),

    /** "Algo ligero." */
    Light("light"),

    /** "Algo nuevo": lo que nunca se abrió o apenas se probó. */
    Fresh("new"),

    /** Sin preferencia: un poco de todo. */
    Any("any"),
    ;

    companion object {
        fun byId(id: String?): SessionMood = entries.firstOrNull { it.id == id?.lowercase() } ?: Any
    }
}

/** Por qué entra un juego en el plan. */
enum class PlanReason { Continue, Arc, Light, Fresh, Revisit, Filler }

data class PlanBlock(val game: Game, val minutes: Int, val reason: PlanReason)

data class SessionPlan(
    val blocks: List<PlanBlock>,
    val minMinutes: Int,
    val maxMinutes: Int,
    val mood: SessionMood,
    /** Se dejaron fuera los sistemas pesados por la batería o la temperatura. */
    val deviceLimited: Boolean,
) {
    val totalMinutes: Int get() = blocks.sumOf { it.minutes }
    val isEmpty: Boolean get() = blocks.isEmpty()
}

/**
 * Minisesiones: "quiero jugar 30-40 minutos", "algo ligero", "quiero seguir
 * con algo" → uno a tres juegos de la biblioteca que llenan ese rato.
 *
 * Lo que dura una sesión "normal" de cada juego sale del propio historial del
 * usuario (la mediana de sus sesiones de verdad); sin historial, del peso del
 * sistema y del género. Con poca batería o el móvil caliente, lo pesado queda
 * fuera.
 */
object SessionPlanner {

    private const val MAX_BLOCKS = 3
    private const val MIN_BLOCK = 8
    private const val LIGHT_HEAVY_MAX = 25

    fun plan(
        minMinutes: Int,
        maxMinutes: Int,
        mood: SessionMood,
        games: List<Game>,
        profiles: Map<String, GameProfile>,
        device: DeviceState? = null,
        /** Juegos del arco activo, que tienen preferencia en "seguir". */
        arcKeys: Set<String> = emptySet(),
        /** Juegos que se acaban de proponer: se evitan para no repetir siempre lo mismo. */
        avoid: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): SessionPlan {
        val lo = minMinutes.coerceIn(5, 600)
        val hi = maxMinutes.coerceAtLeast(lo)
        val limited = device != null && (device.isLowBattery || device.isHot || device.powerSave)
        val pool = games.filter { g -> !limited || SystemWeight.of(g.systemId) != SystemWeight.Heavy }
        val scored = pool.mapNotNull { g -> score(g, profiles[g.key], mood, arcKeys, now)?.let { Triple(g, it.first, it.second) } }
            .map { (g, s, r) -> Triple(g, if (g.key in avoid) s - 2.0 else s, r) }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return SessionPlan(emptyList(), lo, hi, mood, limited)

        val target = (lo + hi) / 2
        val blocks = ArrayList<PlanBlock>()
        val (first, _, firstReason) = scored.first()
        val firstLength = typicalMinutes(first, profiles[first.key])
        if (firstLength >= lo) {
            blocks += PlanBlock(first, firstLength.coerceIn(lo, hi), firstReason)
        } else {
            blocks += PlanBlock(first, firstLength.coerceAtLeast(MIN_BLOCK), firstReason)
            // Relleno: lo más corto y ligero que quepa en lo que queda.
            for ((g, _, reason) in scored.drop(1)) {
                if (blocks.size >= MAX_BLOCKS) break
                val used = blocks.sumOf { it.minutes }
                val left = target - used
                if (left < MIN_BLOCK) break
                val length = typicalMinutes(g, profiles[g.key])
                if (length > hi - used) continue
                blocks += PlanBlock(g, length.coerceIn(MIN_BLOCK, left.coerceAtLeast(MIN_BLOCK)), if (reason == PlanReason.Continue) reason else PlanReason.Filler)
            }
            // Si ni con relleno se llega, el primero se estira hasta el mínimo.
            val total = blocks.sumOf { it.minutes }
            if (total < lo) blocks[0] = blocks[0].copy(minutes = blocks[0].minutes + (lo - total))
        }
        return SessionPlan(blocks, lo, hi, mood, limited)
    }

    /**
     * Lo que suele durar una sesión de este juego: la mediana de las sesiones
     * reales del usuario o, si no hay, una estimación por sistema y género.
     */
    fun typicalMinutes(game: Game, profile: GameProfile?): Int {
        val own = profile?.medianMinutes ?: 0
        if (own >= MIN_BLOCK && (profile?.sessions ?: 0) >= 2) return own
        val base = when (SystemWeight.of(game.systemId)) {
            SystemWeight.Heavy -> 45
            SystemWeight.Medium -> 35
            SystemWeight.Light -> 20
        }.let { if (game.isApp) 20 else it }
        return (base * genreFactor(game.meta.genre)).toInt()
    }

    /** Géneros de partidas cortas frente a los que piden sentarse. */
    fun genreFactor(genre: String?): Double {
        val g = genre?.lowercase() ?: return 1.0
        return when {
            LIGHT_GENRES.any { g.contains(it) } -> 0.7
            HEAVY_GENRES.any { g.contains(it) } -> 1.3
            else -> 1.0
        }
    }

    private val LIGHT_GENRES = listOf(
        "puzzle", "arcade", "fight", "lucha", "racing", "carreras", "sport", "deport", "pinball",
        "shoot'em", "shmup", "party", "rhythm", "ritmo", "platform", "plataforma",
    )
    private val HEAVY_GENRES = listOf("rpg", "rol", "strategy", "estrategia", "simulation", "simulación", "adventure", "aventura")

    /** Puntuación de un juego para este humor; null = no encaja. */
    private fun score(
        g: Game,
        p: GameProfile?,
        mood: SessionMood,
        arcKeys: Set<String>,
        now: Long,
    ): Pair<Double, PlanReason>? {
        val state = p?.state ?: PlayState.New
        val days = p?.daysSinceLastPlayed(now) ?: 999
        val art = if (g.hasPrimaryArt) 0.3 else 0.0
        val light = when (SystemWeight.of(g.systemId)) {
            SystemWeight.Light -> 1.0
            SystemWeight.Medium -> 0.5
            SystemWeight.Heavy -> 0.0
        } + (1.0 - genreFactor(g.meta.genre)).coerceAtLeast(0.0)
        val struggling = p?.lastWasEarlyExit == true
        return when (mood) {
            SessionMood.Continue -> when {
                g.key in arcKeys -> 5.0 - days * 0.02 to PlanReason.Arc
                state == PlayState.InProgress -> 4.0 - days * 0.1 + art to PlanReason.Continue
                state == PlayState.Tried && !struggling -> 2.0 - days * 0.05 to PlanReason.Continue
                state == PlayState.Abandoned && (p?.totalMinutes ?: 0) >= 30 -> 1.5 - days * 0.005 to PlanReason.Revisit
                else -> null
            }
            SessionMood.Light -> {
                // Una consola pesada no es "algo ligero", salvo que sus sesiones de verdad sean cortas.
                if (SystemWeight.of(g.systemId) == SystemWeight.Heavy && typicalMinutes(g, p) > LIGHT_HEAVY_MAX) return null
                val familiar = if (state == PlayState.InProgress || state == PlayState.Dormant) 0.6 else 0.0
                1.0 + light * 2.0 + familiar + art - (if (struggling) 2.0 else 0.0) to PlanReason.Light
            }
            SessionMood.Fresh -> when (state) {
                PlayState.New -> 3.0 + art + (g.meta.rating ?: 0.5f) + (if (g.meta.matched) 0.4 else 0.0) to PlanReason.Fresh
                PlayState.Tried -> if (struggling) null else 1.5 + art to PlanReason.Fresh
                else -> null
            }
            SessionMood.Any -> when (state) {
                PlayState.InProgress -> 3.0 - days * 0.1 + art to PlanReason.Continue
                PlayState.New -> 2.0 + art + abs(g.key.hashCode() % 7) * 0.05 to PlanReason.Fresh
                PlayState.Tried -> 1.5 + art to PlanReason.Fresh
                PlayState.Abandoned -> 1.0 to PlanReason.Revisit
                PlayState.Dormant -> 0.8 + light * 0.3 to PlanReason.Revisit
            }.let { if (struggling) it.copy(first = it.first - 1.5) else it }
        }
    }
}
