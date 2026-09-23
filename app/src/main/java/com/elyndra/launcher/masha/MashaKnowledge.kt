package com.elyndra.launcher.masha

import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.domain.Game
import com.elyndra.launcher.domain.curation.CurationReport
import com.elyndra.launcher.domain.curation.LibraryCurator
import com.elyndra.launcher.domain.games
import com.elyndra.launcher.domain.lists.ListContext
import com.elyndra.launcher.domain.profile.GameProfile
import com.elyndra.launcher.domain.profile.GameProfiles
import com.elyndra.launcher.domain.profile.LaunchRecord
import com.elyndra.launcher.launch.EmulatorInventory
import com.elyndra.launcher.library.Names
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lo que Masha sabe de la biblioteca en un momento dado: los juegos, el
 * perfil vivo de cada uno, la revisión de la biblioteca y el historial de
 * lanzamientos. Es la misma instantánea para el chat, la línea ambiental, el
 * widget y los avisos.
 */
data class KnowledgeSnapshot(
    val library: Library,
    val games: List<Game>,
    val profiles: Map<String, GameProfile>,
    val curation: CurationReport,
    val launches: List<LaunchRecord>,
    val now: Long,
) {
    val byKey: Map<String, Game> by lazy { games.associateBy { it.key } }

    fun game(key: String): Game? = byKey[key]

    fun listContext() = ListContext(games, profiles, curation, now)

    /**
     * Del título que dice el usuario (o el modelo) a un juego: exacto, luego
     * contenido en el título y, si no, el parecido más alto que pase del 70 %.
     */
    fun resolve(title: String): Game? {
        val q = title.trim()
        if (q.isEmpty()) return null
        return games.firstOrNull { it.title.equals(q, ignoreCase = true) }
            ?: games.filter { it.title.contains(q, ignoreCase = true) }.minByOrNull { it.title.length }
            ?: games.maxByOrNull { Names.similarity(q, it.title) }?.takeIf { Names.similarity(q, it.title) >= 0.7 }
    }
}

/**
 * Calcula la [KnowledgeSnapshot] y la guarda mientras la biblioteca no cambie.
 * La revisión de la biblioteca (lo más caro: analizar el nombre de cada ROM)
 * solo se rehace cuando cambian las ROMs, no cuando cambia un contador.
 */
@Singleton
class MashaKnowledge @Inject constructor(
    private val repo: LibraryRepository,
    private val inventory: EmulatorInventory,
) {
    private val mutex = Mutex()
    private var cached: KnowledgeSnapshot? = null
    private var launchesStale = true
    private var launches: List<LaunchRecord> = emptyList()
    private var curationFor: Pair<List<RomEntry>, List<Game>>? = null
    private var curation: CurationReport = CurationReport()

    suspend fun snapshot(now: Long = System.currentTimeMillis()): KnowledgeSnapshot = mutex.withLock {
        repo.awaitLoaded()
        val lib = repo.current
        cached?.let { c ->
            // Mismo estado de la biblioteca y nada nuevo en los lanzamientos: sirve la de antes.
            if (c.library === lib && !launchesStale && now - c.now < STALE_MS) return@withLock c
        }
        if (launchesStale) {
            launches = inventory.recentLaunches()
            launchesStale = false
        }
        withContext(Dispatchers.Default) {
            val games = lib.games()
            val profiles = GameProfiles.buildAll(games, lib.sessions, launches, now)
            val previous = curationFor
            if (previous == null || previous.first !== lib.roms) {
                curation = LibraryCurator.analyze(games)
                curationFor = lib.roms to games
            }
            KnowledgeSnapshot(lib, games, profiles, curation, launches, now).also { cached = it }
        }
    }

    /** Hubo un lanzamiento nuevo: la siguiente instantánea relee el historial. */
    fun onLaunchRecorded() {
        launchesStale = true
    }

    private companion object {
        /** Los estados "hace N días" caducan solos: se recalcula al menos cada diez minutos. */
        const val STALE_MS = 10L * 60 * 1000
    }
}
