package com.elyndra.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.elyndra.launcher.core.device.StutterMemory
import com.elyndra.launcher.ports.PortCatalogRepository
import com.elyndra.launcher.ui.meridian.WheelBackdrop

/** Ajustes no sensibles (aspecto, opciones de metadatos, sesión de juego en curso). */
class SettingsStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    init {
        migrateGlass()
        migrateLucyChat()
    }

    /**
     * Una vez por instalación (v4): el chat de Lucy vuelve a oculto. En
     * versiones anteriores quedó guardado a la vista (al tocar su botón o X
     * en el mando) y la pantalla dejaba de ser de ella; desde aquí, lo que el
     * usuario elija se respeta.
     */
    internal fun migrateLucyChat() {
        if (prefs.getBoolean(LUCY_CHAT_RESET_KEY, false)) return
        prefs.edit {
            remove("lucy.chatVisible")
            putBoolean(LUCY_CHAT_RESET_KEY, true)
        }
    }

    /**
     * Una vez por instalación (versión [GLASS_VERSION] en [GLASS_VERSION_KEY]):
     * lo guardado que es igual al valor por defecto de antes se trata como
     * "sin elegir", para que el cristal nuevo llegue también a quien ya tenía
     * la app. Lo que el usuario cambió de verdad se respeta.
     *
     * - "meridian.wheelGlass": guardado igual a lo que su tema daba sin
     *   elegir (cristal en claro, difuminado en oscuro) no se distingue de no
     *   haber elegido, y se borra (ahora el cristal es el de los dos temas).
     *   Distinto (difuminado en claro, cristal en oscuro) se eligió: se queda.
     * - "blur": guardado en el valor por defecto (18) se borra; otro valor se
     *   eligió con el control y se queda (escala el cristal desde el nuevo).
     */
    internal fun migrateGlass() {
        if (prefs.getInt(GLASS_VERSION_KEY, 0) >= GLASS_VERSION) return
        val dark = prefs.getBoolean("darkMode", Palettes.DEFAULT_DARK)
        prefs.edit {
            if (prefs.contains(WHEEL_GLASS_KEY) && prefs.getBoolean(WHEEL_GLASS_KEY, true) == WheelBackdrop.legacyDefault(dark)) remove(WHEEL_GLASS_KEY)
            if (prefs.contains("blur") && prefs.getInt("blur", Palettes.DEFAULT_BLUR) == LEGACY_DEFAULT_BLUR) remove("blur")
            putInt(GLASS_VERSION_KEY, GLASS_VERSION)
        }
    }

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

    /** Hasta qué cambio de serie del orden de imágenes se ha pasado ya lo guardado (ver MetadataPriorityStore). */
    var metaPriorityArtVersion: Int
        get() = prefs.getInt("meta.priority.art.v", 0)
        set(v) = prefs.edit { putInt("meta.priority.art.v", v) }

    /** Por dónde va la comprobación diaria de los ports "aún no en Android" (ver PortAvailability.batch). */
    var portsAvailabilityCursor: Int
        get() = prefs.getInt("ports.availability.cursor", 0)
        set(v) = prefs.edit { putInt("ports.availability.cursor", v) }

    /** La pasada única que mejora el arte de la biblioteca con el orden nuevo ya terminó (ver ArtUpgradeWorker). */
    var artUpgradeDone: Boolean
        get() = prefs.getBoolean("art.upgrade.done", false)
        set(v) = prefs.edit { putBoolean("art.upgrade.done", v) }

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

    /**
     * Avisos de Lucy fuera de la app (nota diaria, calor, juegos nuevos,
     * metadatos). Cada tipo tiene además su canal en Android; cuándo sale cada
     * uno lo decide NotificationPolicy.
     */
    var mashaNudges: Boolean
        get() = prefs.getBoolean("masha.nudges", true)
        set(v) = prefs.edit { putBoolean("masha.nudges", v) }

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

    /**
     * Bajar los paquetes de traducción también con datos móviles (apagado de
     * serie: solo por Wi-Fi). Hereda el antiguo "solo Wi-Fi", al revés.
     */
    var translateAllowMobile: Boolean
        get() = prefs.getBoolean("translate.allowMobile", !prefs.getBoolean("translate.wifiOnly", true))
        set(v) = prefs.edit { putBoolean("translate.allowMobile", v).remove("translate.wifiOnly") }

    /** Último idioma de la app visto: al cambiar, se avisa si falta un paquete de traducción. Null = aún ninguno. */
    var translateLastLang: String?
        get() = prefs.getString("translate.lastLang", null)
        set(v) = prefs.edit { putString("translate.lastLang", v) }

    /** Ajustes de traducción que ya no existen ("traducir automáticamente": ahora se traduce siempre que haya paquete). */
    fun dropRetiredTranslationPrefs() {
        if (prefs.contains("translate.auto")) prefs.edit { remove("translate.auto") }
    }

    /** Píldora de hora y batería en Biblioteca y Carpeta; sin guardar (instalación nueva), apagada. Lo guardado manda. */
    var statusVisible: Boolean
        get() = prefs.getBoolean("status.visible", DEFAULT_STATUS_VISIBLE)
        set(v) = prefs.edit { putBoolean("status.visible", v) }

    /** Qué enseña la píldora: "both", "time" o "battery" (ver StatusMode). */
    var statusMode: String?
        get() = prefs.getString("status.mode", null)
        set(v) = prefs.edit { putString("status.mode", v) }

    var darkMode: Boolean
        // Claro es el de partida; lo guardado manda.
        get() = prefs.getBoolean("darkMode", Palettes.DEFAULT_DARK)
        set(v) = prefs.edit { putBoolean("darkMode", v) }

    /** La intro de ELYNDRA en cada arranque en frío. */
    var introEnabled: Boolean
        get() = prefs.getBoolean("intro.enabled", true)
        set(v) = prefs.edit { putBoolean("intro.enabled", v) }

    /** Color de la intro (id de [com.elyndra.launcher.ui.intro.IntroColor]). */
    var introColor: String?
        get() = prefs.getString("intro.color", null)
        set(v) = prefs.edit { putString("intro.color", v) }

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

    /**
     * Halo del marco de la card seleccionada (filo en degradado, barrido de luz,
     * resplandor y luz en el estante). Encendido de serie.
     */
    var selectionGlow: Boolean
        get() = prefs.getBoolean("selection.glow", DEFAULT_SELECTION_GLOW)
        set(v) = prefs.edit { putBoolean("selection.glow", v) }

    /**
     * Polvo estelar alrededor del icono o la carátula seleccionados. Encendido
     * si no hay nada guardado; quien ya lo eligió conserva su valor.
     */
    var selectionParticles: Boolean
        get() = prefs.getBoolean("selection.particles", DEFAULT_SELECTION_PARTICLES)
        set(v) = prefs.edit { putBoolean("selection.particles", v) }

    /** Color (ARGB) de la selección: marco, halo y partículas. Sin nada guardado, el primario de Plasma. */
    var selectionParticleColor: Int
        get() = prefs.getInt("selection.particleColor", DEFAULT_SELECTION_PARTICLE_COLOR)
        set(v) = prefs.edit { putInt("selection.particleColor", v) }

    /**
     * Cómo se ve la lista con la ventana apaisada y ancha: "meridian" (rueda
     * vertical) o "classic" (carrusel). Null = nunca elegido: Meridian (ver
     * `LayoutStyle.byId`), también para quien ya tenía la app instalada.
     */
    var layoutStyle: String?
        get() = prefs.getString("layout.style", null)
        set(v) = prefs.edit { if (v == null) remove("layout.style") else putString("layout.style", v) }

    /**
     * Ajustes → Pantalla → Rendimiento: "auto", "quality" o "performance"
     * (ver `PerformanceTier.Mode`). Clave nueva: sin valor es Auto, así que
     * quien ya tenía la app no nota nada en un equipo capaz.
     */
    var performanceMode: String?
        get() = prefs.getString("perf.mode", null)
        set(v) = prefs.edit { if (v == null) remove("perf.mode") else putString("perf.mode", v) }

    /**
     * En Auto se midieron tirones sostenidos y se pasó a Rendimiento
     * (`JankFallback`). Sin valor, no. Se olvida al elegir Auto otra vez.
     */
    var performanceStutter: Boolean
        get() = prefs.getBoolean("perf.stutter", false)
        set(v) = prefs.edit { if (v) putBoolean("perf.stutter", true) else remove("perf.stutter") }

    /** Ya se avisó de ese paso a Rendimiento (el aviso sale una sola vez por decisión). */
    var performanceStutterNoticed: Boolean
        get() = prefs.getBoolean("perf.stutterNotice", false)
        set(v) = prefs.edit { if (v) putBoolean("perf.stutterNotice", true) else remove("perf.stutterNotice") }

    /** Lo guardado de los tirones, junto (ver `StutterMemory`). */
    var stutterMemory: StutterMemory.Stored
        get() = StutterMemory.Stored(performanceStutter, performanceStutterNoticed)
        set(v) {
            performanceStutter = v.stutter
            performanceStutterNoticed = v.noticeShown
        }

    /** Meridian: el fondo de la rueda toma el color del arte seleccionado. Apagado, el velo neutro del tema. */
    var meridianAdaptiveColor: Boolean
        get() = prefs.getBoolean("meridian.adaptiveColor", DEFAULT_MERIDIAN_ADAPTIVE_COLOR)
        set(v) = prefs.edit { putBoolean("meridian.adaptiveColor", v) }

    /**
     * Meridian: detrás de la rueda, cristal (true) o difuminado (false). Null
     * = nunca elegido: cristal en los dos temas (ver [WheelBackdrop]).
     */
    var meridianWheelGlass: Boolean?
        get() = if (prefs.contains(WHEEL_GLASS_KEY)) prefs.getBoolean(WHEEL_GLASS_KEY, true) else null
        set(v) = prefs.edit { if (v == null) remove(WHEEL_GLASS_KEY) else putBoolean(WHEEL_GLASS_KEY, v) }

    /** Biblioteca y Carpeta: un toque en lo seleccionado lo abre. Apagado, se abre con doble toque, como antes. */
    var tapOpensSelected: Boolean
        get() = prefs.getBoolean("tap.openSelected", DEFAULT_TAP_OPENS_SELECTED)
        set(v) = prefs.edit { putBoolean("tap.openSelected", v) }

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

    /**
     * Conversación en vivo por el altavoz: hablar encima de Lucy la corta (si
     * en un móvil se corta sola con su propia voz, se apaga y queda el botón).
     * Con auriculares siempre se puede.
     */
    var lucyVoiceInterrupt: Boolean
        get() = prefs.getBoolean("lucy.voiceInterrupt", true)
        set(v) = prefs.edit { putBoolean("lucy.voiceInterrupt", v) }

    /** La conversación con Lucy a la vista. Por defecto no: se la ve a ella, con subtítulos. */
    var lucyChatVisible: Boolean
        get() = prefs.getBoolean("lucy.chatVisible", false)
        set(v) = prefs.edit { putBoolean("lucy.chatVisible", v) }

    /**
     * El micrófono de Lucy bloqueado por el usuario. Se recuerda entre visitas:
     * bloqueado, abrir su pantalla no graba. Por defecto, desbloqueado (en vivo).
     */
    var lucyMicLocked: Boolean
        get() = prefs.getBoolean("lucy.micLocked", false)
        set(v) = prefs.edit { putBoolean("lucy.micLocked", v) }

    /** Las sugerencias rápidas bajo Lucy ("Tengo 30-40 minutos"…). Por defecto no: la pantalla es para ella. */
    var lucySuggestions: Boolean
        get() = prefs.getBoolean("lucy.suggestions", false)
        set(v) = prefs.edit { putBoolean("lucy.suggestions", v) }

    /** Ya se pidió el permiso del micrófono al abrir su pantalla (no se vuelve a pedir solo). */
    var lucyMicAsked: Boolean
        get() = prefs.getBoolean("lucy.micAsked", false)
        set(v) = prefs.edit { putBoolean("lucy.micAsked", v) }

    /** Con el chat oculto, una línea con lo que dice Lucy mientras habla. */
    var lucySubtitles: Boolean
        get() = prefs.getBoolean("lucy.subtitles", true)
        set(v) = prefs.edit { putBoolean("lucy.subtitles", v) }

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

    /* ── actualizaciones (releases de GitHub) ─────────────────── */

    /** Mirar solo, al abrir la app, si hay versión nueva (como mucho una vez al día). */
    var updateAutoCheck: Boolean
        get() = prefs.getBoolean("update.auto", true)
        set(v) = prefs.edit { putBoolean("update.auto", v) }

    /** Ofrecer también las pre-releases (betas). Apagado de serie desde la 1.0.0, la primera estable. */
    var updatePrereleases: Boolean
        get() = prefs.getBoolean("update.prereleases", false)
        set(v) = prefs.edit { putBoolean("update.prereleases", v) }

    /** Última comprobación que llegó a GitHub (epoch ms); 0 = nunca. */
    var updateLastCheckAt: Long
        get() = prefs.getLong("update.lastCheckAt", 0L)
        set(v) = prefs.edit { putLong("update.lastCheckAt", v) }

    /** ETag de la última lista de releases, para pedirla de forma condicional. */
    var updateEtag: String?
        get() = prefs.getString("update.etag", null)
        set(v) = prefs.edit { if (v == null) remove("update.etag") else putString("update.etag", v) }

    /** De dónde se lee el catálogo de la tienda de ports (vacío = el de siempre). */
    var portsCatalogUrl: String
        get() = prefs.getString("ports.catalogUrl", null)?.takeIf { it.isNotBlank() } ?: PortCatalogRepository.DEFAULT_URL
        set(v) = prefs.edit {
            if (v.isBlank() || v == PortCatalogRepository.DEFAULT_URL) remove("ports.catalogUrl") else putString("ports.catalogUrl", v.trim())
        }

    /** Tienda de ports: cuándo apareció cada port en el catálogo ("id=ms" por línea, ver PortFirstSeen). */
    var portsFirstSeen: String
        get() = prefs.getString("ports.firstSeen", "").orEmpty()
        set(v) = prefs.edit { putString("ports.firstSeen", v) }

    /** Tienda de ports: la lista de deseos (ids de port del catálogo). */
    var portsWishlist: Set<String>
        get() = prefs.getStringSet("ports.wishlist", emptySet()).orEmpty().toSet()
        set(v) = prefs.edit { putStringSet("ports.wishlist", v) }

    /** Tienda de ports: solo avisar de las actualizaciones (por defecto) o instalarlas solas cuando Android lo deja. */
    var portsUpdateMode: com.elyndra.launcher.domain.ports.PortUpdateMode
        get() = if (prefs.getBoolean("ports.autoUpdate", false)) {
            com.elyndra.launcher.domain.ports.PortUpdateMode.Automatic
        } else {
            com.elyndra.launcher.domain.ports.PortUpdateMode.NotifyOnly
        }
        set(v) = prefs.edit { putBoolean("ports.autoUpdate", v == com.elyndra.launcher.domain.ports.PortUpdateMode.Automatic) }

    /** Versión (etiqueta) que se canceló: la comprobación automática no vuelve a ofrecerla. */
    var updateSkippedTag: String?
        get() = prefs.getString("update.skipped", null)
        set(v) = prefs.edit { if (v == null) remove("update.skipped") else putString("update.skipped", v) }

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

        /** El chat de Lucy ya se devolvió a oculto una vez (ver [migrateLucyChat]). */
        const val LUCY_CHAT_RESET_KEY = "lucy.v4.chatReset"

        /** La versión de los ajustes del cristal de Meridian (ver [migrateGlass]). */
        const val GLASS_VERSION_KEY = "glass.version"
        const val GLASS_VERSION = 2
        const val WHEEL_GLASS_KEY = "meridian.wheelGlass"

        /** El "Desenfoque" por defecto que guardaban las versiones de antes. */
        const val LEGACY_DEFAULT_BLUR = 18

        /** Cian de fósforo: se ve bien sobre el tema claro y sobre el oscuro. */
        const val DEFAULT_PARTICLE_COLOR = 0xFF5CF2FF.toInt()

        /** El primario de Plasma, la paleta de firma de partida (halo y partículas de la selección). */
        const val DEFAULT_SELECTION_PARTICLE_COLOR = 0xFF7C5CFF.toInt()

        const val DEFAULT_SELECTION_GLOW = true

        /** La píldora de hora y batería, apagada de serie: se enciende en Ajustes. */
        const val DEFAULT_STATUS_VISIBLE = false

        /** Un toque en lo seleccionado lo abre; apagado, se abre con doble toque como antes. */
        const val DEFAULT_TAP_OPENS_SELECTED = true

        /** El fondo de Meridian toma el color del arte (encendido de serie). */
        const val DEFAULT_MERIDIAN_ADAPTIVE_COLOR = true
        const val DEFAULT_SELECTION_PARTICLES = true

        /*
         * Las claves siguen diciendo "lucy" a propósito, como las de "videoBg":
         * la asistente se llamaba así, y renombrarlas devolvería el botón a su
         * esquina a quien ya lo había movido. Cambia el nombre, no dónde se guarda.
         */
        private const val MASHA_X = "lucy.x"
        private const val MASHA_Y = "lucy.y"
    }
}
