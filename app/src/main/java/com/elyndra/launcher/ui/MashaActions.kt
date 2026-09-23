package com.elyndra.launcher.ui

import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.library.Names
import com.elyndra.launcher.masha.MashaAttachment
import com.elyndra.launcher.masha.MashaBrain
import com.elyndra.launcher.masha.MashaToolbox
import com.elyndra.launcher.masha.MashaTools
import com.elyndra.launcher.masha.ToolCall
import com.elyndra.launcher.masha.ToolResult
import com.elyndra.launcher.masha.ToolSpec
import com.elyndra.launcher.masha.bool
import com.elyndra.launcher.masha.str
import com.elyndra.launcher.masha.toolFail
import com.elyndra.launcher.masha.toolOk
import com.elyndra.launcher.metadata.ArtKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Ejecuta contra la biblioteca real lo que Masha pide.
 *
 * Lo que no toca la interfaz (buscar, perfilar, planear, listas, arcos,
 * memoria) lo hace [com.elyndra.launcher.masha.MashaSkills]; aquí queda lo que
 * sí: lanzar, filtrar el carrusel, cambiar el tema, añadir y quitar. Todo lo
 * que toca estado de Compose se hace en el hilo principal.
 *
 * Los textos de `detail` son para el modelo, no para el usuario: él los cuenta
 * con sus palabras y en el idioma de la conversación.
 */
class MashaActions(private val vm: ElyndraViewModel, private val brain: MashaBrain) : MashaToolbox {

    override val specs: List<ToolSpec> get() = MashaTools.specs

    override suspend fun execute(call: ToolCall): ToolResult {
        val a = call.arguments
        val skills = brain.skills
        return when (call.name) {
            MashaTools.LAUNCH_GAME -> launchGame(a.str("title"))
            MashaTools.FIND_GAMES -> skills.findGames(a)
            MashaTools.GET_GAME_PROFILE -> skills.gameProfile(a)
            MashaTools.SUGGEST_EMULATOR -> skills.suggestEmulator(a)
            MashaTools.SET_GAME_EMULATOR -> setGameEmulator(a)
            MashaTools.PLAN_SESSION -> skills.planSession(a)
            MashaTools.CREATE_LIST -> skills.createList(a)
            MashaTools.OPEN_LIST -> skills.openList(a)
            MashaTools.CREATE_ARC -> skills.createArc(a)
            MashaTools.GET_ARCS -> skills.getArcs()
            MashaTools.UPDATE_METADATA -> updateMetadata(a)
            MashaTools.CURATION_REPORT -> skills.curationReport(a)
            MashaTools.GET_STATS -> skills.stats(a)
            MashaTools.REMEMBER -> skills.remember(a)
            MashaTools.FORGET -> skills.forget(a)
            MashaTools.FILTER_LIBRARY -> filterLibrary(a)
            MashaTools.SET_ART -> setArt(a.str("title"), a.str("kind"))
            MashaTools.ADD_GAME -> addGame(a.str("title"))
            MashaTools.REMOVE_GAME -> removeGame(a.str("title"))
            MashaTools.LIST_INSTALLED_APPS -> listInstalledApps()
            MashaTools.SET_ACCENT -> setAccent(a.str("accent"))
            MashaTools.SET_DARK_MODE -> setDarkMode(a.bool("enabled"))
            else -> toolFail("unknown tool ${call.name}")
        }
    }

    /* ── acciones ─────────────────────────────────────────────── */

    suspend fun launchGame(title: String?): ToolResult {
        val target = resolve(title) ?: return notFound(title)
        if (target.what == What.Folder) return toolFail("'${target.title}' is an emulator folder, not a game")
        val opened = onMain { vm.openByKey(target.key) }
        return if (opened) {
            toolOk("launching '${target.title}'", MashaAttachment.Done("▶ ${target.title}"), mutating = true)
        } else {
            toolFail("could not launch '${target.title}'")
        }
    }

    private suspend fun setGameEmulator(a: JsonObject): ToolResult {
        val target = resolve(a.str("title")) ?: return notFound(a.str("title"))
        val rom = vm.library.roms.firstOrNull { it.key == target.key }
            ?: return toolFail("'${target.title}' is not a ROM; Android games don't use emulators")
        val query = a.str("emulator") ?: return toolFail("which emulator?")
        val emulatorId = vm.orchestrator.resolveEmulator(query, rom.systemId)
            ?: return toolFail("no emulator called '$query'")
        val system = Systems.byId(rom.systemId)
        if (system != null && emulatorId !in system.emulators) {
            return toolFail("${vm.orchestrator.name(emulatorId)} doesn't run ${system.name} games")
        }
        if (!vm.orchestrator.isInstalled(emulatorId)) {
            return toolFail("${vm.orchestrator.name(emulatorId)} is not installed on this device")
        }
        onMain { vm.setRomEmulatorByKey(rom.key, emulatorId) }
        val name = vm.orchestrator.name(emulatorId)
        return toolOk("'${target.title}' will now launch with $name", MashaAttachment.Done("⚙ ${target.title} → $name"), mutating = true)
    }

