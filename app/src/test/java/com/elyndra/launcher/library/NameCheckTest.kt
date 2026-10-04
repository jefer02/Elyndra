package com.elyndra.launcher.library

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.InMemoryLibraryStore
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.MatchMethod
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.metadata.MergedMetadata
import com.elyndra.launcher.metadata.MetaField
import com.elyndra.launcher.metadata.ScrapeApply
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.ui.IdentifyRules
import com.elyndra.launcher.ui.NameMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameCheckTest {

    @Test
    fun unusableNames() {
        val bad = listOf(
            null, "", "   ", "Unknown", "untitled", "GAME", "rom", "?",
            "com.miniclip.eightballpool", "com.studio.game", "org.ppsspp.ppsspp",
            "CUSA01715", "SLUS_012.34", "SLES-01234", "BLUS30443", "PCSE00123", "ULUS10041",
            "0100000000010000", "d41d8cd98f00b204e9800998ecf8427e", "6f1ed002-ab5d-42e0-868f-9e0b6c8c5bd9",
            "12345", "2024", "_-_-", "x",
            "Final Fantasy VII.iso", "Half-Life 2.exe", "zelda.sfc", "game.nsp",
            "super_mario_kart_usa", "Super.Mario.Bros",
        )
        for (n in bad) assertFalse("debería ser inservible: $n", NameCheck.isNameUsable(n))
    }

    @Test
    fun usableNames() {
        val good = listOf(
            "Super Mario Bros.", "Dr. Mario", "Tekken 3", "FIFA 2002", "Doom 64", "Halo 3", "Final Fantasy VII",
            "8 Ball Pool", "Pokémon Rojo", "ゼルダの伝説", "Grand Theft Auto: San Andreas", "Mr. Driller",
            "Crash Bandicoot 2 - Cortex Strikes Back", "God of War III Remastered", "Minecraft", "PUBG Mobile",
            "Asphalt 9: Legends", "Mario Kart 8 Deluxe", "Uncharted", "Bloodborne™",
        )
        for (n in good) assertTrue("debería servir: $n", NameCheck.isNameUsable(n))
    }

    @Test
    fun filenameCleanupGuess() {
        assertEquals("Super Mario Bros", NameCheck.guess("super_mario_bros_(USA).nes"))
        assertEquals("Crash Bandicoot", NameCheck.guess("Crash Bandicoot (USA) (Rev 1).cue"))
        assertEquals("Final Fantasy VII", NameCheck.guess("Final Fantasy VII [SLUS-00700].bin"))
        assertEquals("Super Mario Bros", NameCheck.guess("Super.Mario.Bros.sfc"))
        assertEquals("Dr. Mario", NameCheck.guess("Dr. Mario (World).nes"))
        assertEquals("Eightballpool", NameCheck.guess("com.miniclip.eightballpool"))
        assertEquals("Space Runner", NameCheck.guess("com.studio.space_runner"))
        assertEquals("Zelda", NameCheck.guess("ZELDA.SFC"))
        assertEquals("", NameCheck.guess(""))
        assertEquals("", NameCheck.guess("(USA)"))
    }
}

class NameOverrideTest {

    private val auto = GameMeta(scrapedAt = 1, matched = true, name = "Crash Bandicoot", ssGameId = "123", steamAppId = 9, matchedBy = MatchMethod.HASH)

    @Test
    fun userNameWinsOverMetadataAndFileName() {
        val locked = IdentifyRules.withUserName(auto, "Crash Bandicoot: Warped")
        assertEquals("Crash Bandicoot: Warped", locked.lockedName)
        // Otro juego: lo identificado antes ya no vale.
        assertNull(locked.ssGameId)
        assertNull(locked.steamAppId)
        // Mismo nombre que ya tenía: solo se fija, sin perder la identificación.
        val same = IdentifyRules.withUserName(auto, "crash bandicoot")
        assertEquals("123", same.ssGameId)
        assertTrue(same.nameLocked)
        // Un nombre en blanco o sin fijar no cuenta.
        assertNull(auto.copy(userName = "Algo", nameLocked = false).lockedName)
        assertNull(auto.copy(userName = "  ", nameLocked = true).lockedName)
    }

    @Test
    fun pickingAResultStoresItsSourceId() {
        val steam = IdentifyRules.withMatch(auto, NameMatch("Hollow Knight", null, "PC", null, Service.Steam, "367520"))
        assertEquals(367520L, steam.steamAppId)
        assertEquals("Hollow Knight", steam.lockedName)
        assertEquals(MatchMethod.MANUAL, steam.matchedBy)
        val lr = IdentifyRules.withMatch(auto, NameMatch("Crash Bandicoot (Europe)", null, "PS1", null, Service.Libretro, "Crash Bandicoot (Europe)"))
        assertEquals("Crash Bandicoot (Europe)", lr.libretroName)
        // El nombre que se enseña, sin la región.
        assertEquals("Crash Bandicoot", lr.lockedName)
    }

