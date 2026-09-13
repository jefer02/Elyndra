package com.elyndra.launcher.lucy

import org.json.JSONArray
import org.json.JSONObject

/* ─────────────────────────────────────────────────────────────
   Lo que Lucy puede hacer, no solo contar.

   Son las `functionDeclarations` que viajan en cada llamada a
   Gemini. El modelo decide cuál pedir; quien las ejecuta es
   LucyActions, contra la biblioteca real.

   Regla de oro, repetida en el prompt: solo se llaman cuando el
   usuario lo ha pedido. Una recomendación no abre nada por su
   cuenta — primero pregunta.
   ───────────────────────────────────────────────────────────── */

object LucyTools {

    const val OPEN_GAME = "open_game"
    const val ADD_GAME = "add_game"
    const val REMOVE_GAME = "remove_game"
    const val SET_ART = "set_art"
    const val SET_ACCENT = "set_accent"
    const val SET_DARK_MODE = "set_dark_mode"
    const val LIST_INSTALLED_APPS = "list_installed_apps"

    /** Clases de imagen que Lucy puede pedir. */
    val ART_KINDS = listOf("cover", "background", "logo", "icon")

    private fun schema(vararg props: Pair<String, JSONObject>, required: List<String>) = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        put("required", JSONArray(required))
    }

    private fun string(description: String, enum: List<String>? = null) = JSONObject().apply {
        put("type", "STRING")
        put("description", description)
        enum?.let { put("enum", JSONArray(it)) }
    }

    private fun bool(description: String) = JSONObject().apply {
        put("type", "BOOLEAN")
        put("description", description)
    }

    private fun declaration(name: String, description: String, params: JSONObject?) = JSONObject().apply {
        put("name", name)
        put("description", description)
        params?.let { put("parameters", it) }
    }

    fun declarations(): JSONArray = JSONArray().apply {
        put(
            declaration(
                OPEN_GAME,
                "Lanza un juego de la biblioteca del usuario. Úsalo solo cuando el usuario haya pedido abrirlo " +
                    "o haya dicho que sí a tu pregunta de si lo abres.",
                schema(
                    "title" to string("Título del juego tal y como aparece en la biblioteca."),
                    required = listOf("title"),
                ),
            ),
        )
        put(
            declaration(
                ADD_GAME,
                "Añade a la biblioteca un juego Android que ya está instalado en el teléfono. " +
                    "Si no sabes el nombre exacto, llama antes a $LIST_INSTALLED_APPS.",
                schema(
                    "title" to string("Nombre de la app instalada."),
                    required = listOf("title"),
                ),
            ),
        )
        put(
            declaration(
                REMOVE_GAME,
                "Quita de la biblioteca un juego Android o una carpeta de emulador. No borra nada del " +
                    "almacenamiento: ni desinstala la app ni toca las ROMs. Solo si el usuario lo pide.",
                schema(
                    "title" to string("Título del juego o nombre del sistema de la carpeta."),
                    required = listOf("title"),
                ),
            ),
        )
        put(
            declaration(
                SET_ART,
                "Busca y pone la imagen de un juego o de una carpeta: carátula, fondo, logo o icono. " +
                    "Usa los servicios de metadatos que el usuario tenga configurados.",
                schema(
                    "title" to string("Título del juego o nombre del sistema de la carpeta."),
                    "kind" to string("Qué imagen se pone.", ART_KINDS),
                    required = listOf("title", "kind"),
                ),
            ),
        )
        put(
            declaration(
                SET_ACCENT,
                "Cambia el color de acento de la interfaz.",
                schema(
                    "accent" to string(
                        "Color de acento.",
                        listOf(
                            "mandarina", "fuego", "menta", "cobalto", "lila",
                            "coral", "turquesa", "oro", "chicle", "grafito",
                        ),
                    ),
                    required = listOf("accent"),
                ),
            ),
        )
        put(
            declaration(
                SET_DARK_MODE,
                "Pone la interfaz en modo oscuro o claro.",
                schema(
                    "enabled" to bool("true para oscuro, false para claro."),
                    required = listOf("enabled"),
                ),
            ),
        )
        put(
            declaration(
                LIST_INSTALLED_APPS,
                "Lista las apps instaladas en el teléfono que se pueden añadir a la biblioteca.",
                null,
            ),
        )
    }
}
