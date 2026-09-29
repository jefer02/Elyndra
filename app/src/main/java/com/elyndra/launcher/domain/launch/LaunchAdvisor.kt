package com.elyndra.launcher.domain.launch

import com.elyndra.launcher.domain.SystemWeight
import com.elyndra.launcher.domain.device.DeviceState
import com.elyndra.launcher.domain.device.DeviceWarning
import com.elyndra.launcher.domain.profile.EmulatorUsage
import com.elyndra.launcher.domain.profile.GameProfile

/** Todo lo que hace falta para decidir con qué se lanza una ROM. */
data class LaunchContext(
    val gameKey: String,
    val systemId: String,
    /** Emulador elegido para esta ROM en concreto (manda sobre el de la carpeta). */
    val romEmulator: String?,
    val folderEmulator: String?,
    /** Lista recomendada del sistema. */
    val curated: List<String>,
    val isInstalled: (String) -> Boolean,
    val gameUsage: List<EmulatorUsage>,
    val systemUsage: List<EmulatorUsage>,
    val profile: GameProfile?,
    val device: DeviceState?,
    val now: Long,
)

/** La línea de Masha en el velo de lanzamiento: por qué va con lo que va, o qué tener en cuenta. */
sealed interface LaunchNote {
    data class Device(val warnings: List<DeviceWarning>) : LaunchNote

    /** La última vez se salió al instante con este mismo emulador. */
    data class LastExitEarly(val emulatorId: String) : LaunchNote

    /** Vuelta a un juego: cuándo y cuánto fue la última sesión. */
    data class Returning(val daysAgo: Int, val lastMinutes: Int, val emulatorId: String?) : LaunchNote

    /** Emulador probado con este juego. */
    data class Proven(val emulatorId: String, val goodSessions: Int) : LaunchNote

    /** Primera vez con este juego, con el emulador que mejor va con su sistema. */
    data class BestForSystem(val emulatorId: String) : LaunchNote
}

sealed interface LaunchDecision {
    val ranked: List<RankedEmulator>

    /** Adelante. [chosenByUser] = el emulador lo eligió el usuario (juego o carpeta). */
    data class Go(
        val emulatorId: String,
        val chosenByUser: Boolean,
        val note: LaunchNote?,
        override val ranked: List<RankedEmulator>,
    ) : LaunchDecision

    /** El emulador elegido no está instalado, pero hay otro compatible que sí. */
    data class UseInstead(
        val configured: String,
        val alternative: RankedEmulator,
        override val ranked: List<RankedEmulator>,
    ) : LaunchDecision

    /**
     * El elegido va mal con este juego (salidas inmediatas, fallos) y otro
     * instalado le ha ido bien: antes de lanzar, se pregunta.
     */
    data class SuggestSwitch(
        val configured: String,
        val alternative: RankedEmulator,
        val struggling: EmulatorUsage,
        override val ranked: List<RankedEmulator>,
    ) : LaunchDecision

    /** Ningún emulador compatible instalado: se ofrece instalar [recommended]. */
    data class NoneInstalled(
        val configured: String?,
        val recommended: String?,
        override val ranked: List<RankedEmulator>,
    ) : LaunchDecision
}

/**
 * El orquestador de lanzamientos, en puro: decide con qué emulador va cada
 * ROM y qué decir antes de lanzarla.
 *
 * Reglas, por orden:
 *  1. Lo que el usuario eligió manda (el de la ROM y, si no, el de la carpeta).
 *     Masha no cambia de emulador por su cuenta.
 *  2. Pero si lo elegido va mal con *este* juego y otro instalado ha ido bien,
 *     lo dice antes de lanzar y deja elegir ([LaunchDecision.SuggestSwitch]).
 *  3. Si lo elegido no está instalado y otro compatible sí, lo ofrece.
 *  4. Sin elección, el mejor clasificado por [EmulatorRanker].
 */
object LaunchAdvisor {

    fun decide(ctx: LaunchContext): LaunchDecision {
        val configured = ctx.romEmulator ?: ctx.folderEmulator
        val ranked = EmulatorRanker.rank(
            curated = ctx.curated,
            isInstalled = ctx.isInstalled,
            gameUsage = ctx.gameUsage,
            systemUsage = ctx.systemUsage,
            extra = listOfNotNull(configured),
        )
        val bestInstalled = ranked.firstOrNull { it.installed && it.verdict != Verdict.Struggling }
            ?: ranked.firstOrNull { it.installed }

        if (configured != null) {
            val mine = ranked.firstOrNull { it.emulatorId == configured }
            if (mine == null || !mine.installed) {
                val alternative = bestInstalled ?: return LaunchDecision.NoneInstalled(configured, configured, ranked)
                return LaunchDecision.UseInstead(configured, alternative, ranked)
            }
            if (mine.verdict == Verdict.Struggling) {
                val better = ranked.firstOrNull {
                    it.installed && it.emulatorId != configured &&
                        (it.onGame?.goodSessions ?: 0) >= 1 && it.verdict != Verdict.Struggling
                }
                val usage = mine.onGame
                if (better != null && usage != null) return LaunchDecision.SuggestSwitch(configured, better, usage, ranked)
            }
            return LaunchDecision.Go(configured, chosenByUser = true, note = note(ctx, mine), ranked = ranked)
        }

        val pick = bestInstalled ?: return LaunchDecision.NoneInstalled(null, ranked.firstOrNull()?.emulatorId, ranked)
        return LaunchDecision.Go(pick.emulatorId, chosenByUser = false, note = note(ctx, pick), ranked = ranked)
    }

    /** Lo más útil que se puede decir en el velo, en orden de importancia. */
    fun note(ctx: LaunchContext, chosen: RankedEmulator): LaunchNote? {
        val warnings = ctx.device?.warningsFor(SystemWeight.of(ctx.systemId)).orEmpty()
        if (warnings.isNotEmpty()) return LaunchNote.Device(warnings)

        val profile = ctx.profile
        val last = profile?.lastSession
        if (last != null && last.earlyExit && last.emulatorId == chosen.emulatorId) {
            return LaunchNote.LastExitEarly(chosen.emulatorId)
        }
        if (profile != null && last != null && !last.earlyExit) {
            val days = profile.daysSinceLastPlayed(ctx.now) ?: 0
            // Retomar tiene gracia a partir de un par de días; ayer es "seguir".
            if (days >= 2) return LaunchNote.Returning(days, last.minutes, last.emulatorId)
        }
        return when (chosen.verdict) {
            Verdict.Proven -> {
                val good = chosen.onGame?.goodSessions ?: 0
                if (good > 0) LaunchNote.Proven(chosen.emulatorId, good) else LaunchNote.BestForSystem(chosen.emulatorId)
            }
            Verdict.Promising -> if (chosen.onGame == null) LaunchNote.BestForSystem(chosen.emulatorId) else null
            else -> null
        }
    }
}
