package com.elyndra.launcher.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamesTest {

    @Test
    fun cleanTitleStripsTagsExtensionAndMovesArticle() {
        assertEquals(
            "The Legend of Zelda - A Link to the Past",
            Names.cleanTitle("Legend of Zelda, The - A Link to the Past (USA) [!].sfc"),
        )
        assertEquals("Metroid Dread", Names.cleanTitle("Metroid Dread [0100AF1017E62000][v0].nsp"))
        assertEquals("Super Mario World", Names.cleanTitle("Super_Mario_World.smc"))
        assertEquals("Game", Names.cleanTitle("Game v1.0.2.nsp"))
        assertEquals("Final Fantasy, A New Hope", Names.cleanTitle("Final Fantasy, A New Hope.iso"))
    }

    @Test
    fun cleanTitleNeverReturnsBlank() {
        assertEquals("(Beta)", Names.cleanTitle("(Beta).zip"))
    }

    @Test
    fun normalizeHandlesArticlesAccentsAndRomanNumerals() {
        assertEquals(Names.normalize("The Legend of Zelda"), Names.normalize("Legend of Zelda, The"))
        assertEquals(Names.normalize("Pokémon Red"), Names.normalize("Pokemon Red"))
        assertEquals("final fantasy 7", Names.normalize("Final Fantasy VII"))
        assertEquals(
            Names.normalize("The Legend of Zelda: A Link to the Past"),
            Names.normalize("Legend of Zelda, The: A Link to the Past"),
        )
    }

    @Test
    fun similarityRanksCloseTitlesHigh() {
        assertEquals(1.0, Names.similarity("Final Fantasy VII", "Final Fantasy 7"), 0.0)
        assertEquals(1.0, Names.similarity("Pokémon Red", "Pokemon Red"), 0.0)
        assertTrue(Names.similarity("Super Mario World", "Super Mario Kart") < 0.9)
        assertTrue(Names.similarity("Metroid Dread", "Metroid Dread Special Edition") >= 0.75)
        assertEquals(0.0, Names.similarity("", "Anything"), 0.0)
    }
}

class SafPathsTest {

    private val ext = SafPaths.EXTERNAL_STORAGE_AUTHORITY

    @Test
    fun externalStorageDocIdsBecomePaths() {
        assertEquals("/storage/emulated/0/ROMs/PSP", SafPaths.docIdToPath(ext, "primary:ROMs/PSP"))
        assertEquals("/storage/emulated/0", SafPaths.docIdToPath(ext, "primary:"))
        assertEquals("/storage/1A2B-3C4D/Games", SafPaths.docIdToPath(null, "1A2B-3C4D:Games"))
        assertEquals("/storage/emulated/0/Documents/roms", SafPaths.docIdToPath(ext, "home:roms"))
    }

    @Test
    fun rawDownloadsIdsAndForeignProviders() {
        assertEquals(
            "/storage/emulated/0/Download/x.iso",
            SafPaths.docIdToPath("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download/x.iso"),
        )
        assertNull(SafPaths.docIdToPath("com.google.android.apps.docs.storage", "doc=abc"))
        assertNull(SafPaths.docIdToPath(ext, "no-colon"))
    }

    @Test
    fun authorityAndLastSegment() {
        assertEquals(ext, SafPaths.authorityOf("content://$ext/tree/primary%3AROMs"))
        assertNull(SafPaths.authorityOf("file:///sdcard"))
        assertEquals("PSP", SafPaths.lastSegment("primary:ROMs/PSP"))
        assertEquals("", SafPaths.lastSegment("primary:"))
        assertEquals("Games", SafPaths.displayPath(ext, "primary:Games").substringAfterLast('/'))
    }

    @Test
    fun siblingDocIds() {
        assertEquals("primary:PS1/game.bin", SafFiles.siblingDocId("primary:PS1/game.cue", "game.bin"))
        assertEquals("primary:game.bin", SafFiles.siblingDocId("primary:game.cue", "game.bin"))
    }
}

class DiscSheetsTest {

    @Test
    fun cueReferencesQuotedAndBareFiles() {
        val cue = """
            FILE "Game (Track 1).bin" BINARY
              TRACK 01 MODE2/2352
                INDEX 01 00:00:00
            FILE "Game (Track 2).bin" BINARY
              TRACK 02 AUDIO
        """.trimIndent()
        assertEquals(listOf("Game (Track 1).bin", "Game (Track 2).bin"), DiscSheets.referencedFiles("cue", "Game.cue", cue))
        assertEquals(listOf("game.bin"), DiscSheets.referencedFiles("cue", "x.cue", "FILE game.bin BINARY"))
    }

    @Test
    fun gdiTracksWithQuotedNames() {
        val gdi = "3\n1 0 4 2352 track01.bin 0\n2 450 0 2352 \"track 02.raw\" 0\n3 45000 4 2352 track03.bin 0\n"
        assertEquals(listOf("track01.bin", "track 02.raw", "track03.bin"), DiscSheets.referencedFiles("gdi", "g.gdi", gdi))
    }

    @Test
    fun m3uSkipsCommentsAndPaths() {
        val m3u = "#EXTM3U\nGame (Disc 1).cue\nsub/Game (Disc 2).cue\n\n"
        assertEquals(listOf("Game (Disc 1).cue", "Game (Disc 2).cue"), DiscSheets.referencedFiles("m3u", "Game.m3u", m3u))
    }

    @Test
    fun ccdHidesCompanionImages() {
        assertEquals(listOf("Game.img", "Game.sub"), DiscSheets.referencedFiles("ccd", "Game.ccd", ""))
    }
}
