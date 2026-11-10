package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.PlayStats
import com.elyndra.launcher.domain.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PortPlacementTest {

    private val soh = Fixtures.app("com.soh", "SoH")
    private val diablo = Fixtures.app("com.diablo", "DevilutionX")
    private val keen = Fixtures.app("com.keen", "ReflectionHLE")
    private val spotify = Fixtures.app("com.spotify", "Spotify")
    private val n64a = Fixtures.folder("n64a", "n64")
    private val n64b = Fixtures.folder("n64b", "n64")

    private fun lib(vararg folders: com.elyndra.launcher.data.RomFolder, roms: List<com.elyndra.launcher.data.RomEntry> = emptyList()) =
        Library(folders = folders.toList(), roms = roms, apps = listOf(soh, diablo, spotify))

    private fun Library.app(pkg: String) = apps.single { it.packageName == pkg }

    @Test fun `a port goes to the folder of its system, the most recently played one if several`() {
        val played = Fixtures.rom("1", n64b).copy(stats = PlayStats(minutes = 10, lastPlayed = 500))
        val out = PortPlacement.reconcile(lib(n64a, n64b, roms = listOf(played)), mapOf("com.soh" to "n64"), now = 1)
        assertEquals("n64b", out.app("com.soh").folderId)
        assertFalse(out.app("com.soh").folderManual)
        // Las apps que no son ports no tienen carpeta.
        assertNull(out.app("com.spotify").folderId)
    }

    @Test fun `without a folder for the system a virtual one is created with no SAF tree`() {
        val out = PortPlacement.reconcile(lib(n64a), mapOf("com.diablo" to "pc"), now = 7)
        val virtual = out.folders.single { it.isVirtual }
        assertEquals("v-pc", virtual.id)
        assertEquals("pc", virtual.systemId)
        assertEquals("", virtual.treeUri)
        assertEquals("", virtual.rootDocId)
        assertEquals(7L, virtual.addedAt)
        assertEquals("v-pc", out.app("com.diablo").folderId)
        assertEquals(listOf(n64a), out.realFolders)
    }

    @Test fun `adding a real folder moves the ports into it and the virtual folder goes`() {
        val withVirtual = PortPlacement.reconcile(lib(n64a), mapOf("com.diablo" to "pc"), now = 1)
        val pc = Fixtures.folder("pc1", "pc")
        val out = PortPlacement.reconcile(withVirtual.copy(folders = withVirtual.folders + pc), mapOf("com.diablo" to "pc"), now = 2)
        assertEquals("pc1", out.app("com.diablo").folderId)
        assertTrue(out.folders.none { it.isVirtual })
    }

    @Test fun `an empty virtual folder is removed and an uninstalled port loses its place`() {
        val placed = PortPlacement.reconcile(lib(n64a), mapOf("com.diablo" to "pc"), now = 1)
        val out = PortPlacement.reconcile(placed, emptyMap(), now = 2)
        assertTrue(out.folders.none { it.isVirtual })
        // La app (y su tiempo de juego) se queda; solo pierde la carpeta.
        assertNull(out.app("com.diablo").folderId)
        assertEquals(placed.apps.size, out.apps.size)
    }

    @Test fun `a manual move stays while its folder exists`() {
        val placed = PortPlacement.reconcile(lib(n64a, n64b), mapOf("com.soh" to "n64"), now = 1)
        val moved = PortPlacement.move(placed, "com.soh", "n64a")
        assertTrue(moved.app("com.soh").folderManual)
        val played = Fixtures.rom("1", n64b).copy(stats = PlayStats(lastPlayed = 900))
        val out = PortPlacement.reconcile(moved.copy(roms = listOf(played)), mapOf("com.soh" to "n64"), now = 2)
        assertEquals("n64a", out.app("com.soh").folderId)
        // Si la carpeta elegida desaparece, vuelve a la automática.
        val gone = PortPlacement.reconcile(out.copy(folders = listOf(n64b)), mapOf("com.soh" to "n64"), now = 3)
        assertEquals("n64b", gone.app("com.soh").folderId)
        assertFalse(gone.app("com.soh").folderManual)
    }

    @Test fun `a manual move into a virtual folder gives way to a real folder of that system`() {
        val placed = PortPlacement.reconcile(lib(), mapOf("com.diablo" to "pc"), now = 1)
        val manual = PortPlacement.move(placed, "com.diablo", "v-pc")
        val pc = Fixtures.folder("pc1", "pc")
        val out = PortPlacement.reconcile(manual.copy(folders = manual.folders + pc), mapOf("com.diablo" to "pc"), now = 2)
        assertEquals("pc1", out.app("com.diablo").folderId)
        assertTrue(out.folders.none { it.isVirtual })
    }

    @Test fun `dos games prefer a dos folder, then a pc folder, then a virtual dos one`() {
        val base = Library(apps = listOf(keen))
        assertEquals("v-dos", PortPlacement.reconcile(base, mapOf("com.keen" to "dos"), 1).app("com.keen").folderId)
        val pc = Fixtures.folder("pc1", "pc")
        assertEquals("pc1", PortPlacement.reconcile(base.copy(folders = listOf(pc)), mapOf("com.keen" to "dos"), 1).app("com.keen").folderId)
        val dos = Fixtures.folder("dos1", "dos")
        assertEquals("dos1", PortPlacement.reconcile(base.copy(folders = listOf(pc, dos)), mapOf("com.keen" to "dos"), 1).app("com.keen").folderId)
    }

    @Test fun `an unknown platform goes to pc`() {
        assertEquals("pc", PortPlacement.systemFor("amiga-cd32"))
        assertEquals("switch", PortPlacement.systemFor("switch"))
    }

    @Test fun `nothing to move returns the same library instance`() {
        val placed = PortPlacement.reconcile(lib(n64a), mapOf("com.soh" to "n64", "com.diablo" to "pc"), now = 1)
        assertSame(placed, PortPlacement.reconcile(placed, mapOf("com.soh" to "n64", "com.diablo" to "pc"), now = 2))
        assertSame(placed, PortPlacement.move(placed, "com.soh", "nope"))
    }

    @Test fun `a rescan that changes the roms never drops a port`() {
        val placed = PortPlacement.reconcile(lib(n64a), mapOf("com.soh" to "n64"), now = 1)
        // RomScanner/mergeScan solo cambian las ROMs: las apps (y su carpeta) son las mismas.
        val rescanned = placed.copy(roms = listOf(Fixtures.rom("9", n64a)))
        val out = PortPlacement.reconcile(rescanned, mapOf("com.soh" to "n64"), now = 2)
        assertSame(rescanned, out)
        assertEquals("n64a", out.app("com.soh").folderId)
    }

    @Test fun `a port in a folder keeps its app key so stats and metadata stay`() {
        val entry = com.elyndra.launcher.data.RomEntry(
            id = "port:com.soh", folderId = "n64a", systemId = "n64", docId = "", fileName = "", relPath = "", title = "Ocarina",
            portPackage = "com.soh", portName = "Ship of Harkinian",
        )
        assertEquals("a:com.soh", entry.key)
        assertTrue(entry.isPort)
        assertEquals("PORT", entry.extension)
        assertEquals("PORT · Ship of Harkinian", entry.subtitle)
    }
}
