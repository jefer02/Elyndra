package com.elyndra.launcher.domain.library

import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.UserFolder
import com.elyndra.launcher.domain.Fixtures
import com.elyndra.launcher.domain.ports.PortPlacement
import com.elyndra.launcher.library.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderAddTest {

    private val wii = Fixtures.folder("wii1", "wii")
    private val virtualPc = PortPlacement.virtualFolder("pc", now = 1)

    @Test fun `a real folder offers every option`() {
        assertEquals(
            listOf(FolderAddOption.AndroidApp, FolderAddOption.GameFile, FolderAddOption.Rescan, FolderAddOption.FindPort),
            FolderAdd.options(wii),
        )
    }

    @Test fun `a virtual folder only offers what does not touch the disk`() {
        assertEquals(listOf(FolderAddOption.AndroidApp, FolderAddOption.FindPort), FolderAdd.options(virtualPc))
        assertFalse(FolderAddOption.GameFile in FolderAdd.options(virtualPc))
        assertFalse(FolderAddOption.Rescan in FolderAdd.options(virtualPc))
    }

    @Test fun `a ports-only system folder is virtual and gets the virtual options`() {
        val switch2 = PortPlacement.virtualFolder("switch2", now = 1)
        assertEquals(listOf(FolderAddOption.AndroidApp, FolderAddOption.FindPort), FolderAdd.options(switch2))
    }

    @Test fun `file names lose paths and characters SAF rejects`() {
        assertEquals("Zelda (USA).iso", FolderAdd.safeName("Zelda (USA).iso"))
        assertEquals("game.rvz", FolderAdd.safeName("/storage/emulated/0/Download/game.rvz"))
        assertEquals("a_b_.nsp", FolderAdd.safeName("a:b?.nsp"))
        assertNull(FolderAdd.safeName(null))
        assertNull(FolderAdd.safeName(" "))
        assertNull(FolderAdd.safeName(".."))
    }

    @Test fun `an existing name is found ignoring case`() {
        assertTrue(FolderAdd.exists("Mario.ISO", listOf("mario.iso", "zelda.iso")))
        assertFalse(FolderAdd.exists("metroid.iso", listOf("mario.iso")))
    }

    @Test fun `app choices skip what is already in the folder and list games first`() {
        val lib = Library(folders = listOf(wii), apps = listOf(Fixtures.app("com.here", "Here").copy(folderId = "wii1", folderManual = true)))
        val installed = listOf(
            InstalledApp("com.here", "Here", isGame = true, installedAt = 0),
            InstalledApp("com.notes", "Notes", isGame = false, installedAt = 0),
            InstalledApp("com.zeta", "Zeta", isGame = true, installedAt = 0),
            InstalledApp("com.alpha", "alpha", isGame = true, installedAt = 0),
        )
        assertEquals(listOf("com.alpha", "com.zeta", "com.notes"), FolderAdd.appChoices(installed, lib, "wii1").map { it.packageName })
    }

    @Test fun `app choices skip what lives in a user folder`() {
        val lib = Library(folders = listOf(wii), userFolders = listOf(UserFolder("u-1", "Mine", items = listOf("a:com.filed"))))
        val installed = listOf(
            InstalledApp("com.filed", "Filed", isGame = true, installedAt = 0),
            InstalledApp("com.free", "Free", isGame = true, installedAt = 0),
        )
        assertEquals(listOf("com.free"), FolderAdd.appChoices(installed, lib, "wii1").map { it.packageName })
    }

    @Test fun `placing a new app adds it straight into the folder`() {
        val lib = Library(folders = listOf(wii))
        val out = FolderAdd.placeApp(lib, InstalledApp("com.dolphin.game", "Game", isGame = true, installedAt = 0), "wii1", now = 9)
        val app = out.apps.single()
        assertEquals("wii1", app.folderId)
        assertTrue(app.folderManual)
        assertEquals(9L, app.addedAt)
    }

    @Test fun `placing an app already in the library moves it and keeps its stats`() {
        val old = Fixtures.app("com.game", "Game", stats = com.elyndra.launcher.data.PlayStats(minutes = 42))
        val lib = Library(folders = listOf(wii), apps = listOf(old))
        val out = FolderAdd.placeApp(lib, InstalledApp("com.game", "Game", isGame = true, installedAt = 0), "wii1", now = 9)
        assertEquals("wii1", out.apps.single().folderId)
        assertEquals(42, out.apps.single().stats.minutes)
        // A folder that does not exist changes nothing.
        assertSame(lib, FolderAdd.placeApp(lib, InstalledApp("com.game", "Game", isGame = true, installedAt = 0), "nope", now = 9))
    }

    @Test fun `a manually placed app survives reconcile while its folder exists`() {
        val lib = FolderAdd.placeApp(Library(folders = listOf(wii)), InstalledApp("com.game", "Game", true, 0), "wii1", now = 1)
        val out = PortPlacement.reconcile(lib, emptyMap(), now = 2)
        assertEquals("wii1", out.apps.single().folderId)
        // The folder goes: the app goes back to the Android list.
        val gone = PortPlacement.reconcile(out.copy(folders = emptyList()), emptyMap(), now = 3)
        assertNull(gone.apps.single().folderId)
        assertFalse(gone.apps.single().folderManual)
    }

    @Test fun `an app in a virtual folder keeps it alive and follows a real folder of that system`() {
        val withVirtual = Library(folders = listOf(virtualPc))
        val placed = FolderAdd.placeApp(withVirtual, InstalledApp("com.game", "Game", true, 0), virtualPc.id, now = 1)
        val kept = PortPlacement.reconcile(placed, emptyMap(), now = 2)
        assertTrue(kept.folders.any { it.id == virtualPc.id })
        val pc = Fixtures.folder("pc1", "pc")
        val moved = PortPlacement.reconcile(kept.copy(folders = kept.folders + pc), emptyMap(), now = 3)
        assertEquals("pc1", moved.apps.single().folderId)
        assertTrue(moved.folders.none { it.isVirtual })
    }

    @Test fun `unplace sends an app back to the Android list`() {
        val lib = FolderAdd.placeApp(Library(folders = listOf(wii)), InstalledApp("com.game", "Game", true, 0), "wii1", now = 1)
        val out = FolderAdd.unplace(lib, "com.game")
        assertNull(out.apps.single().folderId)
        assertSame(out, FolderAdd.unplace(out, "com.game"))
    }
}
