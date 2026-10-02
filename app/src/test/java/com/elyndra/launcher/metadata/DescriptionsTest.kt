package com.elyndra.launcher.metadata

import com.elyndra.launcher.core.text.TextTrim
import com.elyndra.launcher.data.GameMeta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionsTest {

    @Test
    fun normalizesSourceLanguageCodes() {
        assertEquals("ja", DescriptionLangs.normalize("jp"))
        assertEquals("pt", DescriptionLangs.normalize("pt-BR"))
        assertEquals("es", DescriptionLangs.normalize("spanish"))
        assertEquals("es", DescriptionLangs.normalize("ES"))
        assertNull(DescriptionLangs.normalize(""))
        assertNull(DescriptionLangs.normalize("xx1"))
        assertEquals("spanish", DescriptionLangs.steamName("es"))
        assertEquals("english", DescriptionLangs.steamName("it"))
    }

    @Test
    fun fallbackOrderIsAppLanguageThenEnglishThenAny() {
        val all = mapOf("fr" to "Fr", "en" to "En", "es" to "Es")
        assertEquals("es", DescriptionMerge.chooseLang(all, "es"))
        assertEquals("en", DescriptionMerge.chooseLang(all, "de"))
        assertEquals("fr", DescriptionMerge.chooseLang(mapOf("fr" to "Fr", "it" to "It"), "de"))
        assertNull(DescriptionMerge.chooseLang(emptyMap(), "es"))
    }

    @Test
    fun aLowerPrioritySourceInTheAppLanguageBeatsAHigherOneInEnglish() {
        // IGDB va primero en la prioridad, pero solo tiene inglés; ScreenScraper tiene español.
        val igdb = SourceData(Service.Igdb).apply { addDescription("en", "An English synopsis.") }
        val ss = SourceData(Service.ScreenScraper).apply {
            addDescription("en", "SS English.")
            addDescription("sp", "Sinopsis en español.")
        }
        val priority = MetadataPriority(
            text = listOf(Service.Igdb, Service.ScreenScraper, Service.RetroAchievements, Service.SteamGridDb),
            art = MetadataPriority.DEFAULT.art,
        )
        val merged = MetadataMerge.merge(mapOf(Service.Igdb to igdb, Service.ScreenScraper to ss), priority)
        // Por idioma gana la fuente con más prioridad que lo tenga…
        assertEquals("An English synopsis.", merged.descriptions["en"])
        assertEquals("Sinopsis en español.", merged.descriptions["es"])
        // …y se enseña la del idioma de la app.
        val meta = GameDescriptionUpdate.apply(GameMeta(scrapedAt = 1), merged.descriptions, "es")
        assertEquals("Sinopsis en español.", meta.description)
        assertEquals("es", meta.descriptionLang)
        val view = DescriptionPick.view(meta, "es")!!
        assertFalse(view.foreign)
        assertEquals("Sinopsis en español.", view.text)
    }

    @Test
    fun onlyForeignDescriptionIsLabelledNotPassedOffAsCorrect() {
        val meta = GameMeta(descriptions = mapOf("en" to "English only."))
        val v = DescriptionPick.view(meta, "es")!!
        assertTrue(v.foreign)
        assertEquals("en", v.lang)
        assertEquals("en" to "English only.", DescriptionPick.translatable(meta, "es"))
        // En inglés no hay nada que traducir.
        assertNull(DescriptionPick.translatable(meta, "en"))
    }

    @Test
    fun translationIsShownUntilTheUserAsksForTheOriginal() {
        val meta = GameMeta(descriptions = mapOf("en" to "English only."))
        val t = DescriptionPick.view(meta, "es", translation = "Solo en inglés.")!!
        assertTrue(t.translated)
        assertFalse(t.foreign)
        assertEquals("en", t.originalLang)
        val o = DescriptionPick.view(meta, "es", translation = "Solo en inglés.", showOriginal = true)!!
        assertEquals("English only.", o.text)
        assertTrue(o.foreign)
    }

    @Test
    fun legacyUntaggedDescriptionIsKeptWithoutALabel() {
        val legacy = GameMeta(description = "Old text")
        val v = DescriptionPick.view(legacy, "es")!!
        assertEquals("Old text", v.text)
        assertNull(v.lang)
        assertFalse(v.foreign)
        // Migración: las apps se etiquetan en inglés (solo IGDB les daba sinopsis).
        val app = GameMeta(description = "Old text", descriptionLang = "en")
        assertTrue(DescriptionPick.view(app, "es")!!.foreign)
    }

    @Test
    fun noDescriptionMeansNothingToShow() {
        assertNull(DescriptionPick.view(GameMeta(), "es"))
        assertNull(DescriptionPick.view(GameMeta(description = "   "), "es"))
    }

    @Test
    fun aPassAddsLanguagesWithoutLosingOthersAndRecordsTheLanguageAsked() {
        val first = GameDescriptionUpdate.apply(GameMeta(scrapedAt = 1), mapOf("es" to "Hola", "en" to "Hi"), "es")
        assertEquals("es", first.descriptionCheckedLang)
        // La app pasa a francés: la nueva pasada trae francés y no borra lo demás.
        val second = GameDescriptionUpdate.apply(first, mapOf("fr" to "Salut"), "fr")
        assertEquals(setOf("es", "en", "fr"), second.descriptions.keys)
        assertEquals("Salut", second.description)
        assertEquals("fr", second.descriptionLang)
        assertEquals("fr", second.descriptionCheckedLang)
        // Si la nueva no trae nada, la principal pasa a la mejor que haya y queda anotado el intento.
        val third = GameDescriptionUpdate.apply(first, emptyMap(), "de")
        assertEquals("Hi", third.description)
        assertEquals("en", third.descriptionLang)
        assertEquals("de", third.descriptionCheckedLang)
    }

    @Test
    fun languageChangeInvalidatesWhatWasShown() {
        // Lo que se enseña depende del idioma de ahora, no del de la descarga.
        val meta = GameDescriptionUpdate.apply(GameMeta(scrapedAt = 1), mapOf("es" to "Hola", "en" to "Hi"), "es")
        assertEquals("Hola", DescriptionPick.view(meta, "es")!!.text)
        assertEquals("Hi", DescriptionPick.view(meta, "en")!!.text)
        val fr = DescriptionPick.view(meta, "fr")!!
        assertEquals("Hi", fr.text)
        assertTrue(fr.foreign)
    }

    @Test
    fun heroTrimCutsAtTheFirstSentence() {
        assertEquals("Short.", TextTrim.shorten("Short."))
        val long = "First sentence here. " + "x".repeat(300)
        assertEquals("First sentence here.", TextTrim.shorten(long))
        assertTrue(TextTrim.shorten("word ".repeat(80)).endsWith("…"))
    }
}

