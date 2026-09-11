package com.elyndra.launcher.data

import com.elyndra.launcher.library.Names
import com.elyndra.launcher.library.RomScanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Fuente única de la biblioteca. Mantiene el estado en memoria (StateFlow) y
 * lo persiste en JSON con escritura atómica (archivo temporal + rename) y un
 * pequeño retardo para agrupar cambios seguidos.
 */
class LibraryRepository(private val file: File, private val scope: CoroutineScope) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val _state = MutableStateFlow(Library())
    val state: StateFlow<Library> = _state.asStateFlow()

    private val loadedSignal = CompletableDeferred<Unit>()
    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    val current: Library get() = _state.value

    /** Carga el JSON del disco. Se llama una vez al arrancar la app. */
    fun load() {
        scope.launch(Dispatchers.IO) {
            val lib = runCatching {
                if (file.exists()) json.decodeFromString(Library.serializer(), file.readText()) else Library()
            }.getOrElse {
                // Un JSON corrupto no debe impedir arrancar: se aparta para poder recuperarlo a mano.
                runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt")) }
                Library()
            }
            _state.value = lib
            loadedSignal.complete(Unit)
        }
    }

    suspend fun awaitLoaded() = loadedSignal.await()

    fun update(transform: (Library) -> Library) {
        _state.update(transform)
        scheduleSave()
    }

    @Synchronized
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(400)
            writeNow()
        }
    }

    suspend fun flush() {
        synchronized(this) { saveJob?.cancel() }
        writeNow()
    }

    private suspend fun writeNow() = saveMutex.withLock {
        runCatching {
            val text = json.encodeToString(Library.serializer(), _state.value)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    /* ── Carpetas y ROMs ───────────────────────────────────────── */

    /** Da de alta una carpeta con las ROMs encontradas. Devuelve las claves de las ROMs nuevas. */
    fun addFolder(folder: RomFolder, found: List<RomScanner.Found>): List<String> {
        val roms = found.map { newRom(folder, it) }
        update { lib ->
            lib.copy(
                folders = lib.folders.filterNot { it.id == folder.id } + folder,
                roms = lib.roms.filterNot { it.folderId == folder.id } + roms,
            )
        }
        return roms.map { it.key }
    }

    data class ScanDiff(val added: List<String>, val removed: Int, val total: Int)

    /** Fusiona un reescaneo: conserva metadatos y tiempo de las ROMs que siguen ahí. */
    fun mergeScan(folderId: String, found: List<RomScanner.Found>, now: Long = System.currentTimeMillis()): ScanDiff {
        var diff = ScanDiff(emptyList(), 0, 0)
        update { lib ->
            val folder = lib.folders.firstOrNull { it.id == folderId } ?: return@update lib
            val existing = lib.roms.filter { it.folderId == folderId }.associateBy { it.docId }
            val added = mutableListOf<String>()
            val merged = found.map { f ->
                existing[f.docId]?.copy(
                    fileName = f.name,
                    relPath = f.relPath,
                    size = f.size,
                    modified = f.modified,
                    isDirectory = f.isDir,
                    hashes = existing[f.docId]?.hashes?.takeIf { it.size == f.size && it.modified == f.modified },
                ) ?: newRom(folder, f).also { added += it.key }
            }
            val removed = existing.size - (merged.size - added.size)
            diff = ScanDiff(added, removed.coerceAtLeast(0), merged.size)
            lib.copy(
                roms = lib.roms.filterNot { it.folderId == folderId } + merged,
                folders = lib.folders.map { if (it.id == folderId) it.copy(lastScan = now) else it },
            )
        }
        return diff
    }

    fun removeFolder(folderId: String) = update { lib ->
        val keys = lib.roms.filter { it.folderId == folderId }.map { it.key }.toSet()
        lib.copy(
            folders = lib.folders.filterNot { it.id == folderId },
            roms = lib.roms.filterNot { it.folderId == folderId },
            sessions = lib.sessions.filterNot { it.key in keys },
        )
    }

    fun setFolderEmulator(folderId: String, emulatorId: String?) = update { lib ->
        lib.copy(folders = lib.folders.map { if (it.id == folderId) it.copy(emulatorId = emulatorId) else it })
    }

    fun setRomEmulator(romId: String, emulatorId: String?) = updateRom(romId) { it.copy(emulatorId = emulatorId) }

    fun updateRom(romId: String, transform: (RomEntry) -> RomEntry) = update { lib ->
        lib.copy(roms = lib.roms.map { if (it.id == romId) transform(it) else it })
    }

    /* ── Apps Android ──────────────────────────────────────────── */

    fun addApps(apps: List<AppEntry>): List<String> {
        update { lib ->
            val have = lib.apps.map { it.packageName }.toSet()
            lib.copy(apps = lib.apps + apps.filterNot { it.packageName in have })
        }
        return apps.map { it.key }
    }

    fun removeApp(packageName: String) = update { lib ->
        lib.copy(
            apps = lib.apps.filterNot { it.packageName == packageName },
            sessions = lib.sessions.filterNot { it.key == "a:$packageName" },
        )
    }

    fun updateApp(packageName: String, transform: (AppEntry) -> AppEntry) = update { lib ->
        lib.copy(apps = lib.apps.map { if (it.packageName == packageName) transform(it) else it })
    }

    /* ── Metadatos y tiempo de juego por clave ("r:…" / "a:…") ── */

    fun updateMeta(key: String, transform: (GameMeta) -> GameMeta) {
        when {
            key.startsWith("r:") -> updateRom(key.removePrefix("r:")) { it.copy(meta = transform(it.meta)) }
            key.startsWith("a:") -> updateApp(key.removePrefix("a:")) { it.copy(meta = transform(it.meta)) }
        }
    }

    private fun updateStats(key: String, transform: (PlayStats) -> PlayStats) {
        when {
            key.startsWith("r:") -> updateRom(key.removePrefix("r:")) { it.copy(stats = transform(it.stats)) }
            key.startsWith("a:") -> updateApp(key.removePrefix("a:")) { it.copy(stats = transform(it.stats)) }
        }
    }

    fun recordLaunch(key: String, now: Long = System.currentTimeMillis()) =
        updateStats(key) { it.copy(launches = it.launches + 1, lastPlayed = now) }

    fun addPlaytime(key: String, minutes: Int, start: Long) {
        updateStats(key) { it.copy(minutes = it.minutes + minutes) }
        update { lib -> lib.copy(sessions = (lib.sessions + PlaySession(key, start, minutes)).takeLast(MAX_SESSIONS)) }
    }

    fun markAutoScan(now: Long) = update { it.copy(lastAutoScan = now) }

    fun romByKey(key: String): RomEntry? =
        if (key.startsWith("r:")) current.roms.firstOrNull { it.id == key.removePrefix("r:") } else null

    fun appByKey(key: String): AppEntry? =
        if (key.startsWith("a:")) current.apps.firstOrNull { it.packageName == key.removePrefix("a:") } else null

    fun folder(folderId: String): RomFolder? = current.folders.firstOrNull { it.id == folderId }

    private fun newRom(folder: RomFolder, f: RomScanner.Found) = RomEntry(
        id = romId(folder.id, f.docId),
        folderId = folder.id,
        systemId = folder.systemId,
        docId = f.docId,
        fileName = f.name,
        relPath = f.relPath,
        size = f.size,
        modified = f.modified,
        isDirectory = f.isDir,
        title = Names.cleanTitle(f.name, stripExtension = !f.isDir || f.name.substringAfterLast('.', "").length in 2..5),
    )

    companion object {
        const val MAX_SESSIONS = 2000

        fun romId(folderId: String, docId: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$folderId|$docId".toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
