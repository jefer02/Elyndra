package com.elyndra.launcher.domain.profile

import com.elyndra.launcher.data.PlaySession
import com.elyndra.launcher.domain.Game

/** Un intento de lanzamiento y cómo acabó (espejo de dominio de `launch_events`). */
data class LaunchRecord(
    val gameKey: String,
    val systemId: String?,
    val emulatorId: String?,
    val at: Long,
    val outcome: String,
)

/** Cómo acabó un lanzamiento. */
object LaunchOutcome {
    const val STARTED = "started"
    const val NOT_INSTALLED = "not_installed"
    const val NEEDS_PATH = "needs_path"
    const val NEEDS_VITA_TITLE = "needs_vita_title"
    const val NEEDS_PC_LAUNCHER = "needs_pc_launcher"
    const val FAILED = "failed"

    /** Fallos que dicen algo del emulador (no de la configuración del usuario). */
    fun isFailure(outcome: String) = outcome == FAILED
}

/** Uso de un emulador con un juego (o con un sistema entero). */
data class EmulatorUsage(
    val emulatorId: String,
    val sessions: Int,
    /** Sesiones de verdad: más de [GameProfiles.GOOD_SESSION_MINUTES] minutos. */
    val goodSessions: Int,
    val earlyExits: Int,
    val minutes: Int,
    val launches: Int,
    val failedLaunches: Int,
    val lastUsed: Long,
)

/** En qué punto está el usuario con un juego. */
enum class PlayState {
    /** Nunca se ha abierto. */
    New,

    /** Se abrió y apenas se jugó (menos de un cuarto de hora en total), hace poco. */
    Tried,

    /** Jugado en las últimas dos semanas. */
    InProgress,

    /** Empezado y dejado: poco tiempo en total y semanas sin tocarlo. */
    Abandoned,

    /** Muchas horas y semanas sin tocarlo: probablemente terminado o en pausa. */
    Dormant,
}

/**
 * El perfil vivo de un juego: lo que Masha "recuerda" de él. Se calcula de las
 * sesiones y los lanzamientos reales; no se guarda aparte.
 */
data class GameProfile(
    val key: String,
    val totalMinutes: Int,
    val launches: Int,
    val sessions: Int,
    val lastPlayed: Long,
    val lastSession: PlaySession?,
    val medianMinutes: Int,
    val earlyExits: Int,
    val failedLaunches: Int,
    /** Emuladores usados con este juego, el más reciente primero. */
    val emulators: List<EmulatorUsage>,
    val state: PlayState,
) {
    val lastEmulator: String? get() = lastSession?.emulatorId ?: emulators.firstOrNull()?.emulatorId

    /** La última vez salió casi al entrar. */
    val lastWasEarlyExit: Boolean get() = lastSession?.earlyExit == true

    fun daysSinceLastPlayed(now: Long): Int? =
        if (lastPlayed <= 0) null else ((now - lastPlayed) / DAY_MS).toInt().coerceAtLeast(0)

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

object GameProfiles {

    /** Menos que esto al salir cuenta como salida inmediata. */
    const val EARLY_EXIT_MINUTES = 3

    /** Una sesión que ya dice que el emulador funciona. */
    const val GOOD_SESSION_MINUTES = 12

    const val TRIED_MAX_MINUTES = 15
    const val ACTIVE_DAYS = 14
    const val IDLE_DAYS = 21

    /** Por encima de esto, dejarlo no es "abandonar": es haberlo exprimido. */
    const val DORMANT_MINUTES = 10 * 60

    fun isEarlyExit(minutes: Int): Boolean = minutes < EARLY_EXIT_MINUTES

    /** Perfil de un juego a partir de todas las sesiones y lanzamientos (se filtran aquí). */
    fun build(
        game: Game,
        sessions: List<PlaySession>,
        launches: List<LaunchRecord> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): GameProfile {
        val mine = sessions.filter { it.key == game.key }.sortedBy { it.start }
        val myLaunches = launches.filter { it.gameKey == game.key }
        return fromSessions(game, mine, myLaunches, now)
    }

    /** Perfiles de toda la biblioteca de una pasada (agrupando una vez, no por juego). */
    fun buildAll(
        games: List<Game>,
        sessions: List<PlaySession>,
        launches: List<LaunchRecord> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): Map<String, GameProfile> {
        val byGame = sessions.groupBy { it.key }
        val launchesByGame = launches.groupBy { it.gameKey }
        return games.associate { g ->
            g.key to fromSessions(g, byGame[g.key].orEmpty().sortedBy { it.start }, launchesByGame[g.key].orEmpty(), now)
        }
    }

    private fun fromSessions(game: Game, mine: List<PlaySession>, launches: List<LaunchRecord>, now: Long): GameProfile {
        val stats = game.stats
        val played = mine.filterNot { it.earlyExit || it.minutes < EARLY_EXIT_MINUTES }
        val last = mine.lastOrNull()
        val lastPlayed = maxOf(stats.lastPlayed, last?.start ?: 0L)
        val total = maxOf(stats.minutes, mine.sumOf { it.minutes })
        return GameProfile(
            key = game.key,
            totalMinutes = total,
            launches = maxOf(stats.launches, mine.size),
            sessions = mine.size,
            lastPlayed = lastPlayed,
            lastSession = last,
            medianMinutes = median(played.map { it.minutes }),
            earlyExits = mine.count { it.earlyExit || it.minutes < EARLY_EXIT_MINUTES },
            failedLaunches = launches.count { LaunchOutcome.isFailure(it.outcome) },
            emulators = usage(mine, launches),
            state = state(total, maxOf(stats.launches, mine.size), lastPlayed, now),
        )
    }

    fun state(totalMinutes: Int, launches: Int, lastPlayed: Long, now: Long): PlayState {
        if (launches == 0 && totalMinutes == 0) return PlayState.New
        val idleDays = if (lastPlayed <= 0) Int.MAX_VALUE else ((now - lastPlayed) / GameProfile.DAY_MS).toInt()
        return when {
            idleDays <= ACTIVE_DAYS && totalMinutes >= TRIED_MAX_MINUTES -> PlayState.InProgress
            idleDays < IDLE_DAYS -> PlayState.Tried
            totalMinutes >= DORMANT_MINUTES -> PlayState.Dormant
            else -> PlayState.Abandoned
        }
    }

    /** Uso por emulador a partir de sesiones y lanzamientos (de un juego o de un sistema entero). */
    fun usage(sessions: List<PlaySession>, launches: List<LaunchRecord>): List<EmulatorUsage> {
        val ids = (sessions.mapNotNull { it.emulatorId } + launches.mapNotNull { it.emulatorId }).distinct()
        return ids.map { id ->
            val s = sessions.filter { it.emulatorId == id }
            val l = launches.filter { it.emulatorId == id }
            EmulatorUsage(
                emulatorId = id,
                sessions = s.size,
                goodSessions = s.count { it.minutes >= GOOD_SESSION_MINUTES && !it.earlyExit },
                earlyExits = s.count { it.earlyExit || it.minutes < EARLY_EXIT_MINUTES },
                minutes = s.sumOf { it.minutes },
                launches = l.count { it.outcome == LaunchOutcome.STARTED }.coerceAtLeast(s.size),
                failedLaunches = l.count { LaunchOutcome.isFailure(it.outcome) },
                lastUsed = maxOf(s.maxOfOrNull { it.start } ?: 0L, l.maxOfOrNull { it.at } ?: 0L),
            )
        }.sortedByDescending { it.lastUsed }
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
