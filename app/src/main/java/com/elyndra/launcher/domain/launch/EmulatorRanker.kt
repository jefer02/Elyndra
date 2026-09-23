package com.elyndra.launcher.domain.launch

import com.elyndra.launcher.domain.profile.EmulatorUsage
import kotlin.math.ln
import kotlin.math.min

/** Qué se sabe de un emulador para un juego concreto en este dispositivo. */
enum class Verdict {
    /** Ha dado sesiones largas sin tropiezos. */
    Proven,

    /** Hay algo de uso y pinta bien. */
    Promising,

    /** Nunca se ha usado aquí. */
    Untested,

    /** Salidas inmediatas o lanzamientos fallidos: algo no va. */
    Struggling,
}

data class RankedEmulator(
    val emulatorId: String,
    val installed: Boolean,
    val score: Double,
    val verdict: Verdict,
    /** Uso con este juego y con su sistema (null = ninguno). */
    val onGame: EmulatorUsage?,
    val onSystem: EmulatorUsage?,
    /** Posición en la lista recomendada del sistema (-1 = no está: una app elegida a mano). */
    val preferenceIndex: Int,
)

/**
 * Ordena los emuladores de un juego según cómo le han ido *en este
 * dispositivo*.
 *
 * Un lanzador no puede medir fotogramas por segundo, pero sí algo que los
 * delata: si una sesión dura, el emulador funciona; si se sale al minuto o el
 * lanzamiento falla, probablemente no. La lista recomendada del sistema (la
 * experiencia de la comunidad, ver Systems.kt) es el punto de partida, y lo
 * vivido en el propio dispositivo lo va corrigiendo: primero lo de este
 * juego, luego lo del sistema.
 */
object EmulatorRanker {

    private const val PRIOR_WEIGHT = 0.35
    private const val GAME_WEIGHT = 0.6
    private const val SYSTEM_WEIGHT = 0.25
    private const val ENGAGEMENT_WEIGHT = 0.1
    private const val NOT_INSTALLED_PENALTY = 10.0

    fun rank(
        /** La lista recomendada del sistema, en su orden. */
        curated: List<String>,
        isInstalled: (String) -> Boolean,
        gameUsage: List<EmulatorUsage>,
        systemUsage: List<EmulatorUsage>,
        /** Otros ids que también cuentan (el emulador elegido a mano, "custom:…"). */
        extra: List<String> = emptyList(),
    ): List<RankedEmulator> {
        val ids = (curated + extra + gameUsage.map { it.emulatorId }).distinct()
        val onGame = gameUsage.associateBy { it.emulatorId }
        val onSystem = systemUsage.associateBy { it.emulatorId }
        return ids.map { id ->
            val index = curated.indexOf(id)
            val game = onGame[id]
            val system = onSystem[id]
            val installed = isInstalled(id)
            val prior = if (index < 0) PRIOR_WEIGHT * 0.3 else PRIOR_WEIGHT * (1.0 - index.toDouble() / curated.size.coerceAtLeast(1))
            val score = prior +
                GAME_WEIGHT * signal(game) +
                SYSTEM_WEIGHT * signal(system) +
                ENGAGEMENT_WEIGHT * engagement(game) -
                if (installed) 0.0 else NOT_INSTALLED_PENALTY
            RankedEmulator(id, installed, score, verdict(game, system), game, system, index)
        }.sortedByDescending { it.score }
    }

    /**
     * De -1 (siempre falla) a +1 (siempre va bien), escalado por cuánta
     * evidencia hay: una sola sesión buena no pesa como diez.
     */
    fun signal(u: EmulatorUsage?): Double {
        if (u == null) return 0.0
        val n = u.sessions + u.failedLaunches
        if (n == 0) return 0.0
        // Suavizado de Laplace: sin datos, 0,5 (neutro).
        val quality = (u.goodSessions + 1.0) / (u.goodSessions + u.earlyExits + 2.0 * u.failedLaunches + 2.0)
        val confidence = n / (n + 3.0)
        return (quality - 0.5) * 2.0 * confidence
    }

    private fun engagement(u: EmulatorUsage?): Double =
        if (u == null || u.minutes <= 0) 0.0 else min(1.0, ln(1.0 + u.minutes) / ln(1.0 + 600.0))

    fun verdict(game: EmulatorUsage?, system: EmulatorUsage?): Verdict {
        if (game != null) {
            if (game.failedLaunches >= 2 || (game.earlyExits >= 2 && game.goodSessions == 0)) return Verdict.Struggling
            if (game.goodSessions >= 2 || (game.goodSessions >= 1 && game.earlyExits == 0)) return Verdict.Proven
            if (game.goodSessions >= 1) return Verdict.Promising
            if (game.earlyExits >= 1 && game.sessions == game.earlyExits) return Verdict.Struggling
        }
        if (system != null && system.sessions > 0) {
            val badRate = (system.earlyExits + system.failedLaunches).toDouble() / (system.sessions + system.failedLaunches)
            if (system.goodSessions >= 3 && badRate < 0.34) return Verdict.Proven
            if (system.goodSessions >= 1) return Verdict.Promising
        }
        return Verdict.Untested
    }
}
