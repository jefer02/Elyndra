package com.elyndra.launcher.masha

import com.elyndra.launcher.domain.lists.DynamicList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Lo que Masha puede hacer dentro de Elyndra: las herramientas que viajan en
 * cada petición a la IA. Quien las ejecuta es la app (ver ui/MashaActions);
 * aquí solo se describen, en JSON Schema.
 *
 * Las descripciones son para el modelo: dicen cuándo usar cada una, no solo
 * qué hacen. La regla de oro está repetida en el prompt: lo que cambia algo
 * se hace solo si el usuario lo pidió.
 */
object MashaTools {

    const val LAUNCH_GAME = "launch_game"
    const val FIND_GAMES = "find_games"
    const val GET_GAME_PROFILE = "get_game_profile"
    const val SUGGEST_EMULATOR = "suggest_emulator"
    const val SET_GAME_EMULATOR = "set_game_emulator"
    const val PLAN_SESSION = "plan_session"
    const val CREATE_LIST = "create_list"
    const val OPEN_LIST = "open_list"
    const val CREATE_ARC = "create_arc"
    const val GET_ARCS = "get_arcs"
    const val UPDATE_METADATA = "update_metadata"
    const val CURATION_REPORT = "curation_report"
    const val GET_STATS = "get_stats"
    const val REMEMBER = "remember"
    const val FORGET = "forget"
    const val FILTER_LIBRARY = "filter_library"
    const val SET_ART = "set_art"
    const val ADD_GAME = "add_game"
    const val REMOVE_GAME = "remove_game"
    const val LIST_INSTALLED_APPS = "list_installed_apps"
    const val SET_ACCENT = "set_accent"
    const val SET_DARK_MODE = "set_dark_mode"
    const val LIST_UNNAMED_GAMES = "list_unnamed_games"
    const val RENAME_GAME = "rename_game"
    const val LIST_ADDABLE_GAMES = "list_addable_games"
    const val ADD_GAMES = "add_games"
    const val OPEN_ADD_ROMS = "open_add_roms"
    const val LIST_EMULATORS = "list_emulators"
    const val SET_PREFERRED_EMULATOR = "set_preferred_emulator"
    const val UNDO_LAST_ACTION = "undo_last_action"
    const val FIND_PORTS = "find_ports"
    const val OPEN_PORT = "open_port"
    const val INSTALL_PORT = "install_port"
    const val RECOMMEND = "show_recommendations"
    const val CONFIRM_PENDING = "confirm_pending"
    const val GET_DEVICE_STATE = "get_device_state"

    /** Cuántas tarjetas como mucho en una recomendación. */
    const val MAX_PICKS = 4

    /** Las que cambian algo: una respuesta que las usó nunca sale de caché. */
    val MUTATING = setOf(
        LAUNCH_GAME, SET_GAME_EMULATOR, CREATE_LIST, CREATE_ARC, UPDATE_METADATA, REMEMBER, FORGET,
        FILTER_LIBRARY, SET_ART, ADD_GAME, REMOVE_GAME, SET_ACCENT, SET_DARK_MODE,
        RENAME_GAME, ADD_GAMES, OPEN_ADD_ROMS, SET_PREFERRED_EMULATOR, UNDO_LAST_ACTION,
        OPEN_PORT, INSTALL_PORT, CONFIRM_PENDING,
    )

    val ACCENTS = listOf("indigo", "abismo", "medianoche", "mandarina", "fuego", "menta", "cobalto", "lila", "coral", "turquesa", "oro", "chicle", "grafito")
    val ART_KINDS = listOf("cover", "background", "logo", "icon")
    val MOODS = listOf("continue", "light", "new", "any")
    val SORTS = listOf("title", "minutes", "last_played", "added", "rating", "release")

