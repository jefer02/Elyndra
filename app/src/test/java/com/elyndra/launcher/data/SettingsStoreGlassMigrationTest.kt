package com.elyndra.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La migración del cristal (versión 2): lo guardado igual al valor por defecto
 * de antes cuenta como "sin elegir"; lo que el usuario cambió se respeta; y
 * solo corre una vez.
 */
class SettingsStoreGlassMigrationTest {

    private fun store(vararg values: Pair<String, Any?>): Pair<SettingsStore, MemoryPrefs> {
        val prefs = MemoryPrefs(mutableMapOf(*values))
        return SettingsStore(prefs) to prefs
    }

    @Test
    fun `a fresh install gets glass in both themes and the default blur, and is marked migrated`() {
        val (s, prefs) = store()
        assertNull(s.meridianWheelGlass)
        assertEquals(Palettes.DEFAULT_BLUR, s.blur)
        assertEquals(SettingsStore.GLASS_VERSION, prefs.getInt(SettingsStore.GLASS_VERSION_KEY, 0))
    }

    @Test
    fun `the blur wash saved in dark theme was the old default, so it becomes unset (glass)`() {
        val (s, prefs) = store("darkMode" to true, SettingsStore.WHEEL_GLASS_KEY to false)
        assertNull(s.meridianWheelGlass)
        assertFalse(prefs.contains(SettingsStore.WHEEL_GLASS_KEY))
    }

    @Test
    fun `glass saved in light theme was the old default too`() {
        val (s, _) = store("darkMode" to false, SettingsStore.WHEEL_GLASS_KEY to true)
        assertNull(s.meridianWheelGlass)
    }

    @Test
    fun `a backdrop that differs from its theme's old default was a real choice and stays`() {
        // Difuminado en claro y cristal en oscuro: nadie llega ahí sin tocar el ajuste.
        assertEquals(false, store("darkMode" to false, SettingsStore.WHEEL_GLASS_KEY to false).first.meridianWheelGlass)
        assertEquals(true, store("darkMode" to true, SettingsStore.WHEEL_GLASS_KEY to true).first.meridianWheelGlass)
    }

    @Test
    fun `the blur setting at its old default is cleared, a customized one is kept`() {
        val (cleared, prefs) = store("blur" to SettingsStore.LEGACY_DEFAULT_BLUR)
        assertFalse(prefs.contains("blur"))
        assertEquals(Palettes.DEFAULT_BLUR, cleared.blur)
        for (custom in listOf(0, 6, 30, 40)) assertEquals(custom, store("blur" to custom).first.blur)
    }

    @Test
    fun `it runs only once, so a choice made after the migration is never touched again`() {
        val (s, prefs) = store("darkMode" to true, SettingsStore.WHEEL_GLASS_KEY to false)
        assertNull(s.meridianWheelGlass)
        // El usuario vuelve a elegir el difuminado en oscuro, a propósito.
        s.meridianWheelGlass = false
        s.blur = SettingsStore.LEGACY_DEFAULT_BLUR
        val again = SettingsStore(prefs)
        assertEquals(false, again.meridianWheelGlass)
        assertTrue(prefs.contains("blur"))
        assertEquals(SettingsStore.LEGACY_DEFAULT_BLUR, again.blur)
    }

    @Test
    fun `other settings are left alone`() {
        val (s, _) = store("darkMode" to true, "status.visible" to true, "blur" to 30)
        assertTrue(s.darkMode)
        assertTrue(s.statusVisible)
        assertEquals(30, s.blur)
    }
}
