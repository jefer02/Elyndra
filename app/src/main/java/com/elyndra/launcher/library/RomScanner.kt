package com.elyndra.launcher.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.elyndra.launcher.data.GameSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Lectura real de carpetas de ROMs a través del Storage Access Framework.
 *
 * No hace falta ningún permiso de almacenamiento: el usuario concede el árbol
 * con ACTION_OPEN_DOCUMENT_TREE y se consulta con DocumentsContract, que es
 * mucho más rápido que DocumentFile en carpetas grandes.
 */
class RomScanner(private val resolver: ContentResolver) {

    data class Found(
        val docId: String,
        val name: String,
        val relPath: String,
        val size: Long,
        val modified: Long,
        val isDir: Boolean,
        /**
         * Juegos que son una carpeta (PC): el ejecutable que los arranca.
         * [mainDocId] es su documento — se guarda entero y no montado a mano
         * sobre el de la carpeta, porque no todos los proveedores de
         * almacenamiento construyen sus documentId concatenando rutas.
         */
        val mainDocId: String? = null,
        val mainFile: String? = null,
    )

    data class Progress(val scanned: Int, val found: Int, val current: String)

    data class Child(val docId: String, val name: String, val mime: String?, val size: Long, val modified: Long) {
        val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
        val extension: String get() = name.substringAfterLast('.', "").lowercase()
    }

    /** Hijos directos de un documento del árbol. */
    fun children(treeUri: Uri, docId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = ArrayList<Child>()
        resolver.query(uri, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out += Child(
                    docId = id,
                    name = c.getString(1) ?: continue,
                    mime = c.getString(2),
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                    modified = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return out
    }

    /** Subcarpetas visibles de una carpeta (para detectar varios sistemas a la vez). */
    suspend fun subfolders(treeUri: Uri, docId: String): List<Child> = withContext(Dispatchers.IO) {
        children(treeUri, docId).filter { it.isDir && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }
    }

    /** Contenido de un archivo de texto pequeño (hojas .cue/.m3u, .psvita). */
    fun readSmallText(treeUri: Uri, docId: String, maxBytes: Int = 256 * 1024): String? = runCatching {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val n = input.read(buffer, total, maxBytes - total)
                if (n <= 0) break
                total += n
            }
            String(buffer, 0, total, Charsets.UTF_8)
        }
    }.getOrNull()

    /**
     * Recorre la carpeta (y sus subcarpetas) y devuelve los juegos del sistema.
     * Oculta las pistas referenciadas por hojas .cue/.gdi/.m3u y trata como un
     * solo juego las carpetas de PS3 (formato JB, con PS3_GAME dentro).
     */
    suspend fun scan(
        treeUri: Uri,
        rootDocId: String,
        system: GameSystem,
        onProgress: (Progress) -> Unit = {},
    ): List<Found> = withContext(Dispatchers.IO) {
        // El PC no se analiza por extensión: una carpeta por juego (ver abajo).
        if (system.folderGames) return@withContext scanFolderGames(treeUri, rootDocId, system, onProgress)

        val results = ArrayList<Found>()
        val queue = ArrayDeque<Triple<String, String, Int>>()
        queue += Triple(rootDocId, "", 0)
        var scanned = 0

        while (queue.isNotEmpty() && results.size < MAX_RESULTS) {
            coroutineContext.ensureActive()
            val (dirId, rel, depth) = queue.removeFirst()
            val kids = runCatching { children(treeUri, dirId) }.getOrDefault(emptyList())
            scanned += kids.size

            val files = kids.filter { !it.isDir && !it.name.startsWith(".") }
            val dirs = kids.filter { it.isDir && !it.name.startsWith(".") }

            // Pistas referenciadas por hojas de disco válidas para este sistema.
            val hidden = HashSet<String>()
            files.filter { it.extension in DiscSheets.EXTENSIONS && it.extension in system.extensions }.forEach { sheet ->
                val text = if (sheet.extension == "ccd") "" else readSmallText(treeUri, sheet.docId).orEmpty()
                DiscSheets.referencedFiles(sheet.extension, sheet.name, text).forEach { hidden += it.lowercase() }
            }

            for (f in files) {
                if (f.extension !in system.extensions) continue
                if (f.name.lowercase() in hidden) continue
                results += Found(f.docId, f.name, rel + f.name, f.size, f.modified, isDir = false)
                if (results.size % 25 == 0) onProgress(Progress(scanned, results.size, f.name))
            }

            for (d in dirs) {
                val lower = d.name.lowercase()
                if (lower in SKIP_DIRS) continue
                if (system.dirGames && isDirectoryGame(treeUri, d, system)) {
                    results += Found(d.docId, d.name, rel + d.name, 0L, d.modified, isDir = true)
                    continue
                }
                // `code` / `content` / `meta` solo cuelgan de un juego de Wii U ya
                // dado de alta: bajar ahí solo sacaría sus .rpx y .tmd sueltos.
                if (system.id == WiiU.SYSTEM_ID && WiiU.isInnerDir(d.name)) continue
                if (depth < MAX_DEPTH) queue += Triple(d.docId, "$rel${d.name}/", depth + 1)
            }
            onProgress(Progress(scanned, results.size, rel.ifEmpty { "/" }))
        }
        results.sortedBy { it.relPath.lowercase() }
    }

