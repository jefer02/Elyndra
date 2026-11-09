package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.data.FileHashes
import com.elyndra.launcher.data.PlayStats
import com.elyndra.launcher.domain.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortLookupTest {

    private val n64 = Fixtures.folder("n64", "n64")
    private val snes = Fixtures.folder("snes", "snes")

    private val oot = PortEntry(
        id = "soh",
        name = "Ship of Harkinian",
        repo = GhRepo("linkzenic", "Shipwright-Ports"),
        game = PortGame("The Legend of Zelda: Ocarina of Time", "n64"),
        builds = listOf(PortBuild("android")),
        requires = Requirement(true, roms = listOf(RomHash("NTSC 1.0 (US)", "n64", sha1 = "ad69c91157f6705e8ab06c79fe08aad47bb57ba7"))),
    )
    private val alttp = PortEntry(
        id = "moonpearl",
        name = "Moonpearl",
        repo = GhRepo("DoutorRaposo", "moonpearl"),
        game = PortGame("The Legend of Zelda: A Link to the Past", "snes"),
        builds = listOf(PortBuild("android")),
    )
    private val pd = PortEntry(
        id = "perfect-dark",
        name = "Perfect Dark",
        repo = GhRepo("o", "r"),
        game = PortGame("Perfect Dark", "n64"),
        requires = Requirement(true, roms = listOf(RomHash("crc only", crc = "e03b088b"), RomHash("PAL", md5 = "d9b5cd305d228424891ce38e71bc9213"))),
    )

    @Test fun `hashes match case-insensitively and crc only counts alone`() {
        val upper = FileHashes(1, 1, crc = "E03B088B", sha1 = "AD69C91157F6705E8AB06C79FE08AAD47BB57BA7")
        assertTrue(RomMatch.matches(upper, oot.requires.roms.single()))
        assertTrue(RomMatch.matches(upper, RomHash(crc = "e03b088b")))
        assertFalse(RomMatch.matches(upper, RomHash(crc = "e03b088b", md5 = "00".repeat(16))))
        assertFalse(RomMatch.matches(null, RomHash(sha1 = "x")))
        assertFalse(RomMatch.matches(FileHashes(1, 1), RomHash()))
    }

    @Test fun `titles normalise no-intro names regions and accents`() {
        val want = PortLookup.normalizeTitle("The Legend of Zelda: Ocarina of Time")
        assertEquals(want, PortLookup.normalizeTitle("Legend of Zelda, The - Ocarina of Time (USA) (Rev 2)"))
        assertEquals(want, PortLookup.normalizeTitle("legend of zelda ocarina of time [!]"))
        assertEquals(PortLookup.normalizeTitle("Pokemon"), PortLookup.normalizeTitle("Pokémon"))
        assertEquals(listOf("zelda", "ocarina"), PortLookup.normalizeWords("  Zelda  Ocarina "))
    }

    @Test fun `a hash match beats a title match`() {
        val byHash = Fixtures.rom("1", n64, fileName = "oot.z64").copy(hashes = FileHashes(1, 1, sha1 = "ad69c91157f6705e8ab06c79fe08aad47bb57ba7"))
        val byTitle = Fixtures.rom("2", n64, fileName = "Legend of Zelda, The - Ocarina of Time (Europe).z64")
            .copy(stats = PlayStats(lastPlayed = Fixtures.NOW))
        val lib = Fixtures.library(listOf(n64), listOf(byTitle, byHash))
        val m = PortLookup.owned(oot, lib)!!
        assertEquals("1", m.rom.id)
        assertEquals(MatchStrength.Hash, m.strength)
        assertEquals(MatchStrength.Title, PortLookup.portsFor(byTitle, listOf(oot)).single().strength)
    }

    @Test fun `titles only match on the same system`() {
        val wrongSystem = Fixtures.rom("3", snes, fileName = "Legend of Zelda, The - Ocarina of Time.sfc")
        assertTrue(PortLookup.portsFor(wrongSystem, listOf(oot)).isEmpty())
        assertNull(PortLookup.owned(alttp, Fixtures.library(listOf(n64), emptyList())))
    }

    @Test fun `md5 requirements match the library`() {
        val pal = Fixtures.rom("pd", n64, md5 = "D9B5CD305D228424891CE38E71BC9213")
        assertEquals(MatchStrength.Hash, PortLookup.portsFor(pal, listOf(pd)).single().strength)
    }

    @Test fun `because you have lists the most recently played first`() {
        val old = Fixtures.rom("a", snes, fileName = "Legend of Zelda, The - A Link to the Past (USA).sfc")
            .copy(stats = PlayStats(lastPlayed = Fixtures.NOW - Fixtures.DAY))
        val recent = Fixtures.rom("b", n64, fileName = "oot.z64")
            .copy(hashes = FileHashes(1, 1, sha1 = "ad69c91157f6705e8ab06c79fe08aad47bb57ba7"), stats = PlayStats(lastPlayed = Fixtures.NOW))
        val lib = Fixtures.library(listOf(n64, snes), listOf(old, recent))
        assertEquals(listOf("soh", "moonpearl"), PortLookup.becauseYouHave(lib, listOf(alttp, oot, pd)).map { it.port.id })
    }
}
