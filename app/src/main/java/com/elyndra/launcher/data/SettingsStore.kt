package com.elyndra.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Ajustes no sensibles (aspecto, opciones de metadatos, sesión de juego en curso). */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Acento guardado; sin guardar (instalación nueva), el de partida (ver Palettes). */
    var accentId: String
        get() = Palettes.accentId(prefs.getString("accent", null), ACCENTS.map { it.id })
        set(v) = prefs.edit { putString("accent", v) }

    var tintId: String
        get() = Palettes.tintId(prefs.getString("tint", null), TINTS.map { it.id })
        set(v) = prefs.edit { putString("tint", v) }

    var blur: Int
        get() = prefs.getInt("blur", Palettes.DEFAULT_BLUR)
        set(v) = prefs.edit { putInt("blur", v) }

    var alphaPct: Int
        get() = prefs.getInt("alpha", Palettes.DEFAULT_ALPHA)
        set(v) = prefs.edit { putInt("alpha", v) }

    var scrimPct: Int
        get() = prefs.getInt("scrim", Palettes.DEFAULT_SCRIM)
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
    /** Fuentes sin clave (libretro, Steam): encendidas de serie. */
    fun keylessEnabled(id: String): Boolean = prefs.getBoolean("keyless.$id", true)

    fun setKeylessEnabled(id: String, enabled: Boolean) = prefs.edit { putBoolean("keyless.$id", enabled) }

    /** Traducir sola la descripción cuando no está en el idioma de la app (solo con modelos ya bajados). */
    var autoTranslate: Boolean
        get() = prefs.getBoolean("translate.auto", false)
        set(v) = prefs.edit { putBoolean("translate.auto", v) }

    /** Bajar los modelos de traducción solo por Wi-Fi. */
    var translateWifiOnly: Boolean
        get() = prefs.getBoolean("translate.wifiOnly", true)
        set(v) = prefs.edit { putBoolean("translate.wifiOnly", v) }

    /** Píldora de hora y batería en Biblioteca y Carpeta. */
    var statusVisible: Boolean
        get() = prefs.getBoolean("status.visible", true)
        set(v) = prefs.edit { putBoolean("status.visible", v) }

    /** Qué enseña la píldora: "both", "time" o "battery" (ver StatusMode). */
    var statusMode: String?
        get() = prefs.getString("status.mode", null)
        set(v) = prefs.edit { putString("status.mode", v) }

    var darkMode: Boolean
        // Claro es el de partida; lo guardado manda.
        get() = prefs.getBoolean("darkMode", Palettes.DEFAULT_DARK)
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

    /**
     * Color de las partículas fosforescentes que deja Masha al arrastrarla
     * (ARGB). Por omisión, un cian de fósforo de monitor CRT.
     */
    var mashaParticleColor: Int
        get() = prefs.getInt("masha.particleColor", DEFAULT_PARTICLE_COLOR)
        set(v) = prefs.edit { putInt("masha.particleColor", v) }

    /** Partículas de neón que caen alrededor del icono o la carátula seleccionados. */
    var selectionParticles: Boolean
        get() = prefs.getBoolean("selection.particles", true)
        set(v) = prefs.edit { putBoolean("selection.particles", v) }

    /** Color (ARGB) de esas partículas. Por omisión, un magenta de neón. */
    var selectionParticleColor: Int
        get() = prefs.getInt("selection.particleColor", DEFAULT_SELECTION_PARTICLE_COLOR)
        set(v) = prefs.edit { putInt("selection.particleColor", v) }

    /* ── Masha: voz y ambiente sonoro ─────────────────────────── */

    /** Masha lee sus respuestas en voz alta en su pantalla. */
    var mashaVoice: Boolean
        get() = prefs.getBoolean("masha.voice", true)
        set(v) = prefs.edit { putBoolean("masha.voice", v) }

    /** Voz natural (Supertonic, en el móvil) cuando está descargada; apagada = siempre la del sistema. */
    var mashaVoiceNatural: Boolean
        get() = prefs.getBoolean("masha.voice.natural", true)
        set(v) = prefs.edit { putBoolean("masha.voice.natural", v) }

    /** Cuál de las cinco voces femeninas de la voz natural (0–4). */
    var mashaVoiceSpeaker: Int
        get() = prefs.getInt("masha.voice.speaker", 0).coerceIn(0, 4)
        set(v) = prefs.edit { putInt("masha.voice.speaker", v.coerceIn(0, 4)) }

    /** Velocidad de la voz (0,8–1,25; 1 = normal), para las dos voces. */
    var mashaVoiceRate: Float
        get() = prefs.getFloat("masha.voice.rate", 1f).coerceIn(0.8f, 1.25f)
        set(v) = prefs.edit { putFloat("masha.voice.rate", v.coerceIn(0.8f, 1.25f)) }

    /**
     * Masha a 120 Hz en pantallas que lo admiten. Apagado (por defecto), su pantalla
     * pide 60 Hz: el holograma se ve igual de fluido a 60 fps y la GPU trabaja la mitad
     * (menos batería y calor en conversaciones largas).
     */
    var mashaHighRefresh: Boolean
        get() = prefs.getBoolean("masha.highRefresh", false)
        set(v) = prefs.edit { putBoolean("masha.highRefresh", v) }

    /** El ambiente sonoro del holotanque (zumbido, pads, datos) en la pantalla de Masha. */
    var mashaSoundscape: Boolean
        get() = prefs.getBoolean("masha.soundscape", true)
        set(v) = prefs.edit { putBoolean("masha.soundscape", v) }

    /** Volumen del ambiente (0–100), siempre por debajo de la voz. */
    var mashaSoundscapeVolume: Int
        get() = prefs.getInt("masha.soundscapeVolume", 45)
        set(v) = prefs.edit { putInt("masha.soundscapeVolume", v) }

    /* ── pantalla ─────────────────────────────────────────────── */

    /**
     * Fotogramas por segundo pedidos a la pantalla: 120, 60 o 0 = automático
     * (120 si la pantalla lo admite). Ver [com.elyndra.launcher.display.FrameRate].
     */
    var frameRate: Int
        get() = prefs.getInt("display.fps", 0)
        set(v) = prefs.edit { putInt("display.fps", v) }

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

    /* ── opciones de desarrollador ────────────────────────────── */

    /** Opciones de desarrollador desbloqueadas (siete toques en la versión, en Ajustes → Acerca de). */
    var developerOptions: Boolean
        get() = prefs.getBoolean("dev.unlocked", false)
        set(v) = prefs.edit { putBoolean("dev.unlocked", v) }

    /**
     * Ajuste de sincronía de la voz (ms) por salida de audio, con las claves de
     * [com.elyndra.launcher.ui.masha.lipsync.AudioRouteOffsets] (`speaker`, `wired`, `bt:…`, `bt`).
     */
    fun voiceOffsets(): Map<String, Int> = prefs.all.mapNotNull { (k, v) ->
        if (k.startsWith(VOICE_OFFSET) && v is Int) k.removePrefix(VOICE_OFFSET) to v else null
    }.toMap()

    fun setVoiceOffset(routeKey: String, ms: Int?) =
        prefs.edit { if (ms == null) remove(VOICE_OFFSET + routeKey) else putInt(VOICE_OFFSET + routeKey, ms) }

    companion object {
        private const val VOICE_OFFSET = "voice.offset."

        /** Cian de fósforo: se ve bien sobre el tema claro y sobre el oscuro. */
        const val DEFAULT_PARTICLE_COLOR = 0xFF5CF2FF.toInt()

        /** El mismo cian de fósforo, el primero de la paleta de partículas. */
        const val DEFAULT_SELECTION_PARTICLE_COLOR = DEFAULT_PARTICLE_COLOR

        /*
         * Las claves siguen diciendo "lucy" a propósito, como las de "videoBg":
         * la asistente se llamaba así, y renombrarlas devolvería el botón a su
         * esquina a quien ya lo había movido. Cambia el nombre, no dónde se guarda.
         */
        private const val MASHA_X = "lucy.x"
        private const val MASHA_Y = "lucy.y"
    }
}
