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
import java.security.MessageDigest

/**
 * Fuente única de la biblioteca.
 *
 * La interfaz trabaja contra una instantánea en memoria (StateFlow) que cambia
 * al instante y de forma síncrona: quien modifica algo puede releerlo en la
 * línea siguiente. La persistencia va detrás, con un pequeño retardo para
 * agrupar cambios seguidos: la [LibraryStore] recibe lo último guardado y lo
 * nuevo, y escribe solo la diferencia (Room, ver RoomLibraryStore).
 */
class LibraryRepository(private val store: LibraryStore, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(Library())
    val state: StateFlow<Library> = _state.asStateFlow()

    private val loadedSignal = CompletableDeferred<Unit>()
    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    /** Lo último que llegó a la tienda: la base de la siguiente diferencia. */
    private var persisted = Library()

    val current: Library get() = _state.value

    /** Carga la biblioteca guardada. Se llama una vez al arrancar la app. */
    fun load() {
        scope.launch(Dispatchers.IO) {
            // Si la base de datos no se puede leer, se arranca vacío: mejor una
            // biblioteca en blanco que una app que no abre. Como lo guardado
            // pasa a ser "nada", los siguientes guardados solo añaden filas.
            val lib = runCatching { store.load() }.getOrElse { Library() }
            persisted = lib
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

    private suspend fun writeNow() {
        // Antes de cargar no hay base contra la que calcular la diferencia:
        // lo que se cambiara entonces lo sustituye la propia carga.
        loadedSignal.await()
        saveMutex.withLock {
            val next = _state.value
            if (next === persisted) return@withLock
            // Si falla, `persisted` no se mueve: el siguiente guardado vuelve a
            // intentar la diferencia completa y no se pierde nada.
            runCatching { store.save(persisted, next) }.onSuccess { persisted = next }
        }
    }

    /* ── Carpetas y ROMs ───────────────────────────────────────── */

    /** Da de alta una carpeta con las ROMs encontradas. Devuelve las claves de las ROMs nuevas. */
    fun addFolder(folder: RomFolder, found: List<RomScanner.Found>): List<String> {
        val roms = found.filterNot { it.docId in folder.excluded }.map { newRom(folder, it) }
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
            // Lo que el usuario quitó no vuelve por un reanálisis: el archivo
            // sigue ahí, pero él ya dijo que no lo quiere en la biblioteca.
            val merged = found.filterNot { it.docId in folder.excluded }.map { f ->
                existing[f.docId]?.copy(
                    fileName = f.name,
                    relPath = f.relPath,
                    size = f.size,
                    modified = f.modified,
                    isDirectory = f.isDir,
                    // El .exe se vuelve a resolver en cada análisis: una
                    // actualización del juego puede haberlo movido o renombrado.
                    mainDocId = f.mainDocId,
                    mainFile = f.mainFile,
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

    /**
     * Quita un juego de la biblioteca sin tocar el archivo.
     *
     * Elyndra no borra nada del almacenamiento: lo que hace es olvidarse del
     * juego y anotar su documento en [RomFolder.excluded], para que el
     * siguiente análisis no lo devuelva.
     */
    fun removeRom(romId: String) = update { lib ->
        val rom = lib.roms.firstOrNull { it.id == romId } ?: return@update lib
        lib.copy(
            roms = lib.roms.filterNot { it.id == romId },
            folders = lib.folders.map {
                if (it.id == rom.folderId) it.copy(excluded = it.excluded + rom.docId) else it
            },
            sessions = lib.sessions.filterNot { it.key == rom.key },
        )
    }

    /** Deshace todos los "quitar" de una carpeta; los juegos vuelven al reanalizar. */
    fun restoreRemoved(folderId: String) = updateFolder(folderId) { it.copy(excluded = emptySet()) }

    /** Id del juego dentro del runtime de Windows; null lo borra. */
    fun setPcGameId(romId: String, gameId: String?) = updateRom(romId) { it.copy(pcGameId = gameId) }

    fun updateFolder(folderId: String, transform: (RomFolder) -> RomFolder) = update { lib ->
        lib.copy(folders = lib.folders.map { if (it.id == folderId) transform(it) else it })
    }

    /** Fija (o borra, con [path] = null) una imagen de carpeta: "cover", "hero", "logo" o "icon". */
    fun setFolderArt(folderId: String, kind: String, path: String?) = updateFolder(folderId) { f ->
        when (kind) {
            "cover" -> f.copy(cover = path)
            "hero" -> f.copy(hero = path)
            "logo" -> f.copy(logo = path)
            "icon" -> f.copy(icon = path)
            else -> f
        }
    }

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

    fun addPlaytime(key: String, minutes: Int, start: Long) =
        recordSession(PlaySession(key, start, minutes))

    /**
     * Guarda una sesión terminada y suma sus minutos al juego. Las salidas
     * inmediatas (0 minutos) también se guardan: no suman tiempo, pero son lo
     * que delata un emulador que no funciona con ese juego.
     */
    fun recordSession(session: PlaySession) {
        if (session.minutes > 0) updateStats(session.key) { it.copy(minutes = it.minutes + session.minutes) }
        update { lib -> lib.copy(sessions = (lib.sessions + session).takeLast(MAX_SESSIONS)) }
    }

    fun markAutoScan(now: Long) = update { it.copy(lastAutoScan = now) }

    fun romByKey(key: String): RomEntry? =
        if (key.startsWith("r:")) current.roms.firstOrNull { it.id == key.removePrefix("r:") } else null

    fun appByKey(key: String): AppEntry? =
        if (key.startsWith("a:")) current.apps.firstOrNull { it.packageName == key.removePrefix("a:") } else null

    fun folder(folderId: String): RomFolder? = current.folders.firstOrNull { it.id == folderId }

    fun folderByKey(key: String): RomFolder? =
        if (key.startsWith("f:")) folder(key.removePrefix("f:")) else null

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
        mainDocId = f.mainDocId,
        mainFile = f.mainFile,
        addedAt = System.currentTimeMillis(),
    )

    companion object {
        /**
         * Tope de sesiones guardadas. Con el JSON eran 2000 para no reescribir
         * un archivo enorme en cada cambio; en Room solo se escribe la sesión
         * nueva, y el historial completo es lo que alimenta el perfil de cada
         * juego y la elección de emulador. El tope queda como seguro.
         */
        const val MAX_SESSIONS = 20_000

        fun romId(folderId: String, docId: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$folderId|$docId".toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
