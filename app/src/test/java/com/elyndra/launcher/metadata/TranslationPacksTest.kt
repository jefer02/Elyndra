package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.GameMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationNeedsTest {

    @Test
    fun englishOnlyToSpanishNeedsSpanishAndEnglish() {
        assertEquals(setOf("es", "en"), TranslationNeeds.required(setOf("en"), "es"))
        // English sale de serie en ML Kit: solo falta el español.
        assertEquals(setOf("es"), TranslationNeeds.missing(setOf("en"), "es", installed = setOf("en")))
    }

    @Test
    fun englishIsThePivotBetweenTwoOtherLanguages() {
        assertEquals(setOf("es", "fr", "en"), TranslationNeeds.required(setOf("fr"), "es"))
    }

    @Test
    fun nothingIsNeededWhenEverythingIsAlreadyInTheTargetLanguage() {
        assertTrue(TranslationNeeds.required(setOf("en"), "en").isEmpty())
        assertTrue(TranslationNeeds.required(emptySet(), "es").isEmpty())
        assertTrue(TranslationNeeds.missing(setOf("en"), "es", installed = setOf("en", "es")).isEmpty())
        // Un idioma que no se puede traducir no pide paquetes.
        assertTrue(TranslationNeeds.required(setOf("xx"), "es") { it != "xx" }.isEmpty())
    }

    @Test
    fun foreignSourcesAreTheLanguagesShownAsForeign() {
        val metas = listOf(
            GameMeta(descriptions = mapOf("en" to "English only")),
            GameMeta(descriptions = mapOf("es" to "Español", "en" to "English")),
            GameMeta(descriptions = mapOf("ja" to "日本語")),
            GameMeta(),
        )
        assertEquals(setOf("en", "ja"), TranslationNeeds.foreignSources(metas, "es"))
        assertEquals(setOf("ja"), TranslationNeeds.foreignSources(metas, "en"))
    }

    @Test
    fun theNoticeOnlyShowsWhenTheLanguageChangedAndSomethingIsMissing() {
        // Primera vez: solo se anota, sin aviso.
        assertNull(TranslationNeeds.notice(null, "es", setOf("es"), downloading = false))
        // Mismo idioma.
        assertNull(TranslationNeeds.notice("es", "es", setOf("es"), downloading = false))
        // Todo instalado: se aplica el idioma sin decir nada.
        assertNull(TranslationNeeds.notice("en", "es", emptySet(), downloading = false))
        // Ya se está bajando.
        assertNull(TranslationNeeds.notice("en", "es", setOf("es"), downloading = true))
        assertEquals(setOf("es"), TranslationNeeds.notice("en", "es", setOf("es"), downloading = false))
    }
}

class TranslationPacksTest {

    /** Gestor de modelos falso: cada descarga espera a [gate] (o falla si [fail]). */
    private class FakeModels(val installed: MutableSet<String> = mutableSetOf("en")) : DescriptionTranslator {
        var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var fail = false
        val downloads = mutableListOf<Pair<String, Boolean>>()
        override fun supports(from: String, to: String) = from != "xx" && to != "xx"
        override suspend fun downloadedModels() = installed.toSet()
        override suspend fun download(langs: Set<String>, wifiOnly: Boolean) {
            langs.forEach { downloads += it to wifiOnly }
            gate.await()
            if (fail) error("network")
            installed += langs
        }
        override suspend fun translate(text: String, from: String, to: String) = "[$to] $text"
        override suspend fun deleteModel(lang: String) {
            installed -= lang
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private suspend fun waitFor(check: () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(5)
    }

    @Test
    fun nothingIsDownloadedWithoutBeingAsked() = runBlocking {
        val models = FakeModels()
        val packs = TranslationPacks(models, scope, unmetered = { true })
        assertEquals(setOf("en"), packs.refresh())
        assertTrue(models.downloads.isEmpty())
        assertTrue(packs.states.value.isEmpty())
    }

    @Test
    fun downloadingShowsItsStateThenInstallsAndReports() = runBlocking {
        val models = FakeModels().apply { gate = CompletableDeferred() }
        val packs = TranslationPacks(models, scope, unmetered = { true })
        val done = CompletableDeferred<Boolean>()
        packs.download(setOf("es"), allowMobileData = false) { done.complete(it) }
        assertEquals(mapOf("es" to PackState.Downloading), packs.states.value)
        assertTrue(packs.busy)
        models.gate.complete(Unit)
        assertTrue(withTimeout(5_000) { done.await() })
        assertTrue("es" in packs.installed.value)
        assertTrue(packs.states.value.isEmpty())
        // Por Wi-Fi: no se permitieron datos móviles.
        assertEquals(listOf("es" to true), models.downloads)
    }

    @Test
    fun withoutWifiItWaitsAndFollowsTheNetwork() = runBlocking {
        var wifi = false
        val models = FakeModels().apply { gate = CompletableDeferred() }
        val packs = TranslationPacks(models, scope, unmetered = { wifi }, pollMs = 10)
        packs.download(setOf("es"), allowMobileData = false)
        assertEquals(PackState.WaitingForWifi, packs.states.value["es"])
        wifi = true
        waitFor { packs.states.value["es"] == PackState.Downloading }
        models.gate.complete(Unit)
        waitFor { "es" in packs.installed.value }
    }

    @Test
    fun withMobileDataAllowedItDownloadsRightAway() = runBlocking {
        val models = FakeModels()
        val packs = TranslationPacks(models, scope, unmetered = { false })
        val done = CompletableDeferred<Boolean>()
        packs.download(setOf("fr"), allowMobileData = true) { done.complete(it) }
        assertTrue(withTimeout(5_000) { done.await() })
        assertEquals(listOf("fr" to false), models.downloads)
    }

    @Test
    fun aFailedDownloadIsAnErrorThatCanBeRetried() = runBlocking {
        val models = FakeModels().apply { fail = true }
        val packs = TranslationPacks(models, scope, unmetered = { true })
        val first = CompletableDeferred<Boolean>()
        packs.download(setOf("de"), allowMobileData = false) { first.complete(it) }
        assertFalse(withTimeout(5_000) { first.await() })
        assertEquals(mapOf("de" to PackState.Error), packs.states.value)
        models.fail = false
        val second = CompletableDeferred<Boolean>()
        packs.download(setOf("de"), allowMobileData = false) { second.complete(it) }
        assertTrue(withTimeout(5_000) { second.await() })
        assertTrue(packs.states.value.isEmpty())
    }

    @Test
    fun alreadyInstalledPacksAreNotDownloadedAgainAndCanBeDeleted() = runBlocking {
        val models = FakeModels(mutableSetOf("en", "es"))
        val packs = TranslationPacks(models, scope, unmetered = { true })
        packs.refresh()
        var ok = false
        packs.download(setOf("es"), allowMobileData = false) { ok = it }
        assertTrue(ok)
        assertTrue(models.downloads.isEmpty())
        packs.delete("es")
        assertFalse("es" in packs.installed.value)
    }
}
