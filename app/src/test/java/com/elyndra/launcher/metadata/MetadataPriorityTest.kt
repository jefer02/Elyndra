package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.MatchMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prioridad de fuentes: cada campo sale de la primera fuente de la lista que lo tiene. */
class MetadataPriorityTest {

    private fun ss() = SourceData(Service.ScreenScraper).apply {
        setText(MetaField.Name, "Okami (SS)")
        setText(MetaField.Genre, "Action")
        setArt(MetaField.Cover, "ss-cover")
        setArt(MetaField.Logo, "ss-logo")
        matched(MatchMethod.HASH, 1.0)
    }

    private fun igdb() = SourceData(Service.Igdb).apply {
        setText(MetaField.Name, "Ōkami")
        setText(MetaField.Description, "A wolf god.")
        setArt(MetaField.Cover, "igdb-cover")
        setArt(MetaField.Hero, "igdb-hero")
        matched(MatchMethod.NAME, 0.97)
    }

    @Test
    fun theDefaultKeepsTheTextOrderAndPutsSgdbAndIgdbFirstForArt() {
        val merged = MetadataMerge.merge(mapOf(Service.ScreenScraper to ss(), Service.Igdb to igdb()), MetadataPriority.DEFAULT)
        // Textos: el orden de siempre.
        assertEquals("Okami (SS)", merged.text[MetaField.Name])
        assertEquals("A wolf god.", merged.text[MetaField.Description])
        // Imágenes: IGDB antes que ScreenScraper; el logo, que IGDB no da, de ScreenScraper.
        assertEquals(Service.Igdb to "igdb-cover", merged.art[MetaField.Cover])
        assertEquals(Service.Igdb to "igdb-hero", merged.art[MetaField.Hero])
        assertEquals(Service.ScreenScraper to "ss-logo", merged.art[MetaField.Logo])
        assertEquals(
            listOf(Service.SteamGridDb, Service.Igdb, Service.ScreenScraper, Service.RetroAchievements, Service.Libretro, Service.Steam, Service.GooglePlay),
            MetadataPriority.DEFAULT.art,
        )
    }

    @Test
    fun eachArtKindFallsToTheNextSourceThatHasIt() {
        val sgdb = SourceData(Service.SteamGridDb).apply {
            setArt(MetaField.Cover, "sgdb-cover")
            setArt(MetaField.Logo, "sgdb-logo")
        }
        val shots = SourceData(Service.GooglePlay).apply { setArt(MetaField.Screenshot, "play-shot"); setArt(MetaField.Icon, "play-icon") }
        val merged = MetadataMerge.merge(mapOf(Service.GooglePlay to shots, Service.ScreenScraper to ss(), Service.Igdb to igdb(), Service.SteamGridDb to sgdb), MetadataPriority.DEFAULT)
        assertEquals(Service.SteamGridDb to "sgdb-cover", merged.art[MetaField.Cover])
        assertEquals(Service.SteamGridDb to "sgdb-logo", merged.art[MetaField.Logo])
        // SteamGridDB no trajo fondo: el siguiente que lo tiene.
        assertEquals(Service.Igdb to "igdb-hero", merged.art[MetaField.Hero])
        // Nadie más tiene captura ni icono: Google Play, el último, los pone.
        assertEquals(Service.GooglePlay to "play-shot", merged.art[MetaField.Screenshot])
        assertEquals(Service.GooglePlay to "play-icon", merged.art[MetaField.Icon])
        // Y si la descarga de la primera falla, la cadena sigue en orden.
        assertEquals(listOf(Service.SteamGridDb, Service.Igdb, Service.ScreenScraper), merged.artChain[MetaField.Cover]!!.map { it.first })
    }

