package com.elyndra.launcher.domain.library

import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.library.InstalledApp

/** Lo que ofrece "Añadir juego" dentro de una carpeta. */
enum class FolderAddOption {
    /** Una app instalada, puesta en esta carpeta (como un port: sale de la lista de apps). */
    AndroidApp,

    /** Un archivo elegido con el selector del sistema, copiado a la carpeta por SAF. */
    GameFile,

    /** Volver a analizar la carpeta. */
    Rescan,

    /** La tienda de ports, filtrada a la plataforma de la carpeta. */
    FindPort,
}

/**
 * "Añadir juego" desde una carpeta (Kotlin puro, FolderAddTest).
 *
 * Una carpeta de verdad admite todo; una virtual (la de unos ports, sin
 * árbol SAF) solo lo que no escribe ni lee el disco: una app y un port.
 */
object FolderAdd {

    fun options(folder: RomFolder): List<FolderAddOption> =
        if (folder.isVirtual) {
            listOf(FolderAddOption.AndroidApp, FolderAddOption.FindPort)
        } else {
            listOf(FolderAddOption.AndroidApp, FolderAddOption.GameFile, FolderAddOption.Rescan, FolderAddOption.FindPort)
        }

    /** Los caracteres que los proveedores de documentos no aceptan en un nombre. */
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /**
     * El nombre con el que se copia un archivo: el suyo, sin ruta ni
     * caracteres prohibidos. Null si no queda nada que valga ("", ".", "..").
     */
    fun safeName(displayName: String?): String? {
        val base = displayName?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
        val clean = base.replace(FORBIDDEN, "_").trim().trimEnd('.')
        return clean.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }

    /** ¿Ya hay en la carpeta un archivo con ese nombre? (SAF no distingue mayúsculas en casi ningún proveedor.) */
    fun exists(name: String, children: List<String>): Boolean = children.any { it.equals(name, ignoreCase = true) }

    /**
     * Las apps que se pueden poner en [folderId]: las instaladas que no
     * están ya en ella. Los juegos primero; dentro, por nombre.
     */
    fun appChoices(installed: List<InstalledApp>, library: Library, folderId: String): List<InstalledApp> {
        val here = library.apps.filter { it.folderId == folderId }.mapTo(HashSet()) { it.packageName }
        // Lo que está en una carpeta del usuario sale solo ahí: aquí no se vería.
        val filed = UserFolderLogic.owners(library)
        return installed.filter { it.packageName !in here && "a:${it.packageName}" !in filed }
            .sortedWith(compareByDescending<InstalledApp> { it.isGame }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /**
     * La biblioteca con [app] en [folderId], puesta a mano: si ya estaba en
     * la biblioteca se mueve (conserva su tiempo de juego y sus metadatos); si
     * no, entra ya en la carpeta. Una carpeta que no existe no cambia nada.
     */
    fun placeApp(library: Library, app: InstalledApp, folderId: String, now: Long): Library {
        if (library.folders.none { it.id == folderId }) return library
        val existing = library.apps.firstOrNull { it.packageName == app.packageName }
        if (existing == null) {
            val entry = AppEntry(packageName = app.packageName, label = app.label, addedAt = now, folderId = folderId, folderManual = true)
            return library.copy(apps = library.apps + entry)
        }
        if (existing.folderId == folderId && existing.folderManual) return library
        return library.copy(apps = library.apps.map { if (it === existing) it.copy(folderId = folderId, folderManual = true) else it })
    }

    /** "Sin carpeta": [packageName] vuelve a la lista de apps Android (un port vuelve a su carpeta automática). */
    fun unplace(library: Library, packageName: String): Library {
        val app = library.apps.firstOrNull { it.packageName == packageName } ?: return library
        if (app.folderId == null && !app.folderManual) return library
        return library.copy(apps = library.apps.map { if (it === app) it.copy(folderId = null, folderManual = false) else it })
    }
}
