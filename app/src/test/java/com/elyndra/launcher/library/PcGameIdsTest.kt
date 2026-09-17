package com.elyndra.launcher.library

import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcGameIdsTest {

    /** Carpeta de PC de mentira: un mapa de documento → contenido / hijos. */
    private class FakeFiles(
        val contents: Map<String, String> = emptyMap(),
        val children: Map<String, List<PcGameIds.Entry>> = emptyMap(),
    ) : PcGameIds.Files {
        override suspend fun read(treeUri: String, docId: String): String? = contents[docId]
        override suspend fun list(treeUri: String, docId: String): List<PcGameIds.Entry> =
            children[docId].orEmpty()
    }

    private val folder = RomFolder(
        id = "f1",
        systemId = "pc",
        treeUri = "content://tree/primary%3AJuegos",
        rootDocId = "primary:Juegos",
        displayPath = "Juegos PC",
    )

    private fun rom(
        fileName: String,
        docId: String = "doc",
        isDirectory: Boolean = false,
        mainDocId: String? = null,
        mainFile: String? = null,
        pcGameId: String? = null,
    ) = RomEntry(
        id = "r1",
        folderId = folder.id,
        systemId = folder.systemId,
        docId = docId,
        fileName = fileName,
        relPath = fileName,
        isDirectory = isDirectory,
        title = fileName,
        mainDocId = mainDocId,
        mainFile = mainFile,
        pcGameId = pcGameId,
    )

    private fun resolve(files: FakeFiles, rom: RomEntry, f: RomFolder = folder) =
        runBlocking { PcGameIds(files).resolve(f, rom) }

    @Test
    fun anIsoWhoseContentIsTheIdLaunchesWithThatId() {
        val files = FakeFiles(contents = mapOf("doc" to "268910\n"))
        val resolved = resolve(files, rom("Hollow Knight.iso"))
        assertEquals("268910", resolved.id)
        // No lo exportó el runtime: vale igual, aunque no traiga tienda.
        assertTrue(resolved.assigned)
    }

    @Test
    fun aTxtAndASteamFileWorkTheSameWay() {
        assertEquals("2551", resolve(FakeFiles(mapOf("doc" to "2551")), rom("Hades.txt")).id)
        val steam = resolve(FakeFiles(mapOf("doc" to "10521")), rom("Hades.steam"))
        assertEquals("10521", steam.id)
        // El .steam sí es del runtime: viaja con su tienda, no como id suelto.
        assertFalse(steam.assigned)
    }

    @Test
    fun theIdTypedByHandWinsOverTheFile() {
        val files = FakeFiles(contents = mapOf("doc" to "268910"))
        assertEquals("70", resolve(files, rom("Hollow Knight.iso", pcGameId = "70")).id)
        // Y borrarlo devuelve el mando al archivo.
        assertEquals("268910", resolve(files, rom("Hollow Knight.iso", pcGameId = " ")).id)
    }

    @Test
    fun aFolderGameTakesTheIdFromTheFileNextToItsExe() {
        val files = FakeFiles(
            contents = mapOf("id" to "367520"),
            children = mapOf(
                "dir" to listOf(
                    PcGameIds.Entry("exe", "Hollow Knight.exe", 40L * 1024 * 1024),
                    PcGameIds.Entry("id", "bannerhub.txt", 8),
                ),
            ),
        )
        val game = rom("Hollow Knight", docId = "dir", isDirectory = true, mainDocId = "exe", mainFile = "Hollow Knight.exe")
        val resolved = resolve(files, game)
        assertEquals("367520", resolved.id)
        assertTrue(resolved.assigned)
    }

    @Test
    fun nothingUsableLeavesTheLaunchWithoutAnId() {
        // Un .exe suelto no lleva id dentro, y la carpeta no tiene nada más.
        assertNull(resolve(FakeFiles(), rom("Hollow Knight.exe")).id)
        // Un .iso que es un disco de verdad: lo que se lee no es un id.
        val disc = FakeFiles(mapOf("doc" to "CD001 disc image with spaces"))
        assertNull(resolve(disc, rom("Hollow Knight.iso")).id)
    }

    @Test
    fun outsidePcOnlyTheIdTypedByHandCounts() {
        val ps2 = folder.copy(systemId = "ps2")
        val files = FakeFiles(contents = mapOf("doc" to "268910"))
        // Un .iso de PS2 es un disco: no se lee buscando ids.
        assertNull(resolve(files, rom("Gran Turismo 4.iso"), ps2).id)
        assertEquals("70", resolve(files, rom("Gran Turismo 4.iso", pcGameId = "70"), ps2).id)
    }
}
