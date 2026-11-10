package com.elyndra.launcher.domain.library

import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.UserFolder

/**
 * Las carpetas que crea el usuario (Kotlin puro, UserFolderLogicTest).
 *
 * Una carpeta guarda claves de juego, no juegos: un juego puede estar en
 * varias y borrar una carpeta no toca lo que hay dentro. Si no cambia nada se
 * devuelve la misma [Library]: LibraryDiff compara por identidad.
 */
object UserFolderLogic {

    /** Distingue su id de los de `rom_folders`: comparten el prefijo de clave `f:`. */
    const val ID_PREFIX = "u-"
    const val KEY_PREFIX = "f:$ID_PREFIX"
    const val NAME_MAX = 60
    const val DESCRIPTION_MAX = 1000

    /** El "sistema" de su [com.elyndra.launcher.data.RomFolder] de vista: no existe en Systems. */
    const val SYSTEM_ID = "user-folder"
    private const val ABBR_LENGTH = 3

    fun isUserKey(key: String?): Boolean = key?.startsWith(KEY_PREFIX) == true

    /** El nombre en una línea y sin controles; null si no queda nada. */
    fun cleanName(raw: String?): String? = raw?.let { clean(it.replace(WHITESPACE, " "), NAME_MAX) }

    /** Conserva los saltos de línea; en blanco, null. */
    fun cleanDescription(raw: String?): String? = raw?.let { text ->
        clean(text.filter { it == '\n' || !it.isISOControl() }, DESCRIPTION_MAX)
    }

    private fun clean(text: String, max: Int): String? =
        text.filterNot { it.isISOControl() && it != '\n' }.trim().take(max)
            // El recorte no parte un emoji por la mitad.
            .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
            .trim()
            .takeIf { it.isNotEmpty() }

    /** El rótulo corto de su card, sin partir un emoji. */
    fun abbr(name: String): String =
        name.substring(0, name.offsetByCodePoints(0, minOf(ABBR_LENGTH, name.codePointCount(0, name.length)))).uppercase()

    fun sorted(folders: List<UserFolder>): List<UserFolder> = folders.sortedWith(compareBy({ it.position }, { it.createdAt }, { it.id }))

    /** Las carpetas en las que está [key]. */
    fun containing(library: Library, key: String): List<UserFolder> = sorted(library.userFolders.filter { key in it.items })

    /** Solo apps y ports: también sale en la sección Android. Una carpeta vacía, no. */
    fun isAndroidOnly(folder: UserFolder): Boolean = folder.items.isNotEmpty() && folder.items.all { it.startsWith("a:") }

    /** El mosaico sin arte propio: las carátulas de sus primeros juegos, en su orden. */
    fun collageCovers(folder: UserFolder, coverOf: (String) -> String?): List<String> =
        folder.items.asSequence()
            .mapNotNull(coverOf)
            .filter { it.isNotBlank() }
            .distinct()
            .take(FolderArtResolver.COLLAGE_MAX)
            .toList()

    fun create(library: Library, id: String, name: String, now: Long): Library {
        val clean = cleanName(name) ?: return library
        if (library.userFolders.any { it.id == id }) return library
        val position = (library.userFolders.maxOfOrNull { it.position } ?: -1) + 1
        return library.copy(userFolders = library.userFolders + UserFolder(id, clean, position = position, createdAt = now))
    }

    fun rename(library: Library, id: String, name: String): Library {
        val clean = cleanName(name) ?: return library
        return change(library, id) { if (it.name == clean) it else it.copy(name = clean) }
    }

    fun describe(library: Library, id: String, description: String?): Library {
        val clean = cleanDescription(description)
        return change(library, id) { if (it.description == clean) it else it.copy(description = clean) }
    }

    fun delete(library: Library, id: String): Library =
        if (library.userFolders.none { it.id == id }) library else library.copy(userFolders = library.userFolders.filterNot { it.id == id })

    /** Añade al final lo que exista en la biblioteca y no esté ya. */
    fun add(library: Library, id: String, keys: List<String>): Library {
        val live = liveKeys(library)
        return change(library, id) { f ->
            val fresh = keys.distinct().filter { it in live && it !in f.items }
            if (fresh.isEmpty()) f else f.copy(items = f.items + fresh)
        }
    }

    fun remove(library: Library, id: String, keys: Collection<String>): Library {
        val out = keys.toSet()
        return change(library, id) { f -> if (f.items.none { it in out }) f else f.copy(items = f.items.filterNot { it in out }) }
    }

    /**
     * [key] movido [delta] puestos entre los que se ven ([visible]), sin
     * pasarse de los extremos. Un juego oculto (app desinstalada, filtro) no
     * gasta pulsaciones, y conserva su sitio respecto a los demás.
     */
    fun moved(items: List<String>, visible: Set<String>, key: String, delta: Int): List<String> {
        val shown = items.filter { it in visible }
        val from = shown.indexOf(key)
        if (from < 0) return items
        val to = (from + delta).coerceIn(0, shown.lastIndex)
        if (to == from) return items
        val anchor = shown[to]
        val out = items.toMutableList().apply { remove(key) }
        out.add(out.indexOf(anchor) + (if (delta > 0) 1 else 0), key)
        return out
    }

    fun move(library: Library, id: String, key: String, delta: Int, visible: Set<String>): Library = change(library, id) { f ->
        val items = moved(f.items, visible, key, delta)
        if (items === f.items) f else f.copy(items = items)
    }

    /** Puestos que hay entre [key] y [target] entre los que se ven; null si alguno no está. */
    fun distance(items: List<String>, visible: Set<String>, key: String, target: String): Int? {
        val shown = items.filter { it in visible }
        val from = shown.indexOf(key)
        val to = shown.indexOf(target)
        return if (from < 0 || to < 0) null else to - from
    }

    /**
     * [after] sin los juegos que se fueron de la biblioteca. Se llama en cada
     * cambio (una pasada de metadatos son miles): mientras no cambie qué juegos
     * hay, no monta ningún conjunto.
     */
    fun prune(before: Library, after: Library): Library {
        if (after.userFolders.isEmpty() || sameGames(before, after)) return after
        val live = liveKeys(after)
        var changed = false
        val folders = after.userFolders.map { f ->
            if (f.items.all { it in live }) {
                f
            } else {
                changed = true
                f.copy(items = f.items.filter { it in live })
            }
        }
        return if (changed) after.copy(userFolders = folders) else after
    }

    private fun sameGames(a: Library, b: Library): Boolean {
        if (a.roms !== b.roms) {
            if (a.roms.size != b.roms.size) return false
            for (i in a.roms.indices) if (a.roms[i].id != b.roms[i].id) return false
        }
        if (a.apps !== b.apps) {
            if (a.apps.size != b.apps.size) return false
            for (i in a.apps.indices) if (a.apps[i].packageName != b.apps[i].packageName) return false
        }
        return true
    }

    fun liveKeys(library: Library): Set<String> {
        val keys = HashSet<String>((library.roms.size + library.apps.size) * 2)
        library.roms.mapTo(keys) { it.key }
        library.apps.mapTo(keys) { it.key }
        return keys
    }

    private inline fun change(library: Library, id: String, transform: (UserFolder) -> UserFolder): Library {
        val folder = library.userFolders.firstOrNull { it.id == id } ?: return library
        val updated = transform(folder)
        if (updated === folder) return library
        return library.copy(userFolders = library.userFolders.map { if (it === folder) updated else it })
    }

    private val WHITESPACE = Regex("\\s+")
}
