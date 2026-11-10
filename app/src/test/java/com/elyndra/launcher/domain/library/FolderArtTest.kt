package com.elyndra.launcher.domain.library

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.PlayStats
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.domain.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderArtTest {

    @Test fun `every system has bundled art`() {
        val missing = Systems.ALL.map { it.id }.filter { SystemArt.spec(it) == null }
        assertEquals(emptyList<String>(), missing)
        // And nothing in the table points at a system that does not exist.
        assertEquals(emptyList<String>(), SystemArt.SPECS.keys.filter { Systems.byId(it) == null })
    }

    @Test fun `the systems asked for by name are covered`() {
        for (id in listOf("wii", "ps4", "switch", "switch2", "pc", "dos")) {
            assertTrue(id, FolderArtResolver.resolve(null, id, emptyList()) is FolderArt.System)
        }
    }

    @Test fun `bundled colours are opaque and darker at the bottom`() {
        for ((id, spec) in SystemArt.SPECS) {
            assertEquals(id, 0xFFL, spec.top ushr 24)
            assertEquals(id, 0xFFL, spec.bottom ushr 24)
            assertTrue(id, luma(spec.bottom) < luma(spec.top))
        }
    }

    @Test fun `the manual icon wins over everything`() {
        assertEquals(FolderArt.Manual("media/f_wii/icon.png"), FolderArtResolver.resolve("media/f_wii/icon.png", "wii", listOf("c.png")))
        assertEquals(FolderArt.Manual("x.png"), FolderArtResolver.resolve("x.png", "unknown", emptyList()))
    }

    @Test fun `a system with bundled art uses it even with covers`() {
        val art = FolderArtResolver.resolve(null, "ps4", listOf("a.png"))
        assertEquals(FolderArt.System("ps4", SystemArt.spec("ps4")!!), art)
    }

    @Test fun `without bundled art a collage of up to four distinct covers`() {
        val art = FolderArtResolver.resolve(null, "custom", listOf("a", "b", "a", "", "c", "d", "e"))
        assertEquals(FolderArt.Collage(listOf("a", "b", "c", "d")), art)
    }

    @Test fun `without bundled art or covers the gradient`() {
        assertEquals(FolderArt.Gradient, FolderArtResolver.resolve(null, "custom", emptyList()))
        assertEquals(FolderArt.Gradient, FolderArtResolver.resolve(" ", "custom", listOf("")))
    }

    @Test fun `collage covers follow the folder's games, last played first`() {
        val f = Fixtures.folder("x", "custom")
        val old = Fixtures.rom("1", f, meta = GameMeta(cover = "old.png"), stats = PlayStats(lastPlayed = 10))
        val recent = Fixtures.rom("2", f, meta = GameMeta(cover = "recent.png"), stats = PlayStats(lastPlayed = 99))
        val noCover = Fixtures.rom("3", f, stats = PlayStats(lastPlayed = 500))
        assertEquals(listOf("recent.png", "old.png"), FolderArtResolver.collageCovers(listOf(old, noCover, recent)))
        val many = (1..6).map { Fixtures.rom("r$it", f, meta = GameMeta(cover = "c$it.png")) }
        assertEquals(FolderArtResolver.COLLAGE_MAX, FolderArtResolver.collageCovers(many).size)
    }

    private fun luma(argb: Long): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
