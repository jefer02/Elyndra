package com.elyndra.launcher.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.domain.ports.PortAction
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.PortLink
import com.elyndra.launcher.ports.PortInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Descargar e instalar ports y llevarlos a la biblioteca.
 *
 * Instalar pasa siempre por un diálogo con el repositorio, la licencia, las
 * estrellas, la fecha de la release y lo que pide el port (la copia propia
 * del juego), y luego por el diálogo de Android. Abrir un port instalado es
 * otro botón: nunca se abre solo al acabar.
 */
class PortInstallController(private val vm: ElyndraViewModel, private val installer: PortInstaller) {

    /** Una descarga o instalación en curso. */
    sealed interface Task {
        val port: PortEntry

        data class Downloading(override val port: PortEntry, val progress: Float) : Task
        data class Installing(override val port: PortEntry) : Task
    }

    val tasks = mutableStateMapOf<String, Task>()
    private val jobs = HashMap<String, Job>()

    /** Avance (0..1) de la descarga de [id], o null si no se está bajando. */
    fun progress(id: String): Float? = (tasks[id] as? Task.Downloading)?.progress

    fun busy(id: String): Boolean = tasks.containsKey(id)

    /** ¿Es un port instalado desde la tienda? (la insignia PORT de la biblioteca). */
    fun isPortKey(key: String): Boolean = vm.ports.installed.values.any { PortLink.key(it.packageName) == key }

    /** El botón grande de la ficha (y A sobre él). */
    fun primary(port: PortEntry) {
        if (busy(port.id)) return
        when (vm.ports.action(port)) {
            PortAction.Free -> vm.ports.ensureReleases(port, prefetch = false, force = true)
            PortAction.Install, PortAction.Update -> confirm(port)
            PortAction.Installed -> open(port)
            PortAction.PcOnly, PortAction.Unavailable -> Unit
        }
    }

    /** Lo que se ve antes de instalar: de dónde sale, con qué licencia y qué pide. */
    private fun confirm(port: PortEntry) {
        val target = vm.ports.latest(port) ?: return
        if (!installer.canInstall()) {
            vm.showDialog(
                DialogSpec(
                    title = UiText.res(R.string.ports_permission_title),
                    message = UiText.res(R.string.ports_permission_msg),
                    confirm = DialogButton(UiText.res(R.string.update_permission_action)) {
                        runCatching { vm.app.startActivity(installer.permissionIntent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    dismiss = DialogButton(UiText.res(R.string.cancel)) {},
                ),
            )
            return
        }
        val repo = vm.ports.repoInfo[port.id]
        val lang = vm.settings.lang
        val license = repo?.license ?: port.license
        val requirement = if (port.requires.userRom) {
            listOfNotNull(vm.app.getString(R.string.ports_needs_rom), port.requirementText(lang)).joinToString("\n")
        } else {
            vm.app.getString(R.string.ports_no_rom_needed)
        }
        val date = target.release.publishedAt.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
        vm.showDialog(
            DialogSpec(
                title = UiText.res(R.string.ports_confirm_title, port.name),
                message = UiText.res(
                    R.string.ports_confirm_message,
                    port.releaseRepo.slug,
                    license ?: vm.app.getString(R.string.ports_license_unknown),
                    repo?.stars?.toString() ?: "—",
                    listOfNotNull(target.release.tag, date).joinToString(" · "),
                    requirement,
                ),
                confirm = DialogButton(UiText.res(if (vm.ports.action(port) == PortAction.Update) R.string.ports_action_update else R.string.ports_action_install)) {
                    start(port)
                },
                dismiss = DialogButton(UiText.res(R.string.cancel)) {},
            ),
        )
    }

    private fun start(port: PortEntry) {
        val target = vm.ports.latest(port) ?: return
        tasks[port.id] = Task.Downloading(port, 0f)
        jobs[port.id] = vm.viewModelScope.launch {
            val outcome = try {
                installer.install(
                    port,
                    target,
                    expectedPackage = vm.ports.installed[port.id]?.packageName,
                    onProgress = { p -> tasks[port.id] = Task.Downloading(port, p) },
                    onInstalling = { tasks[port.id] = Task.Installing(port) },
                )
            } finally {
                tasks.remove(port.id)
                jobs.remove(port.id)
            }
            when (outcome) {
                is PortInstaller.Outcome.Installed -> {
                    reconcile()
                    link(port, outcome.packageName, outcome.label)
                    vm.showToast(UiText.res(R.string.ports_install_done, port.name))
                }
                PortInstaller.Outcome.Cancelled -> vm.showToast(UiText.res(R.string.ports_install_cancelled))
                PortInstaller.Outcome.SignatureMismatch -> fail(port, R.string.ports_signature_mismatch)
                PortInstaller.Outcome.PackageMismatch -> fail(port, R.string.ports_package_mismatch)
                PortInstaller.Outcome.Checksum -> fail(port, R.string.ports_checksum_failed)
                PortInstaller.Outcome.Network -> fail(port, R.string.ports_download_failed)
                PortInstaller.Outcome.RateLimited -> fail(port, R.string.ports_rate_limited)
                is PortInstaller.Outcome.Failed -> vm.showToast(UiText.res(R.string.ports_install_failed, port.name))
            }
        }
    }

    private fun fail(port: PortEntry, message: Int) {
        vm.showDialog(
            DialogSpec(
                error = true,
                title = UiText.res(R.string.ports_install_failed, port.name),
                message = UiText.res(message),
                confirm = DialogButton(UiText.res(R.string.close)) {},
            ),
        )
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        tasks.remove(id)
    }

    /**
     * El port, en la biblioteca ya: la app `a:<paquete>` con el nombre del
     * original más "(Port)", su descripción y el arte del juego original
     * (fijado, para que las pasadas de metadatos no lo cambien).
     */
    private fun link(port: PortEntry, pkg: String, label: String) {
        val key = PortLink.key(pkg)
        if (vm.app.library.appByKey(key) == null) {
            vm.app.library.addApps(listOf(AppEntry(packageName = pkg, label = label, addedAt = System.currentTimeMillis())))
        }
        vm.app.library.updateMeta(key) { PortLink.meta(it, port, vm.settings.lang) }
        vm.viewModelScope.launch {
            vm.refreshInstalled()
            vm.materialize(listOf(key))
            vm.applyPortArt(key, vm.ports.originalArtFor(port), fallbackCover = port.image)
        }
    }

    /** Abre el port instalado: solo cuando el usuario lo pide. */
    private fun open(port: PortEntry) {
        val pkg = vm.ports.installed[port.id]?.packageName ?: return
        if (!vm.openByKey(PortLink.key(pkg))) {
            vm.app.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
                runCatching { vm.app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    /**
     * Lo instalado, contra lo que de verdad hay en el teléfono: un port
     * desinstalado desde fuera deja de contar (la tienda vuelve a ofrecer
     * "Instalar"; su entrada de la biblioteca la quita la limpieza de apps).
     */
    suspend fun reconcile() {
        val rows = runCatching { installer.installed() }.getOrDefault(emptyList())
        val (present, gone) = rows.partition { installer.isPackageInstalled(it.packageName) }
        gone.forEach { runCatching { installer.forget(it.portId) } }
        vm.ports.installed = present.associateBy { it.portId }
    }
}
