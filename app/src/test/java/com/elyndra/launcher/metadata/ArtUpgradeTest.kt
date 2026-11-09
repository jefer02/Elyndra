package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.ArtOrigin
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.MatchMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArtUpgradeTest {

    private val keys = setOf(Service.SteamGridDb, Service.Igdb, Service.ScreenScraper)
    private val priority = MetadataPriority.DEFAULT

    private val meta = GameMeta(
        scrapedAt = 1, matched = true, sources = listOf("ss"), name = "Ōkami", description = "A wolf god.",
        descriptionLang = "en", descriptions = mapOf("en" to "A wolf god.", "es" to "Un dios lobo."), descriptionCheckedLang = "es",
        descriptionSources = setOf("ss"), releaseDate = "2006", developer = "Clover", publisher = "Capcom", genre = "Action",
        players = "1", rating = 0.9f, cover = "/c.jpg", hero = "/h.jpg", logo = "/l.png", screenshot = "/s.jpg",
        ssGameId = "77", igdbId = 1, sgdbId = 2, steamAppId = 3, userName = "Okami HD", nameLocked = true,
        matchedBy = MatchMethod.HASH, matchConfidence = 1f,
        artOrigins = mapOf(
            "cover" to ArtOrigin("ss", "u1"),
            "hero" to ArtOrigin("libretro", "u2"),
            "logo" to ArtOrigin("sgdb", "u3"),
            "shot" to ArtOrigin("local"),
        ),
    )

    @Test fun `only kinds from a source that now ranks lower are upgraded`() {
        // La carátula (SS) y el fondo (libretro) los dan ahora mejor SGDB/IGDB; el logo ya es de SGDB; la captura es local.
        assertEquals(setOf(MetaField.Cover, MetaField.Hero), ArtUpgrade.kinds(meta, priority, keys, isApp = false))
    }

    @Test fun `pinned and local art is never touched`() {
        val pinned = meta.copy(pinned = listOf("cover", "hero"))
        assertTrue(ArtUpgrade.kinds(pinned, priority, keys, isApp = false).isEmpty())
        val local = meta.copy(artOrigins = mapOf("cover" to ArtOrigin(ArtUpgrade.LOCAL), "hero" to ArtOrigin(ArtUpgrade.LOCAL)))
        assertTrue(ArtUpgrade.kinds(local, priority, keys, isApp = false).isEmpty())
    }

    @Test fun `without sgdb or igdb keys, or with a custom order that keeps them behind, nothing is upgraded`() {
        assertTrue(ArtUpgrade.kinds(meta, priority, setOf(Service.ScreenScraper), isApp = false).isEmpty())
        val ssFirst = priority.copy(art = listOf(Service.ScreenScraper, Service.Libretro, Service.SteamGridDb, Service.Igdb))
        // La carátula de SS va primera para este usuario; el fondo de libretro, por delante de SGDB/IGDB también.
        assertTrue(ArtUpgrade.kinds(meta, ssFirst, keys, isApp = false).isEmpty())
    }

    @Test fun `unknown origins or no origin are left alone and icons only count for apps`() {
        val unknown = meta.copy(artOrigins = mapOf("cover" to ArtOrigin("catalog")))
        assertTrue(ArtUpgrade.kinds(unknown, priority, keys, isApp = false).isEmpty())
        assertTrue(ArtUpgrade.kinds(GameMeta(cover = "/x"), priority, keys, isApp = false).isEmpty())
        val icon = GameMeta(icon = "/i", artOrigins = mapOf("icon" to ArtOrigin("gplay")))
        assertEquals(setOf(MetaField.Icon), ArtUpgrade.kinds(icon, priority, keys, isApp = true))
        assertTrue(ArtUpgrade.kinds(icon, priority, keys, isApp = false).isEmpty())
    }

    @Test fun `an art-only pass leaves every text field unchanged`() {
        val art = ScrapeApply.Art(
            cover = "/new-c.jpg", hero = "/new-h.jpg", logo = null, screenshot = null, icon = null,
            origins = mapOf("cover" to ArtOrigin("sgdb", "n1"), "hero" to ArtOrigin("igdb", "n2")),
        )
        val after = ScrapeApply.applyArt(meta, art)
        assertEquals("/new-c.jpg", after.cover)
        assertEquals("/new-h.jpg", after.hero)
        assertEquals("/l.png", after.logo)
        assertEquals(ArtOrigin("sgdb", "n1"), after.artOrigins["cover"])
        // Todo lo demás, idéntico: se comparan los dos sin las imágenes y su origen.
        fun noArt(m: GameMeta) = m.copy(cover = null, hero = null, logo = null, screenshot = null, icon = null, artOrigins = emptyMap())
        assertEquals(noArt(meta), noArt(after))
    }

    @Test fun `an art-only pass never replaces pinned art even if it brought some`() {
        val pinned = meta.copy(pinned = listOf("cover"))
        val after = ScrapeApply.applyArt(pinned, ScrapeApply.Art(cover = "/new.jpg", origins = mapOf("cover" to ArtOrigin("sgdb"))))
        assertEquals("/c.jpg", after.cover)
        assertEquals(ArtOrigin("ss", "u1"), after.artOrigins["cover"])
    }

    @Test fun `improves compares with where the art came from`() {
        assertTrue(ArtUpgrade.improves(meta, MetaField.Cover, Service.Igdb, priority))
        assertFalse(ArtUpgrade.improves(meta, MetaField.Logo, Service.Igdb, priority))
        assertFalse(ArtUpgrade.improves(meta, MetaField.Cover, Service.Libretro, priority))
    }

    /** La pasada solo de arte no lee el archivo del juego: ni hashes ni ScreenScraper (que identifica por hash). */
    @Test fun `the art pass never hashes files nor asks screenscraper`() {
        val text = File("src/main/java/com/elyndra/launcher/metadata/MetadataEngine.kt").readText()
        val start = text.indexOf("private suspend fun artPass(")
        val end = text.indexOf("/* ── Hashes", start).takeIf { it > 0 } ?: text.indexOf("private suspend fun saveDescriptions", start)
        assertTrue(start > 0)
        val body = text.substring(start, text.indexOf("\n    private suspend fun ", text.indexOf("private suspend fun upgradeArt(") + 30).takeIf { it > 0 } ?: end)
        for (banned in listOf("ensureHashes", "ssInto", "raInto", "Hashing.", "ScrapeApply.apply(")) {
            assertFalse("$banned in the art pass", body.contains(banned))
        }
        assertTrue(body.contains("ScrapeApply.applyArt"))
    }
}
