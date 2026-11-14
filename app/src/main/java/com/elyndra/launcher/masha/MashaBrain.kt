package com.elyndra.launcher.masha

import com.elyndra.launcher.domain.library.UserFolderLogic
import com.elyndra.launcher.core.device.DeviceStateMonitor
import com.elyndra.launcher.data.AffinityPrefs
import com.elyndra.launcher.data.ArcRepository
import com.elyndra.launcher.data.ConversationPrefs
import com.elyndra.launcher.data.InsightPacingPrefs
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.domain.insights.Insight
import com.elyndra.launcher.domain.insights.InsightInput
import com.elyndra.launcher.domain.insights.InsightPacing
import com.elyndra.launcher.domain.insights.MashaInsights
import com.elyndra.launcher.domain.ports.PortRecommend
import com.elyndra.launcher.domain.relationship.Affinity
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
 * Lo que hay detrás de Lucy (Masha en el código) en cada punto de la app: junta la IA, lo que se
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
    private val pacingStore: InsightPacingPrefs,
    affinityStore: AffinityPrefs,
    conversationStore: ConversationPrefs,
) {

    /** De qué iba la última conversación y cuándo fue (ver [ConversationRecall]). */
    val conversation: ConversationStore = conversationStore

    /** La relación con Lucy: cuánto de cálida suena (nunca lo que puede hacer). */
    val affinity = Affinity(affinityStore)

    /**
     * Qué ha dicho ya el bocadillo y cuándo. Guardado: sobrevive a que Android
     * cierre la app entre partidas, así que abrirla no basta para que hable.
     */
    val pacing = InsightPacing(pacingStore)

    /** Masha puede usar la IA ahora mismo (clave, permiso del usuario y red). */
    fun canGoOnline(): Boolean = ai.isAvailable && device.snapshot().isOnline

    suspend fun context(language: String, screen: String?, focus: String?, ports: PortRecommend.Summary? = null, deviceWarned: Boolean = false): BuiltContext {
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
                deviceWarned = deviceWarned,
                zone = ZoneId.systemDefault(),
                language = language,
                screen = screen,
                focus = focus,
                services = Service.entries.filter { credentials.isConfigured(it) }.map { serviceLabel(it) },
                metadataRunning = engine.isRunning,
                installedEmulators = inventory.installed().map { it.label }.distinct(),
                folderEmulators = folderEmulators,
                savedLists = skills.savedListNames(),
                userFolders = UserFolderLogic.sorted(snapshot.library.userFolders).map { it.name },
                relationship = affinity.level,
                lastConversation = conversation.summary?.let { it to conversation.lastActivityAt },
                ports = ports,
            ),
        )
    }

    /** Sugerencias ambientales, la más útil primero, sin las descartadas hoy. */
    suspend fun insights(): List<Insight> {
        val s = knowledge.snapshot()
        // Las carpetas virtuales (ports) no tienen emulador: nada que echar en falta.
        val missing = s.library.realFolders.mapNotNull { f ->
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

    /** Borra todo lo que Masha guarda: recuerdos, hilo, el último tema, respuestas en caché y la relación. */
    suspend fun forgetEverything() {
        affinity.reset()
        conversation.clear()
        memory.clearMemories()
        memory.clearHistory()
        cache.clear()
        settings.mashaDismissed = emptySet()
        pacingStore.clear()
    }

    fun emulatorName(id: String): String = orchestrator.name(id)

    private fun serviceLabel(s: Service) = when (s) {
        Service.ScreenScraper -> "ScreenScraper"
        Service.Igdb -> "IGDB"
        Service.SteamGridDb -> "SteamGridDB"
        Service.RetroAchievements -> "RetroAchievements"
        Service.Libretro -> "libretro"
        Service.Steam -> "Steam"
        Service.GooglePlay -> "Google Play"
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