    @Test
    fun onlyTheOldDefaultArtOrderIsMigratedACustomOrderStays() {
        val old = MetadataPriority.format(MetadataPriority.LEGACY_DEFAULT_ART)
        assertEquals(null, MetadataPriority.migrateArt(old))
        assertEquals(null, MetadataPriority.migrateArt(null))
        // Guardado a medias (de una versión con menos fuentes): se completa igual que el de serie viejo.
        assertEquals(null, MetadataPriority.migrateArt("gplay,ss,igdb,sgdb,steam,libretro"))
        val custom = "ss,sgdb,igdb,ra,libretro,steam,gplay"
        assertEquals(custom, MetadataPriority.migrateArt(custom))
        assertEquals(MetadataPriority.DEFAULT.art, MetadataPriority.parse(MetadataPriority.migrateArt(old), MetadataPriority.DEFAULT.art))
        assertEquals(Service.ScreenScraper, MetadataPriority.parse(MetadataPriority.migrateArt(custom), MetadataPriority.DEFAULT.art).first())
    }

    @Test
    fun textsAndImagesFollowTheirOwnOrder() {
        val priority = MetadataPriority(
            text = listOf(Service.Igdb, Service.ScreenScraper, Service.RetroAchievements, Service.SteamGridDb),
            art = listOf(Service.ScreenScraper, Service.Igdb, Service.SteamGridDb, Service.RetroAchievements),
        )
        val merged = MetadataMerge.merge(mapOf(Service.ScreenScraper to ss(), Service.Igdb to igdb()), priority)
        assertEquals("Ōkami", merged.text[MetaField.Name])
        assertEquals(Service.ScreenScraper to "ss-cover", merged.art[MetaField.Cover])
    }

    @Test
    fun aSourceIsOnlyAskedWhenItCanStillWinSomething() {
        val results = mapOf(Service.ScreenScraper to ss())
        // SteamGridDB delante en imágenes (de serie): lo quiere todo.
        assertEquals(MetadataMerge.provides(Service.SteamGridDb), MetadataMerge.wanted(Service.SteamGridDb, results, MetadataPriority.DEFAULT))
        // Con ScreenScraper delante, le queda el fondo y el icono, que SS no trajo.
        val ssFirst = MetadataPriority.DEFAULT.copy(art = listOf(Service.ScreenScraper, Service.SteamGridDb, Service.Igdb, Service.RetroAchievements))
        assertEquals(setOf(MetaField.Hero, MetaField.Icon), MetadataMerge.wanted(Service.SteamGridDb, results, ssFirst))
    }

    @Test
    fun theIdentificationIsTheMostConfidentOne() {
        val merged = MetadataMerge.merge(mapOf(Service.Igdb to igdb(), Service.ScreenScraper to ss()), MetadataPriority.DEFAULT)
        assertEquals(MatchMethod.HASH, merged.matchedBy)
        assertEquals(1f, merged.confidence)
        assertEquals(listOf(Service.ScreenScraper, Service.Igdb), merged.sources)
    }

    @Test
    fun nothingMatchedMeansNotMatched() {
        val merged = MetadataMerge.merge(emptyMap(), MetadataPriority.DEFAULT)
        assertTrue(!merged.matched)
        assertEquals(null, merged.matchedBy)
    }

    @Test
    fun storedOrdersAreRepairedNotTrusted() {
        assertEquals(
            // Lo que falta se añade detrás, en el orden de partida (con las fuentes sin clave).
            listOf(Service.SteamGridDb, Service.ScreenScraper, Service.Igdb, Service.RetroAchievements, Service.Libretro, Service.Steam, Service.GooglePlay),
            MetadataPriority.parse("sgdb,ss,ss,bogus", MetadataPriority.DEFAULT.art),
        )
        assertEquals(MetadataPriority.DEFAULT.text, MetadataPriority.parse(null, MetadataPriority.DEFAULT.text))
        assertEquals("ss,igdb", MetadataPriority.format(listOf(Service.ScreenScraper, Service.Igdb)))
    }

    @Test
    fun similarityBecomesAMethod() {
        assertEquals(MatchMethod.NAME, MetadataMerge.nameMethod(0.99))
        assertEquals(MatchMethod.FUZZY, MetadataMerge.nameMethod(0.8))
    }
}
