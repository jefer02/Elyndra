package com.elyndra.launcher.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * Idioma de la app, independiente del del sistema.
 *
 * Android 13+ tiene idioma por aplicación nativo (LocaleManager): el sistema
 * lo guarda, lo aplica a todos los componentes y lo muestra en Ajustes del
 * sistema (res/xml/locales_config.xml). En versiones anteriores se guarda en
 * preferencias y cada Activity/Service envuelve su contexto con [wrap].
 */
object AppLocale {

    /** Etiqueta BCP-47 → nombre nativo, en el orden de las píldoras del diseño. */
    val SUPPORTED: List<Pair<String, String>> = listOf(
        "es" to "Español",
        "en" to "English",
        "pt" to "Português",
        "fr" to "Français",
        "de" to "Deutsch",
        "ja" to "日本語",
    )

    private const val PREFS = "locale"
    private const val KEY = "tag"

    private fun isSupported(tag: String?) = tag != null && SUPPORTED.any { it.first == tag }

    /** Idioma efectivo: el elegido en la app o, si no hay, el del sistema (o inglés). */
    fun current(context: Context): String {
        val chosen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeIf { !it.isEmpty }
                ?.get(0)?.language
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }
        if (isSupported(chosen)) return chosen!!
        val system = Resources.getSystem().configuration.locales[0].language
        return if (isSupported(system)) system else "en"
    }

    /** Cambia el idioma; la Activity se recrea para aplicarlo. */
    fun set(activity: Activity, tag: String) {
        if (!isSupported(tag)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY, tag) }
            activity.recreate()
        }
    }

    /** Contexto con el idioma elegido (solo hace falta antes de Android 13). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (!isSupported(tag)) return base
        val locale = Locale.forLanguageTag(tag!!)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
