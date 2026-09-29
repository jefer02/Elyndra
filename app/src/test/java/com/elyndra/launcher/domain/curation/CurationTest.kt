package com.elyndra.launcher.domain.curation

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.domain.Fixtures
import com.elyndra.launcher.domain.Fixtures.folder
import com.elyndra.launcher.domain.Fixtures.rom
import com.elyndra.launcher.domain.games
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurationTest {

    /* ── etiquetas de los volcados ────────────────────────────── */

    @Test
    fun noIntroNamesAreUnderstood() {
        val t = RomTags.parse("Final Fantasy VII (USA) (Disc 2 of 3) (Rev 1).cue")
        assertEquals("Final Fantasy VII", t.baseTitle)
        assertEquals(setOf("USA"), t.regions)
        assertEquals(2, t.disc)
        assertEquals(3, t.discTotal)
        assertEquals("1", t.revision)
    }

    @Test
    fun languagesAreNotRegions() {
        val t = RomTags.parse("Tintin (Europe) (En,Fr,De).sfc")
        assertEquals(setOf("Europe"), t.regions)
        assertEquals(setOf("en", "fr", "de"), t.languages)
    }

    @Test
    fun goodToolsFlagsAreUnderstood() {
        val t = RomTags.parse("Super Mario World (U) [!].smc")
        assertEquals(setOf("USA"), t.regions)
        assertTrue(t.verified)
        assertTrue(RomTags.parse("Zelda (E) [b1].sfc").badDump)
        assertTrue(RomTags.parse("Zelda (JU) [h1C].sfc").hack)
        assertEquals(setOf("Japan", "USA"), RomTags.parse("Zelda (JU).sfc").regions)
        assertEquals("Eng", RomTags.parse("Mother 3 (J) [T+Eng].gba").translation)
    }

    @Test
    fun discsWithoutParenthesesAreFound() {
        val t = RomTags.parse("Metal Gear Solid CD2.bin")
        assertEquals(2, t.disc)
        assertEquals("Metal Gear Solid", t.baseTitle)
        assertNull(RomTags.parse("Discworld (Europe).cue").disc)
    }

    @Test
    fun betasAndDemosAreMarked() {
        assertTrue(RomTags.parse("Sonic (Beta 2).md").beta)
        assertTrue(RomTags.parse("Sonic (Proto).md").prototype)
        assertTrue(RomTags.parse("Sonic (Demo).md").demo)
    }

    /* ── revisión de la biblioteca ────────────────────────────── */

    private val psx = folder("psx", "psx")
    private val snes = folder("snes", "snes")

    @Test
    fun identicalFilesAreDuplicates() {
        val lib = Fixtures.library(
            listOf(psx),
            listOf(
                rom("a", psx, "Crash (USA).cue", md5 = "same"),
                rom("b", psx, "Crash Bandicoot copy.cue", md5 = "same"),
                rom("c", psx, "Spyro (USA).cue", md5 = "other"),
            ),
        )
        val report = LibraryCurator.analyze(lib.games())
        val group = report.duplicates.single()
        assertEquals(DuplicateGroup.Kind.SameFile, group.kind)
        assertEquals(setOf("r:a", "r:b"), group.keys.toSet())
    }

    @Test
    fun severalRevisionsOfTheSameGameAreVersions() {
        val lib = Fixtures.library(
            listOf(snes),
            listOf(rom("a", snes, "Zelda (USA).sfc"), rom("b", snes, "Zelda (USA) (Rev 1) [!].sfc")),
        )
        val group = LibraryCurator.analyze(lib.games()).duplicates.single()
        assertEquals(DuplicateGroup.Kind.Versions, group.kind)
        // La buena primero: verificada y con la revisión más alta.
        assertEquals("r:b", group.keys.first())
    }

    @Test
    fun theSameGameFromSeveralRegionsIsReported() {
        val lib = Fixtures.library(
            listOf(snes),
            listOf(rom("a", snes, "Chrono Trigger (USA).sfc"), rom("b", snes, "Chrono Trigger (Japan).sfc")),
        )
        val report = LibraryCurator.analyze(lib.games())
        assertEquals(listOf("USA", "Japan"), report.regions.single().regions)
        assertTrue(report.duplicates.isEmpty())
    }

    @Test
    fun aMissingDiscIsFound() {
        val lib = Fixtures.library(
            listOf(psx),
            listOf(
                rom("a", psx, "Final Fantasy VII (USA) (Disc 1 of 3).cue"),
                rom("c", psx, "Final Fantasy VII (USA) (Disc 3 of 3).cue"),
                rom("d", psx, "Tekken 3 (USA).cue"),
            ),
        )
        val set = LibraryCurator.analyze(lib.games()).incomplete.single()
        assertEquals(listOf(1, 3), set.present)
        assertEquals(listOf(2), set.missing)
    }

    @Test
    fun unhelpfulNamesAreReported() {
        val lib = Fixtures.library(
            listOf(psx),
            listOf(
                rom("a", psx, "Final_Fantasy_VII.bin"),
                rom("b", psx, "SLUS-00892.bin"),
                rom("c", psx, "ff7.bin"),
                rom("d", psx, "Good Name (USA).bin", meta = GameMeta(scrapedAt = 1, matched = false)),
                rom("e", psx, "Fine Game (USA).bin"),
            ),
        )
        val reasons = LibraryCurator.analyze(lib.games()).naming.associate { it.key to it.reason }
        assertEquals(NamingIssue.Reason.Scene, reasons["r:a"])
        assertEquals(NamingIssue.Reason.Serial, reasons["r:b"])
        assertEquals(NamingIssue.Reason.Cryptic, reasons["r:c"])
        assertEquals(NamingIssue.Reason.Unmatched, reasons["r:d"])
        assertNull(reasons["r:e"])
    }

    @Test
    fun seriesGroupNumberedAndSubtitledEntries() {
        val lib = Fixtures.library(
            listOf(psx),
            listOf(
                rom("ff7", psx, "Final Fantasy VII (USA).cue", meta = GameMeta(releaseDate = "1997")),
                rom("ff9", psx, "Final Fantasy IX (USA).cue", meta = GameMeta(releaseDate = "2000")),
                rom("sotn", psx, "Castlevania - Symphony of the Night (USA).cue"),
                rom("chr", psx, "Castlevania Chronicles (USA).cue"),
                rom("re", psx, "Resident Evil (USA).cue"),
                rom("recv", psx, "Resident Evil Code Veronica (USA).cue"),
                rom("tekken", psx, "Tekken 3 (USA).cue"),
            ),
        )
        val series = LibraryCurator.analyze(lib.games()).series.associate { it.name to it.keys }
        assertEquals(listOf("r:ff7", "r:ff9"), series["Final Fantasy"])
        assertEquals(setOf("r:sotn", "r:chr"), series["Castlevania"]?.toSet())
        assertEquals(setOf("r:re", "r:recv"), series["Resident Evil"]?.toSet())
        assertTrue(series.values.none { "r:tekken" in it })
    }

    @Test
    fun twoRegionsOfOneGameAreNotASeries() {
        val lib = Fixtures.library(
            listOf(snes),
            listOf(rom("a", snes, "Chrono Trigger (USA).sfc"), rom("b", snes, "Chrono Trigger (Japan).sfc")),
        )
        assertTrue(LibraryCurator.analyze(lib.games()).series.isEmpty())
    }

    @Test
    fun seriesKeysIgnoreNumbersAndGenericWords() {
        assertEquals("final fantasy", LibraryCurator.seriesKey("Final Fantasy VIII"))
        assertEquals("mega man", LibraryCurator.seriesKey("Mega Man X4"))
        assertEquals("legend of zelda", LibraryCurator.seriesKey("The Legend of Zelda: Ocarina of Time"))
        assertNull(LibraryCurator.seriesKey("Super"))
    }
}
