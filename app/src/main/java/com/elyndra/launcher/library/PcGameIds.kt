package com.elyndra.launcher.library

import android.net.Uri
import com.elyndra.launcher.data.BannerHub
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * De dónde sale el id con el que se lanza un juego de PC.
 *
 * Los runtimes de Windows (BannerHub, GameHub, GameNative) no abren archivos:
 * abren un juego de su propia biblioteca, por id. Ese id puede venir de tres
 * sitios y se miran en este orden:
 *
 *   1. El que el usuario le haya puesto al juego ([RomEntry.pcGameId]).
 *   2. El archivo del propio juego, cuando lo que se dio de alta es un
 *      archivo-id (`Hollow Knight.iso` con `367520` dentro) o el archivo que
 *      exportó el runtime (.steam, .gog…).
 *   3. Un archivo-id suelto dentro de la carpeta del juego, para las
 *      bibliotecas donde cada juego es su carpeta con el .exe.
 *
 * El id escrito a mano va primero **a propósito**: es lo último que ha hecho
 * el usuario y tiene que notarse al lanzar, o el diálogo que lo pide parecería
 * no hacer nada. Borrarlo —dejarlo en blanco— devuelve el mando al archivo.
 *
 * Lo que sale de (1) y de un archivo-id que no exportó el runtime vale aunque
 * el juego se diera de alta como carpeta con su .exe: por eso se distingue con
 * [Resolved.assigned] (ver `RomRef.idIsAssigned`).
 */
class PcGameIds(private val files: Files) {

    constructor(scanner: RomScanner) : this(SafFiles(scanner))

    /** Lo único que hace falta del almacenamiento; así esto se puede probar. */
    interface Files {
        suspend fun read(treeUri: String, docId: String): String?
        suspend fun list(treeUri: String, docId: String): List<Entry>
    }

    data class Entry(val docId: String, val name: String, val size: Long)

    data class Resolved(val id: String?, val assigned: Boolean) {
        companion object { val None = Resolved(null, false) }
    }

    suspend fun resolve(folder: RomFolder, rom: RomEntry): Resolved {
        rom.pcGameId?.takeIf { it.isNotBlank() }?.let { return Resolved(it, assigned = true) }
        if (folder.systemId != PcGames.SYSTEM_ID) return Resolved.None
        val tree = folder.treeUri
        return fromOwnFile(tree, rom) ?: fromSidecarFile(tree, rom) ?: Resolved.None
    }

    /** El id de lo que se dio de alta: el archivo-id, o el que exportó el runtime. */
    private suspend fun fromOwnFile(tree: String, rom: RomEntry): Resolved? {
        val name = rom.mainFile?.substringAfterLast('/') ?: rom.fileName
        val launcher = PcGames.launcherOf(name)
        // El .desktop de Winlator se entrega entero por su ruta, sin leerlo.
        if (launcher != null && !launcher.carriesId) return null
        if (launcher == null && !PcGames.isIdFileName(name)) return null
        val id = BannerHub.parseId(files.read(tree, rom.mainDocId ?: rom.docId)) ?: return null
        // Lo que exporta el runtime ya viaja con su tienda; un archivo-id
        // cualquiera no, así que cuenta como un id asignado.
        return Resolved(id, assigned = launcher == null)
    }

    /**
     * El id anotado en un archivo suelto dentro de la carpeta del juego.
     *
     * Solo se mira en juegos que se dieron de alta como carpeta, y solo un
     * nivel: el archivo lo deja el usuario junto al .exe, no enterrado.
     */
    private suspend fun fromSidecarFile(tree: String, rom: RomEntry): Resolved? {
        if (!rom.isDirectory) return null
        val id = files.list(tree, rom.docId)
            .filter { PcGames.isIdFile(it.name, it.size) }
            .sortedBy { it.name.lowercase() }
            .firstNotNullOfOrNull { BannerHub.parseId(files.read(tree, it.docId)) }
        return id?.let { Resolved(it, assigned = true) }
    }

    /** Los mismos archivos, leídos de verdad del árbol SAF concedido. */
    private class SafFiles(private val scanner: RomScanner) : Files {

        override suspend fun read(treeUri: String, docId: String): String? = withContext(Dispatchers.IO) {
            scanner.readSmallText(Uri.parse(treeUri), docId, PcGames.MAX_LAUNCHER_BYTES)
        }

        override suspend fun list(treeUri: String, docId: String): List<Entry> = withContext(Dispatchers.IO) {
            scanner.children(Uri.parse(treeUri), docId)
                .filterNot { it.isDir }
                .map { Entry(it.docId, it.name, it.size) }
        }
    }
}
