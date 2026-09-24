package com.elyndra.launcher.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.data.ACCENTS
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.SecretKeys
import com.elyndra.launcher.data.TINTS
import com.elyndra.launcher.display.FrameRate
import com.elyndra.launcher.masha.MashaError
import com.elyndra.launcher.metadata.ApiException
import com.elyndra.launcher.metadata.FailureKind
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.ui.theme.ElyndraSkin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Id del perfil de BannerHub, el único emulador que se instala con varios paquetes a la vez. */
private const val BANNERHUB = "bannerhub"

/** Ajustes: aspecto, idioma, credenciales de los servicios y mantenimiento de la biblioteca. */
class SettingsController(private val vm: ElyndraViewModel) {

    private val store = vm.app.settings
    private val secrets = vm.app.secrets
    private val creds = vm.app.credentials
    private val engine = vm.engine

    /* ── aspecto ──────────────────────────────────────────────── */

    var accentId by mutableStateOf(store.accentId); private set
    var tintId by mutableStateOf(store.tintId); private set
    var blur by mutableIntStateOf(store.blur); private set
    var alphaPct by mutableIntStateOf(store.alphaPct); private set
    var scrimPct by mutableIntStateOf(store.scrimPct); private set
    var autoMeta by mutableStateOf(store.autoMeta); private set
    var lang by mutableStateOf(AppLocale.current(vm.app)); private set

    /* ── tema claro / oscuro ──────────────────────────────────── */

    var darkMode by mutableStateOf(store.darkMode); private set

    init {
        // `P` guarda el tema en un estado de Compose: fijarlo aquí basta para
        // que toda la interfaz se repinte, sin tocar ningún sitio de llamada.
        P.isDark = store.darkMode
    }

    fun toggleDark() {
        darkMode = !darkMode
        store.darkMode = darkMode
        P.isDark = darkMode
    }

    /* ── fondo de la interfaz: vídeo o imagen ─────────────────── */

    var backgroundEnabled by mutableStateOf(store.backgroundEnabled); private set
    var backgroundUri by mutableStateOf(store.backgroundUri); private set
    var backgroundIsVideo by mutableStateOf(store.backgroundIsVideo); private set
    var backgroundOpacity by mutableIntStateOf(store.backgroundOpacity); private set

    fun toggleBackground() {
        backgroundEnabled = !backgroundEnabled
        store.backgroundEnabled = backgroundEnabled
    }

    // `updateX`, no `setX`: `setBackgroundOpacity` chocaría con el setter que Kotlin
    // ya genera para la propiedad (misma firma JVM). Igual que `updateBlur`.
    fun updateBackgroundOpacity(v: Int) {
        backgroundOpacity = v
        store.backgroundOpacity = v
    }

    /**
     * Fondo elegido con SAF. Hay que quedarse el permiso o se pierde al
     * reiniciar, y hay que anotar si es vídeo o imagen: se pintan distinto
     * (ExoPlayer en bucle frente a una imagen recortada).
     */
    fun onBackgroundPicked(uri: Uri?) {
        if (uri == null) return
        runCatching { vm.app.files.takePermission(uri) }
        val old = backgroundUri
        backgroundUri = uri.toString()
        backgroundIsVideo = isVideo(uri)
        store.backgroundUri = backgroundUri
        store.backgroundIsVideo = backgroundIsVideo
        // Se suelta el anterior solo después: si el usuario vuelve a elegir el
        // mismo archivo, soltarlo antes le quitaría el permiso recién tomado.
        if (old != null && old != backgroundUri) runCatching { vm.app.files.releasePermission(old) }
        if (!backgroundEnabled) toggleBackground()
    }

    fun clearBackground() {
        backgroundUri?.let { old -> runCatching { vm.app.files.releasePermission(old) } }
        backgroundUri = null
        store.backgroundUri = null
        if (backgroundEnabled) toggleBackground()
    }

