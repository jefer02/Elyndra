package com.elyndra.launcher.masha

import com.elyndra.launcher.masha.offline.OfflineIntent
import com.elyndra.launcher.masha.offline.OfflineIntents

/** De qué iba la última conversación: una frase y, si salió uno, el juego. */
data class ConversationTopic(val summary: String, val game: String?)

/** Lo que [ConversationRecall] guarda entre aperturas. */
interface ConversationStore {
    /** Una frase (en inglés: la lee el modelo) sobre el último tema; null = nada guardado. */
    var summary: String?

    /** El juego de ese tema, para el saludo sin conexión; null = ninguno. */
    var topicGame: String?

    /** Último mensaje (de cualquiera de los dos); 0 = nunca. */
    var lastActivityAt: Long

    /** Hasta qué [lastActivityAt] está ya resumida la conversación. */
    var summarizedUpTo: Long

    fun clear()
}

/** El de los tests (y el de partida si no se inyecta otro). */
class MemoryConversationStore : ConversationStore {
    override var summary: String? = null
    override var topicGame: String? = null
    override var lastActivityAt: Long = 0
    override var summarizedUpTo: Long = 0
    override fun clear() {
        summary = null
        topicGame = null
        lastActivityAt = 0
        summarizedUpTo = 0
    }
}

/**
 * Retomar la última conversación, sin Android.
 *
 * - Una conversación termina al salir de la pantalla de Lucy o tras
 *   [IDLE_END_MS] sin mensajes. Entonces se guarda una frase con el tema: con
 *   IA, una llamada barata ([SUMMARY_SYSTEM]); sin ella, [fallback] (los juegos
 *   y las intenciones que salieron).
 * - Al volver, si pasaron más de [GREET_AFTER_MS], Lucy saluda nombrando ese
 *   tema; si no, el hilo sigue sin saludo nuevo ([shouldWelcomeBack]).
 * - El resumen nunca lleva rutas, URIs ni enlaces ([sanitize]): va en el
 *   contexto de cada pregunta.
 */
object ConversationRecall {

    const val IDLE_END_MS = 10L * 60 * 1000
    const val GREET_AFTER_MS = 30L * 60 * 1000
    private const val MAX_SUMMARY = 180
    private const val TRANSCRIPT_TURNS = 12
    private const val TURN_CHARS = 300

    /** Saludar al volver: hay tema guardado y pasó más de media hora desde el último mensaje. */
    fun shouldWelcomeBack(now: Long, store: ConversationStore): Boolean =
        store.summary != null && store.lastActivityAt > 0 && now - store.lastActivityAt > GREET_AFTER_MS

    /** La conversación se dio por terminada por inactividad. */
    fun isIdle(now: Long, lastActivityAt: Long): Boolean = lastActivityAt > 0 && now - lastActivityAt >= IDLE_END_MS

    /** Hubo mensajes desde el último resumen. */
    fun needsSummary(store: ConversationStore): Boolean = store.lastActivityAt > store.summarizedUpTo

    /** El hilo, compacto, para pedir el resumen: los últimos turnos, recortados y sin marcas. */
    fun transcript(turns: List<MashaTurn>): String =
        turns.takeLast(TRANSCRIPT_TURNS).joinToString("\n") { t ->
            val who = if (t.role == MashaTurn.Role.User) "User" else "Lucy"
            "$who: " + AffinityTag.strip(t.text).text.replace('\n', ' ').take(TURN_CHARS)
        }

    const val SUMMARY_SYSTEM = """You summarize a chat between a user and Lucy, the companion inside the Elyndra game launcher. """ +
        """Write ONE short sentence in English (at most 20 words), from Lucy's point of view, naming the main topic so she can bring it up later. """ +
        """Example: "We talked about finishing that Okami run." Mention game titles exactly as written. """ +
        """Never include file names, folders, paths, URIs or links. No quotes, no lists, nothing else."""

    /** La instrucción del saludo al volver (va con el prompt de Lucy y el contexto). */
    fun welcomeInstruction(summary: String, agoText: String): String =
        "The user just opened your screen again after $agoText away. LAST_CONVERSATION: \"$summary\". " +
            "Greet them back in UI_LANGUAGE, in your own voice and at your RELATIONSHIP_LEVEL, referring to that topic naturally " +
            "(for example \"Hey, you're back. Did you ever finish that Okami run we talked about?\"). One or two short sentences, " +
            "ending with a light question. No guilt about being away. Do not add the <<A:…>> line."

