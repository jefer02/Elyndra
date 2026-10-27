package com.elyndra.launcher.data

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La píldora de hora y batería: apagada en una instalación nueva; lo que el usuario guardó, se respeta. */
class SettingsStoreStatusTest {

    @Test
    fun `on a fresh install the time and battery pill is off`() {
        assertFalse(SettingsStore.DEFAULT_STATUS_VISIBLE)
        assertFalse(SettingsStore(MemoryPrefs()).statusVisible)
    }

    @Test
    fun `a stored choice wins over the default, on or off`() {
        assertTrue(SettingsStore(MemoryPrefs(mutableMapOf("status.visible" to true))).statusVisible)
        assertFalse(SettingsStore(MemoryPrefs(mutableMapOf("status.visible" to false))).statusVisible)
    }

    @Test
    fun `reading the default writes nothing, and switching it on is stored`() {
        val prefs = MemoryPrefs()
        val store = SettingsStore(prefs)
        assertFalse(store.statusVisible)
        assertFalse(prefs.contains("status.visible"))
        store.statusVisible = true
        assertTrue(store.statusVisible)
        assertTrue(SettingsStore(prefs).statusVisible)
        store.statusVisible = false
        assertFalse(SettingsStore(prefs).statusVisible)
    }
}

/** SharedPreferences en memoria: lo justo para leer y escribir ajustes sin Android. */
private class MemoryPrefs(private val values: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removed.forEach(values::remove)
            values.putAll(pending)
            return true
        }
        override fun apply() { commit() }
    }
}