    /** El tipo lo dice el proveedor; si calla, la extensión del nombre. */
    private fun isVideo(uri: Uri): Boolean {
        val mime = runCatching { vm.app.contentResolver.getType(uri) }.getOrNull()
        if (mime != null) return mime.startsWith("video/")
        val ext = uri.toString().substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /* ── botón de Masha ───────────────────────────────────────── */

    /**
     * Dónde está el botón de Masha, en dp desde la esquina superior izquierda.
     * `null` en cualquiera de los dos = nunca se ha movido, y entonces manda
     * su esquina de siempre.
     */
    var mashaX by mutableStateOf(store.mashaX); private set
    var mashaY by mutableStateOf(store.mashaY); private set

    /** Masha se queda donde se la suelte, también al volver a abrir la app. */
    fun moveMasha(x: Float, y: Float) {
        mashaX = x
        mashaY = y
        store.mashaX = x
        store.mashaY = y
    }

    /** Color (ARGB) de la estela de partículas de Masha al arrastrarla. */
    var mashaParticleColor by mutableIntStateOf(store.mashaParticleColor); private set

    fun updateMashaParticleColor(argb: Int) {
        mashaParticleColor = argb
        store.mashaParticleColor = argb
    }

    /* ── pantalla: 120 / 60 fps ───────────────────────────────── */

    /** La pantalla llega a 120 Hz (o más). Si no, la opción de 120 fps sale desactivada. */
    val supportsHighRefresh: Boolean = FrameRate.supportsHigh(vm.app)

    /** Lo guardado: 120, 60 o [FrameRate.AUTO]. */
    private var storedFrameRate by mutableIntStateOf(store.frameRate)

    /** Los fps que se piden de verdad a la pantalla; la Activity los aplica al cambiar. */
    val frameRate: Int get() = FrameRate.effective(storedFrameRate, supportsHighRefresh)

    fun updateFrameRate(fps: Int) {
        if (fps == FrameRate.HIGH && !supportsHighRefresh) return
        storedFrameRate = fps
        store.frameRate = fps
    }

    /* ── Masha ────────────────────────────────────────────────── */

    var mashaOnline by mutableStateOf(store.mashaOnline); private set
    var mashaAmbient by mutableStateOf(store.mashaAmbient); private set
    var mashaNudges by mutableStateOf(store.mashaNudges); private set
    var mashaKey by mutableStateOf(vm.brain.config.userKey); private set
    var mashaTest by mutableStateOf<MashaTest>(MashaTest.Idle); private set

    /** El usuario ha dado acceso de uso (tiempo de juego exacto). Se relee al abrir Ajustes. */
    var usageAccess by mutableStateOf(false); private set

    sealed interface MashaTest {
        data object Idle : MashaTest
        data object Checking : MashaTest
        data class Ok(val balance: String?) : MashaTest
        data class Failed(val message: UiText) : MashaTest
    }

    val mashaHasKey: Boolean get() = vm.brain.config.hasKey
    val mashaBuiltInKey: Boolean get() = vm.brain.config.usesBuiltInKey

    fun toggleMashaOnline() {
        mashaOnline = !mashaOnline
        store.mashaOnline = mashaOnline
    }

    fun toggleMashaAmbient() {
        mashaAmbient = !mashaAmbient
        store.mashaAmbient = mashaAmbient
        vm.masha.refreshInsight()
    }

    fun toggleMashaNudges() {
        mashaNudges = !mashaNudges
        store.mashaNudges = mashaNudges
    }

    fun updateMashaKey(value: String) {
        mashaKey = value
        vm.brain.config.setUserKey(value)
        mashaTest = MashaTest.Idle
    }

    /** "Probar conexión": consulta el saldo de la cuenta (no gasta tokens). */
    fun testMasha() {
        if (!vm.brain.config.hasKey) {
            mashaTest = MashaTest.Failed(UiText.res(R.string.masha_err_no_key))
            return
        }
        mashaTest = MashaTest.Checking
        vm.viewModelScope.launch {
            mashaTest = vm.brain.ai.ping().fold(
                onSuccess = { MashaTest.Ok(it) },
                onFailure = { e -> MashaTest.Failed((e as? MashaError)?.let { vm.masha.errorText(it) } ?: UiText.Raw(e.message ?: "?")) },
            )
        }
    }

    fun refreshUsageAccess() {
        usageAccess = vm.sessions.hasUsageAccess()
    }

    /** Lleva a Ajustes del sistema → Acceso de uso (con Elyndra señalada si el sistema lo admite). */
    fun openUsageAccess() {
        val opened = runCatching { vm.app.startActivity(vm.sessions.usageAccessIntent()) }.isSuccess
        if (!opened) runCatching { vm.app.startActivity(vm.sessions.usageAccessFallbackIntent()) }
    }

    fun forgetMasha() {
        vm.showDialog(
            DialogSpec(
                title = UiText.res(R.string.settings_masha_forget),
                message = UiText.res(R.string.settings_masha_forget_msg),
                destructive = true,
                confirm = DialogButton(UiText.res(R.string.remove)) {
                    vm.viewModelScope.launch {
                        vm.brain.forgetEverything()
                        vm.masha.clearConversation(null)
                        vm.showToast(UiText.res(R.string.toast_masha_forgot))
                    }
                },
                dismiss = DialogButton(UiText.res(R.string.close)) {},
            ),
        )
    }

    /* ── prioridad de fuentes de metadatos ────────────────────── */

    var priority by mutableStateOf(vm.metadataPriority.get()); private set

    /** Mueve un servicio una posición en el orden de textos o de imágenes. */
    fun movePriority(art: Boolean, service: Service, delta: Int) {
        val list = (if (art) priority.art else priority.text).toMutableList()
        val from = list.indexOf(service)
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from < 0 || from == to) return
        list.removeAt(from)
        list.add(to, service)
        priority = if (art) priority.copy(art = list) else priority.copy(text = list)
        vm.metadataPriority.set(priority)
    }

