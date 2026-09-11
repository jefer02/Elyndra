package com.elyndra.launcher.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.library.InstalledApp
import com.elyndra.launcher.library.RomScanner
import com.elyndra.launcher.library.SafPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Pantalla "Añadir": juegos Android instalados y carpetas de ROMs. */
class AddController(private val vm: ElyndraViewModel) {

    private val app get() = vm.app

    var tab by mutableStateOf(AddTab.Android); private set

    /* ── Juegos Android ───────────────────────────────────────── */

    var apps by mutableStateOf<List<InstalledApp>>(emptyList()); private set
    var appsLoading by mutableStateOf(false); private set
    var showAllApps by mutableStateOf(false); private set
    val picked = mutableStateListOf<String>()

    fun onOpen() {
        loadApps()
    }

    fun updateTab(t: AddTab) {
        tab = t
    }

    fun loadApps() {
        if (appsLoading) return
        appsLoading = true
        vm.viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { app.apps.launchable() }
            apps = list
            appsLoading = false
            if (list.none { it.isGame }) showAllApps = true
        }
    }

    fun toggleShowAll() {
        showAllApps = !showAllApps
    }

    fun gameCount(): Int = apps.count { it.isGame }

    fun visibleApps(): List<InstalledApp> = if (showAllApps) apps else apps.filter { it.isGame }

    fun isInLibrary(pkg: String): Boolean = vm.library.apps.any { it.packageName == pkg }

    fun togglePicked(pkg: String) {
        if (isInLibrary(pkg)) return
        if (!picked.remove(pkg)) picked.add(pkg)
    }

    fun addPicked() {
        if (picked.isEmpty()) return
        val now = System.currentTimeMillis()
        val entries = picked.mapNotNull { pkg ->
            apps.firstOrNull { it.packageName == pkg }?.let { AppEntry(packageName = pkg, label = it.label, addedAt = now) }
        }
        val keys = app.library.addApps(entries)
        picked.clear()
        vm.showToast(UiText.plural(R.plurals.toast_added_apps, keys.size))
        vm.go(Screen.Library)
        keys.firstOrNull()?.let { vm.select(it) }
        if (app.settings.autoMeta && keys.isNotEmpty() && app.credentials.anyConfigured()) {
            vm.engine.start(keys, force = false)
        }
    }

    /* ── Carpeta de ROMs ──────────────────────────────────────── */

    var folder by mutableStateOf<PickedFolder?>(null); private set
    var systemId by mutableStateOf<String?>(null); private set
    var emulatorId by mutableStateOf<String?>(null); private set
    var scan by mutableStateOf<ScanState>(ScanState.Idle); private set
    var bulkProgress by mutableStateOf<UiText?>(null); private set

    private var scanJob: Job? = null

    /** Resultado de ACTION_OPEN_DOCUMENT_TREE. */
    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        val granted = runCatching { app.files.takePermission(uri) }.isSuccess
        if (!granted) {
            vm.showToast(UiText.res(R.string.toast_folder_permission_failed))
            return
        }
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return
        val display = SafPaths.displayPath(uri.authority, rootDocId)
        val name = SafPaths.lastSegment(rootDocId).ifEmpty { display }
        if (vm.library.folders.any { it.treeUri == uri.toString() && it.rootDocId == rootDocId }) {
            // Ya está dada de alta: se avisa y no se prepara otra (evita tarjetas duplicadas).
            vm.showToast(UiText.res(R.string.folder_already_added))
            return
        }
        scanJob?.cancel()
        scan = ScanState.Idle
        folder = PickedFolder(uri, rootDocId, display, name)
        Systems.detect(name)?.let { setSystem(it.id) }

        vm.viewModelScope.launch {
            val subs = runCatching { app.scanner.subfolders(uri, rootDocId) }.getOrDefault(emptyList())
            val detected = subs.mapNotNull { c -> Systems.detect(c.name)?.let { DetectedSub(c.docId, c.name, it) } }
            if (folder?.rootDocId == rootDocId) folder = folder?.copy(detected = detected)
        }
    }

    fun setSystem(id: String) {
        systemId = id
        emulatorId = vm.defaultEmulator(id)
        scanJob?.cancel()
        scan = ScanState.Idle
    }

    fun setEmulator(id: String) {
        emulatorId = id
    }

    fun chooseOtherApp() {
        vm.appPicker { pkg -> emulatorId = Emulators.CUSTOM_PREFIX + pkg }
    }

    /** Botón principal: analizar, o añadir si el análisis ya terminó con resultados. */
    fun primaryAction() {
        when (val s = scan) {
            is ScanState.Scanning -> Unit
            is ScanState.Done -> if (s.found.isNotEmpty()) confirmAdd(s.found) else startScan()
            else -> startScan()
        }
    }

    fun startScan() {
        val f = folder ?: return
        val system = Systems.byId(systemId)
        if (system == null) {
            vm.showToast(UiText.res(R.string.choose_system_first))
            return
        }
        scanJob?.cancel()
        scan = ScanState.Scanning(0, 0, "")
        scanJob = vm.viewModelScope.launch {
            val found = try {
                app.scanner.scan(f.treeUri, f.rootDocId, system) { p ->
                    scan = ScanState.Scanning(p.scanned, p.found, p.current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scan = ScanState.Failed(UiText.res(R.string.scan_failed, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            scan = ScanState.Done(found)
        }
    }

    private fun confirmAdd(found: List<RomScanner.Found>) {
        val f = folder ?: return
        val sys = Systems.byId(systemId) ?: return
        val now = System.currentTimeMillis()
        val rf = RomFolder(
            id = UUID.randomUUID().toString(),
            systemId = sys.id,
            treeUri = f.treeUri.toString(),
            rootDocId = f.rootDocId,
            displayPath = f.displayPath,
            emulatorId = emulatorId ?: vm.defaultEmulator(sys.id),
            addedAt = now,
            lastScan = now,
        )
        val keys = app.library.addFolder(rf, found)
        vm.showToast(UiText.plural(R.plurals.toast_folder_added, keys.size, keys.size, sys.name))
        resetRoms()
        vm.go(Screen.Library)
        vm.select(rf.key)
        if (app.settings.autoMeta && keys.isNotEmpty() && app.credentials.anyConfigured()) {
            vm.engine.start(keys, force = false)
        }
    }

    /** Añade de una vez todas las subcarpetas que se reconocieron como sistemas. */
    fun addAllDetected() {
        val f = folder ?: return
        val subs = f.detected
        if (subs.isEmpty() || bulkProgress != null) return
        scanJob?.cancel()
        scanJob = vm.viewModelScope.launch {
            val keys = mutableListOf<String>()
            var systems = 0
            subs.forEachIndexed { i, sub ->
                bulkProgress = UiText.res(R.string.adding_systems, sub.system.name, i + 1, subs.size)
                val exists = vm.library.folders.any { it.treeUri == f.treeUri.toString() && it.rootDocId == sub.docId }
                if (exists) return@forEachIndexed
                val found = runCatching { app.scanner.scan(f.treeUri, sub.docId, sub.system) }.getOrDefault(emptyList())
                if (found.isEmpty()) return@forEachIndexed
                val now = System.currentTimeMillis()
                val rf = RomFolder(
                    id = UUID.randomUUID().toString(),
                    systemId = sub.system.id,
                    treeUri = f.treeUri.toString(),
                    rootDocId = sub.docId,
                    displayPath = SafPaths.displayPath(f.treeUri.authority, sub.docId),
                    emulatorId = vm.defaultEmulator(sub.system.id),
                    addedAt = now,
                    lastScan = now,
                )
                keys += app.library.addFolder(rf, found)
                systems++
            }
            bulkProgress = null
            vm.showToast(UiText.plural(R.plurals.toast_bulk_added, keys.size, keys.size, systems))
            resetRoms()
            vm.go(Screen.Library)
            if (app.settings.autoMeta && keys.isNotEmpty() && app.credentials.anyConfigured()) {
                vm.engine.start(keys, force = false)
            }
        }
    }

    private fun resetRoms() {
        folder = null
        systemId = null
        emulatorId = null
        scan = ScanState.Idle
    }
}