    val specs: List<ToolSpec> by lazy {
        listOf(
            tool(LAUNCH_GAME, "Launch a game from the user's library with the best emulator for it on this device. Only when the user asked to play/open it, or said yes to your suggestion.") {
                string("title", "Game title as it appears in the library.")
                required("title")
            },
            tool(FIND_GAMES, "Search the FULL library (the catalog in the context is only a sample). Filters combine with AND. The app shows the results as cards. Use it to answer 'which games…' questions with real data.") {
                string("query", "Text in the title.")
                enum("list", "A built-in dynamic list to start from.", DynamicList.entries.map { it.id })
                stringArray("systems", "System names or ids, e.g. 'PlayStation 2', 'ps2', 'snes', 'android'.")
                stringArray("genres", "Genre words, e.g. 'rpg', 'platform', 'racing'.")
                integer("min_minutes", "Minimum total minutes played.")
                integer("max_minutes", "Maximum total minutes played.")
                integer("played_within_days", "Played in the last N days.")
                integer("not_played_for_days", "Played before, but not in the last N days.")
                boolean("android", "true = only Android games, false = only ROMs.")
                string("series", "Series/franchise name, e.g. 'Final Fantasy'.")
                enum("sort", "Order of the results.", SORTS)
                integer("limit", "Max results (default 12, max 40).")
            },
            tool(GET_GAME_PROFILE, "Everything Elyndra knows about one game: playtime, sessions, last session, emulators used and how they went, state, metadata/artwork status, achievements.") {
                string("title", "Game title.")
                required("title")
            },
            tool(SUGGEST_EMULATOR, "Rank the compatible emulators for a game on this device, with evidence (good sessions, instant exits, failed launches) and whether each is installed.") {
                string("title", "Game title.")
                required("title")
            },
            tool(SET_GAME_EMULATOR, "Make one game always launch with a given emulator. Only when the user asked or agreed.") {
                string("title", "Game title.")
                string("emulator", "Emulator name or id, e.g. 'DuckStation', 'AetherSX2', 'ra_mupen64plus_next'.")
                required("title", "emulator")
            },
            tool(PLAN_SESSION, "Build a mini-session that fits the user's time: 1-3 games from the library with minutes each, respecting device battery/heat. Use it for 'I have X minutes', 'something light', 'continue something'.") {
                integer("min_minutes", "Shortest acceptable session.")
                integer("max_minutes", "Longest acceptable session.")
                enum("mood", "continue = pick up in-progress games; light = short/relaxed; new = never opened or barely tried; any = mix.", MOODS)
                required("min_minutes", "max_minutes", "mood")
            },
            tool(CREATE_LIST, "Save a named list. Either give a rule (dynamic, always up to date) or explicit titles (fixed). Only when the user wants to keep it.") {
                string("name", "List name, in the user's language.")
                enum("list", "Built-in dynamic list to base it on.", DynamicList.entries.map { it.id })
                stringArray("systems", "System filter.")
                stringArray("genres", "Genre filter.")
                string("series", "Series filter.")
                integer("min_minutes", "Minimum minutes played.")
                integer("max_minutes", "Maximum minutes played.")
                boolean("android", "Only Android (true) or only ROMs (false).")
                stringArray("titles", "Fixed list of game titles, in order.")
                required("name")
            },
            tool(OPEN_LIST, "Show a saved list (or a built-in dynamic list) as cards.") {
                string("name", "Saved list name, or a built-in id like 'never_opened'.")
                required("name")
            },
            tool(CREATE_ARC, "Create a thematic multi-session arc: an ordered sequence of library games with a time goal per step. Only games that exist in the library.") {
                string("title", "Arc title, in the user's language.")
                string("theme", "Short theme, e.g. 'Castlevania saga', 'PS1 horror'.")
                string("description", "One sentence describing the arc.")
                stringArray("games", "Game titles in play order.")
                string("series", "Instead of games: build it from this series, in release order.")
                integer("minutes_per_step", "Target minutes per game (default: about three normal sessions).")
                required("title")
            },
            tool(GET_ARCS, "Active arcs and their progress.") {},
            tool(UPDATE_METADATA, "Download metadata and artwork now. scope=game for one title, missing for everything incomplete, all to refresh the whole library.") {
                enum("scope", "What to update.", listOf("game", "missing", "all"))
                string("title", "Game title when scope=game.")
                required("scope")
            },
            tool(CURATION_REPORT, "Library clean-up findings: duplicates, incomplete multi-disc sets, poorly named ROMs, mixed regions, and series groups.") {
                enum("detail", "Which part to detail.", listOf("summary", "duplicates", "incomplete_sets", "poorly_named", "mixed_regions", "series"))
            },
            tool(GET_DEVICE_STATE, "Battery %, charging, power save and temperature of this device right now. Read-only. Call it ONLY when the user asks about the device (battery, charge, heat); never on your own.") {},
            tool(GET_STATS, "Playtime statistics: totals, per system, top games, sessions and trends.") {
                enum("period", "Time window.", listOf("week", "month", "year", "all"))
            },
            tool(REMEMBER, "Remember a lasting preference or fact the user told you, to use in future conversations.") {
                string("fact", "Short, self-contained sentence in the user's language.")
                string("game", "Game title it refers to, if any.")
                enum("kind", "preference or fact.", listOf("preference", "fact"))
                required("fact")
            },
            tool(FORGET, "Forget something you remembered, when the user asks.") {
                string("fact", "What to forget (approximate wording is fine).")
                required("fact")
            },
            tool(FILTER_LIBRARY, "Show the library carousel filtered: a search text and/or a category. Closes the chat and takes the user there.") {
                string("query", "Search text (empty = none).")
                enum("category", "Which items.", listOf("all", "android", "consoles"))
            },
            tool(SET_ART, "Find and apply artwork for a game or an emulator folder using the configured services.") {
                string("title", "Game title or system name of the folder.")
                enum("kind", "Which image.", ART_KINDS)
                required("title", "kind")
            },
            tool(ADD_GAME, "Add an installed Android game to the library. Call list_installed_apps first if unsure of the exact name.") {
                string("title", "Installed app name.")
                required("title")
            },
            tool(REMOVE_GAME, "Hide an Android game from the library. Nothing is uninstalled or deleted, and undo_last_action brings it back. Only when asked. (ROMs and whole folders are removed from their long-press menu, not by you.)") {
                string("title", "Game title.")
                required("title")
            },
            tool(LIST_INSTALLED_APPS, "List installed apps that could be added to the library.") {},
            tool(SET_ACCENT, "Change the interface accent colour.") {
                enum("accent", "Accent colour.", ACCENTS)
                required("accent")
            },
            tool(SET_DARK_MODE, "Switch the interface between dark and light.") {
                boolean("enabled", "true = dark, false = light.")
                required("enabled")
            },
            tool(LIST_UNNAMED_GAMES, "Games whose name is not usable to find metadata (package ids, serials like CUSA01715, hashes, raw file names). Returns each current raw name, its source (Android or a system) and a cleaned-up guess.") {},
            tool(
                RENAME_GAME,
                "Set a game's name. The name is locked: metadata syncs never overwrite it, and its metadata is refreshed right away. " +
                    "If the user asked for it (or agreed), it is done right away; if the name is only your idea, the app shows a confirm card. " +
                    "Set user_dictated=true only when the user gave the exact name.",
            ) {
                string("game", "Current title of the game in the library.")
                string("name", "The new name.")
                boolean("user_dictated", "true if the user dictated this exact name (then no confirmation is needed).")
                boolean("user_requested", "true if the user asked for this directly or just agreed to your proposal: then it runs right away, with no confirm card.")
                required("game", "name")
            },
            tool(LIST_ADDABLE_GAMES, "Games on the device that are not in the library yet: installed Android apps/games, and new ROMs/PC games inside folders Elyndra already has access to (also ones the user removed before). Only names, never paths.") {},
            tool(
                ADD_GAMES,
                "Add games to the library (never deletes anything). If the user asked for it (or agreed), they are added right away; " +
                    "if it is only your idea and there is more than one, the app shows them in a confirm card first. " +
                    "At most 50 per call; the rest are reported as skipped.",
            ) {
                stringArray("titles", "Exact titles from list_addable_games.")
                boolean("all", "true = every game list_addable_games found (instead of titles).")
                boolean("user_requested", "true if the user asked for this directly or just agreed to your proposal: then it runs right away, with no confirm card.")
            },
            tool(OPEN_ADD_ROMS, "Open the Add screen on the ROMs tab so the user can pick a new folder with the system picker (you cannot pick folders yourself). Tell the user which folder to select.") {
                string("system", "System the folder is for, if known.")
            },
            tool(LIST_EMULATORS, "Emulators on this device: the ones Elyndra recognizes (with the systems they run and where they are the preferred emulator), the recognized ones not set up for any system, and installed apps that look like emulators but have no known profile (Elyndra cannot launch those safely).") {},
            tool(SET_PREFERRED_EMULATOR, "Make a recognized, installed emulator the preferred one for all of a system's folders. Done right away if the user asked for it; a confirm card if it is only your idea.") {
                string("system", "System name or id, e.g. 'PlayStation 2', 'psp'.")
                string("emulator", "Emulator name or id.")
                boolean("user_requested", "true if the user asked for this directly or just agreed to your proposal: then it runs right away, with no confirm card.")
                required("system", "emulator")
            },
            tool(
                RECOMMEND,
                "Show what you recommend as cards: cover, system, playtime, state, emulator and Play/Open buttons. Use it EVERY time you recommend games " +
                    "or ports, instead of describing them in text. Up to $MAX_PICKS items, best first, each with a one-line reason in the user's language. " +
                    "Library games by title; ports by their id from the ports search.",
            ) {
                objectArray("items", "The recommendations, best first.") {
                    string("title", "Library game title, exactly as in the data.")
                    string("port_id", "For a port: its id from the ports search (leave title empty).")
                    string("reason", "One short line: why this one, for them, now.")
                    required("reason")
                }
                required("items")
            },
            tool(UNDO_LAST_ACTION, "Undo the last change made through you (add, rename, emulator, accent, dark mode, artwork, hiding a game…). Only when the user asks.") {},
            tool(
                CONFIRM_PENDING,
                "Answer the confirm card that is waiting (the latest one): when the user says yes or no to it in any words, in any language.",
            ) {
                string("answer", "confirm or cancel.")
                required("answer")
            },
            tool(
                FIND_PORTS,
                "Native Android ports (decompilations/recompilations/source ports) from Elyndra's Ports store, for console and PC games. " +
                    "Search by port or original game name (just the name: 'Doom', not 'something like Doom'), by genre, or by mood. " +
                    "Without query, genre or mood: library games that have a port. One result per game, compatible with this device first; " +
                    "incompatible ones are only counted unless include_incompatible. Each result says its state (installed, update_available, " +
                    "available, not_on_android), whether it runs here, and which original game's data files it needs (the user must own it; never offer or link ROMs).",
            ) {
                string("query", "Port or original game name, e.g. 'Ocarina of Time', 'Doom', 'Mario 64'.")
                string("genre", "Optional genre: platformer, racing, rpg, shooter or strategy.")
                string("mood", "Optional mood in the user's words, e.g. 'scary', 'chill', 'with friends', 'fast', 'story', 'puzzle'.")
                boolean("include_incompatible", "Also return ports that can't be installed on this device.")
            },
            tool(OPEN_PORT, "Open a port's page in the Ports store. Only when the user wants to see it.") {
                string("port_id", "The port id from find_ports.")
                string("game", "Or the original game's name.")
            },
            tool(
                INSTALL_PORT,
                "Install a port from the Ports store with the store's own install flow. If the user asked for it (or agreed) it starts right away; " +
                    "if it is only your idea, a confirm card first. Android always shows its own install dialog: tell the user Android will ask them to confirm. " +
                    "Say first if it needs files from the original game.",
            ) {
                string("port_id", "The port id from find_ports.")
                string("game", "Or the original game's name.")
                boolean("user_requested", "true if the user asked for this directly or just agreed to your proposal: then it runs right away, with no confirm card.")
            },
        )
    }

