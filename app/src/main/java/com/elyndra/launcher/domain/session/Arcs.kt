package com.elyndra.launcher.domain.session

import com.elyndra.launcher.domain.Game
import com.elyndra.launcher.domain.curation.Series
import com.elyndra.launcher.domain.profile.GameProfile

enum class ArcStatus(val id: String) {
    Active("active"),
    Completed("completed"),
    Abandoned("abandoned"),
    ;

    companion object {
        fun byId(id: String?): ArcStatus = entries.firstOrNull { it.id == id } ?: Active
    }
}

data class ArcStep(
    val position: Int,
    val gameKey: String,
    val goal: String?,
    val targetMinutes: Int,
    /** Minutos que ya tenía el juego al crear el arco: el progreso se cuenta desde ahí. */
    val baselineMinutes: Int,
    val completedAt: Long? = null,
)

/**
 * Un arco: una serie de sesiones con hilo ("la saga Castlevania en orden de
 * salida", "una semana de terror en PS1"). Cada paso es un juego con un
 * objetivo de tiempo; al llegar a él, el paso se da por hecho y Masha propone
 * el siguiente.
 */
data class Arc(
    val id: String,
    val title: String,
    val theme: String?,
    val description: String?,
    val status: ArcStatus,
    val steps: List<ArcStep>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ArcProgress(
    val arc: Arc,
    /** El paso en curso (el primero sin terminar); null = arco completo. */
    val current: ArcStep?,
    val doneSteps: Int,
    /** Minutos jugados en el paso en curso desde que empezó el arco. */
    val currentMinutes: Int,
) {
    val totalSteps: Int get() = arc.steps.size
    val isComplete: Boolean get() = current == null
}

object Arcs {

    const val MIN_STEP_MINUTES = 30
    const val MAX_STEP_MINUTES = 240

    fun progress(arc: Arc, minutesOf: (String) -> Int): ArcProgress {
        val current = arc.steps.sortedBy { it.position }.firstOrNull { it.completedAt == null }
        return ArcProgress(
            arc = arc,
            current = current,
            doneSteps = arc.steps.count { it.completedAt != null },
            currentMinutes = current?.let { (minutesOf(it.gameKey) - it.baselineMinutes).coerceAtLeast(0) } ?: 0,
        )
    }

    /** Pasos sin cerrar que ya han llegado a su tiempo objetivo. */
    fun reachedSteps(arc: Arc, minutesOf: (String) -> Int): List<ArcStep> =
        arc.steps.filter { it.completedAt == null && minutesOf(it.gameKey) - it.baselineMinutes >= it.targetMinutes }

    /** Tiempo objetivo por defecto de un paso: unas tres sesiones normales de ese juego. */
    fun defaultTarget(game: Game, profile: GameProfile?): Int =
        (SessionPlanner.typicalMinutes(game, profile) * 3).coerceIn(MIN_STEP_MINUTES, MAX_STEP_MINUTES)

    /** Arco con los juegos dados, en su orden. Los que no están en la biblioteca se ignoran. */
    fun draft(
        id: String,
        title: String,
        theme: String?,
        description: String?,
        keys: List<String>,
        games: Map<String, Game>,
        profiles: Map<String, GameProfile>,
        goals: Map<String, String> = emptyMap(),
        targetMinutes: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): Arc {
        val steps = keys.distinct().mapNotNull { games[it] }.mapIndexed { i, g ->
            val profile = profiles[g.key]
            ArcStep(
                position = i,
                gameKey = g.key,
                goal = goals[g.key],
                targetMinutes = targetMinutes?.coerceIn(10, 600) ?: defaultTarget(g, profile),
                baselineMinutes = profile?.totalMinutes ?: g.stats.minutes,
            )
        }
        return Arc(id, title, theme, description, ArcStatus.Active, steps, now, now)
    }

    /** Arco de una saga en orden de salida (ver LibraryCurator.series). */
    fun fromSeries(
        id: String,
        series: Series,
        games: Map<String, Game>,
        profiles: Map<String, GameProfile>,
        now: Long = System.currentTimeMillis(),
    ): Arc = draft(id, series.name, series.name, null, series.keys, games, profiles, now = now)
}
