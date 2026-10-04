package com.elyndra.launcher.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.metadata.DescriptionPick
import com.elyndra.launcher.metadata.DescriptionView
import com.elyndra.launcher.metadata.PackState
import com.elyndra.launcher.metadata.TranslationCache
import com.elyndra.launcher.metadata.TranslationNeeds
import com.elyndra.launcher.metadata.TranslationPacks
import com.elyndra.launcher.metadata.TranslationResult
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.theme.darkGlass
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Descripciones en pantalla: qué texto va (ver [DescriptionPick]), su
 * traducción y "Ver original".
 *
 * Con el paquete de idioma ya instalado, lo que no está en el idioma de la app
 * se traduce solo. Si falta, se enseña el original con su etiqueta y se ofrece
 * bajarlo: al cambiar el idioma de la app (si hace falta alguno) o al pulsar
 * "Traducir". Nunca se baja nada sin que el usuario lo pida, y por Wi-Fi salvo
 * que permita datos móviles.
 */
class DescriptionsController(private val vm: ElyndraViewModel) {

    private val cache: TranslationCache get() = vm.translations
    private val packs: TranslationPacks get() = vm.packs
    private val store get() = vm.app.settings

    var allowMobile by mutableStateOf(store.translateAllowMobile); private set

    /** Paquetes de idioma instalados (Ajustes → Metadatos). */
    var installed by mutableStateOf<Set<String>>(emptySet()); private set

    /** Paquetes que se están bajando, esperan Wi-Fi o fallaron. */
    var packStates by mutableStateOf<Map<String, PackState>>(emptyMap()); private set

    /** Traducciones cargadas: "clave|idioma|hash del original" → texto. */
    private val translated = mutableStateMapOf<String, String>()

    /** Juegos en los que se pidió ver el original en vez de la traducción. */
    private val original = mutableStateMapOf<String, Boolean>()

    /** Juego cuya descripción se está traduciendo. */
    var busy by mutableStateOf<String?>(null); private set

    init {
        store.dropRetiredTranslationPrefs()
        vm.viewModelScope.launch { packs.installed.collect { installed = it } }
        vm.viewModelScope.launch { packs.states.collect { packStates = it } }
        vm.viewModelScope.launch { packs.refresh() }
    }

    private fun slot(key: String, lang: String, source: String) = "$key|$lang|${TranslationCache.hash(source)}"

    /** Lo que se enseña de [meta] en el idioma de la app. */
    fun view(key: String, meta: GameMeta, lang: String): DescriptionView? {
        val source = DescriptionPick.translatable(meta, lang)
        val t = source?.let { translated[slot(key, lang, it.second)] }
        return DescriptionPick.view(meta, lang, t, original[key] == true)
    }

    /** ¿Están ya los paquetes para traducir de [from] a [to]? (sin bajar nada) */
    private fun hasPacks(from: String, to: String): Boolean {
        val needed = TranslationNeeds.required(setOf(from), to, packs::supports)
        return needed.isNotEmpty() && packs.installed.value.containsAll(needed)
    }

    /** La traducción guardada o, si los paquetes ya están, la traduce ahora. Sin paquetes: nada (se ve el original). */
    suspend fun load(key: String, meta: GameMeta, lang: String) {
        val (from, text) = DescriptionPick.translatable(meta, lang) ?: return
        val s = slot(key, lang, text)
        if (s in translated) return
        cache.cached(key, lang, from, text)?.let {
            translated[s] = it
            return
        }
        if (!hasPacks(from, lang)) return
        (cache.translate(key, lang, from, text, allowDownload = false) as? TranslationResult.Done)?.let { translated[s] = it.text }
    }

    fun showOriginal(key: String, value: Boolean) {
        if (value) original[key] = true else original.remove(key)
    }

    /** "Traducir": con los paquetes, al momento; si faltan, primero se pregunta. */
    fun translate(key: String, meta: GameMeta) {
        val lang = vm.settings.lang
        val (from, text) = DescriptionPick.translatable(meta, lang) ?: return
        if (busy != null) return
        showOriginal(key, false)
        val missing = TranslationNeeds.missing(setOf(from), lang, packs.installed.value, packs::supports)
        if (missing.isNotEmpty()) {
            offerDownload(missing, lang)
            return
        }
        vm.viewModelScope.launch {
            busy = key
            val result = cache.translate(key, lang, from, text, allowDownload = false)
            busy = null
            when (result) {
                is TranslationResult.Done -> translated[slot(key, lang, text)] = result.text
                is TranslationResult.NeedsModels -> offerDownload(result.langs, lang)
                TranslationResult.Unsupported -> vm.showToast(UiText.res(R.string.translate_unsupported))
                TranslationResult.Failed -> vm.showToast(UiText.res(R.string.translate_failed))
            }
        }
    }

    /**
     * El idioma de la app (al volver a primer plano). Si cambió y para
     * enseñar la biblioteca en él falta algún paquete, se avisa una vez; si
     * no falta nada, no se dice nada. La primera vez solo se anota.
     */
    fun onAppLanguage(lang: String) {
        val last = store.translateLastLang
        if (last == lang) return
        store.translateLastLang = lang
        vm.viewModelScope.launch {
            val have = packs.refresh()
            val sources = TranslationNeeds.foreignSources(allMetas(), lang)
            val missing = TranslationNeeds.missing(sources, lang, have, packs::supports)
            val offer = TranslationNeeds.notice(last, lang, missing, packs.busy) ?: return@launch
            if (vm.dialog == null) offerDownload(offer, lang)
        }
    }

