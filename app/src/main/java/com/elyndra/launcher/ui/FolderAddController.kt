package com.elyndra.launcher.ui

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.domain.library.FolderAdd
import com.elyndra.launcher.domain.library.FolderAddOption
import com.elyndra.launcher.domain.library.UserFolderLogic
import com.elyndra.launcher.library.InstalledApp
import com.elyndra.launcher.library.SafGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Añadir juego" dentro de una carpeta (el botón de su barra, SELECT con el
 * mando y sus opciones): una app instalada, un archivo copiado por SAF,
 * volver a analizarla o buscar un port de su plataforma. Qué se ofrece en
 * cada carpeta lo decide [FolderAdd].
 */
class FolderAddController(private val vm: ElyndraViewModel) {

    private val app get() = vm.app

    /**
     * Volver a pedir el árbol de una carpeta, ahora con escritura. Como el
     * selector de archivos, lo lanza [ElyndraApp] (hace falta un launcher) y
     * devuelve lo elegido por [onTreeGranted].
     */
    data class TreeRequest(val initial: Uri, val onGranted: (Uri?) -> Unit)

    var treeRequest by mutableStateOf<TreeRequest?>(null); private set

    /** Una copia en curso (no se lanza otra encima). */
    var copying by mutableStateOf(false); private set

    fun onTreeGranted(uri: Uri?) {
        val request = treeRequest ?: return
        treeRequest = null
        request.onGranted(uri)
    }

    /** La hoja de "Añadir juego" de [folder]. */
    fun open(folder: RomFolder) {
        if (UserFolderLogic.isUserKey(folder.key)) {
            vm.userFolderPicker.open(folder.id)
            return
        }
        val system = Systems.byId(folder.systemId)?.name ?: folder.systemId
        val actions = FolderAdd.options(folder).map { option ->
            when (option) {
                FolderAddOption.AndroidApp -> SheetAction(
                    UiText.res(R.string.folder_add_android),
                    detail = UiText.res(R.string.folder_add_android_detail),
                    icon = SheetIcon.App,
                    opensSheet = true,
                ) { pickApp(folder) }
                FolderAddOption.GameFile -> SheetAction(
                    UiText.res(R.string.folder_add_file),
                    detail = UiText.res(R.string.folder_add_file_detail),
                    icon = SheetIcon.Add,
                ) { pickFile(folder) }
                FolderAddOption.Rescan -> SheetAction(UiText.res(R.string.rescan_folder), icon = SheetIcon.Rescan) { vm.rescanFolder(folder) }
                FolderAddOption.FindPort -> SheetAction(
                    UiText.res(R.string.folder_add_port),
                    detail = UiText.Raw(system),
                    icon = SheetIcon.Search,
                ) { vm.openPortsFor(folder.systemId) }
            }
        }
        vm.showSheet(ActionSheetSpec(UiText.res(R.string.folder_add_title), UiText.Raw(system), actions))
    }

    /* ── App Android ──────────────────────────────────────────── */

    private fun pickApp(folder: RomFolder) {
        vm.viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { app.apps.launchable() }
            val choices = FolderAdd.appChoices(installed, vm.library, folder.id)
            if (choices.isEmpty()) {
                vm.showToast(UiText.res(R.string.folder_add_no_apps))
                return@launch
            }
            val actions = choices.map { a ->
                SheetAction(
                    UiText.Raw(a.label),
                    detail = UiText.Raw(a.packageName),
                    selected = vm.library.apps.any { it.packageName == a.packageName && it.folderId != null },
                ) { placeApp(folder, a) }
            }
            vm.showSheet(ActionSheetSpec(UiText.res(R.string.folder_add_android), UiText.Raw(Systems.byId(folder.systemId)?.name ?: folder.systemId), actions))
        }
    }

    private fun placeApp(folder: RomFolder, picked: InstalledApp) {
        val wasNew = vm.library.apps.none { it.packageName == picked.packageName }
        app.library.update { FolderAdd.placeApp(it, picked, folder.id, System.currentTimeMillis()) }
        val key = "a:${picked.packageName}"
        vm.showToast(UiText.res(R.string.folder_add_app_done, picked.label))
        vm.materialize(listOf(key))
        if (wasNew && app.settings.autoMeta && app.credentials.anyConfigured()) vm.engine.start(listOf(key), force = false)
    }

    /* ── Archivo de juego ─────────────────────────────────────── */

    private fun pickFile(folder: RomFolder) {
        if (!SafGuard.allows(folder, "folderAddFile") || copying) return
        vm.requestFile(listOf("*/*")) { uri -> if (uri != null) withWriteAccess(folder) { copy(folder, uri) } }
    }

    /**
     * [then] con permiso de escritura sobre el árbol de [folder]. Si el que
     * se guardó es solo de lectura, se vuelve a pedir el MISMO árbol; otro
     * distinto no vale (nunca se escribe fuera de la carpeta).
     */
    private fun withWriteAccess(folder: RomFolder, then: () -> Unit) {
        val tree = SafGuard.tree(folder, "folderAddWrite") ?: return
        if (app.files.canWrite(folder.treeUri)) {
            then()
            return
        }
        val initial = DocumentsContract.buildDocumentUriUsingTree(tree, folder.rootDocId)
        vm.showToast(UiText.res(R.string.folder_add_write_needed))
        treeRequest = TreeRequest(initial) { granted ->
            when {
                granted == null -> Unit
                granted.toString() != folder.treeUri -> vm.showToast(UiText.res(R.string.folder_add_wrong_folder))
                else -> {
                    runCatching { app.files.takeWritePermission(granted) }
                    if (app.files.canWrite(folder.treeUri)) then() else vm.showToast(UiText.res(R.string.folder_add_write_denied))
                }
            }
        }
    }

    private fun copy(folder: RomFolder, source: Uri) {
        val tree = SafGuard.tree(folder, "folderAddCopy") ?: return
        copying = true
        vm.viewModelScope.launch {
            try {
                val (display, mime) = withContext(Dispatchers.IO) { describe(source) }
                val name = FolderAdd.safeName(display)
                if (name == null) {
                    vm.showToast(UiText.res(R.string.folder_add_copy_failed))
                    return@launch
                }
                val existing = withContext(Dispatchers.IO) { app.scanner.children(tree, folder.rootDocId).map { it.name } }
                if (FolderAdd.exists(name, existing)) {
                    vm.showToast(UiText.res(R.string.folder_add_exists, name))
                    return@launch
                }
                vm.showToast(UiText.res(R.string.folder_add_copying, name), durationMs = 60_000)
                val doc = withContext(Dispatchers.IO) { app.files.copyInto(folder.treeUri, folder.rootDocId, source, name, mime) }
                if (doc == null) {
                    vm.showToast(UiText.res(R.string.folder_add_copy_failed))
                    return@launch
                }
                vm.showToast(UiText.res(R.string.folder_add_copied, name))
                vm.rescanFolder(folder)
            } finally {
                copying = false
            }
        }
    }

    /** El nombre y el tipo del archivo elegido. */
    private fun describe(uri: Uri): Pair<String?, String> {
        val resolver = app.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        return name to mime
    }
}
