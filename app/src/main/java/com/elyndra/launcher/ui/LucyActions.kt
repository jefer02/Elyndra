package com.elyndra.launcher.ui

import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.library.Names
import com.elyndra.launcher.lucy.LucyTools
import com.elyndra.launcher.metadata.ArtKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ejecuta contra la biblioteca real lo que Lucy pide.
 *
 * El modelo nombra las cosas por su título; aquí se resuelve a la clave de la
 * biblioteca. Todo lo que toca estado de la interfaz se hace en el hilo
 * principal, que es donde vive el estado de Compose.
 */
class LucyActions(private val vm: ElyndraViewModel) {

    /** Un elemento de la biblioteca, sea lo que sea. */
    private data class Target(val key: String, val title: String, val what: String)

    suspend fun run(name: String, args: JSONObject): JSONObject = when (name) {
        LucyTools.OPEN_GAME -> openGame(args.optString("title"))
        LucyTools.ADD_GAME -> addGame(args.optString("title"))
        LucyTools.REMOVE_GAME -> removeGame(args.optString("title"))
        LucyTools.SET_ART -> setArt(args.optString("title"), args.optString("kind"))
        LucyTools.SET_ACCENT -> setAccent(args.optString("accent"))
        LucyTools.SET_DARK_MODE -> setDarkMode(args.optBoolean("enabled"))
        LucyTools.LIST_INSTALLED_APPS -> listInstalledApps()
        else -> fail("acción desconocida")
    }

    /* ── acciones ─────────────────────────────────────────────── */

    private suspend fun openGame(title: String): JSONObject {
        val target = resolve(title) ?: return notFound(title)
        if (target.what == "folder") {
            return fail("«${target.title}» es una carpeta de ROMs, no un juego: no se lanza.")
        }
        val opened = onMain { vm.openByKey(target.key) }
        return if (opened) ok("Lanzando «${target.title}».") else fail("no se ha podido lanzar «${target.title}»")
    }

    private suspend fun addGame(title: String): JSONObject {
        val installed = vm.installedApps()
        val match = installed.firstOrNull { it.label.equals(title, ignoreCase = true) }
            ?: installed.maxByOrNull { Names.similarity(title, it.label) }
                ?.takeIf { Names.similarity(title, it.label) >= 0.7 }
            ?: return fail("no hay ninguna app instalada que se llame «$title»")
        if (vm.library.apps.any { it.packageName == match.packageName }) {
            return fail("«${match.label}» ya está en la biblioteca")
        }
        val key = onMain { vm.addInstalledApp(match.packageName, match.label) }
            ?: return fail("no se ha podido añadir «${match.label}»")
        return ok("«${match.label}» añadido a la biblioteca.").put("key", key)
    }

    private suspend fun removeGame(title: String): JSONObject {
        val target = resolve(title) ?: return notFound(title)
        if (target.what == "rom") {
            return fail(
                "«${target.title}» es una ROM suelta: se quita su carpeta entera o se borra el archivo, " +
                    "no se puede quitar una sola desde aquí.",
            )
        }
        val removed = onMain { vm.removeFromLibrary(target.key) }
        return if (removed) {
            ok("«${target.title}» fuera de la biblioteca. No se ha borrado nada del almacenamiento.")
        } else {
            fail("no se ha podido quitar «${target.title}»")
        }
    }

    private suspend fun setArt(title: String, kindName: String): JSONObject {
        val target = resolve(title) ?: return notFound(title)
        val kind = when (kindName.lowercase()) {
            "cover" -> ArtKind.Cover
            "background" -> ArtKind.Background
            "logo" -> ArtKind.Logo
            "icon" -> ArtKind.Icon
            else -> return fail("clase de imagen desconocida: $kindName")
        }
        if (kind == ArtKind.Cover && target.what != "rom") {
            return fail("«${target.title}» se representa con icono o logo, no con carátula")
        }
        val applied = vm.applyArtAuto(target.key, kind)
        return if (applied) {
            ok("Puesta la imagen ($kindName) de «${target.title}».")
        } else {
            fail("ningún servicio configurado ha dado una imagen ($kindName) para «${target.title}»")
        }
    }

    private suspend fun setAccent(accent: String): JSONObject {
        val id = accent.trim().lowercase()
        if (id !in ACCENT_IDS) return fail("color desconocido: $accent")
        onMain { vm.settings.setAccent(id) }
        return ok("Acento cambiado a $id.")
    }

    private suspend fun setDarkMode(enabled: Boolean): JSONObject {
        onMain { if (vm.settings.darkMode != enabled) vm.settings.toggleDark() }
        return ok(if (enabled) "Interfaz en modo oscuro." else "Interfaz en modo claro.")
    }

    private suspend fun listInstalledApps(): JSONObject {
        val inLibrary = vm.library.apps.map { it.packageName }.toSet()
        val apps = vm.installedApps()
            .filter { it.packageName !in inLibrary }
            .sortedByDescending { it.isGame }
            .take(80)
        return ok("apps instaladas que no están en la biblioteca")
            .put("apps", JSONArray(apps.map { it.label }))
    }

    /* ── utilidades ───────────────────────────────────────────── */

    /**
     * Del título que dice el modelo a un elemento real: primero exacto, luego
     * por contención y, si no, el parecido más alto que llegue al 70 %.
     */
    private fun resolve(title: String): Target? {
        val q = title.trim()
        if (q.isEmpty()) return null
        val targets = vm.library.apps.map { Target(it.key, it.displayTitle, "app") } +
            vm.library.roms.map { Target(it.key, it.displayTitle, "rom") } +
            vm.library.folders.mapNotNull { f ->
                Systems.byId(f.systemId)?.let { Target(f.key, it.name, "folder") }
            }
        return targets.firstOrNull { it.title.equals(q, ignoreCase = true) }
            ?: targets.firstOrNull { it.title.contains(q, ignoreCase = true) }
            ?: targets.maxByOrNull { Names.similarity(q, it.title) }
                ?.takeIf { Names.similarity(q, it.title) >= 0.7 }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private fun ok(detail: String) = JSONObject().put("ok", true).put("detail", detail)

    private fun fail(detail: String) = JSONObject().put("ok", false).put("detail", detail)

    private fun notFound(title: String) =
        fail("«$title» no está en la biblioteca del usuario")

    private companion object {
        val ACCENT_IDS = setOf(
            "mandarina", "fuego", "menta", "cobalto", "lila",
            "coral", "turquesa", "oro", "chicle", "grafito",
        )
    }
}
