package com.elyndra.launcher.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.metadata.DescriptionPick
import com.elyndra.launcher.metadata.DescriptionView
import com.elyndra.launcher.metadata.TranslationCache
import com.elyndra.launcher.metadata.TranslationResult
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Descripciones en pantalla: qué texto va (ver [DescriptionPick]), su
 * traducción a demanda y "Ver original".
 *
 * Nunca se baja un modelo de idioma sin preguntar: la primera vez sale un
 * diálogo con el tamaño, y con "solo Wi-Fi" (por defecto) no se baja por datos.
 */
class DescriptionsController(private val vm: ElyndraViewModel) {

    private val cache: TranslationCache get() = vm.translations
    private val store get() = vm.app.settings

    var autoTranslate by mutableStateOf(store.autoTranslate); private set
    var wifiOnly by mutableStateOf(store.translateWifiOnly); private set

    /** Modelos de idioma descargados (Ajustes → Traducción). */
    var models by mutableStateOf<List<String>>(emptyList()); private set

    /** Traducciones cargadas: "clave|idioma|hash del original" → texto. */
    private val translated = mutableStateMapOf<String, String>()

    /** Juegos en los que se pidió ver el original en vez de la traducción. */
    private val original = mutableStateMapOf<String, Boolean>()

    /** Juego cuya descripción se está traduciendo. */
    var busy by mutableStateOf<String?>(null); private set

    private fun slot(key: String, lang: String, source: String) = "$key|$lang|${TranslationCache.hash(source)}"

    /** Lo que se enseña de [meta] en el idioma de la app. */
    fun view(key: String, meta: GameMeta, lang: String): DescriptionView? {
        val source = DescriptionPick.translatable(meta, lang)
        val t = source?.let { translated[slot(key, lang, it.second)] }
        return DescriptionPick.view(meta, lang, t, original[key] == true)
    }

    /** Carga la traducción guardada y, con "Traducir automáticamente", traduce si ya hay modelos (sin bajar nada). */
    suspend fun load(key: String, meta: GameMeta, lang: String) {
        val (from, text) = DescriptionPick.translatable(meta, lang) ?: return
        val s = slot(key, lang, text)
        if (s in translated) return
        cache.cached(key, lang, from, text)?.let {
            translated[s] = it
            return
        }
        if (!autoTranslate) return
        (cache.translate(key, lang, from, text, allowDownload = false) as? TranslationResult.Done)?.let { translated[s] = it.text }
    }

    fun showOriginal(key: String, value: Boolean) {
        if (value) original[key] = true else original.remove(key)
    }

    /** "Traducir": con modelos, al momento; si faltan, primero se pregunta. */
    fun translate(key: String, meta: GameMeta) {
        val lang = vm.settings.lang
        val (from, text) = DescriptionPick.translatable(meta, lang) ?: return
        if (busy != null) return
        showOriginal(key, false)
        vm.viewModelScope.launch {
            busy = key
            val result = cache.translate(key, lang, from, text, allowDownload = false)
            busy = null
            when (result) {
                is TranslationResult.Done -> translated[slot(key, lang, text)] = result.text
                is TranslationResult.NeedsModels -> confirmDownload(key, from, lang, text, result.langs)
                TranslationResult.Unsupported -> vm.showToast(UiText.res(R.string.translate_unsupported))
                TranslationResult.Failed -> vm.showToast(UiText.res(R.string.translate_failed))
            }
        }
    }

    private fun confirmDownload(key: String, from: String, lang: String, text: String, langs: Set<String>) {
        val names = langs.joinToString(", ") { languageName(it, lang) }
        vm.showDialog(
            DialogSpec(
                title = UiText.res(R.string.translate_model_title),
                message = UiText.res(
                    R.string.translate_model_msg,
                    names,
                    TranslationCache.MODEL_MB * langs.size,
                    UiText.res(if (wifiOnly) R.string.translate_model_wifi else R.string.translate_model_any),
                ),
                confirm = DialogButton(UiText.res(R.string.translate_download)) { download(key, from, lang, text) },
                dismiss = DialogButton(UiText.res(R.string.cancel)) {},
            ),
        )
    }

    private fun download(key: String, from: String, lang: String, text: String) {
        if (wifiOnly && !onUnmeteredNetwork(vm.app)) {
            vm.showToast(UiText.res(R.string.translate_needs_wifi))
            return
        }
        vm.viewModelScope.launch {
            busy = key
            val result = cache.translate(key, lang, from, text, allowDownload = true, wifiOnly = wifiOnly)
            busy = null
            refreshModels()
            when (result) {
                is TranslationResult.Done -> translated[slot(key, lang, text)] = result.text
                TranslationResult.Unsupported -> vm.showToast(UiText.res(R.string.translate_unsupported))
                else -> vm.showToast(UiText.res(R.string.translate_failed))
            }
        }
    }

    /* ── Ajustes → Traducción ─────────────────────────────────── */

    fun toggleAuto() {
        autoTranslate = !autoTranslate
        store.autoTranslate = autoTranslate
    }

    fun toggleWifiOnly() {
        wifiOnly = !wifiOnly
        store.translateWifiOnly = wifiOnly
    }

    fun refreshModels() {
        vm.viewModelScope.launch {
            models = runCatching { cache.downloadedModels() }.getOrDefault(emptySet()).sorted()
        }
    }

    fun deleteModel(lang: String) {
        vm.viewModelScope.launch {
            runCatching { cache.deleteModel(lang) }
            refreshModels()
        }
    }

    companion object {
        /** "en" → "Inglés" (en el idioma de la app). */
        fun languageName(code: String, appLang: String): String {
            val loc = Locale.forLanguageTag(appLang)
            return Locale.forLanguageTag(code).getDisplayLanguage(loc).replaceFirstChar { it.titlecase(loc) }
        }

        private fun onUnmeteredNetwork(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }
}

/**
 * La descripción de un juego para la pantalla: la del idioma de la app o,
 * si no hay, otra con su etiqueta (y su traducción, si se pidió). Null =
 * sin descripción: quien llama no deja hueco.
 */
@Composable
fun rememberDescription(vm: ElyndraViewModel, key: String?, meta: GameMeta?): DescriptionView? {
    val lang = vm.settings.lang
    if (key == null || meta == null) return null
    val c = vm.descriptions
    LaunchedEffect(key, meta.descriptions, meta.description, lang, c.autoTranslate) { c.load(key, meta, lang) }
    return c.view(key, meta, lang)
}

/**
 * La sinopsis recortada para el hero; si no está en el idioma de la app, con
 * su idioma al final ("… · Inglés"), para que no parezca un fallo.
 */
@Composable
fun heroDescription(d: DescriptionView, appLang: String): String {
    val short = com.elyndra.launcher.core.text.TextTrim.shorten(d.text)
    val label = when {
        d.translated -> androidx.compose.ui.res.stringResource(
            R.string.description_translated_from,
            DescriptionsController.languageName(d.originalLang ?: appLang, appLang),
        )
        d.foreign && d.lang != null -> DescriptionsController.languageName(d.lang, appLang)
        else -> return short
    }
    return "$short · $label"
}
