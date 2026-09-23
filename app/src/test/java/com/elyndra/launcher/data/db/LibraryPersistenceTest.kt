package com.elyndra.launcher.data.db

import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.ArtOrigin
import com.elyndra.launcher.data.FileHashes
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.InMemoryLibraryStore
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.LibraryStore
import com.elyndra.launcher.data.PlaySession
import com.elyndra.launcher.data.PlayStats
import com.elyndra.launcher.data.RaInfo
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La biblioteca en Room: lo que se guarda es lo que se lee, y solo se escribe lo que cambia. */
class LibraryPersistenceTest {

    private val folder = RomFolder(
        id = "f1",
        systemId = "psx",
        treeUri = "content://tree/psx",
        rootDocId = "primary:psx",
        displayPath = "ROMs/psx",
        emulatorId = "duckstation",
        addedAt = 5,
        excluded = setOf("primary:psx/Removed.cue"),
    )

    private val richMeta = GameMeta(
        scrapedAt = 1,
        matched = true,
        sources = listOf("ss", "igdb"),
        name = "Crash Bandicoot",
        description = "A bandicoot.",
        genre = "Platform",
        cover = "media/a/cover_1.jpg",
        hero = "media/a/hero_1.jpg",
        pinned = listOf("hero"),
        artOrigins = mapOf("cover" to ArtOrigin("ss", "https://ss/cover.jpg"), "hero" to ArtOrigin("local")),
        matchedBy = "hash",
        matchConfidence = 1f,
        ra = RaInfo(gameId = 7, title = "Crash Bandicoot", achievements = 10, earned = 3, matchedBy = "hash"),
    )

    private fun rom(id: String, meta: GameMeta = GameMeta(), stats: PlayStats = PlayStats(), folderId: String = "f1") = RomEntry(
        id = id,
        folderId = folderId,
        systemId = "psx",
        docId = "primary:psx/$id.cue",
        fileName = "$id.cue",
        relPath = "$id.cue",
        size = 700,
        modified = 1,
        title = id,
        meta = meta,
        stats = stats,
        hashes = FileHashes(700, 1, crc = "abc", md5 = "m$id"),
        addedAt = 9,
    )

    private val library = Library(
        folders = listOf(folder),
        roms = listOf(rom("r1", richMeta, PlayStats(30, 99, 2)), rom("r2")),
        apps = listOf(AppEntry("com.a", "A", 3)),
        sessions = listOf(PlaySession("r:r1", 50, 30, "duckstation", 80, PlaySession.SOURCE_USAGE, false)),
        lastAutoScan = 42,
    )

    /** Aplica los cambios a unas "tablas" en memoria y vuelve a montar la biblioteca. */
    private fun persistAndReload(changes: LibraryChanges): Library = LibraryMapper.assemble(
        folders = changes.upsertFolders,
        exclusions = changes.upsertExclusions,
        roms = changes.upsertRoms,
        apps = changes.upsertApps,
        metadata = changes.upsertMetadata,
        artwork = changes.upsertArtwork,
        stats = changes.upsertStats,
        sessions = changes.upsertSessions,
        state = LibraryStateEntity(lastAutoScan = changes.lastAutoScan ?: 0, importedAt = 0),
    )

    @Test
    fun whatIsSavedIsWhatIsRead() {
        val reloaded = persistAndReload(LibraryDiff.between(Library(), library))
        assertEquals(library, reloaded)
    }

    @Test
    fun artworkKeepsItsSourceAndPin() {
        val rows = LibraryMapper.artwork("r:r1", richMeta).associateBy { it.kind }
        assertEquals("ss", rows.getValue("cover").source)
        assertEquals("https://ss/cover.jpg", rows.getValue("cover").remoteUrl)
        assertEquals("local", rows.getValue("hero").source)
        assertTrue(rows.getValue("hero").pinned)
        assertEquals(setOf("cover", "hero"), rows.keys)
    }

    @Test
    fun aGameNeverScrapedHasNoMetadataRow() {
        assertEquals(null, LibraryMapper.metadata("r:r2", GameMeta()))
        assertTrue(LibraryMapper.artwork("r:r2", GameMeta()).isEmpty())
        assertEquals(null, LibraryMapper.stats("r:r2", PlayStats()))
    }

