package com.elyndra.launcher.masha

import com.elyndra.launcher.core.device.DeviceStateMonitor
import com.elyndra.launcher.data.ArcRepository
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.domain.insights.Insight
import com.elyndra.launcher.domain.insights.InsightInput
import com.elyndra.launcher.domain.insights.MashaInsights
import com.elyndra.launcher.domain.session.ArcStep
import com.elyndra.launcher.launch.EmulatorInventory
import com.elyndra.launcher.launch.LaunchOrchestrator
import com.elyndra.launcher.metadata.MetadataEngine
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.metadata.ServiceCredentials
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lo que hay detrás de Masha en cada punto de la app: junta la IA, lo que se
 * sabe de la biblioteca, la memoria, el dispositivo y los arcos, y arma con
 * ello el contexto de cada pregunta y las sugerencias ambientales.
 *
 * El chat, la línea sobre el carrusel, el widget y los avisos salen de aquí,
 * así que todos cuentan lo mismo.
 */
@Singleton
class MashaBrain @Inject constructor(
    val ai: MashaAI,
    val config: MashaConfig,
    val skills: MashaSkills,
    val knowledge: MashaKnowledge,
    val memory: MashaMemory,
    val cache: MashaCache,
    val device: DeviceStateMonitor,
    private val arcs: ArcRepository,
    private val inventory: EmulatorInventory,
    private val orchestrator: LaunchOrchestrator,
    private val credentials: ServiceCredentials,
    private val engine: MetadataEngine,
    private val settings: SettingsStore,
) {

    /** Masha puede usar la IA ahora mismo (clave, permiso del usuario y red). */
    fun canGoOnline(): Boolean = ai.isAvailable && device.snapshot().isOnline

    suspend fun context(language: String, screen: String?, focus: String?): BuiltContext {
        val snapshot = knowledge.snapshot()
        val folderEmulators = snapshot.library.folders
            .mapNotNull { f -> f.emulatorId?.let { e -> f.systemId to (orchestrator.name(e) to orchestrator.isInstalled(e)) } }
            .toMap()
        return MashaContextBuilder.build(
            ContextInput(
                snapshot = snapshot,
                arcs = skills.arcProgress(),
                memories = memory.all(20),
                device = device.snapshot(),
                zone = ZoneId.systemDefault(),
                language = language,
                screen = screen,
                focus = focus,
                services = Service.entries.filter { credentials.isConfigured(it) }.map { serviceLabel(it) },
                metadataRunning = engine.isRunning,
                installedEmulators = inventory.installed().map { it.label }.distinct(),
                folderEmulators = folderEmulators,
                savedLists = skills.savedListNames(),
            ),
        )
    }

    /** Sugerencias ambientales, la más útil primero, sin las descartadas hoy. */
    suspend fun insights(): List<Insight> {
        val s = knowledge.snapshot()
        val missing = s.library.folders.mapNotNull { f ->
            val emulator = f.emulatorId ?: return@mapNotNull null
            if (orchestrator.isInstalled(emulator)) null else Triple(f.id, Systems.byId(f.systemId)?.name ?: f.systemId, emulator)
        }
        val progress = engine.progress.value
        val lastPass = progress.takeIf { it.finishedAt > 0 && !it.cancelled && it.total > 0 }
            ?.let { Triple(it.matched, it.missed, it.finishedAt) }
        return MashaInsights.compute(
            InsightInput(
                games = s.games,
                profiles = s.profiles,
                curation = s.curation,
                arcs = skills.arcProgress(),
                device = device.snapshot(),
                servicesConfigured = credentials.anyConfigured(),
                missingEmulators = missing,
                lastMetadataPass = lastPass,
                dismissed = dismissedToday(s.now),
                now = s.now,
            ),
        )
    }

    /** "Ahora no": la sugerencia no vuelve a salir en lo que queda del día. */
    fun dismiss(insight: Insight, now: Long = System.currentTimeMillis()) {
        val day = now / DAY_MS
        settings.mashaDismissed = settings.mashaDismissed.filter { it.startsWith("$day|") }.toSet() + "$day|${insight.id}"
    }

    private fun dismissedToday(now: Long): Set<String> {
        val prefix = "${now / DAY_MS}|"
        return settings.mashaDismissed.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    }

    /** Cierra los pasos de arco cumplidos. Devuelve los recién cerrados. */
    suspend fun syncArcs(): List<Pair<String, ArcStep>> {
        val s = knowledge.snapshot()
        return arcs.sync({ key -> s.profiles[key]?.totalMinutes ?: s.game(key)?.stats?.minutes ?: 0 })
            .map { (arc, step) -> arc.title to step }
    }

    /** Borra todo lo que Masha guarda: recuerdos, hilo y respuestas en caché. */
    suspend fun forgetEverything() {
        memory.clearMemories()
        memory.clearHistory()
        cache.clear()
        settings.mashaDismissed = emptySet()
    }

    fun emulatorName(id: String): String = orchestrator.name(id)

    private fun serviceLabel(s: Service) = when (s) {
        Service.ScreenScraper -> "ScreenScraper"
        Service.Igdb -> "IGDB"
        Service.SteamGridDb -> "SteamGridDB"
        Service.RetroAchievements -> "RetroAchievements"
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
