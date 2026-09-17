package com.elyndra.launcher.library

import com.elyndra.launcher.data.Systems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcGamesTest {

    private var counter = 0

    private fun exe(relPath: String, mb: Long = 1) =
        PcGames.Executable("doc${counter++}", relPath, mb * 1024 * 1024)

    @Test
    fun pcSystemScansFolderByFolder() {
        val pc = Systems.byId("pc")!!
        assertTrue(pc.folderGames)
        assertTrue("exe" in pc.extensions)
    }

    @Test
    fun folderNameWins() {
        val main = PcGames.pickExecutable(
            "Hollow Knight",
            listOf(exe("UnityCrashHandler64.exe"), exe("Hollow Knight.exe", mb = 20), exe("unins000.exe")),
        )
        assertEquals("Hollow Knight.exe", main?.name)
    }

    @Test
    fun installersAndCrashHandlersAreNeverTheGame() {
        val main = PcGames.pickExecutable(
            "Some Game 2024",
            listOf(exe("vcredist_x64.exe"), exe("DXSETUP.exe"), exe("game.exe", mb = 60)),
        )
        assertEquals("game.exe", main?.name)
    }

    @Test
    fun shallowerExecutableWins() {
        val main = PcGames.pickExecutable(
            "Celeste",
            listOf(exe("tools/Celeste.exe", mb = 5), exe("Celeste.exe", mb = 5)),
        )
        assertEquals("Celeste.exe", main?.relPath)
    }

    @Test
    fun nestedExecutableStillFoundWhenItIsTheOnlyOne() {
        val main = PcGames.pickExecutable("Hades", listOf(exe("x64/Hades.exe", mb = 30)))
        assertEquals("x64/Hades.exe", main?.relPath)
        assertEquals(1, main?.depth)
    }

    @Test
    fun sizeBreaksTheTieWhenNoNameMatches() {
        val main = PcGames.pickExecutable(
            "My Game",
            listOf(exe("helper.exe", mb = 1), exe("engine.exe", mb = 90)),
        )
        assertEquals("engine.exe", main?.name)
    }

    @Test
    fun junkIsStillBetterThanNothing() {
        // Una carpeta cuyo unico ejecutable es un instalador se da de alta igual:
        // dejarla fuera seria peor que ofrecerla.
        val main = PcGames.pickExecutable("Weird Game", listOf(exe("setup.exe", mb = 4)))
        assertNotNull(main)
        assertEquals("setup.exe", main?.name)
    }

    @Test
    fun aFolderWithoutExecutablesIsNotAGame() {
        assertFalse(PcGames.looksLikeGame(emptyList()))
        assertTrue(PcGames.looksLikeGame(listOf(exe("game.exe"))))
        assertNull(PcGames.pickExecutable("Saves", emptyList()))
    }

    @Test
    fun titleKeepsTheFolderNameWithoutTags() {
        assertEquals("Hollow Knight", PcGames.titleOf("Hollow Knight [GOG]"))
        // Un punto en el nombre de la carpeta no es una extension: "Deus Ex 2.0"
        // conserva su version en vez de quedarse en "Deus Ex 2".
        assertEquals("Deus Ex 2.0", PcGames.titleOf("Deus Ex 2.0"))
        // El punto final si se cae, como en cualquier otro titulo (Names.cleanTitle).
        assertEquals("S.T.A.L.K.E.R", PcGames.titleOf("S.T.A.L.K.E.R."))
    }
}
