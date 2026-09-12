package com.elyndra.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Ajustes no sensibles (aspecto, opciones de metadatos, sesión de juego en curso). */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var accentId: String
        get() = prefs.getString("accent", "mandarina") ?: "mandarina"
        set(v) = prefs.edit { putString("accent", v) }

    var tintId: String
        get() = prefs.getString("tint", "papel") ?: "papel"
        set(v) = prefs.edit { putString("tint", v) }

    var blur: Int
        get() = prefs.getInt("blur", 16)
        set(v) = prefs.edit { putInt("blur", v) }

    var alphaPct: Int
        get() = prefs.getInt("alpha", 55)
        set(v) = prefs.edit { putInt("alpha", v) }

    var scrimPct: Int
        get() = prefs.getInt("scrim", 62)
        set(v) = prefs.edit { putInt("scrim", v) }

    /** Descargar metadatos en cuanto se añade algo a la biblioteca. */
    var autoMeta: Boolean
        get() = prefs.getBoolean("autoMeta", true)
        set(v) = prefs.edit { putBoolean("autoMeta", v) }

    /** Tamaño máximo (MB) de archivo para calcular CRC/MD5/SHA1 al identificar ROMs. */
    var hashLimitMb: Int
        get() = prefs.getInt("hashLimitMb", 256)
        set(v) = prefs.edit { putInt("hashLimitMb", v) }

    /** Credenciales comprobadas con éxito (se invalida al editarlas). */
    fun isVerified(service: String): Boolean = prefs.getBoolean("verified.$service", false)

    fun setVerified(service: String, verified: Boolean) = prefs.edit { putBoolean("verified.$service", verified) }

    /** Caducidad (epoch ms) del token de IGDB guardado en SecretStore. */
    var igdbTokenExpiry: Long
        get() = prefs.getLong("igdb.tokenExpiry", 0L)
        set(v) = prefs.edit { putLong("igdb.tokenExpiry", v) }

    /** Juego lanzado cuyo tiempo se mide al volver a Elyndra. */
    var pendingSessionKey: String?
        get() = prefs.getString("session.key", null)
        set(v) = prefs.edit { if (v == null) remove("session.key") else putString("session.key", v) }

    var pendingSessionStart: Long
        get() = prefs.getLong("session.start", 0L)
        set(v) = prefs.edit { putLong("session.start", v) }

    /* ── tema y fondo ─────────────────────────────────────────── */

    /** Modo oscuro de la interfaz (independiente del tema del sistema). */
    var darkMode: Boolean
        get() = prefs.getBoolean("darkMode", false)
        set(v) = prefs.edit { putBoolean("darkMode", v) }

    /** URI (SAF, con permiso persistente) del vídeo de fondo de la interfaz. */
    var videoBgUri: String?
        get() = prefs.getString("videoBg.uri", null)
        set(v) = prefs.edit { if (v == null) remove("videoBg.uri") else putString("videoBg.uri", v) }

    /** El vídeo de fondo se pinta solo si además está activado. */
    var videoBgEnabled: Boolean
        get() = prefs.getBoolean("videoBg.enabled", false)
        set(v) = prefs.edit { putBoolean("videoBg.enabled", v) }

    /** Opacidad (%) con la que se mezcla el vídeo sobre el fondo. */
    var videoBgOpacity: Int
        get() = prefs.getInt("videoBg.opacity", 45)
        set(v) = prefs.edit { putInt("videoBg.opacity", v) }

    /** Criterio de orden de la biblioteca (id de [com.elyndra.launcher.ui.SortMode]). */
    var sortMode: String
        get() = prefs.getString("sortMode", "name") ?: "name"
        set(v) = prefs.edit { putString("sortMode", v) }
}
