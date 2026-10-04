package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.ui.heroWithLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuándo se vuelve a pedir una descripción y qué se enseña (ver [DescriptionRecheck], [DescriptionPick]). */
class DescriptionCoherenceTest {

    private val play = setOf(Service.GooglePlay.id)

    @Test
    fun aSourceThatAppearsLaterIsAskedEvenIfTheLanguageWasAlreadyChecked() {
        // Scrapeado en español antes de existir Play: nadie dio descripción.
        val before = GameDescriptionUpdate.apply(GameMeta(scrapedAt = 1), emptyMap(), "es", consulted = emptySet())
        assertEquals("es", before.descriptionCheckedLang)
        assertTrue(DescriptionRecheck.needed(before, "es", play))
        // Play respondió (aunque solo en inglés): ya no se repite.
        val after = GameDescriptionUpdate.apply(before, mapOf("en" to "English"), "es", consulted = play)
        assertFalse(DescriptionRecheck.needed(after, "es", play))
    }

    @Test
    fun aNewLanguageIsAskedOnce() {
        val es = GameDescriptionUpdate.apply(GameMeta(scrapedAt = 1), mapOf("en" to "Hi"), "es", play)
        assertTrue(DescriptionRecheck.needed(es, "fr", play))
        val fr = GameDescriptionUpdate.apply(es, emptyMap(), "fr", play)
        assertFalse(DescriptionRecheck.needed(fr, "fr", play))
    }

    @Test
    fun aPackageNotOnPlayIsLeftAloneUntilTheMissExpires() {
        // Play dijo 404: no se anota como respondida, y mientras dura el fallo no es fuente capaz.
        val missed = GameDescriptionUpdate.apply(GameMeta(scrapedAt = 1), emptyMap(), "es", consulted = emptySet())
        assertFalse(DescriptionRecheck.needed(missed, "es", capable = emptySet()))
        // Caducado el fallo, Play vuelve a ser capaz y se le pregunta una vez.
        assertTrue(DescriptionRecheck.needed(missed, "es", capable = play))
    }

    @Test
    fun dataFromBeforeRecordingSourcesIsAskedOnce() {
        val legacy = GameMeta(scrapedAt = 1, descriptionCheckedLang = "es", descriptionSources = null)
        assertTrue(DescriptionRecheck.needed(legacy, "es", play))
    }

    @Test
    fun nothingIsAskedWithoutACapableSourceOrWhenTheLanguageIsThere() {
        val meta = GameMeta(scrapedAt = 1)
        // Consola sin ScreenScraper ni Steam: ninguna fuente puede dar descripción.
        assertFalse(DescriptionRecheck.needed(meta, "es", emptySet()))
        // Ya está en español.
        assertFalse(DescriptionRecheck.needed(meta.copy(descriptions = mapOf("es" to "Hola")), "es", play))
        // Nunca pasó por el motor: eso lo hace una pasada completa, no esta.
        assertFalse(DescriptionRecheck.needed(GameMeta(), "es", play))
    }

    @Test
    fun consultedSourcesAddUpInTheSameLanguageAndResetInANewOne() {
        val a = GameDescriptionUpdate.apply(GameMeta(scrapedAt = 1), emptyMap(), "es", setOf("steam"))
        val b = GameDescriptionUpdate.apply(a, emptyMap(), "es", setOf("ss"))
        assertEquals(setOf("steam", "ss"), b.descriptionSources)
        val c = GameDescriptionUpdate.apply(b, emptyMap(), "en", setOf("igdb"))
        assertEquals(setOf("igdb"), c.descriptionSources)
    }

    @Test
    fun theAppLanguageVersionAlwaysWinsOverOtherLanguagesAndTranslations() {
        val meta = GameMeta(descriptions = mapOf("en" to "English", "es" to "Español"))
        // Aunque haya una traducción guardada, si existe la versión en el idioma de la app, va esa.
        val v = DescriptionPick.view(meta, "es", translation = "Traducción automática")!!
        assertEquals("Español", v.text)
        assertFalse(v.foreign)
        assertFalse(v.translated)
    }

    @Test
    fun theHeroLabelGoesFirstSoTheEllipsisNeverHidesIt() {
        assertEquals("Inglés · A long synopsis", heroWithLabel("Inglés", "A long synopsis"))
    }
}