    @Test
    fun reidentifyingForgetsTheOldDescription() {
        val described = auto.copy(
            description = "Protect your Core Crystal",
            descriptionLang = "en",
            descriptions = mapOf("en" to "Protect your Core Crystal"),
            descriptionCheckedLang = "es",
            descriptionSources = setOf("steam"),
        )
        for (m in listOf(
            IdentifyRules.withUserName(described, "Geometry Dash"),
            IdentifyRules.withMatch(described, NameMatch("Geometry Dash", null, null, null, Service.Steam, "322170")),
            IdentifyRules.reset(described),
        )) {
            assertNull(m.description)
            assertTrue(m.descriptions.isEmpty())
            assertNull(m.descriptionCheckedLang)
            assertNull(m.descriptionSources)
        }
        // Mismo nombre: es el mismo juego, la descripción se queda.
        assertEquals("Protect your Core Crystal", IdentifyRules.withUserName(described, described.name!!).description)
    }

    @Test
    fun resetRemovesTheOverride() {
        val back = IdentifyRules.reset(IdentifyRules.withMatch(auto, NameMatch("X", null, null, null, Service.Igdb, "42")))
        assertNull(back.lockedName)
        assertFalse(back.nameLocked)
        assertNull(back.igdbId)
        assertNull(back.matchedBy)
    }

    private fun merged(name: String, ss: String? = "999", steam: Long? = 77) = MergedMetadata(
        text = mapOf(MetaField.Name to name),
        rating = null,
        art = emptyMap(),
        sources = listOf(Service.ScreenScraper),
        ssId = ss,
        igdbId = null,
        sgdbId = null,
        steamAppId = steam,
        ra = null,
        matchedBy = MatchMethod.NAME,
        confidence = 0.9f,
    )

    @Test
    fun syncNeverOverwritesALockedName() {
        val locked = IdentifyRules.withMatch(auto, NameMatch("Hollow Knight", null, null, null, Service.Steam, "367520"))
        val after = ScrapeApply.apply(locked, merged("Wrong Game"), "es", ScrapeApply.Art(), now = 5)
        assertEquals("Hollow Knight", after.lockedName)
        assertTrue(after.nameLocked)
        // La identificación a mano conserva su id y su método.
        assertEquals(367520L, after.steamAppId)
        assertEquals(MatchMethod.MANUAL, after.matchedBy)
        // Sin bloqueo, la pasada sí actualiza nombre e ids.
        val free = ScrapeApply.apply(auto, merged("Crash Bandicoot (Remaster)"), "es", ScrapeApply.Art(), now = 5)
        assertEquals("Crash Bandicoot (Remaster)", free.name)
        assertEquals("999", free.ssGameId)
    }

    @Test
    fun pinnedArtIsKept() {
        val old = auto.copy(cover = "media/x/cover_1.jpg", pinned = listOf("cover"))
        // La pasada no trae portada (no se descarga lo fijado): se queda la elegida.
        val after = ScrapeApply.apply(old, merged("Crash"), "es", ScrapeApply.Art(hero = "media/x/hero_2.jpg"), now = 5)
        assertEquals("media/x/cover_1.jpg", after.cover)
        assertEquals(listOf("cover"), after.pinned)
        assertEquals("media/x/hero_2.jpg", after.hero)
    }
}

class NameSurvivesRescanTest {

    private val folder = RomFolder("f1", "psx", "content://tree/psx", "primary:psx", "ROMs/psx")

    private fun found(rel: String, name: String = rel.substringAfterLast('/'), size: Long = 700) = RomScanner.Found(
        docId = "primary:psx/$rel",
        name = name,
        relPath = rel,
        size = size,
        modified = 1,
        isDir = false,
    )

    @Test
    fun renamedGameKeepsItsNameWhenRescannedOrMovedWithinTheFolder() {
        val job = Job()
        try {
            val repo = LibraryRepository(InMemoryLibraryStore(), CoroutineScope(job))
            repo.addFolder(folder, listOf(found("SLUS_012.34.bin"), found("Other.bin", size = 5)))
            val rom = repo.current.roms.first { it.fileName == "SLUS_012.34.bin" }
            assertFalse(NameCheck.isNameUsable(rom.displayTitle))
            repo.updateMeta(rom.key) { IdentifyRules.withUserName(it, "Spyro the Dragon") }

            // Reescaneo igual: sigue ahí.
            repo.mergeScan(folder.id, listOf(found("SLUS_012.34.bin"), found("Other.bin", size = 5)))
            assertEquals("Spyro the Dragon", repo.romByKey(rom.key)!!.displayTitle)

            // Movido a una subcarpeta de la misma carpeta: misma clave, mismo nombre.
            val diff = repo.mergeScan(folder.id, listOf(found("PS1/SLUS_012.34.bin"), found("Other.bin", size = 5)))
            assertTrue(diff.added.isEmpty())
            val moved = repo.romByKey(rom.key)!!
            assertEquals("primary:psx/PS1/SLUS_012.34.bin", moved.docId)
            assertEquals("Spyro the Dragon", moved.displayTitle)
        } finally {
            job.cancel()
        }
    }
}