    @Test
    fun nothingChangedWritesNothing() {
        assertTrue(LibraryDiff.between(library, library).isEmpty)
        // Otra instancia con el mismo contenido tampoco escribe nada.
        assertTrue(LibraryDiff.between(library, library.copy()).isEmpty)
    }

    @Test
    fun playingAGameOnlyTouchesItsStatsAndTheNewSession() {
        val played = library.copy(
            roms = library.roms.map { if (it.id == "r2") it.copy(stats = PlayStats(12, 100, 1)) else it },
            sessions = library.sessions + PlaySession("r:r2", 100, 12),
        )
        val c = LibraryDiff.between(library, played)
        assertEquals(1, c.upsertStats.size)
        assertEquals(1, c.upsertSessions.size)
        assertEquals(2, c.size)
    }

    @Test
    fun removingAFolderTakesEverythingOfItsGamesWithIt() {
        val gone = library.copy(folders = emptyList(), roms = emptyList(), sessions = emptyList())
        val c = LibraryDiff.between(library, gone)
        assertEquals(1, c.deleteFolders.size)
        assertEquals(2, c.deleteRoms.size)
        assertEquals(1, c.deleteMetadata.size)
        assertEquals(2, c.deleteArtwork.size)
        assertEquals(1, c.deleteStats.size)
        assertEquals(1, c.deleteSessions.size)
    }

    @Test
    fun clearingOneImageDeletesOnlyThatRow() {
        val cleared = library.copy(
            roms = library.roms.map {
                if (it.id == "r1") it.copy(meta = it.meta.copy(cover = null, artOrigins = it.meta.artOrigins - "cover")) else it
            },
        )
        val c = LibraryDiff.between(library, cleared)
        assertEquals(listOf("cover"), c.deleteArtwork.map { it.kind })
        assertTrue(c.upsertArtwork.isEmpty())
        assertTrue(c.upsertMetadata.isEmpty())
    }

    @Test
    fun aRomWithoutFolderIsNotPersisted() {
        val orphan = library.copy(roms = library.roms + rom("lost", folderId = "nope"))
        val c = LibraryDiff.between(library, orphan)
        assertTrue(c.upsertRoms.none { it.id == "lost" })
    }

    @Test
    fun theRepositorySavesTheDifferenceAndCanReloadIt() = runBlocking {
        val job = Job()
        val store = InMemoryLibraryStore(library)
        val repo = LibraryRepository(store, CoroutineScope(job))
        repo.load()
        repo.awaitLoaded()
        assertEquals(library, repo.current)

        repo.addPlaytime("r:r2", 25, start = 500)
        repo.flush()
        assertEquals(repo.current, store.saved)
        assertEquals(25, store.saved.roms.first { it.id == "r2" }.stats.minutes)
        job.cancel()
    }

    @Test
    fun aFailedSaveIsRetriedWithTheWholeDifference() = runBlocking {
        val job = Job()
        val store = FlakyStore(library)
        val repo = LibraryRepository(store, CoroutineScope(job))
        repo.load()
        repo.awaitLoaded()

        store.failNext = true
        repo.addPlaytime("r:r2", 10, start = 1)
        repo.flush()
        assertEquals(library, store.saved)

        repo.addPlaytime("r:r2", 5, start = 2)
        repo.flush()
        // El segundo guardado lleva también lo del primero, que falló.
        assertEquals(library, store.lastPrevious)
        assertEquals(15, store.saved.roms.first { it.id == "r2" }.stats.minutes)
        job.cancel()
    }

    @Test
    fun earlyExitsAreStoredWithoutAddingTime() = runBlocking {
        val job = Job()
        val repo = LibraryRepository(InMemoryLibraryStore(library), CoroutineScope(job))
        repo.load()
        repo.awaitLoaded()
        repo.recordSession(PlaySession("r:r2", 7, 0, "duckstation", 8, earlyExit = true))
        assertEquals(0, repo.current.roms.first { it.id == "r2" }.stats.minutes)
        assertTrue(repo.current.sessions.any { it.key == "r:r2" && it.earlyExit })
        job.cancel()
    }

    private class FlakyStore(initial: Library) : LibraryStore {
        var saved = initial
        var lastPrevious: Library? = null
        var failNext = false

        override suspend fun load(): Library = saved

        override suspend fun save(previous: Library, next: Library) {
            if (failNext) {
                failNext = false
                error("disco lleno")
            }
            lastPrevious = previous
            saved = next
        }
    }
}
