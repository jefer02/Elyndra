package com.elyndra.launcher.ui

import androidx.annotation.StringRes
import com.elyndra.launcher.R
import com.elyndra.launcher.ui.components.ConsoleGlyph

/** Las páginas de Ajustes, en el orden del raíl. */
enum class SettingsCategory(@StringRes val title: Int, @StringRes val description: Int, val glyph: ConsoleGlyph) {
    Display(R.string.settings_cat_display, R.string.settings_cat_display_desc, ConsoleGlyph.Display),
    Sound(R.string.settings_cat_sound, R.string.settings_cat_sound_desc, ConsoleGlyph.Sound),
    Music(R.string.settings_cat_music, R.string.settings_cat_music_desc, ConsoleGlyph.Music),
    Appearance(R.string.settings_cat_appearance, R.string.settings_cat_appearance_desc, ConsoleGlyph.Appearance),
    Library(R.string.settings_cat_library, R.string.settings_cat_library_desc, ConsoleGlyph.Library),
    Metadata(R.string.settings_cat_metadata, R.string.settings_cat_metadata_desc, ConsoleGlyph.Metadata),
    Masha(R.string.settings_cat_masha, R.string.settings_cat_masha_desc, ConsoleGlyph.Masha),
    About(R.string.settings_cat_about, R.string.settings_cat_about_desc, ConsoleGlyph.About),
}

/** Cómo se recorre Ajustes. Kotlin puro (se prueba en la JVM). */
object SettingsNav {

    /** A partir de este ancho de ventana (dp), raíl de categorías y panel; por debajo, lista y páginas. */
    const val WIDE_MIN_DP = 600f

    fun isWide(widthDp: Float): Boolean = widthDp >= WIDE_MIN_DP

    /** LB/RB: la categoría anterior o la siguiente, dando la vuelta como las pestañas de una consola. */
    fun step(current: SettingsCategory, delta: Int): SettingsCategory {
        val all = SettingsCategory.entries
        return all[(all.indexOf(current) + delta).mod(all.size)]
    }
}
