package com.elyndra.launcher.ui

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.BuildConfig
import com.elyndra.launcher.R
import com.elyndra.launcher.input.Pad
import com.elyndra.launcher.update.InstallResult
import com.elyndra.launcher.update.Release
import com.elyndra.launcher.update.UpdateDebug
import com.elyndra.launcher.update.UpdateError
import com.elyndra.launcher.update.UpdateInstaller
import com.elyndra.launcher.update.UpdateLogic
import com.elyndra.launcher.update.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Actualizaciones desde las releases de GitHub: la comprobación (sola, como
 * mucho una vez al día, o a mano desde Ajustes) y el diálogo que ofrece la
 * versión nueva, la descarga y la instala.
 *
 * La lógica que se puede probar sin Android está en [UpdateLogic]; la red, en
 * [UpdateRepository]; instalar, en [UpdateInstaller]. Aquí solo el estado que
 * pinta la interfaz.
 */
class UpdateController(
    private val vm: ElyndraViewModel,
    private val repo: UpdateRepository,
    private val installer: UpdateInstaller,
) {

    /** Lo que se sabe de la última comprobación (lo enseña Ajustes → Actualizaciones). */
    sealed interface Check {
        data object Idle : Check
        data object Checking : Check
        data object UpToDate : Check
        data class Available(val release: Release) : Check
        data class Failed(@StringRes val reason: Int) : Check
    }

    /** El diálogo de actualización, por fases. */
    sealed interface Dialog {
        val release: Release

        data class Prompt(override val release: Release, val notes: String) : Dialog
        data class Permission(override val release: Release) : Dialog
        data class Downloading(override val release: Release) : Dialog
        data class Installing(override val release: Release) : Dialog
        data class Failed(override val release: Release, @StringRes val reason: Int) : Dialog
    }

    /** Un botón del diálogo; el primero es el principal (el que señala el mando al abrirse). */
    class Button(@StringRes val label: Int, val primary: Boolean, val action: () -> Unit)

    private val store = vm.app.settings

    var check by mutableStateOf<Check>(Check.Idle); private set
    var dialog by mutableStateOf<Dialog?>(null); private set

    /** Avance de la descarga, 0..1. */
    var progress by mutableFloatStateOf(0f); private set

    /** Botón que señala el mando (-1 = ninguno, con el dedo). */
    var focus by mutableIntStateOf(-1); private set

    var lastCheckAt by mutableLongStateOf(store.updateLastCheckAt); private set
    var autoCheck by mutableStateOf(store.updateAutoCheck); private set
    var prereleases by mutableStateOf(store.updatePrereleases); private set

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** Último intento automático en este proceso: sin red no se reintenta en cada vuelta a la app. */
    private var lastAutoAttempt = 0L
    private var cleaned = false

    private val downloads: File get() = File(vm.app.cacheDir, "updates")

    val installedVersion: String get() = BuildConfig.VERSION_NAME
    val installedCode: Int get() = BuildConfig.VERSION_CODE

    /* ── ajustes ──────────────────────────────────────────────── */

    fun toggleAutoCheck() {
        autoCheck = !autoCheck
        store.updateAutoCheck = autoCheck
    }

    fun togglePrereleases() {
        prereleases = !prereleases
        store.updatePrereleases = prereleases
        // Lo que había cambia de sentido: la próxima comprobación lo recalcula.
        if (check is Check.Available || check is Check.UpToDate) check = Check.Idle
    }

    /* ── comprobar ────────────────────────────────────────────── */

    /**
     * La app vuelve a primer plano (y al arrancar): se borra lo que quedase
     * de una descarga, se sigue si se volvió de conceder el permiso y, si
     * toca, se mira en silencio si hay versión nueva.
     */
    fun onForeground() {
        if (!cleaned) {
            cleaned = true
            if (downloadJob == null) runCatching { downloads.deleteRecursively() }
        }
        val d = dialog
        if (d is Dialog.Permission && installer.canInstall()) {
            start(d.release)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAutoAttempt < RETRY_MS) return
        if (UpdateLogic.shouldAutoCheck(store.updateAutoCheck, now, store.updateLastCheckAt)) {
            lastAutoAttempt = now
            runCheck(manual = false)
        }
    }

    /** "Buscar actualizaciones" en Ajustes: con mensaje si falla y enseñando la versión aunque se cancelara antes. */
    fun checkNow() = runCheck(manual = true)

    /** Solo en depuración: una versión nueva de mentira (ver [UpdateDebug]). */
    fun simulate() {
        if (UpdateDebug.label == null) return
        checkJob?.cancel()
        check = Check.Checking
        checkJob = vm.viewModelScope.launch {
            val real = try {
                repo.releases()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val fake = UpdateDebug.fakeNewer(real, installedVersion) ?: return@launch
            check = Check.Available(fake)
            showPrompt(fake)
        }
    }

    private fun runCheck(manual: Boolean) {
        if (check is Check.Checking) return
        check = Check.Checking
        checkJob = vm.viewModelScope.launch {
            val releases = try {
                repo.releases()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // La automática falla en silencio; la manual lo dice en Ajustes.
                check = if (manual) Check.Failed(reasonOf(e)) else Check.Idle
                return@launch
            }
            val now = System.currentTimeMillis()
            store.updateLastCheckAt = now
            lastCheckAt = now
            val newest = UpdateLogic.newest(releases, installedVersion, store.updatePrereleases)
            if (newest == null) {
                check = Check.UpToDate
                return@launch
            }
            check = Check.Available(newest)
            if (!UpdateLogic.shouldPrompt(newest, store.updateSkippedTag, manual)) return@launch
            // Sola, no se pone encima de otro diálogo ni de un lanzamiento: Ajustes ya la enseña.
            if (!manual && (vm.dialog != null || vm.opening != null || dialog != null)) return@launch
            // Una vez enseñada sola, no vuelve a preguntar por ella si no se acepta (cancelar, B, tocar fuera).
            if (!manual) store.updateSkippedTag = newest.tag
            showPrompt(newest)
        }
    }

    /** Ajustes → "Ver": vuelve a abrir el diálogo de la versión encontrada. */
    fun showAvailable() {
        (check as? Check.Available)?.let { showPrompt(it.release) }
    }

    private fun showPrompt(release: Release) {
        show(Dialog.Prompt(release, UpdateLogic.trimNotes(release.notes)))
    }

    private fun show(d: Dialog) {
        dialog = d
        // Con mando, señalado el botón principal (Actualizar).
        focus = if (vm.input.gamepadPresent || vm.input.active) 0 else -1
    }

    /* ── actualizar ───────────────────────────────────────────── */

    private fun accept(release: Release) {
        if (store.updateSkippedTag.equals(release.tag, ignoreCase = true)) store.updateSkippedTag = null
        start(release)
    }

    private fun start(release: Release) {
        val asset = UpdateLogic.chooseAsset(release.assets, Build.SUPPORTED_ABIS.toList())
        if (asset == null) {
            // Sin un APK seguro para este dispositivo, que elija el usuario en la página.
            dialog = null
            vm.showToast(UiText.res(R.string.update_no_asset))
            vm.openUrl(release.pageUrl)
            return
        }
        if (!installer.canInstall()) {
            show(Dialog.Permission(release))
            return
        }
        if (downloadJob != null) return
        progress = 0f
        show(Dialog.Downloading(release))
        downloadJob = vm.viewModelScope.launch {
            try {
                val sha = repo.expectedSha256(asset, release)
                val apk = repo.download(asset, downloads, sha) { progress = it }
                show(Dialog.Installing(release))
                when (installer.install(apk)) {
                    InstallResult.Success -> dialog = null
                    InstallResult.Cancelled -> {
                        dialog = null
                        vm.showToast(UiText.res(R.string.update_cancelled))
                    }
                    InstallResult.SignatureMismatch -> show(Dialog.Failed(release, R.string.update_error_signature))
                    is InstallResult.Failed -> show(Dialog.Failed(release, R.string.update_error_install))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateError.Checksum) {
                show(Dialog.Failed(release, R.string.update_error_checksum))
            } catch (e: Exception) {
                show(Dialog.Failed(release, R.string.update_error_download))
            } finally {
                downloadJob = null
                runCatching { downloads.deleteRecursively() }
            }
        }
    }

    /** Cancelar, B, atrás o tocar fuera: se corta lo que esté en marcha y se cierra. */
    fun dismiss() {
        val d = dialog ?: return
        if (d is Dialog.Downloading) {
            downloadJob?.cancel()
            vm.showToast(UiText.res(R.string.update_cancelled))
        }
        dialog = null
    }

    fun buttons(d: Dialog): List<Button> = when (d) {
        is Dialog.Prompt -> listOf(
            Button(R.string.update_action, primary = true) { accept(d.release) },
            Button(R.string.cancel, primary = false, ::dismiss),
        )
        is Dialog.Permission -> listOf(
            Button(R.string.update_permission_action, primary = true) { vm.openExternal(installer.permissionIntent()) },
            Button(R.string.cancel, primary = false, ::dismiss),
        )
        is Dialog.Downloading -> listOf(Button(R.string.cancel, primary = false, ::dismiss))
        is Dialog.Installing -> listOf(Button(R.string.close, primary = false, ::dismiss))
        is Dialog.Failed -> listOf(
            Button(R.string.update_open_page, primary = true) {
                dialog = null
                vm.openUrl(d.release.pageUrl)
            },
            Button(R.string.close, primary = false, ::dismiss),
        )
    }

    /** El mando sobre el diálogo: cruceta entre botones, A pulsa, B cancela. */
    fun pad(pad: Pad): Boolean {
        val d = dialog ?: return false
        val buttons = buttons(d)
        when (pad) {
            Pad.Left, Pad.Up -> focus = if (focus < 0) 0 else (focus - 1).coerceAtLeast(0)
            Pad.Right, Pad.Down -> focus = if (focus < 0) 0 else (focus + 1).coerceAtMost(buttons.lastIndex)
            Pad.Confirm -> (buttons.getOrNull(focus) ?: buttons.first()).action()
            Pad.Back -> dismiss()
            else -> Unit
        }
        return true
    }

    private fun reasonOf(e: Exception): Int = when (e) {
        is UpdateError.Network -> R.string.update_state_error_network
        is UpdateError.RateLimited -> R.string.update_state_error_rate
        else -> R.string.update_state_error
    }

    private companion object {
        /** Sin red, la comprobación automática se reintenta como mucho cada hora. */
        const val RETRY_MS = 60L * 60 * 1000
    }
}
