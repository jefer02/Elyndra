package com.elyndra.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Ajustes no sensibles (aspecto, opciones de metadatos, sesión de juego en curso). */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var accentId: String
        get() = prefs.getString("accent", "lila") ?: "lila"
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

    /**
     * Paquete elegido a mano para un emulador que se instala con varios
     * (BannerHub y compañía). Null = detectarlo solo, que es lo normal.
     *
     * Hace falta porque varias builds se instalan bajo paquetes de otras apps
     * y puede haber más de una a la vez: sin esto se lanzaría siempre la
     * primera que se encuentre, que no tiene por qué ser la del usuario.
     */
    fun preferredPackage(emulatorId: String): String? = prefs.getString("pkg.$emulatorId", null)

    fun setPreferredPackage(emulatorId: String, pkg: String?) =
        prefs.edit { if (pkg == null) remove("pkg.$emulatorId") else putString("pkg.$emulatorId", pkg) }

    /** Credenciales comprobadas con éxito (se invalida al editarlas). */
    fun isVerified(service: String): Boolean = prefs.getBoolean("verified.$service", false)

    fun setVerified(service: String, verified: Boolean) = prefs.edit { putBoolean("verified.$service", verified) }

    /** Caducidad (epoch ms) del token de IGDB guardado en SecretStore. */
    var igdbTokenExpiry: Long
        get() = prefs.getLong("igdb.tokenExpiry", 0L)
        set(v) = prefs.edit { putLong("igdb.tokenExpiry", v) }

    /**
     * Orden de fuentes para textos e imágenes ("ss,igdb,ra,sgdb"). Null = el de
     * siempre (ver MetadataPriority.DEFAULT).
     */
    var metaPriorityText: String?
        get() = prefs.getString("meta.priority.text", null)
        set(v) = prefs.edit { if (v == null) remove("meta.priority.text") else putString("meta.priority.text", v) }

    var metaPriorityArt: String?
        get() = prefs.getString("meta.priority.art", null)
        set(v) = prefs.edit { if (v == null) remove("meta.priority.art") else putString("meta.priority.art", v) }

    /** Juego lanzado cuyo tiempo se mide al volver a Elyndra. */
    var pendingSessionKey: String?
        get() = prefs.getString("session.key", null)
        set(v) = prefs.edit { if (v == null) remove("session.key") else putString("session.key", v) }

    var pendingSessionStart: Long
        get() = prefs.getLong("session.start", 0L)
        set(v) = prefs.edit { putLong("session.start", v) }

    /** Emulador de la sesión pendiente (null en apps Android). */
    var pendingSessionEmulator: String?
        get() = prefs.getString("session.emulator", null)
        set(v) = prefs.edit { if (v == null) remove("session.emulator") else putString("session.emulator", v) }

    /** Paquete que se lanzó: es el que se busca en UsageStatsManager para medir el tiempo real. */
    var pendingSessionPackage: String?
        get() = prefs.getString("session.package", null)
        set(v) = prefs.edit { if (v == null) remove("session.package") else putString("session.package", v) }

    /* ── Masha ────────────────────────────────────────────────── */

    /**
     * Masha habla con la IA en línea (DeepSeek). Apagado, sigue funcionando
     * entera sin conexión: planes, listas, lanzamientos y estadísticas salen
     * de los datos locales; solo se pierde la conversación libre.
     */
    var mashaOnline: Boolean
        get() = prefs.getBoolean("masha.online", true)
        set(v) = prefs.edit { putBoolean("masha.online", v) }

    /** La línea de Masha sobre el carrusel (sugerencias ambientales). */
    var mashaAmbient: Boolean
        get() = prefs.getBoolean("masha.ambient", true)
        set(v) = prefs.edit { putBoolean("masha.ambient", v) }

    /** Avisos de Masha fuera de la app (como mucho uno cada pocos días, nunca de noche). */
    var mashaNudges: Boolean
        get() = prefs.getBoolean("masha.nudges", true)
        set(v) = prefs.edit { putBoolean("masha.nudges", v) }

    /** Último aviso enviado (epoch ms), para no repetirse. */
    var mashaLastNudgeAt: Long
        get() = prefs.getLong("masha.lastNudgeAt", 0L)
        set(v) = prefs.edit { putLong("masha.lastNudgeAt", v) }

    /** Huella del último aviso: el mismo consejo no se manda dos veces seguidas. */
    var mashaLastNudgeId: String?
        get() = prefs.getString("masha.lastNudgeId", null)
        set(v) = prefs.edit { if (v == null) remove("masha.lastNudgeId") else putString("masha.lastNudgeId", v) }

    /** Sugerencias descartadas ("día|id"): solo valen las de hoy, las viejas se limpian solas. */
    var mashaDismissed: Set<String>
        get() = prefs.getStringSet("masha.dismissed", emptySet()).orEmpty().toSet()
        set(v) = prefs.edit { putStringSet("masha.dismissed", v) }

    /** Última vez que se abrió Elyndra: quien acaba de estar dentro no necesita un aviso. */
    var lastOpenedAt: Long
        get() = prefs.getLong("app.lastOpenedAt", 0L)
        set(v) = prefs.edit { putLong("app.lastOpenedAt", v) }

    /* ── tema y fondo ─────────────────────────────────────────── */

    /** Modo oscuro de la interfaz (independiente del tema del sistema). */
    var darkMode: Boolean
        get() = prefs.getBoolean("darkMode", false)
        set(v) = prefs.edit { putBoolean("darkMode", v) }

    /**
     * URI (SAF, con permiso persistente) del fondo de la interfaz.
     *
     * Las claves siguen diciendo "videoBg" a propósito: el ajuste empezó
     * admitiendo solo vídeo y renombrarlas dejaría sin fondo a quien ya tenía
     * uno puesto. Lo que cambia es qué se acepta, no dónde se guarda.
     */
    var backgroundUri: String?
        get() = prefs.getString("videoBg.uri", null)
        set(v) = prefs.edit { if (v == null) remove("videoBg.uri") else putString("videoBg.uri", v) }

    /**
     * Si el fondo elegido es un vídeo. Falso = imagen fija.
     *
     * Por omisión es `true`: lo guardado antes de admitir imágenes solo podía
     * ser un vídeo, así que esa preferencia se sigue leyendo bien.
     */
    var backgroundIsVideo: Boolean
        get() = prefs.getBoolean("videoBg.isVideo", true)
        set(v) = prefs.edit { putBoolean("videoBg.isVideo", v) }

    /** El fondo se pinta solo si además está activado. */
    var backgroundEnabled: Boolean
        get() = prefs.getBoolean("videoBg.enabled", false)
        set(v) = prefs.edit { putBoolean("videoBg.enabled", v) }

    /** Opacidad (%) con la que se mezcla el fondo sobre el papel. */
    var backgroundOpacity: Int
        get() = prefs.getInt("videoBg.opacity", 45)
        set(v) = prefs.edit { putInt("videoBg.opacity", v) }

    /* ── botón de Masha ───────────────────────────────────────── */

    /**
     * Dónde dejó el usuario el botón de Masha: dp desde la esquina superior
     * izquierda del espacio útil. Sin valor = su esquina de siempre (abajo a
     * la derecha), así que se guarda como par y se lee como par.
     *
     * Se guarda en dp y no en fracción de pantalla porque el botón mide dp:
     * al girar el móvil se recorta contra el nuevo tamaño (ver LibraryScreen)
     * y así conserva la distancia al borde en vez de saltar.
     */
    var mashaX: Float?
        get() = if (prefs.contains(MASHA_X)) prefs.getFloat(MASHA_X, 0f) else null
        set(v) = prefs.edit { if (v == null) remove(MASHA_X) else putFloat(MASHA_X, v) }

    var mashaY: Float?
        get() = if (prefs.contains(MASHA_Y)) prefs.getFloat(MASHA_Y, 0f) else null
        set(v) = prefs.edit { if (v == null) remove(MASHA_Y) else putFloat(MASHA_Y, v) }

    /** Criterio de orden de la biblioteca (id de [com.elyndra.launcher.ui.SortMode]). */
    var sortMode: String
        get() = prefs.getString("sortMode", "name") ?: "name"
        set(v) = prefs.edit { putString("sortMode", v) }

    /**
     * Ya se ha pedido el permiso de notificaciones alguna vez.
     *
     * Android solo enseña el diálogo las primeras veces: si se vuelve a pedir
     * después de dos negativas se deniega solo, sin que el usuario vea nada.
     * Se pregunta una vez, al empezar, y a partir de ahí se ofrece desde
     * Ajustes cuando hace falta de verdad.
     */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean("perm.notificationsAsked", false)
        set(v) = prefs.edit { putBoolean("perm.notificationsAsked", v) }

    private companion object {
        /*
         * Las claves siguen diciendo "lucy" a propósito, como las de "videoBg":
         * la asistente se llamaba así, y renombrarlas devolvería el botón a su
         * esquina a quien ya lo había movido. Cambia el nombre, no dónde se guarda.
         */
        const val MASHA_X = "lucy.x"
        const val MASHA_Y = "lucy.y"
    }
}