    fun resetPriority() {
        vm.metadataPriority.reset()
        priority = vm.metadataPriority.get()
    }

    /* ── orden de la biblioteca ───────────────────────────────── */

    var sortMode by mutableStateOf(SortMode.byId(store.sortMode)); private set

    fun setSort(mode: SortMode) {
        sortMode = mode
        store.sortMode = mode.id
    }

    val skin: ElyndraSkin
        get() = ElyndraSkin(
            accent = ACCENTS.firstOrNull { it.id == accentId } ?: ACCENTS.first { it.id == "lila" },
            tint = TINTS.firstOrNull { it.id == tintId } ?: TINTS[0],
            blur = blur,
            alphaPct = alphaPct,
            scrimPct = scrimPct,
        )

    fun setAccent(id: String) { accentId = id; store.accentId = id }
    fun setTint(id: String) { tintId = id; store.tintId = id }
    fun updateBlur(v: Int) { blur = v; store.blur = v }
    fun setAlpha(v: Int) { alphaPct = v; store.alphaPct = v }
    fun setScrim(v: Int) { scrimPct = v; store.scrimPct = v }

    fun toggleAutoMeta() {
        autoMeta = !autoMeta
        store.autoMeta = autoMeta
    }

    fun refreshLanguage() {
        lang = AppLocale.current(vm.app)
    }

    /** La Activity aplica el idioma (y se recrea); aquí se anota para la píldora activa. */
    fun onLanguageChosen(tag: String) {
        lang = tag
    }

    /* ── credenciales ─────────────────────────────────────────── */

    enum class Field(val key: String, val service: Service, val secret: Boolean) {
        SsUser(SecretKeys.SS_USER, Service.ScreenScraper, false),
        SsPassword(SecretKeys.SS_PASSWORD, Service.ScreenScraper, true),
        SsDevId(SecretKeys.SS_DEV_ID, Service.ScreenScraper, false),
        SsDevPassword(SecretKeys.SS_DEV_PASSWORD, Service.ScreenScraper, true),
        IgdbClientId(SecretKeys.IGDB_CLIENT_ID, Service.Igdb, false),
        IgdbClientSecret(SecretKeys.IGDB_CLIENT_SECRET, Service.Igdb, true),
        SgdbKey(SecretKeys.SGDB_KEY, Service.SteamGridDb, true),
        RaUser(SecretKeys.RA_USER, Service.RetroAchievements, false),
        RaKey(SecretKeys.RA_KEY, Service.RetroAchievements, true),
    }

    private val values = mutableStateMapOf<Field, String>().apply {
        Field.entries.forEach { put(it, secrets.get(it.key)) }
    }

    fun value(field: Field): String = values[field].orEmpty()

    fun update(field: Field, value: String) {
        values[field] = value
        secrets.set(field.key, value.trim())
        store.setVerified(field.service.id, false)
        if (field.service == Service.Igdb) creds.save(null)
        states[field.service] = idleState(field.service)
    }

    var showSsDev by mutableStateOf(false); private set

