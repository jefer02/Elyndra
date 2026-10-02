package com.elyndra.launcher.sound

import android.content.SharedPreferences
import androidx.core.content.edit

/** Lo mínimo de un almacén clave-valor: en la app, SharedPreferences; en las pruebas, un mapa. */
interface KeyValues {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getString(key: String): String?
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putString(key: String, value: String?)
}

class PrefsKeyValues(private val prefs: SharedPreferences) : KeyValues {
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
    override fun putInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
    override fun putString(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }
}

/**
 * Los ajustes de sonido: interruptor general, volumen, sonido al navegar,
 * paquete y los sonidos propios por evento (nombre del archivo copiado en
 * `filesDir/sounds`; null = el del paquete).
 */
class SoundSettings(private val kv: KeyValues) {

    var enabled: Boolean
        get() = kv.getBoolean("sound.enabled", DEFAULT_ENABLED)
        set(v) = kv.putBoolean("sound.enabled", v)

    /** 0…100. */
    var volume: Int
        get() = kv.getInt("sound.volume", DEFAULT_VOLUME).coerceIn(0, 100)
        set(v) = kv.putInt("sound.volume", v.coerceIn(0, 100))

    var navigation: Boolean
        get() = kv.getBoolean("sound.navigation", DEFAULT_NAVIGATION)
        set(v) = kv.putBoolean("sound.navigation", v)

    var pack: SoundPack
        get() = SoundPack.byId(kv.getString("sound.pack"))
        set(v) = kv.putString("sound.pack", v.id)

    /** Música de fondo de la interfaz. */
    var musicEnabled: Boolean
        get() = kv.getBoolean("music.enabled", DEFAULT_MUSIC_ENABLED)
        set(v) = kv.putBoolean("music.enabled", v)

    /** 0…100. */
    var musicVolume: Int
        get() = kv.getInt("music.volume", DEFAULT_MUSIC_VOLUME).coerceIn(0, 100)
        set(v) = kv.putInt("music.volume", v.coerceIn(0, 100))

    /** Audio elegido por el usuario (URI de SAF con permiso persistente); null = el de Elyndra. */
    var musicUri: String?
        get() = kv.getString("music.uri")
        set(v) = kv.putString("music.uri", v)

    fun custom(sound: UiSound): String? = kv.getString("sound.custom.${sound.id}")

    fun setCustom(sound: UiSound, file: String?) = kv.putString("sound.custom.${sound.id}", file)

    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_VOLUME = 60
        const val DEFAULT_NAVIGATION = true
        const val DEFAULT_MUSIC_ENABLED = true
        const val DEFAULT_MUSIC_VOLUME = 40
    }
}
