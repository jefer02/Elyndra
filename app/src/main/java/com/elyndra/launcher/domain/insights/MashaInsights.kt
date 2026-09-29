package com.elyndra.launcher.domain.insights

import com.elyndra.launcher.domain.Game
import com.elyndra.launcher.domain.curation.CurationReport
import com.elyndra.launcher.domain.device.DeviceState
import com.elyndra.launcher.domain.profile.GameProfile
import com.elyndra.launcher.domain.profile.PlayState
import com.elyndra.launcher.domain.session.ArcProgress

/**
 * Algo que Masha puede decir sin que se lo pregunten.
 *
 * [id] es una huella estable: con ella se descarta una sugerencia ("ahora no")
 * y se evita repetir el mismo aviso. [weight] ordena: gana lo más útil ahora.
 */
sealed interface Insight {
    val id: String
    val weight: Int

    /** Se puede mandar como notificación (lo demás solo se dice dentro de la app). */
    val notifiable: Boolean get() = false

    data object EmptyLibrary : Insight {
        override val id = "empty"
        override val weight = 100
    }

    data class DeviceHot(val tempC: Float?) : Insight {
        override val id = "hot"
        override val weight = 90
    }

    data class ArcNext(val arcId: String, val arcTitle: String, val gameKey: String, val gameTitle: String, val step: Int, val total: Int) : Insight {
        override val id = "arc:$arcId:$step"
        override val weight = 80
        override val notifiable = true
    }

    data class MetadataFinished(val matched: Int, val missed: Int, val finishedAt: Long) : Insight {
        override val id = "meta:$finishedAt"
        override val weight = 75
    }

    data class ContinueGame(val gameKey: String, val title: String, val daysAgo: Int, val lastMinutes: Int, val emulatorId: String?) : Insight {
        override val id = "continue:$gameKey:$daysAgo"
        override val weight = 70 - daysAgo
        override val notifiable = daysAgo >= 3
    }

    data class EmulatorMissing(val folderId: String, val systemName: String, val emulatorId: String) : Insight {
        override val id = "emu:$folderId:$emulatorId"
        override val weight = 65
    }

    data class Abandoned(val gameKey: String, val title: String, val daysIdle: Int, val minutes: Int) : Insight {
        override val id = "abandoned:$gameKey"
        override val weight = 50
        override val notifiable = minutes >= 60
    }

    data class ConfigureServices(val missingArt: Int) : Insight {
        override val id = "services"
        override val weight = 45
    }

    data class NeverOpened(val count: Int, val sampleKey: String, val sampleTitle: String) : Insight {
        override val id = "new:$sampleKey"
        override val weight = 40 + minOf(count, 10)
    }

    data class MissingArt(val count: Int) : Insight {
        override val id = "art:$count"
        override val weight = 35
    }

    data class CleanUp(val duplicates: Int, val incomplete: Int, val naming: Int) : Insight {
        override val id = "clean:$duplicates:$incomplete:$naming"
        override val weight = 30
    }

    data class Unmatched(val count: Int) : Insight {
        override val id = "unmatched:$count"
        override val weight = 25
    }
}

/** Lo que Masha mira para decidir qué decir. */
data class InsightInput(
    val games: List<Game>,
    val profiles: Map<String, GameProfile>,
    val curation: CurationReport,
    val arcs: List<ArcProgress>,
    val device: DeviceState?,
    val servicesConfigured: Boolean,
    /** Carpetas cuyo emulador no está instalado: (carpeta, sistema, emulador). */
    val missingEmulators: List<Triple<String, String, String>>,
    /** Última pasada de metadatos terminada: (acertados, fallados, cuándo); null = ninguna reciente. */
    val lastMetadataPass: Triple<Int, Int, Long>?,
    val dismissed: Set<String>,
    val now: Long,
)

object MashaInsights {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val FRESH_PASS_MS = 2L * 60 * 60 * 1000

    fun compute(input: InsightInput): List<Insight> {
        val out = ArrayList<Insight>()
        val games = input.games
        if (games.isEmpty()) return listOf(Insight.EmptyLibrary)

        input.device?.takeIf { it.isHot }?.let { out += Insight.DeviceHot(it.batteryTempC) }

        input.arcs.firstOrNull { !it.isComplete }?.let { p ->
            val step = p.current ?: return@let
            val game = games.firstOrNull { it.key == step.gameKey } ?: return@let
            out += Insight.ArcNext(p.arc.id, p.arc.title, game.key, game.title, p.doneSteps + 1, p.totalSteps)
        }

        input.lastMetadataPass?.let { (matched, missed, at) ->
            if (at > 0 && input.now - at < FRESH_PASS_MS) out += Insight.MetadataFinished(matched, missed, at)
        }

        // Seguir con lo último que se jugó de verdad (no una salida al minuto).
        games.mapNotNull { g -> input.profiles[g.key]?.let { g to it } }
            .filter { (_, p) -> p.state == PlayState.InProgress && p.lastSession?.earlyExit != true }
            .maxByOrNull { (_, p) -> p.lastPlayed }
            ?.let { (g, p) ->
                val days = p.daysSinceLastPlayed(input.now) ?: 0
                if (days >= 1) out += Insight.ContinueGame(g.key, g.title, days, p.lastSession?.minutes ?: 0, p.lastEmulator)
            }

        input.missingEmulators.firstOrNull()?.let { (folder, system, emu) -> out += Insight.EmulatorMissing(folder, system, emu) }

        // Abandonado con algo de tiempo encima: vale un "¿lo retomas?", no un sermón.
        games.mapNotNull { g -> input.profiles[g.key]?.let { g to it } }
            .filter { (_, p) -> p.state == PlayState.Abandoned && p.totalMinutes >= 30 }
            .filter { (_, p) -> (p.daysSinceLastPlayed(input.now) ?: 0) in 21..180 }
            .maxByOrNull { (_, p) -> p.totalMinutes }
            ?.let { (g, p) -> out += Insight.Abandoned(g.key, g.title, p.daysSinceLastPlayed(input.now) ?: 0, p.totalMinutes) }

        val missingArt = games.count { !it.hasPrimaryArt }
        if (!input.servicesConfigured && missingArt >= 5) out += Insight.ConfigureServices(missingArt)

        val never = games.filter { input.profiles[it.key]?.state == PlayState.New }
        if (never.isNotEmpty()) {
            // La muestra rota a diario para no proponer siempre el mismo.
            val day = (input.now / DAY_MS).toInt()
            val withArt = never.filter { it.hasPrimaryArt }.ifEmpty { never }
            val sample = withArt[Math.floorMod(day, withArt.size)]
            out += Insight.NeverOpened(never.size, sample.key, sample.title)
        }

        if (input.servicesConfigured && missingArt >= 5) out += Insight.MissingArt(missingArt)

        val c = input.curation
        val cleanup = c.duplicates.size + c.incomplete.size + c.naming.size
        if (cleanup >= 3) out += Insight.CleanUp(c.duplicates.size, c.incomplete.size, c.naming.size)

        val unmatched = games.count { it.meta.scrapedAt > 0 && !it.meta.matched }
        if (unmatched >= 3) out += Insight.Unmatched(unmatched)

        return out.filterNot { it.id in input.dismissed }.sortedByDescending { it.weight }
    }
}
