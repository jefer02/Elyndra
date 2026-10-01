package com.elyndra.launcher.ui

import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.library.NameCheck
import com.elyndra.launcher.library.Names
import com.elyndra.launcher.masha.ConfirmKind
import com.elyndra.launcher.masha.MashaAttachment
import com.elyndra.launcher.masha.MashaWriteRules
import com.elyndra.launcher.masha.MashaWrites
import com.elyndra.launcher.masha.ToolResult
import com.elyndra.launcher.masha.WriteOutcome
import com.elyndra.launcher.masha.bool
import com.elyndra.launcher.masha.str
import com.elyndra.launcher.masha.toolFail
import com.elyndra.launcher.masha.toolOk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Las herramientas de Masha que ponen nombre a juegos, añaden lo que hay en
 * el dispositivo y eligen emulador. Solo añaden o renombran: ninguna borra
 * juegos ni archivos. Lo que escribe pide confirmación (ver [MashaWrites]) y
 * se puede deshacer.
 *
 * Al modelo solo le llegan nombres de juegos, de paquetes y de sistemas:
 * nunca rutas, URIs ni credenciales.
 */
class MashaLibraryActions(private val vm: ElyndraViewModel, private val writes: MashaWrites) {

    /* ── sin nombre ───────────────────────────────────────────── */

    fun listUnnamed(): ToolResult {
        val apps = vm.library.apps.filter { !NameCheck.isNameUsable(it.displayTitle) }
            .map { MashaLibraryJson.Unnamed(it.displayTitle, "Android", NameCheck.guess(it.displayTitle)) }
        val roms = vm.library.roms.filter { !NameCheck.isNameUsable(it.displayTitle) }
            .map { MashaLibraryJson.Unnamed(it.displayTitle, Systems.byId(it.systemId)?.name ?: it.systemId, NameCheck.guess(it.fileName)) }
        val all = apps + roms
        if (all.isEmpty()) return toolOk("every game has a usable name")
        return toolOk("${all.size} games need a name") { put("games", MashaLibraryJson.unnamed(all.take(60))) }
    }

    suspend fun rename(a: JsonObject): ToolResult {
        val game = a.str("game")?.trim().orEmpty()
        val name = MashaWriteRules.cleanName(a.str("name")) ?: return toolFail("'${a.str("name").orEmpty()}' is not a usable game name")
        val target = findGame(game) ?: return toolFail("'$game' is not in the user's library")
        val dictated = a.bool("user_dictated") == true
        val doIt: suspend () -> WriteOutcome = {
            val before = withContext(Dispatchers.Main) { metaOf(target.key) }
            withContext(Dispatchers.Main) {
                vm.app.library.updateMeta(target.key) { IdentifyRules.withUserName(it, name) }
                vm.engine.refresh(target.key)
            }
            WriteOutcome("✎ ${target.title} → $name") {
                withContext(Dispatchers.Main) { if (before != null) vm.app.library.updateMeta(target.key) { before } }
                "rename of '${target.title}'"
            }
        }
        // Dictado por el usuario: no hay nada que confirmar; se hace y se puede deshacer.
        if (dictated) {
            val out = doIt()
            out.undo?.let { writes.record(out.label, it) }
            return toolOk("'${target.title}' is now called '$name' (locked; metadata refreshing)", MashaAttachment.Done(out.label), mutating = true)
        }
        val w = writes.propose(ConfirmKind.RENAME, listOf(target.title, name), doIt)
        return toolOk(
            "waiting for the user's confirmation in the chat card; tell them in one sentence what you will set",
            MashaAttachment.Confirm(w.id, w.kind, w.items),
            mutating = true,
        )
    }

    /* ── lo que se puede añadir ───────────────────────────────── */