    /**
     * Análisis de una biblioteca de PC: **una carpeta por juego**.
     *
     * El usuario elige la carpeta raíz donde tiene sus juegos y aquí se
     * recorre subcarpeta a subcarpeta. Cada subcarpeta directa es un juego —
     * no se baja un nivel más a buscar "juegos dentro de juegos", que es justo
     * lo que llenaría la biblioteca de ejecutables sueltos— y de cada una se
     * saca el .exe que la arranca ([PcGames.pickExecutable]).
     *
     * Una subcarpeta sin ningún ejecutable dentro se deja fuera: es la carpeta
     * de guardados, la de mods o una descompresión a medias.
     *
     * También se recogen los ejecutables sueltos que haya en la propia raíz,
     * para el portable que no está metido en su carpeta.
     */
    private suspend fun scanFolderGames(
        treeUri: Uri,
        rootDocId: String,
        system: GameSystem,
        onProgress: (Progress) -> Unit,
    ): List<Found> {
        val results = ArrayList<Found>()
        val kids = runCatching { children(treeUri, rootDocId) }.getOrDefault(emptyList())
        var scanned = kids.size

        // Portables sueltos en la raíz.
        kids.filter { !it.isDir && !it.name.startsWith(".") && it.extension in system.extensions }
            .forEach { results += Found(it.docId, it.name, it.name, it.size, it.modified, isDir = false) }

        val dirs = kids.filter { it.isDir && !it.name.startsWith(".") && it.name.lowercase() !in SKIP_DIRS }
        for (dir in dirs) {
            coroutineContext.ensureActive()
            if (results.size >= MAX_RESULTS) break
            onProgress(Progress(scanned, results.size, dir.name))

            val executables = ArrayList<PcGames.Executable>()
            scanned += collectExecutables(treeUri, dir.docId, "", 0, executables)
            if (!PcGames.looksLikeGame(executables)) continue

            val main = PcGames.pickExecutable(dir.name, executables)
            results += Found(
                docId = dir.docId,
                name = dir.name,
                relPath = dir.name,
                size = main?.size ?: 0L,
                modified = dir.modified,
                isDir = true,
                mainDocId = main?.docId,
                mainFile = main?.relPath,
            )
            onProgress(Progress(scanned, results.size, dir.name))
        }
        return results.sortedBy { it.relPath.lowercase() }
    }

    /**
     * Ejecutables de la carpeta de un juego, bajando hasta [PcGames.MAX_DEPTH].
     *
     * Devuelve cuántas entradas se han mirado, solo para el contador de
     * progreso. El tope de candidatos evita que una carpeta con miles de
     * archivos (un juego con mods, o la raíz equivocada) se coma el análisis.
     */
    private fun collectExecutables(
        treeUri: Uri,
        docId: String,
        rel: String,
        depth: Int,
        out: MutableList<PcGames.Executable>,
    ): Int {
        if (depth > PcGames.MAX_DEPTH || out.size >= MAX_EXECUTABLES) return 0
        val kids = runCatching { children(treeUri, docId) }.getOrDefault(emptyList())
        var scanned = kids.size
        for (child in kids) {
            if (child.name.startsWith(".")) continue
            if (child.isDir) {
                if (child.name.lowercase() in SKIP_DIRS) continue
                scanned += collectExecutables(treeUri, child.docId, "$rel${child.name}/", depth + 1, out)
            } else if (child.extension in PcGames.EXECUTABLE_EXTENSIONS) {
                if (out.size >= MAX_EXECUTABLES) break
                out += PcGames.Executable(child.docId, rel + child.name, child.size)
            }
        }
        return scanned
    }

    /**
     * Carpetas que son un juego entero: PS3 en formato JB (con `PS3_GAME` dentro)
     * y Wii U desempaquetado (con `code`, `content` y `meta`).
     */
    private fun isDirectoryGame(treeUri: Uri, dir: Child, system: GameSystem): Boolean {
        if (system.id == WiiU.SYSTEM_ID) {
            val subs = runCatching { children(treeUri, dir.docId) }.getOrDefault(emptyList())
            return WiiU.isGameDir(subs.filter { it.isDir }.map { it.name })
        }
        if (dir.name.lowercase().endsWith(".ps3")) return true
        return runCatching { children(treeUri, dir.docId).any { it.isDir && it.name.equals("PS3_GAME", ignoreCase = true) } }
            .getOrDefault(false)
    }

    companion object {
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        private const val MAX_DEPTH = 8
        private const val MAX_RESULTS = 50_000

        /** Candidatos a ejecutable que se miran por juego de PC antes de elegir. */
        private const val MAX_EXECUTABLES = 400

        /** Carpetas de medios, partidas o BIOS que nunca contienen juegos. */
        private val SKIP_DIRS = setOf(
            "bios", "saves", "save", "savestates", "states", "screenshots", "media", "images", "videos",
            "manuals", "downloaded_media", "cheats", "shaders", "thumbnails", "boxart", "covers", "snap",
            "snaps", "wheel", "marquees", "logos", "overlays", "playlists", "lost.dir", "android",
            "system volume information", "\$recycle.bin", ".trash",
        )
    }
}
