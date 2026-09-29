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
    fun theDefaultKeepsTheHistoricalOrder() {
        val merged = MetadataMerge.merge(mapOf(Service.ScreenScraper to ss(), Service.Igdb to igdb()), MetadataPriority.DEFAULT)
        assertEquals("Okami (SS)", merged.text[MetaField.Name])
        // Lo que ScreenScraper no tiene lo pone el siguiente.
        assertEquals("A wolf god.", merged.text[MetaField.Description])
        assertEquals(Service.ScreenScraper to "ss-cover", merged.art[MetaField.Cover])
        assertEquals(Service.Igdb to "igdb-hero", merged.art[MetaField.Hero])
    }

    @Test
    fun textsAndImagesFollowTheirOwnOrder() {
        val priority = MetadataPriority(
            text = listOf(Service.Igdb, Service.ScreenScraper, Service.RetroAchievements, Service.SteamGridDb),
            art = MetadataPriority.DEFAULT.art,
        )
        val merged = MetadataMerge.merge(mapOf(Service.ScreenScraper to ss(), Service.Igdb to igdb()), priority)
        assertEquals("Ōkami", merged.text[MetaField.Name])
        assertEquals(Service.ScreenScraper to "ss-cover", merged.art[MetaField.Cover])
    }

    @Test
    fun aSourceIsOnlyAskedWhenItCanStillWinSomething() {
        val results = mapOf(Service.ScreenScraper to ss())
        // SteamGridDB detrás de ScreenScraper: le queda el fondo y el icono, que SS no trajo.
        assertEquals(setOf(MetaField.Hero, MetaField.Icon), MetadataMerge.wanted(Service.SteamGridDb, results, MetadataPriority.DEFAULT))
        // Con SteamGridDB delante en imágenes, lo quiere todo.
        val sgdbFirst = MetadataPriority.DEFAULT.copy(art = listOf(Service.SteamGridDb, Service.ScreenScraper, Service.Igdb, Service.RetroAchievements))
        assertEquals(MetadataMerge.provides(Service.SteamGridDb), MetadataMerge.wanted(Service.SteamGridDb, results, sgdbFirst))
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
            listOf(Service.SteamGridDb, Service.ScreenScraper, Service.Igdb, Service.RetroAchievements),
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