    private suspend fun updateMetadata(a: JsonObject): ToolResult {
        if (!vm.app.credentials.anyConfigured()) {
            return toolFail("no metadata service is configured yet: Settings → Metadata APIs")
        }
        return when (a.str("scope")) {
            "game" -> {
                val target = resolve(a.str("title")) ?: return notFound(a.str("title"))
                onMain { vm.updateMetadata(listOf(target.key), force = true) }
                toolOk("updating metadata for '${target.title}'", MashaAttachment.Done("↻ ${target.title}"), mutating = true)
            }
            "all" -> {
                onMain { vm.updateMetadata(null, force = true) }
                toolOk("refreshing the whole library in the background", MashaAttachment.Done("↻"), mutating = true)
            }
            else -> {
                val keys = vm.library.roms.map { it.key } + vm.library.apps.map { it.key }
                val missing = keys.filter { vm.engine.needsWork(it) }
                if (missing.isEmpty()) return toolOk("nothing is missing metadata")
                onMain { vm.updateMetadata(missing, force = false) }
                toolOk("updating ${missing.size} games with missing metadata", MashaAttachment.Done("↻ ${missing.size}"), mutating = true)
            }
        }
    }

    private suspend fun filterLibrary(a: JsonObject): ToolResult {
        val category = when (a.str("category")) {
            "android" -> LibraryFilter.Android
            "consoles" -> LibraryFilter.Consoles
            else -> LibraryFilter.All
        }
        val query = a.str("query").orEmpty()
        onMain { vm.showLibraryFiltered(query, category) }
        return toolOk("library filtered", mutating = true)
    }

    private suspend fun addGame(title: String?): ToolResult {
        val wanted = title?.trim().orEmpty()
        if (wanted.isEmpty()) return toolFail("which app?")
        val installed = vm.installedApps()
        val match = installed.firstOrNull { it.label.equals(wanted, ignoreCase = true) }
            ?: installed.maxByOrNull { Names.similarity(wanted, it.label) }?.takeIf { Names.similarity(wanted, it.label) >= 0.7 }
            ?: return toolFail("there is no installed app called '$wanted'")
        if (vm.library.apps.any { it.packageName == match.packageName }) return toolFail("'${match.label}' is already in the library")
        val key = onMain { vm.addInstalledApp(match.packageName, match.label) } ?: return toolFail("could not add '${match.label}'")
        return toolOk("'${match.label}' added to the library", MashaAttachment.Games(null, listOf(key)), mutating = true)
    }

    private suspend fun removeGame(title: String?): ToolResult {
        val target = resolve(title) ?: return notFound(title)
        if (target.what == What.Rom) {
            return toolFail("'${target.title}' is a single ROM: it can be removed from its long-press menu, or its whole folder removed")
        }
        val removed = onMain { vm.removeFromLibrary(target.key) }
        return if (removed) {
            toolOk("'${target.title}' removed from the library; nothing was deleted from storage", MashaAttachment.Done("✕ ${target.title}"), mutating = true)
        } else {
            toolFail("could not remove '${target.title}'")
        }
    }

    private suspend fun setArt(title: String?, kindName: String?): ToolResult {
        val target = resolve(title) ?: return notFound(title)
        val kind = when (kindName?.lowercase()) {
            "cover" -> ArtKind.Cover
            "background" -> ArtKind.Background
            "logo" -> ArtKind.Logo
            "icon" -> ArtKind.Icon
            else -> return toolFail("unknown image kind: $kindName")
        }
        if (kind == ArtKind.Cover && target.what != What.Rom) return toolFail("'${target.title}' uses an icon or logo, not a cover")
        val applied = vm.applyArtAuto(target.key, kind)
        return if (applied) {
            onMain { vm.materializeArt(target.key, kind) }
            toolOk("applied the $kindName of '${target.title}'", MashaAttachment.Done("🖼 ${target.title}"), mutating = true)
        } else {
            toolFail("no configured service returned a $kindName for '${target.title}'")
        }
    }

    private suspend fun listInstalledApps(): ToolResult {
        val inLibrary = vm.library.apps.map { it.packageName }.toSet()
        val apps = vm.installedApps().filter { it.packageName !in inLibrary }.sortedByDescending { it.isGame }.take(80)
        return toolOk("installed apps that are not in the library") {
            putJsonArray("apps") { apps.forEach { add(it.label) } }
        }
    }

    private suspend fun setAccent(accent: String?): ToolResult {
        val id = accent?.trim()?.lowercase().orEmpty()
        if (id !in MashaTools.ACCENTS) return toolFail("unknown accent: $accent")
        onMain { vm.settings.setAccent(id) }
        return toolOk("accent set to $id", mutating = true)
    }

    private suspend fun setDarkMode(enabled: Boolean?): ToolResult {
        val dark = enabled ?: return toolFail("dark or light?")
        onMain { if (vm.settings.darkMode != dark) vm.settings.toggleDark() }
        return toolOk(if (dark) "dark mode on" else "light mode on", mutating = true) { put("dark", dark) }
    }

    /* ── utilidades ───────────────────────────────────────────── */

    private enum class What { Rom, App, Folder }

    private data class Target(val key: String, val title: String, val what: What)

    /** Del título al elemento: juegos (ROM o app) y, si no, carpetas por nombre de sistema. */
    private suspend fun resolve(title: String?): Target? {
        val q = title?.trim().orEmpty()
        if (q.isEmpty()) return null
        brain.knowledge.snapshot().resolve(q)?.let { g -> return Target(g.key, g.title, if (g.isApp) What.App else What.Rom) }
        val folders = vm.library.folders.mapNotNull { f -> Systems.byId(f.systemId)?.let { Target(f.key, it.name, What.Folder) } }
        return folders.firstOrNull { it.title.equals(q, ignoreCase = true) }
            ?: folders.firstOrNull { it.title.contains(q, ignoreCase = true) }
            ?: folders.maxByOrNull { Names.similarity(q, it.title) }?.takeIf { Names.similarity(q, it.title) >= 0.7 }
    }

    private fun notFound(title: String?) = toolFail("'${title.orEmpty()}' is not in the user's library")

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }
}
