package com.elyndra.launcher.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.data.ACCENTS
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.SecretKeys
import com.elyndra.launcher.data.TINTS
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

/** Ajustes: aspecto, idioma, credenciales de los servicios y mantenimiento de la biblioteca. */
class SettingsController(private val vm: ElyndraViewModel) {

    private val store = vm.app.settings
    private val secrets = vm.app.secrets
    private val creds = vm.app.credentials
    private val engine = vm.engine

    /* ── aspecto ──────────────────────────────────────────────── */

    var accentId by mutableStateOf(store.accentId); private set
    var tintId by mutableStateOf(store.tintId); private set
    var blur by mutableStateOf(store.blur); private set
    var alphaPct by mutableStateOf(store.alphaPct); private set
    var scrimPct by mutableStateOf(store.scrimPct); private set
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

    /* ── fondo de vídeo de la interfaz ────────────────────────── */

    var videoBgEnabled by mutableStateOf(store.videoBgEnabled); private set
    var videoBgUri by mutableStateOf(store.videoBgUri); private set
    var videoBgOpacity by mutableStateOf(store.videoBgOpacity); private set

    fun toggleVideoBg() {
        videoBgEnabled = !videoBgEnabled
        store.videoBgEnabled = videoBgEnabled
    }

    // `updateX`, no `setX`: `setVideoBgOpacity` chocaría con el setter que Kotlin
    // ya genera para la propiedad (misma firma JVM). Igual que `updateBlur`.
    fun updateVideoBgOpacity(v: Int) {
        videoBgOpacity = v
        store.videoBgOpacity = v
    }

    /** Vídeo elegido con SAF: hay que quedarse el permiso o se pierde al reiniciar. */
    fun onVideoPicked(uri: Uri?) {
        if (uri == null) return
        runCatching { vm.app.files.takePermission(uri) }
        videoBgUri = uri.toString()
        store.videoBgUri = videoBgUri
        if (!videoBgEnabled) toggleVideoBg()
    }

    fun clearVideoBg() {
        videoBgUri?.let { old -> runCatching { vm.app.files.releasePermission(old) } }
        videoBgUri = null
        store.videoBgUri = null
        if (videoBgEnabled) toggleVideoBg()
    }

    /* ── orden de la biblioteca ───────────────────────────────── */

    var sortMode by mutableStateOf(SortMode.byId(store.sortMode)); private set

    fun setSort(mode: SortMode) {
        sortMode = mode
        store.sortMode = mode.id
    }

    val skin: ElyndraSkin
        get() = ElyndraSkin(
            accent = ACCENTS.firstOrNull { it.id == accentId } ?: ACCENTS[0],
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
    var mediaBytes by mutableStateOf(-1L); private set

    fun onOpen() {
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
        fun helpUrl(s: Service): String = when (s) {
            Service.ScreenScraper -> "https://www.screenscraper.fr/membreinscription.php"
            Service.Igdb -> "https://dev.twitch.tv/console/apps"
            Service.SteamGridDb -> "https://www.steamgriddb.com/profile/preferences/api"
            Service.RetroAchievements -> "https://retroachievements.org/settings"
        }
    }
}