    /* ── pequeño DSL de JSON Schema ───────────────────────────── */

    private class Params {
        val properties = LinkedHashMap<String, JsonObject>()
        val required = ArrayList<String>()

        fun string(name: String, description: String) = prop(name, "string", description)
        fun integer(name: String, description: String) = prop(name, "integer", description)
        fun boolean(name: String, description: String) = prop(name, "boolean", description)

        fun enum(name: String, description: String, values: List<String>) {
            properties[name] = buildJsonObject {
                put("type", "string")
                put("description", description)
                putJsonArray("enum") { values.forEach { add(it) } }
            }
        }

        fun stringArray(name: String, description: String) {
            properties[name] = buildJsonObject {
                put("type", "array")
                put("description", description)
                putJsonObject("items") { put("type", "string") }
            }
        }

        fun required(vararg names: String) {
            required += names
        }

        fun objectArray(name: String, description: String, item: Params.() -> Unit) {
            val p = Params().apply(item)
            properties[name] = buildJsonObject {
                put("type", "array")
                put("description", description)
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") { p.properties.forEach { (k, v) -> put(k, v) } }
                    if (p.required.isNotEmpty()) putJsonArray("required") { p.required.forEach { add(it) } }
                }
            }
        }

        private fun prop(name: String, type: String, description: String) {
            properties[name] = buildJsonObject {
                put("type", type)
                put("description", description)
            }
        }
    }

    private fun tool(name: String, description: String, params: Params.() -> Unit): ToolSpec {
        val p = Params().apply(params)
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { p.properties.forEach { (k, v) -> put(k, v) } }
            if (p.required.isNotEmpty()) putJsonArray("required") { p.required.forEach { add(it) } }
        }
        return ToolSpec(name, description, schema)
    }
}