    fun toggleSsDev() { showSsDev = !showSsDev }

    val builtInSsDev: Boolean get() = creds.builtInScreenScraperDev

    val states = mutableStateMapOf<Service, ServiceState>().apply {
        Service.entries.forEach { put(it, idleState(it)) }
    }

    private fun idleState(s: Service): ServiceState = when {
        !creds.isConfigured(s) -> ServiceState(
            ServiceState.Status.Unconfigured,
            if (s == Service.ScreenScraper && !creds.ss().hasDev) UiText.res(R.string.err_missing_dev) else null,
        )
        store.isVerified(s.id) -> ServiceState(ServiceState.Status.Connected)
        else -> ServiceState(ServiceState.Status.Unverified)
    }

    fun state(s: Service): ServiceState = states[s] ?: idleState(s)

    /** "Probar conexión": una llamada real al servicio con las credenciales guardadas. */
    fun test(service: Service) {
        if (!creds.isConfigured(service)) {
            states[service] = idleState(service)
            return
        }
        states[service] = ServiceState(ServiceState.Status.Checking)
        vm.viewModelScope.launch {
            try {
                val detail: UiText = when (service) {
                    Service.ScreenScraper -> {
                        if (creds.ss().hasUser) {
                            val u = engine.screenScraper.user()
                            UiText.res(R.string.ss_status_detail, u.id, u.requestsToday, u.maxRequestsPerDay)
                        } else {
                            engine.screenScraper.checkDeveloper()
                            UiText.res(R.string.ss_status_anonymous)
                        }
                    }
                    Service.Igdb -> {
                        creds.save(null)
                        val token = engine.igdb.authenticate()
                        val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.forLanguageTag(lang))
                        UiText.res(R.string.igdb_status_detail, fmt.format(Date(token.expiresAt)))
                    }
                    Service.SteamGridDb -> {
                        engine.steamGridDb.validate()
                        UiText.res(R.string.sgdb_status_ok)
                    }
                    Service.RetroAchievements -> {
                        val p = engine.retroAchievements.profile()
                        UiText.res(R.string.ra_status_detail, p.user, p.points)
                    }
                }
                store.setVerified(service.id, true)
                states[service] = ServiceState(ServiceState.Status.Connected, detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.setVerified(service.id, false)
                states[service] = ServiceState(ServiceState.Status.Error, errorText(e))
            }
        }
    }

    fun errorText(e: Throwable): UiText = when (e) {
        is ApiException.Unauthorized -> UiText.res(R.string.err_credentials)
        is ApiException.QuotaExceeded -> UiText.res(R.string.err_quota)
        is ApiException.Blocked -> UiText.res(R.string.err_blocked)
        is ApiException.RateLimited -> UiText.res(R.string.err_rate)
        is ApiException.Network -> UiText.res(R.string.err_network)
        is ApiException.Server -> UiText.res(R.string.err_server, e.code)
        is ApiException.BadResponse, is ApiException.NotFound -> UiText.res(R.string.err_bad_response)
        else -> UiText.Raw(e.message ?: e.javaClass.simpleName)
    }

    fun failureText(kind: FailureKind): UiText = when (kind) {
        FailureKind.Credentials -> UiText.res(R.string.err_credentials)
        FailureKind.Quota -> UiText.res(R.string.err_quota)
        FailureKind.Blocked -> UiText.res(R.string.err_blocked)
        FailureKind.Unavailable -> UiText.res(R.string.err_unavailable)
        FailureKind.Network -> UiText.res(R.string.err_network)
    }

    /* ── metadatos ────────────────────────────────────────────── */

    val anyServiceConfigured: Boolean get() = Service.entries.any { states[it]?.status != ServiceState.Status.Unconfigured }

    fun applyMetadata(force: Boolean) {
        if (!creds.anyConfigured()) {
            vm.showToast(UiText.res(R.string.configure_a_service))
            return
        }
        if (vm.library.roms.isEmpty() && vm.library.apps.isEmpty()) {
            vm.showToast(UiText.res(R.string.library_empty_toast))
            return
        }
        engine.start(null, force)
    }

    fun cancelMetadata() = engine.cancel()

    /* ── biblioteca ───────────────────────────────────────────── */

    var rescanning by mutableStateOf(false); private set
    var mediaBytes by mutableLongStateOf(-1L); private set