    suspend fun listAddable(): ToolResult {
        val apps = vm.addableApps()
        val roms = vm.addableRoms()
        if (apps.isEmpty() && roms.isEmpty()) {
            return toolOk("nothing new: every installed game and every file in the granted folders is already in the library")
        }
        return toolOk("games on the device that are not in the library") {
            put("apps", MashaLibraryJson.apps(apps.take(80).map { MashaLibraryJson.App(it.label, it.packageName, it.isGame) }))
            put("roms", MashaLibraryJson.roms(roms.take(80).map { MashaLibraryJson.Rom(it.title, Systems.byId(it.folder.systemId)?.name ?: it.folder.systemId, it.removedBefore) }))
            put("folders_note", "only folders already granted are checked; for new folders call $OPEN_ADD")
        }
    }

    suspend fun addGames(a: JsonObject): ToolResult {
        val all = a.bool("all") == true
        val wanted = (a["titles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
        if (!all && wanted.isEmpty()) return toolFail("which games? give titles or all=true")
        val apps = vm.addableApps()
        val roms = vm.addableRoms()
        val pickedApps = if (all) apps.filter { it.isGame } else wanted.mapNotNull { w -> bestApp(w, apps) }.distinctBy { it.packageName }
        val pickedRoms = if (all) roms.filterNot { it.removedBefore } else wanted.mapNotNull { w -> bestRom(w, roms) }.distinctBy { it.found.docId }
        val notFound = if (all) emptyList() else wanted.filter { w -> bestApp(w, apps) == null && bestRom(w, roms) == null }
        val items = pickedApps.map { AddItem.App(it.packageName, it.label) } + pickedRoms.map { AddItem.Rom(it) }
        if (items.isEmpty()) return toolFail("none of those are installed or in a granted folder: ${notFound.joinToString()}")
        val (batch, skipped) = MashaWriteRules.cap(items)
        val doIt: suspend () -> WriteOutcome = {
            val added = withContext(Dispatchers.Main) {
                val appKeys = batch.filterIsInstance<AddItem.App>().mapNotNull { vm.addInstalledApp(it.pkg, it.label) }
                val romKeys = vm.addRomsToLibrary(batch.filterIsInstance<AddItem.Rom>().map { it.rom })
                appKeys + romKeys
            }
            WriteOutcome("＋ ${added.size}", ok = added.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    // Deshacer = quitar de la biblioteca lo añadido. Ningún archivo se toca.
                    added.forEach { key ->
                        if (key.startsWith("a:")) vm.app.library.removeApp(key.removePrefix("a:"))
                        else vm.library.roms.firstOrNull { it.key == key }?.let { vm.app.library.removeRom(it.id) }
                    }
                }
                "added ${added.size} games"
            }
        }
        val names = batch.map { it.title }
        // Un solo juego pedido por su nombre: se añade sin más (se puede deshacer).
        if (batch.size == 1 && !all) {
            val out = doIt()
            out.undo?.let { writes.record(out.label, it) }
            return toolOk("added '${names.first()}' to the library", MashaAttachment.Done("＋ ${names.first()}"), mutating = true)
        }
        val w = writes.propose(ConfirmKind.ADD_GAMES, names, doIt)
        return toolOk(
            "waiting for the user's confirmation in the chat card (${batch.size} games" +
                (if (skipped.isNotEmpty()) ", ${skipped.size} over the limit of ${MashaWriteRules.BATCH_CAP} skipped" else "") +
                (if (notFound.isNotEmpty()) "; not found: ${notFound.joinToString()}" else "") + ")",
            MashaAttachment.Confirm(w.id, w.kind, names, skipped = skipped.size),
            mutating = true,
        )
    }

    suspend fun openAddRoms(a: JsonObject): ToolResult {
        withContext(Dispatchers.Main) { vm.openAddRoms() }
        val system = a.str("system")?.let { Systems.byId(it)?.name ?: it }
        return toolOk(
            "the Add screen is open on the ROMs tab; the user must pick the folder in the system picker" +
                (system?.let { " (the folder with their $it games)" } ?: ""),
            mutating = true,
        )
    }

    /* ── emuladores ───────────────────────────────────────────── */

    suspend fun listEmulators(): ToolResult {
        val installed = Emulators.ALL.filter { vm.orchestrator.isInstalled(it.id) }
        val preferredBy = vm.library.folders.groupBy { it.emulatorId }
        val known = installed.map { p ->
            MashaLibraryJson.Emulator(
                name = p.name,
                systems = Systems.ALL.filter { p.id in it.emulators }.map { it.name },
                preferredFor = preferredBy[p.id].orEmpty().mapNotNull { Systems.byId(it.systemId)?.name }.distinct(),
            )
        }
        val knownPackages = Emulators.ALL.flatMap { it.packages }.toSet()
        val unknown = vm.installedApps()
            .filter { it.packageName !in knownPackages && MashaLibraryJson.looksLikeEmulator(it.label, it.packageName) }
            .map { MashaLibraryJson.App(it.label, it.packageName, it.isGame) }
        return toolOk("emulators on this device") {
            put("recognized", MashaLibraryJson.emulators(known.filter { it.preferredFor.isNotEmpty() }))
            put("recognized_not_preferred", MashaLibraryJson.emulators(known.filter { it.preferredFor.isEmpty() }))
            put("unrecognized_apps", MashaLibraryJson.apps(unknown))
            put("note", "unrecognized apps have no known launch profile: say you cannot launch them safely; never invent an intent")
        }
    }

    fun setPreferred(a: JsonObject): ToolResult {
        val systemQuery = a.str("system")?.trim().orEmpty()
        val system = Systems.byId(systemQuery) ?: Systems.ALL.firstOrNull { it.name.equals(systemQuery, true) || it.short.equals(systemQuery, true) }
            ?: Systems.ALL.maxByOrNull { Names.similarity(systemQuery, it.name) }?.takeIf { Names.similarity(systemQuery, it.name) >= 0.7 }
            ?: return toolFail("unknown system '$systemQuery'")
        val query = a.str("emulator")?.trim().orEmpty()
        val emuId = vm.orchestrator.resolveEmulator(query, system.id)
            ?: return toolFail("'$query' is not an emulator Elyndra knows; it cannot be launched safely")
        if (emuId !in system.emulators) return toolFail("${vm.orchestrator.name(emuId)} doesn't run ${system.name} games")
        if (!vm.orchestrator.isInstalled(emuId)) return toolFail("${vm.orchestrator.name(emuId)} is not installed")
        if (vm.library.folders.none { it.systemId == system.id }) {
            return toolFail("there is no ${system.name} folder in the library yet; add one first ($OPEN_ADD)")
        }
        val name = vm.orchestrator.name(emuId)
        val w = writes.propose(ConfirmKind.SET_EMULATOR, listOf(name, system.name)) {
            val before = withContext(Dispatchers.Main) { vm.setSystemEmulator(system.id, emuId) }
            WriteOutcome("⚙ ${system.name} → $name") {
                withContext(Dispatchers.Main) { vm.restoreFolderEmulators(before) }
                "emulator change for ${system.name}"
            }
        }
        return toolOk("waiting for the user's confirmation in the chat card", MashaAttachment.Confirm(w.id, w.kind, w.items), mutating = true)
    }

    suspend fun undo(): ToolResult {
        val what = writes.undoLast() ?: return toolFail("there is nothing to undo")
        return toolOk("undid the $what", MashaAttachment.Done("↶"), mutating = true)
    }

    /* ── utilidades ───────────────────────────────────────────── */

    private data class Found(val key: String, val title: String)

    private fun findGame(q: String): Found? {
        if (q.isEmpty()) return null
        val all = vm.library.roms.map { Found(it.key, it.displayTitle) } + vm.library.apps.map { Found(it.key, it.displayTitle) }
        return all.firstOrNull { it.title.equals(q, ignoreCase = true) }
            ?: all.maxByOrNull { Names.similarity(q, it.title) }?.takeIf { Names.similarity(q, it.title) >= 0.75 }
    }

    private fun metaOf(key: String): GameMeta? =
        vm.library.roms.firstOrNull { it.key == key }?.meta ?: vm.library.apps.firstOrNull { it.key == key }?.meta

    private fun bestApp(q: String, apps: List<com.elyndra.launcher.library.InstalledApp>) =
        apps.firstOrNull { it.label.equals(q, true) || it.packageName == q }
            ?: apps.maxByOrNull { Names.similarity(q, it.label) }?.takeIf { Names.similarity(q, it.label) >= 0.8 }

    private fun bestRom(q: String, roms: List<ElyndraViewModel.AddableRom>) =
        roms.firstOrNull { it.title.equals(q, true) }
            ?: roms.maxByOrNull { Names.similarity(q, it.title) }?.takeIf { Names.similarity(q, it.title) >= 0.8 }

    private sealed interface AddItem {
        val title: String
        data class App(val pkg: String, val label: String) : AddItem { override val title get() = label }
        data class Rom(val rom: ElyndraViewModel.AddableRom) : AddItem { override val title get() = rom.title }
    }

    private companion object {
        const val OPEN_ADD = "open_add_roms"
    }
}

/**
 * Lo que de estas herramientas se le manda al modelo, en JSON. Solo nombres
 * (de juegos, paquetes y sistemas) y banderas: nunca rutas, URIs ni archivos.
 * Kotlin puro, para comprobarlo en la JVM.
 */
object MashaLibraryJson {

