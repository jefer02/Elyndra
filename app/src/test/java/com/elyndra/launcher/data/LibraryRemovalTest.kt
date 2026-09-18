package com.elyndra.launcher.data

import com.elyndra.launcher.library.RomScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Quitar un juego de la biblioteca sin tocar el archivo que hay en el disco. */
class LibraryRemovalTest {

    private lateinit var file: File
    private lateinit var job: Job
    private lateinit var repo: LibraryRepository

    private val folder = RomFolder(
        id = "f1",
        systemId = "psp",
        treeUri = "content://tree/primary%3AROMs",
        rootDocId = "primary:ROMs",
        displayPath = "ROMs/psp",
    )

    private fun found(name: String) = RomScanner.Found(
        docId = "primary:ROMs/psp/$name",
        name = name,
        relPath = name,
        size = 100,
        modified = 1,
        isDir = false,
    )

    private val scan = listOf(found("Tekken.iso"), found("Wipeout.iso"))

    @Before
    fun setUp() {
        file = File.createTempFile("library", ".json").also { it.delete() }
        job = Job()
        repo = LibraryRepository(file, CoroutineScope(job))
        repo.addFolder(folder, scan)
    }

    @After
    fun tearDown() {
        job.cancel()
        file.delete()
    }

    @Test
    fun removingAGameTakesItOutAndRemembersIt() {
        val tekken = repo.current.roms.first { it.fileName == "Tekken.iso" }
        repo.removeRom(tekken.id)

        assertEquals(listOf("Wipeout.iso"), repo.current.roms.map { it.fileName })
        // El archivo sigue en el disco; lo que se guarda es que ya no se quiere.
        assertEquals(setOf(tekken.docId), repo.current.folders.first().excluded)
    }

    @Test
    fun aRescanDoesNotBringItBack() {
        val tekken = repo.current.roms.first { it.fileName == "Tekken.iso" }
        repo.removeRom(tekken.id)

        val diff = repo.mergeScan(folder.id, scan)

        assertEquals(listOf("Wipeout.iso"), repo.current.roms.map { it.fileName })
        assertTrue("no debería dar de alta nada", diff.added.isEmpty())
    }

    @Test
    fun restoringBringsItBackOnTheNextScan() {
        val tekken = repo.current.roms.first { it.fileName == "Tekken.iso" }
        repo.removeRom(tekken.id)
        repo.restoreRemoved(folder.id)

        assertTrue(repo.current.folders.first().excluded.isEmpty())
        val diff = repo.mergeScan(folder.id, scan)
        assertEquals(listOf("Tekken.iso", "Wipeout.iso"), repo.current.roms.map { it.fileName }.sorted())
        assertEquals(1, diff.added.size)
    }

    @Test
    fun theTimePlayedGoesWithTheGame() {
        val tekken = repo.current.roms.first { it.fileName == "Tekken.iso" }
        repo.addPlaytime(tekken.key, 30, start = 1)
        assertTrue(repo.current.sessions.any { it.key == tekken.key })

        repo.removeRom(tekken.id)
        assertTrue(repo.current.sessions.none { it.key == tekken.key })
    }
}