    fun onOpen() {
        refreshUsageAccess()
        Service.entries.forEach { s ->
            if (states[s]?.status != ServiceState.Status.Checking && states[s]?.status != ServiceState.Status.Connected) {
                states[s] = idleState(s)
            }
        }
        vm.viewModelScope.launch { mediaBytes = withContext(Dispatchers.IO) { vm.app.media.sizeBytes() } }
    }

    fun rescanAll() {
        if (rescanning || vm.library.folders.isEmpty()) return
        rescanning = true
        vm.viewModelScope.launch {
            var added = 0
            var removed = 0
            vm.library.folders.forEach { f ->
                vm.rescan(f, silent = true)?.let {
                    added += it.added.size
                    removed += it.removed
                }
            }
            rescanning = false
            vm.showToast(UiText.res(R.string.toast_rescan, added, removed))
        }
    }

    /* ── BannerHub: qué build se lanza ────────────────────────── */

    /** Paquete elegido a mano para BannerHub; null = el que se detecte. */
    var bannerHubPackage by mutableStateOf(store.preferredPackage(BANNERHUB)); private set

    /**
     * Elegir con qué build de BannerHub se lanzan los juegos.
     *
     * Hace falta porque el runtime se instala con varios paquetes distintos
     * según el fork, y algunos se publican bajo el nombre de otra app: con dos
     * instaladas, "detectar" acierta la primera, no la que el usuario usa.
     */
    fun pickBannerHubPackage() {
        val profile = Emulators.byId(BANNERHUB) ?: return
        val auto = SheetAction(
            UiText.res(R.string.bannerhub_package_auto),
            detail = detectedBannerHubPackage()?.let { UiText.Raw(it) },
            selected = bannerHubPackage == null,
        ) { chooseBannerHubPackage(null) }
        val options = profile.packages.map { pkg ->
            SheetAction(
                UiText.Raw(pkg),
                detail = if (vm.app.launcher.isPackageInstalled(pkg)) UiText.res(R.string.installed) else null,
                selected = bannerHubPackage == pkg,
                dimmed = !vm.app.launcher.isPackageInstalled(pkg),
            ) { chooseBannerHubPackage(pkg) }
        }
        vm.showSheet(
            ActionSheetSpec(
                UiText.res(R.string.bannerhub_package),
                UiText.res(R.string.bannerhub_package_desc),
                listOf(auto) + options,
            ),
        )
    }

    private fun chooseBannerHubPackage(pkg: String?) {
        bannerHubPackage = pkg
        store.setPreferredPackage(BANNERHUB, pkg)
    }

    /** Paquete que se usaría ahora mismo si no se elige ninguno a mano. */
    fun detectedBannerHubPackage(): String? = Emulators.byId(BANNERHUB)
        ?.packages
        ?.firstOrNull { vm.app.launcher.isPackageInstalled(it) }

    fun clearImages() {
        vm.showDialog(
            DialogSpec(
                title = UiText.res(R.string.clear_images),
                message = UiText.res(R.string.clear_images_msg),
                confirm = DialogButton(UiText.res(R.string.remove)) {
                    engine.cancel()
                    vm.app.media.clearAll()
                    vm.app.library.update { lib ->
                        lib.copy(
                            roms = lib.roms.map { it.copy(meta = it.meta.copy(cover = null, hero = null, logo = null, screenshot = null)) },
                            apps = lib.apps.map { it.copy(meta = it.meta.copy(cover = null, hero = null, logo = null, screenshot = null)) },
                        )
                    }
                    mediaBytes = 0
                    vm.showToast(UiText.res(R.string.toast_images_cleared))
                },
                dismiss = DialogButton(UiText.res(R.string.close)) {},
            ),
        )
    }

    companion object {
        /** Reserva por si el proveedor no declara el tipo del archivo elegido. */
        private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "m4v", "mov", "3gp", "avi", "ts")

        fun helpUrl(s: Service): String = when (s) {
            Service.ScreenScraper -> "https://www.screenscraper.fr/membreinscription.php"
            Service.Igdb -> "https://dev.twitch.tv/console/apps"
            Service.SteamGridDb -> "https://www.steamgriddb.com/profile/preferences/api"
            Service.RetroAchievements -> "https://retroachievements.org/settings"
        }
    }
}
