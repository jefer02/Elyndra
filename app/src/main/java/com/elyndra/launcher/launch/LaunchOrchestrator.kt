package com.elyndra.launcher.launch

import com.elyndra.launcher.core.device.DeviceStateMonitor
import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.data.db.LaunchEventEntity
import com.elyndra.launcher.domain.device.DeviceState
import com.elyndra.launcher.domain.launch.LaunchAdvisor
import com.elyndra.launcher.domain.launch.LaunchContext
import com.elyndra.launcher.domain.launch.LaunchDecision
import com.elyndra.launcher.domain.launch.RankedEmulator
import com.elyndra.launcher.domain.profile.GameProfiles
import com.elyndra.launcher.library.AppCatalog
import com.elyndra.launcher.library.Names
import com.elyndra.launcher.masha.MashaKnowledge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El orquestador de lanzamientos: decide con qué emulador va cada ROM
 * teniendo en cuenta lo que eligió el usuario, lo que hay instalado y cómo le
 * ha ido a cada emulador con ese juego *en este dispositivo*, y deja apuntado
 * cómo acabó cada intento para aprender de él.
 *
 * La decisión en sí es de [LaunchAdvisor] (dominio puro); aquí se le dan los
 * datos reales.
 */
@Singleton
class LaunchOrchestrator @Inject constructor(
    private val knowledge: MashaKnowledge,
    private val inventory: EmulatorInventory,
    private val launcher: GameLauncher,
    private val apps: AppCatalog,
    private val device: DeviceStateMonitor,
) {

    suspend fun decide(rom: RomEntry, folder: RomFolder, now: Long = System.currentTimeMillis()): LaunchDecision {
        val snapshot = knowledge.snapshot(now)
        val system = Systems.byId(rom.systemId)
        val systemKeys = snapshot.games.filter { it.systemId == rom.systemId }.map { it.key }.toSet()
        val sessions = snapshot.library.sessions
        val launches = snapshot.launches
        return LaunchAdvisor.decide(
            LaunchContext(
                gameKey = rom.key,
                systemId = rom.systemId,
                romEmulator = rom.emulatorId,
                folderEmulator = folder.emulatorId,
                curated = system?.emulators.orEmpty(),
                isInstalled = launcher::isEmulatorInstalled,
                gameUsage = GameProfiles.usage(sessions.filter { it.key == rom.key }, launches.filter { it.gameKey == rom.key }),
                systemUsage = GameProfiles.usage(sessions.filter { it.key in systemKeys }, launches.filter { it.gameKey in systemKeys }),
                profile = snapshot.profiles[rom.key],
                device = device.snapshot(),
                now = now,
            ),
        )
    }

    /** Ranking de emuladores de un juego, para Masha y para la ficha. */
    suspend fun ranking(rom: RomEntry, folder: RomFolder): List<RankedEmulator> = decide(rom, folder).ranked

    /** Apunta cómo acabó un lanzamiento (ver LaunchOutcome). */
    suspend fun record(gameKey: String, systemId: String?, emulatorId: String?, packageName: String?, outcome: String) {
        val d = device.snapshot()
        inventory.recordLaunch(
            LaunchEventEntity(
                gameKey = gameKey,
                systemId = systemId,
                emulatorId = emulatorId,
                packageName = packageName,
                at = System.currentTimeMillis(),
                outcome = outcome,
                batteryPct = d.batteryPct,
                // Mismos números que PowerManager (0 = normal); null si el sistema no lo dice.
                thermal = d.thermal.takeIf { it != DeviceState.Thermal.Unknown }?.let { it.ordinal - 1 },
            ),
        )
        knowledge.onLaunchRecorded()
    }

    /** Paquete instalado que se lanzaría con este emulador (el que se busca luego en UsageStats). */
    fun packageFor(emulatorId: String): String? {
        if (emulatorId.startsWith(Emulators.CUSTOM_PREFIX)) return emulatorId.removePrefix(Emulators.CUSTOM_PREFIX)
        val profile = Emulators.byId(emulatorId) ?: return null
        return launcher.installedComponent(profile)?.substringBefore('/')
    }

    fun name(emulatorId: String): String {
        if (emulatorId.startsWith(Emulators.CUSTOM_PREFIX)) {
            val pkg = emulatorId.removePrefix(Emulators.CUSTOM_PREFIX)
            return apps.label(pkg) ?: pkg
        }
        return Emulators.byId(emulatorId)?.name ?: emulatorId
    }

    fun isInstalled(emulatorId: String): Boolean = launcher.isEmulatorInstalled(emulatorId)

    /**
     * De lo que dice el usuario ("DuckStation", "aether", "ra_mupen64plus_next")
     * al id de un perfil, preferiblemente de los del sistema del juego.
     */
    fun resolveEmulator(query: String, systemId: String?): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        Emulators.byId(q)?.let { return it.id }
        val preferred = Systems.byId(systemId)?.emulators.orEmpty().mapNotNull { Emulators.byId(it) }
        val all = preferred + Emulators.ALL.filterNot { it in preferred }
        return all.firstOrNull { it.name.equals(q, ignoreCase = true) }?.id
            ?: preferred.firstOrNull { it.name.contains(q, ignoreCase = true) }?.id
            ?: all.firstOrNull { it.name.contains(q, ignoreCase = true) }?.id
            ?: all.maxByOrNull { Names.similarity(q, it.name) }?.takeIf { Names.similarity(q, it.name) >= 0.6 }?.id
    }
}
