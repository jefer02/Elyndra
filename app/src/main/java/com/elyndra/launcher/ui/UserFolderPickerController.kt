package com.elyndra.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.domain.library.UserFolderPickLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * El selector de juegos de una carpeta del usuario. El índice se monta una
 * vez, fuera del hilo principal, y cada búsqueda espera a que se deje de
 * escribir: con diez mil ROMs, teclear no le cuesta nada a la interfaz.
 */
class UserFolderPickerController(private val vm: ElyndraViewModel) {

    private val repo get() = vm.app.library

    var folderName by mutableStateOf(""); private set
    var loading by mutableStateOf(false); private set
    var query by mutableStateOf(""); private set
    var results by mutableStateOf<List<UserFolderPickLogic.Candidate>>(emptyList()); private set

    /** Lo marcado, en el orden en que se marcó: así entra en la carpeta. */
    var picked by mutableStateOf<Set<String>>(emptySet()); private set

    private var folderId: String? = null
    private var index: List<UserFolderPickLogic.Candidate> = emptyList()
    private var resultsQuery = ""
    private var job: Job? = null
    private var releaseJob: Job? = null

    fun open(id: String) {
        val folder = repo.userFolder(id) ?: return
        job?.cancel()
        releaseJob?.cancel()
        folderId = id
        folderName = folder.name
        loading = true
        query = ""
        results = emptyList()
        picked = emptySet()
        index = emptyList()
        resultsQuery = ""
        vm.input.onPickerResults(0)
        vm.go(Screen.UserFolderPicker)
        val library = repo.current
        val installed = vm.installedPackages
        val ports = vm.ports.installed.values.mapTo(HashSet()) { it.packageName }
        job = vm.viewModelScope.launch {
            index = withContext(Dispatchers.Default) {
                UserFolderPickLogic.candidates(library, id, installed, ports) { Systems.byId(it)?.short }
            }
            loading = false
            applySearch()
        }
    }

    fun updateQuery(text: String) {
        query = text
        if (loading) return
        job?.cancel()
        job = vm.viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            applySearch()
        }
    }

    private suspend fun applySearch() {
        val text = query
        val previousQuery = resultsQuery
        val previous = results
        val all = index
        val found = withContext(Dispatchers.Default) { UserFolderPickLogic.search(all, text, previousQuery, previous) }
        resultsQuery = UserFolderPickLogic.normalizeQuery(text)
        results = found
        vm.input.onPickerResults(found.size)
    }

    fun toggle(key: String) {
        picked = if (key in picked) picked - key else picked + key
    }

    fun commit() {
        val id = folderId ?: return
        val keys = picked.toList()
        if (keys.isEmpty()) return
        repo.addToUserFolder(id, keys)
        close()
        vm.showToast(UiText.plural(R.plurals.user_folder_added_count, keys.size, keys.size))
    }

    fun close() {
        job?.cancel()
        folderId = null
        vm.go(Screen.Folder)
        // Después de la transición de salida, que aún pinta la lista.
        releaseJob = vm.viewModelScope.launch {
            delay(RELEASE_DELAY_MS)
            index = emptyList()
            results = emptyList()
            picked = emptySet()
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 140L
        const val RELEASE_DELAY_MS = 800L
    }
}