    data class Unnamed(val raw: String, val source: String, val guess: String)
    data class App(val label: String, val packageName: String, val isGame: Boolean)
    data class Rom(val title: String, val system: String, val removedBefore: Boolean)
    data class Emulator(val name: String, val systems: List<String>, val preferredFor: List<String>)

    fun unnamed(list: List<Unnamed>) = kotlinx.serialization.json.buildJsonArray {
        list.forEach { u -> addJsonObject { put("raw_name", u.raw); put("source", u.source); if (u.guess.isNotBlank()) put("guess", u.guess) } }
    }

    fun apps(list: List<App>) = kotlinx.serialization.json.buildJsonArray {
        list.forEach { a -> addJsonObject { put("name", a.label); put("package", a.packageName); put("is_game", a.isGame) } }
    }

    fun roms(list: List<Rom>) = kotlinx.serialization.json.buildJsonArray {
        list.forEach { r -> addJsonObject { put("title", r.title); put("system", r.system); if (r.removedBefore) put("removed_before", true) } }
    }

    fun emulators(list: List<Emulator>) = kotlinx.serialization.json.buildJsonArray {
        list.forEach { e ->
            addJsonObject {
                put("name", e.name)
                putJsonArray("systems") { e.systems.forEach { add(it) } }
                if (e.preferredFor.isNotEmpty()) putJsonArray("preferred_for") { e.preferredFor.forEach { add(it) } }
            }
        }
    }

    /** Palabras que delatan un emulador entre las apps instaladas. */
    private val EMU_HINTS = listOf(
        "emu", "retroarch", "ppsspp", "dolphin", "citra", "lime3ds", "azahar", "yuzu", "suyu", "sudachi", "citron", "eden", "ryujinx",
        "skyline", "strato", "duckstation", "pcsx", "aethersx2", "nethersx2", "drastic", "melonds", "redream", "flycast", "vita3k",
        "mupen", "mgba", "snes9x", "fceumm", "winlator", "gamehub", "bannerhub", "mobox", "rpcs3", "xenia", "cemu", "bachata",
    )

    fun looksLikeEmulator(label: String, packageName: String): Boolean {
        val l = label.lowercase()
        val p = packageName.lowercase()
        return EMU_HINTS.any { it in l || it in p }
    }
}