    /**
     * El tema sin IA: el último juego de la biblioteca que se nombró (el más
     * largo si hay varios en el mismo mensaje) o, si no, la última cosa que el
     * usuario pidió. Null si no hubo conversación.
     */
    fun fallback(turns: List<MashaTurn>, libraryTitles: Collection<String>): ConversationTopic? {
        if (turns.isEmpty()) return null
        val titles = libraryTitles.filter { it.length >= 3 }
        for (turn in turns.asReversed()) {
            val text = AffinityTag.strip(turn.text).text
            val game = titles.filter { text.contains(it, ignoreCase = true) }.maxByOrNull { it.length }
            if (game != null) return ConversationTopic("We talked about $game.", game)
        }
        for (turn in turns.asReversed()) {
            if (turn.role != MashaTurn.Role.User) continue
            val topic = when (val intent = OfflineIntents.detect(turn.text)) {
                is OfflineIntent.Launch -> ConversationTopic("They wanted to play ${intent.title}.", intent.title)
                is OfflineIntent.Plan -> ConversationTopic("We planned a ${intent.minMinutes} to ${intent.maxMinutes} minute gaming session.", null)
                OfflineIntent.Recommend -> ConversationTopic("I picked something for them to play.", null)
                OfflineIntent.Stats -> ConversationTopic("We looked at how much they have been playing.", null)
                is OfflineIntent.ShowList -> ConversationTopic("We went through one of their game lists.", null)
                OfflineIntent.UpdateMetadata -> ConversationTopic("We updated covers and game info.", null)
                OfflineIntent.Cleanup -> ConversationTopic("We tidied up their library.", null)
                OfflineIntent.Arcs -> ConversationTopic("We talked about their game arcs.", null)
                OfflineIntent.AddInstalled -> ConversationTopic("We added their installed games to the library.", null)
                is OfflineIntent.Rename -> ConversationTopic("We renamed one of their games.", null)
                is OfflineIntent.FindPort -> ConversationTopic("We looked for native ports of their games.", null)
                OfflineIntent.Hello, OfflineIntent.Unknown -> null
            }
            if (topic != null) return topic.copy(summary = sanitize(topic.summary) ?: return null, game = topic.game?.let(::cleanTitle))
        }
        return ConversationTopic("We chatted for a bit.", null)
    }

    private val URI = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S*|\bcontent:\S*|\bprimary:\S*|\bfile:\S*""")
    private val WINDOWS_PATH = Regex("""\b[A-Za-z]:[\\/]\S*""")
    private val UNIX_PATH = Regex("""(^|\s)(/|~/|\./)\S*""")
    private val FILE_NAME = Regex("""\S+\.(iso|chd|zip|7z|rar|cso|rvz|nsp|xci|bin|cue|apk|gba|nds|3ds|z64|n64|sfc|smc|nes|gb|gbc|pbp|elf)\b""", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = Regex("""[.!?。！？]""")
    private val SPACES = Regex("""\s+""")

    /**
     * Una frase limpia para guardar y mandar: sin marcas, rutas, URIs, enlaces
     * ni nombres de archivo; solo la primera frase y como mucho [MAX_SUMMARY]
     * caracteres. Null si no queda nada.
     */
    fun sanitize(text: String): String? {
        var s = AffinityTag.strip(text).text
        s = s.replace(URI, " ").replace(WINDOWS_PATH, " ").replace(UNIX_PATH, " ").replace(FILE_NAME, " ")
        s = s.replace(SPACES, " ").trim().trim('"', '“', '”', '«', '»', '\'')
        SENTENCE_END.find(s)?.let { s = s.substring(0, it.range.last + 1) }
        if (s.length > MAX_SUMMARY) s = s.take(MAX_SUMMARY).substringBeforeLast(' ').trimEnd(',', ';', ':') + "…"
        return s.takeIf { it.any(Char::isLetterOrDigit) }
    }

    private fun cleanTitle(title: String): String? = sanitize(title)?.trimEnd('.', '!', '?')
}
