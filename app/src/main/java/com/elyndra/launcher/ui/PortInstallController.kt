package com.elyndra.launcher.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.data.AppEntry
import android.os.Build
import com.elyndra.launcher.domain.ports.AutoUpdatePolicy
import com.elyndra.launcher.domain.ports.PortAction
import com.elyndra.launcher.domain.ports.PortDisplay
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.PortLink
import com.elyndra.launcher.domain.ports.PortPlacement
import com.elyndra.launcher.domain.ports.UnverifiedDialog
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

    /** Los APK que encontró la comprobación diaria para ports "aún no en Android". */
    suspend fun availability(): Map<String, com.elyndra.launcher.domain.ports.PortAvailability.Found> =
        runCatching { installer.availability() }.getOrDefault(emptyMap())

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

    /** El nombre del port instalado como [packageName] (la línea secundaria en su carpeta), si el catálogo ya está leído. */
    fun portNameFor(packageName: String): String? = portForKey(PortLink.key(packageName))?.name

    /** El port del catálogo instalado como [key] (para "PORT · <port>" en la ficha), si el catálogo ya está leído. */
    fun portForKey(key: String): PortEntry? {
        val id = vm.ports.installed.values.firstOrNull { PortLink.key(it.packageName) == key }?.portId ?: return null
        return vm.ports.catalog.firstOrNull { it.id == id }
    }

    /** El botón grande de la ficha (y A sobre él). */
    fun primary(port: PortEntry) {
        if (busy(port.id)) return
        when (vm.ports.action(port)) {
            PortAction.Free -> vm.ports.ensureReleases(port, prefetch = false, force = true)
            PortAction.Install, PortAction.Update -> confirm(port)
            PortAction.Installed -> open(port)
            PortAction.NotOnAndroid, PortAction.VrOnly, PortAction.Unavailable -> Unit
            PortAction.SignatureChanged -> fail(port, R.string.ports_signature_mismatch)
        }
    }

    /**
     * Antes de instalar un port sin verificar (y antes del diálogo de siempre):
     * el repositorio, sus estrellas, la fecha de la última release y que lo
     * hace la comunidad sin revisión. Actualizar uno ya instalado no lo pide.
     */
    private fun confirm(port: PortEntry) {
        val target = vm.ports.latest(port) ?: return
        val lines = UnverifiedDialog.lines(port)
        if (lines.isEmpty() || vm.ports.action(port) == PortAction.Update) {
            confirmInstall(port)
            return
        }
        val stars = vm.ports.repoInfo[port.id]?.stars ?: port.trust.stars
        val published = target.release.publishedAt.takeIf { it > 0 } ?: port.release.publishedAtMillis
        val date = published.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "—"
        val unverified = UiText.res(R.string.ports_unverified_message, port.releaseRepo.slug, stars?.toString() ?: "—", date)
        vm.showDialog(
            DialogSpec(
                title = UiText.res(R.string.ports_unverified_title),
                message = unverified,
                confirm = DialogButton(UiText.res(R.string.ports_unverified_continue)) { confirmInstall(port) },
                dismiss = DialogButton(UiText.res(R.string.cancel)) {},
            ),
        )
    }

    /** Lo que se ve antes de instalar: de dónde sale, con qué licencia y qué pide. */
    private fun confirmInstall(port: PortEntry) {
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
                title = UiText.res(R.string.ports_confirm_title, PortDisplay.title(port)),
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
        val installedPkg = vm.ports.installed[port.id]?.packageName
        // Una actualización con "actualizar automáticamente", Android 12+ y si la instaló Elyndra: sin diálogo.
        val silent = installedPkg != null && AutoUpdatePolicy.route(
            vm.app.settings.portsUpdateMode, Build.VERSION.SDK_INT, installer.installedByElyndra(installedPkg), foreground = true,
        ) == AutoUpdatePolicy.Route.Silent
        tasks[port.id] = Task.Downloading(port, 0f)
        jobs[port.id] = vm.viewModelScope.launch {
            val outcome = try {
                installer.install(
                    port,
                    target,
                    expectedPackage = installedPkg,
                    onProgress = { p -> tasks[port.id] = Task.Downloading(port, p) },
                    onInstalling = { tasks[port.id] = Task.Installing(port) },
                    silent = silent,
                )
            } finally {
                tasks.remove(port.id)
                jobs.remove(port.id)
            }
            when (outcome) {
                is PortInstaller.Outcome.Installed -> {
                    reconcile()
                    link(port, outcome.packageName, outcome.label)
                    vm.showToast(UiText.res(R.string.ports_install_done, PortDisplay.title(port)))
                }
                PortInstaller.Outcome.Cancelled -> vm.showToast(UiText.res(R.string.ports_install_cancelled))
                PortInstaller.Outcome.SignatureMismatch -> {
                    reconcile()
                    fail(port, R.string.ports_signature_mismatch)
                }
                // Sin diálogo no pudo ser: otra vez, con el de siempre (el usuario está delante).
                PortInstaller.Outcome.NeedsUserAction -> retryWithPrompt(port)
                PortInstaller.Outcome.PackageMismatch -> fail(port, R.string.ports_package_mismatch)
                PortInstaller.Outcome.Checksum -> fail(port, R.string.ports_checksum_failed)
                PortInstaller.Outcome.Network -> fail(port, R.string.ports_download_failed)
                PortInstaller.Outcome.RateLimited -> fail(port, R.string.ports_rate_limited)
                is PortInstaller.Outcome.Failed -> vm.showToast(UiText.res(R.string.ports_install_failed, PortDisplay.title(port)))
            }
        }
    }

    private fun retryWithPrompt(port: PortEntry) {
        val target = vm.ports.latest(port) ?: return
        tasks[port.id] = Task.Downloading(port, 0f)
        jobs[port.id] = vm.viewModelScope.launch {
            val outcome = try {
                installer.install(
                    port, target, vm.ports.installed[port.id]?.packageName,
                    onProgress = { p -> tasks[port.id] = Task.Downloading(port, p) },
                    onInstalling = { tasks[port.id] = Task.Installing(port) },
                )
            } finally {
                tasks.remove(port.id)
                jobs.remove(port.id)
            }
            if (outcome is PortInstaller.Outcome.Installed) {
                reconcile()
                link(port, outcome.packageName, outcome.label)
                vm.showToast(UiText.res(R.string.ports_install_done, PortDisplay.title(port)))
            } else if (outcome is PortInstaller.Outcome.SignatureMismatch) {
                reconcile()
                fail(port, R.string.ports_signature_mismatch)
            }
        }
    }

    private fun fail(port: PortEntry, message: Int) {
        vm.showDialog(
            DialogSpec(
                error = true,
                title = UiText.res(R.string.ports_install_failed, PortDisplay.title(port)),
                message = UiText.res(message),
                confirm = DialogButton(UiText.res(R.string.close)) {},
            ),
        )
    }

    /**
     * "Actualizar todo": uno detrás de otro (cada uno con el diálogo de
     * Android si lo pide), para no abrir varios a la vez.
     */
    fun updateAll(ports: List<PortEntry>) {
        if (updateAllJob?.isActive == true) return
        val queue = ports.filter { !busy(it.id) }
        if (queue.isEmpty()) return
        updateAllJob = vm.viewModelScope.launch {
            for (port in queue) {
                // La fila puede venir de la comprobación diaria: las releases, si aún no están.
                vm.ports.loadReleases(port)
                if (vm.ports.action(port) != PortAction.Update) continue
                start(port)
                jobs[port.id]?.join()
            }
        }
    }

    private var updateAllJob: Job? = null

    /**
     * Cada port instalado, en la carpeta de su sistema (PortPlacement). Se
     * llama al leer lo instalado y cuando cambian las carpetas de la
     * biblioteca; si no hay nada que mover, la biblioteca no cambia.
     */
    fun place() {
        val systems = vm.ports.installed.values.associate { p ->
            p.packageName to (p.systemId ?: vm.ports.catalog.firstOrNull { it.id == p.portId }?.platform)
        }.filterValues { it != null }.mapValues { it.value!! }
        // Sin el sistema de alguno (instalado con la primera versión y sin catálogo aún) se espera al catálogo.
        if (systems.size < vm.ports.installed.size && vm.ports.catalog.isEmpty()) return
        vm.app.library.update { PortPlacement.reconcile(it, systems, System.currentTimeMillis()) }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        tasks.remove(id)
    }

    /**
     * El port, en la biblioteca ya: la app `a:<paquete>` con el nombre del
     * juego original, su descripción y el arte del juego original
     * (fijado, para que las pasadas de metadatos no lo cambien).
     */
    private fun link(port: PortEntry, pkg: String, label: String) {
        val key = PortLink.key(pkg)
        if (vm.app.library.appByKey(key) == null) {
            vm.app.library.addApps(listOf(AppEntry(packageName = pkg, label = label, addedAt = System.currentTimeMillis())))
        }
        vm.app.library.updateMeta(key) { PortLink.meta(it, port, vm.settings.lang) }
        place()
        vm.viewModelScope.launch {
            vm.refreshInstalled()
            vm.materialize(listOf(key))
            vm.applyPortArt(key, vm.ports.originalArtFor(port))
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
        place()
        // Los instalados con la primera versión de la tienda llevaban " (Port)" en el nombre fijado.
        present.forEach { port ->
            val key = PortLink.key(port.packageName)
            val meta = vm.app.library.appByKey(key)?.meta ?: return@forEach
            if (PortLink.withoutLegacySuffix(meta) != null) vm.app.library.updateMeta(key) { PortLink.withoutLegacySuffix(it) ?: it }
        }
    }
}