    private fun allMetas(): List<GameMeta> = vm.library.roms.map { it.meta } + vm.library.apps.map { it.meta }

    private fun offerDownload(langs: Set<String>, lang: String) {
        val names = langs.joinToString(", ") { languageNameInline(it, lang) }
        vm.showDialog(
            DialogSpec(
                title = UiText.res(R.string.translate_model_title),
                message = UiText.res(
                    R.string.translate_model_msg,
                    names,
                    TranslationCache.MODEL_MB * langs.size,
                    UiText.res(if (allowMobile) R.string.translate_model_any else R.string.translate_model_wifi),
                ),
                confirm = DialogButton(UiText.res(R.string.translate_download)) { download(langs, lang) },
                dismiss = DialogButton(UiText.res(R.string.translate_later)) {},
            ),
        )
    }

    private fun download(langs: Set<String>, lang: String) {
        packs.download(langs, allowMobile) { ok ->
            vm.viewModelScope.launch {
                if (ok) translatePending(lang) else vm.showToast(UiText.res(R.string.translate_failed))
            }
        }
    }

    /** Tras bajar un paquete: se traduce en segundo plano lo que estaba esperando, y la pantalla se refresca sola. */
    private suspend fun translatePending(lang: String) {
        val games = vm.library.roms.map { it.key to it.meta } + vm.library.apps.map { it.key to it.meta }
        for ((key, meta) in games) load(key, meta, lang)
    }

    /* ── Ajustes → Metadatos → Traducción ─────────────────────── */

    fun toggleAllowMobile() {
        allowMobile = !allowMobile
        store.translateAllowMobile = allowMobile
    }

    /** Vuelve a intentar los que fallaron (o esperan) con el ajuste de datos de ahora. */
    fun retry() {
        val lang = vm.settings.lang
        val langs = packStates.keys
        if (langs.isNotEmpty()) download(langs, lang)
    }

    fun refreshModels() {
        vm.viewModelScope.launch { packs.refresh() }
    }

    fun deleteModel(lang: String) {
        vm.viewModelScope.launch { packs.delete(lang) }
    }

    companion object {
        /** "en" → "Inglés" (en el idioma de la app): para una etiqueta suelta. */
        fun languageName(code: String, appLang: String): String {
            val loc = Locale.forLanguageTag(appLang)
            return Locale.forLanguageTag(code).getDisplayLanguage(loc).replaceFirstChar { it.titlecase(loc) }
        }

        /** El nombre dentro de una frase, como lo escribe cada idioma: "del inglés", "from English", "aus dem Englischen"… */
        fun languageNameInline(code: String, appLang: String): String =
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag(appLang))
    }
}

/**
 * La descripción de un juego para la pantalla: la del idioma de la app o,
 * si no hay, otra con su etiqueta (o su traducción). Null = sin descripción:
 * quien llama no deja hueco.
 */
@Composable
fun rememberDescription(vm: ElyndraViewModel, key: String?, meta: GameMeta?): DescriptionView? {
    val lang = vm.settings.lang
    if (key == null || meta == null) return null
    val c = vm.descriptions
    // Al instalarse un paquete se vuelve a mirar: la traducción aparece sin reiniciar.
    LaunchedEffect(key, meta.descriptions, meta.description, lang, c.installed) { c.load(key, meta, lang) }
    return c.view(key, meta, lang)
}

/**
 * La sinopsis recortada para el hero. Si no está en el idioma de la app, su
 * idioma va delante ("Inglés · …"): al final lo cortaría la elipsis.
 */
@Composable
fun heroDescription(d: DescriptionView, appLang: String): String {
    val short = com.elyndra.launcher.core.text.TextTrim.shorten(d.text)
    val label = when {
        d.translated -> stringResource(
            R.string.description_translated_from,
            DescriptionsController.languageNameInline(d.originalLang ?: appLang, appLang),
        )
        d.foreign && d.lang != null -> DescriptionsController.languageName(d.lang, appLang)
        else -> return short
    }
    return heroWithLabel(label, short)
}

/** "Etiqueta · texto": la etiqueta siempre se ve. */
fun heroWithLabel(label: String, text: String) = "$label · $text"

/**
 * Aviso pequeño, sin bloquear nada, mientras se baja un paquete de idioma:
 * descargando, esperando Wi-Fi o error (con "Reintentar").
 */
@Composable
fun TranslationPackBanner(vm: ElyndraViewModel, modifier: Modifier = Modifier) {
    val c = vm.descriptions
    val states = c.packStates
    if (states.isEmpty()) return
    val lang = vm.settings.lang
    val names = states.keys.joinToString(", ") { DescriptionsController.languageNameInline(it, lang) }
    val text = when {
        PackState.Error in states.values -> stringResource(R.string.translate_banner_error, names)
        PackState.WaitingForWifi in states.values -> stringResource(R.string.translate_banner_waiting, names)
        else -> stringResource(R.string.translate_banner_downloading, names)
    }
    Row(
        modifier
            .padding(horizontal = 24.dp)
            .darkGlass(RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (PackState.Error !in states.values) {
            ArcSpinner(size = 14.dp)
            Spacer(Modifier.width(10.dp))
        }
        ElyText(text, size = 10.5f, weight = FontWeight.Medium, color = Color.White, maxLines = 2)
        if (PackState.Error in states.values) {
            Spacer(Modifier.width(10.dp))
            GhostButton(stringResource(R.string.retry), c::retry)
        }
    }
}
