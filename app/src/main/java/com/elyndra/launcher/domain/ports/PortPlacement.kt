package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.data.Systems

/**
 * En qué carpeta de la biblioteca sale cada port instalado (Kotlin puro,
 * PortPlacementTest).
 *
 * Un port va a la carpeta de su sistema original: la que haya (con varias,
 * la de lo último jugado) o, si no hay ninguna, una carpeta VIRTUAL con el
 * nombre y el arte del sistema (`v-<sistema>`, sin árbol SAF). Cuando el
 * usuario añade una carpeta de verdad de ese sistema, sus ports pasan a ella y
 * la virtual desaparece; una virtual vacía también. Lo que el usuario movió a
 * mano ([com.elyndra.launcher.data.AppEntry.folderManual]) se queda donde lo
 * puso mientras esa carpeta exista (salvo una virtual que ya tiene carpeta de
 * verdad).
 *
 * Nada de esto toca el almacenamiento: solo la instantánea de la biblioteca.
 */
object PortPlacement {

    const val VIRTUAL_PREFIX = "v-"

    fun virtualId(systemId: String): String = VIRTUAL_PREFIX + systemId

    fun isVirtualId(folderId: String?): Boolean = folderId?.startsWith(VIRTUAL_PREFIX) == true

    /** El sistema de la biblioteca de una plataforma del catálogo (las que Elyndra no conoce, a PC). */
    fun systemFor(platform: String): String = if (Systems.byId(platform) != null) platform else "pc"

    fun virtualFolder(systemId: String, now: Long): RomFolder =
        RomFolder(id = virtualId(systemId), systemId = systemId, treeUri = "", rootDocId = "", displayPath = "", addedAt = now, isVirtual = true)

    /**
     * La carpeta de verdad para [systemId]: con varias, la de lo último jugado,
     * luego la última analizada y la última añadida. Los juegos de MS-DOS van a
     * una de MS-DOS o, si no hay, a una de PC.
     */
    fun bestRealFolder(library: Library, systemId: String): RomFolder? {
        val lastPlayed = HashMap<String, Long>()
        for (r in library.roms) lastPlayed.merge(r.folderId, r.stats.lastPlayed, ::maxOf)
        fun best(system: String) = library.folders.filter { !it.isVirtual && it.systemId == system }
            .maxWithOrNull(compareBy<RomFolder>({ lastPlayed[it.id] ?: 0L }, { it.lastScan }, { it.addedAt }, { it.id }))
        return best(systemId) ?: if (systemId == "dos") best("pc") else null
    }

    /**
     * La biblioteca con cada port en su sitio. [ports]: paquete → sistema de
     * los ports instalados (y aún en el teléfono). Las apps que no son ports
     * no tienen carpeta. Si no cambia nada, devuelve la misma [library] (la
     * misma instancia: LibraryDiff compara por identidad).
     */
    fun reconcile(library: Library, ports: Map<String, String>, now: Long): Library {
        val folders = library.folders.associateBy { it.id }
        val needed = LinkedHashMap<String, RomFolder>() // virtuales que hacen falta
        val apps = library.apps.map { app ->
            val system = ports[app.packageName]?.let(::systemFor)
            if (system == null) {
                return@map if (app.folderId == null && !app.folderManual) app else app.copy(folderId = null, folderManual = false)
            }
            val manual = app.folderId?.let { folders[it] }?.takeIf { app.folderManual }
            val keep = manual != null && (!manual.isVirtual || bestRealFolder(library, manual.systemId) == null)
            val target = when {
                keep -> manual!!
                else -> bestRealFolder(library, system) ?: needed.getOrPut(virtualId(system)) {
                    folders[virtualId(system)] ?: virtualFolder(system, now)
                }
            }
            if (target.isVirtual) needed[target.id] = target
            if (app.folderId == target.id && app.folderManual == keep) app else app.copy(folderId = target.id, folderManual = keep)
        }
        // Las virtuales vacías se van; las que hacen falta y no estaban, entran.
        val kept = library.folders.filter { !it.isVirtual || it.id in needed }
        val added = needed.values.filter { it.id !in folders }
        val foldersChanged = kept.size != library.folders.size || added.isNotEmpty()
        val appsChanged = apps.indices.any { apps[it] !== library.apps[it] }
        if (!foldersChanged && !appsChanged) return library
        return library.copy(
            folders = if (foldersChanged) kept + added else library.folders,
            apps = if (appsChanged) apps else library.apps,
        )
    }

    /** "Mover a carpeta": [packageName] a [folderId], fijado a mano. */
    fun move(library: Library, packageName: String, folderId: String): Library {
        if (library.folders.none { it.id == folderId }) return library
        val app = library.apps.firstOrNull { it.packageName == packageName } ?: return library
        if (app.folderId == folderId && app.folderManual) return library
        return library.copy(apps = library.apps.map { if (it === app) it.copy(folderId = folderId, folderManual = true) else it })
    }
}
