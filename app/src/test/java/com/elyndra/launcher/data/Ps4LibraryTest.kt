package com.elyndra.launcher.data

import com.elyndra.launcher.library.RomScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Juegos de PS4 en la biblioteca: título del PARAM.SFO e identidad por CUSA. */
class Ps4LibraryTest {

    private lateinit var job: Job
    private lateinit var repo: LibraryRepository

    private val folder = RomFolder(
        id = "ps4",
        systemId = "ps4",
        treeUri = "content://tree/primary%3APS4",
        rootDocId = "primary:PS4",
        displayPath = "PS4",
    )

    private fun game(dirName: String, serial: String, title: String) = RomScanner.Found(
        docId = "primary:PS4/$dirName",
        name = dirName,
        relPath = dirName,
        size = 0,
        modified = 1,
        isDir = true,
        title = title,
        serial = serial,
    )

    @Before
    fun setUp() {
        job = Job()
        repo = LibraryRepository(InMemoryLibraryStore(), CoroutineScope(job))
        repo.addFolder(folder, listOf(game("CUSA00900", "CUSA00900", "Bloodborne")))
    }

    @After
    fun tearDown() {
        job.cancel()
    }

    @Test
    fun usesTheRealTitleAndKeepsTheSerial() {
        val rom = repo.current.roms.single()
        assertEquals("Bloodborne", rom.title)
        assertEquals("CUSA00900", rom.serial)
        assertTrue(rom.isDirectory)
    }

    @Test
    fun aReExtractedFolderIsTheSameGame() {
        val before = repo.current.roms.single()
        // Bachata lo vuelve a extraer en otra carpeta: otro documento, mismo CUSA.
        val diff = repo.mergeScan(folder.id, listOf(game("Bloodborne", "CUSA00900", "Bloodborne™")))
        val after = repo.current.roms.single()
        assertEquals(before.id, after.id)
        assertEquals("primary:PS4/Bloodborne", after.docId)
        assertEquals("Bloodborne™", after.title)
        assertTrue(diff.added.isEmpty())
        assertEquals(0, diff.removed)
    }

    @Test
    fun aNewInstallIsAddedAndAVanishedFolderIsRemoved() {
        val diff = repo.mergeScan(folder.id, listOf(game("CUSA08790", "CUSA08790", "Otro")))
        assertEquals(1, diff.added.size)
        assertEquals(1, diff.removed)
        assertEquals(listOf("CUSA08790"), repo.current.roms.map { it.serial })
    }

    @Test
    fun theIdComesFromTheSerialNotTheFolder() {
        val id = repo.current.roms.single().id
        assertEquals(LibraryRepository.romId(folder.id, "serial:CUSA00900"), id)
    }
}