class TranslationCacheTest {

    private class FakeTranslator(var models: MutableSet<String> = mutableSetOf("en")) : DescriptionTranslator {
        var downloads = 0
        var calls = 0
        var fail = false
        override fun supports(from: String, to: String) = from != "xx" && to != "xx"
        override suspend fun downloadedModels() = models.toSet()
        override suspend fun download(langs: Set<String>, wifiOnly: Boolean) {
            downloads++
            models += langs
        }
        override suspend fun translate(text: String, from: String, to: String): String {
            calls++
            if (fail) error("boom")
            return "[$to] $text"
        }
        override suspend fun deleteModel(lang: String) {
            models -= lang
        }
    }

    private class MapStore : TranslationStore {
        val map = HashMap<String, StoredTranslation>()
        override suspend fun get(gameKey: String, targetLang: String) = map["$gameKey|$targetLang"]
        override suspend fun put(t: StoredTranslation) { map["${t.gameKey}|${t.targetLang}"] = t }
    }

    @Test
    fun neverDownloadsWithoutPermission() = runBlocking {
        val tr = FakeTranslator()
        val cache = TranslationCache(MapStore(), tr)
        val r = cache.translate("a:x", "es", "en", "Hello")
        assertEquals(TranslationResult.NeedsModels(setOf("es")), r)
        assertEquals(0, tr.downloads)
        assertEquals(0, tr.calls)
    }

    @Test
    fun translatesCachesAndReusesUntilTheOriginalChanges() = runBlocking {
        val tr = FakeTranslator()
        val store = MapStore()
        val cache = TranslationCache(store, tr)
        val r = cache.translate("a:x", "es", "en", "Hello", allowDownload = true)
        assertEquals(TranslationResult.Done("[es] Hello"), r)
        assertEquals(1, tr.downloads)
        // Guardada con su idioma y el original intacto en otro sitio.
        assertEquals("en", store.map["a:x|es"]!!.sourceLang)
        assertEquals("[es] Hello", cache.cached("a:x", "es", "en", "Hello"))
        cache.translate("a:x", "es", "en", "Hello")
        assertEquals(1, tr.calls)
        // El original cambió: la guardada ya no vale.
        assertNull(cache.cached("a:x", "es", "en", "Hello, world"))
        assertEquals(TranslationResult.Done("[es] Hello, world"), cache.translate("a:x", "es", "en", "Hello, world"))
        assertEquals(2, tr.calls)
    }

    @Test
    fun errorsAreResultsNotCrashes() = runBlocking {
        val tr = FakeTranslator(mutableSetOf("en", "es")).apply { fail = true }
        val cache = TranslationCache(MapStore(), tr)
        assertEquals(TranslationResult.Failed, cache.translate("a:x", "es", "en", "Hello"))
        assertEquals(TranslationResult.Unsupported, cache.translate("a:x", "xx", "en", "Hello"))
        assertEquals(TranslationResult.Done("Hola"), cache.translate("a:x", "es", "es", "Hola"))
    }
}
