package com.elyndra.launcher.masha

import com.elyndra.launcher.library.Names

/**
 * Cuándo Lucy hace algo sin más y cuándo pregunta (puro).
 *
 * - Si el usuario lo pidió directamente ("añade estos juegos", "abre el port
 *   de God of War III", "pon el acento morado") o acaba de decir que sí a lo
 *   que ella propuso, se hace ya, sin tarjeta, y queda para deshacer.
 * - Si la idea es de ella, propone (tarjeta de confirmación).
 * - Si el objetivo es ambiguo (varios juegos encajan), una pregunta corta y
 *   no se toca nada.
 */
object ActionGate {

    enum class Decision { Execute, Propose, Ask }

    /** [userAsked]: lo pidió o lo confirmó; [candidates]: cuántos objetivos encajan (1 si no hay que elegir). */
    fun decide(userAsked: Boolean, candidates: Int = 1): Decision = when {
        candidates > 1 -> Decision.Ask
        userAsked -> Decision.Execute
        else -> Decision.Propose
    }

    /**
     * ¿El último mensaje del usuario autoriza la acción? Una petición directa,
     * un sí a una propuesta, o el modelo que dice que el usuario la pidió
     * ([modelSaysRequested], el argumento `user_requested`).
     */
    fun userAsked(lastUserText: String?, modelSaysRequested: Boolean = false): Boolean {
        val text = lastUserText ?: return modelSaysRequested
        return modelSaysRequested || DirectRequest.isDirect(text) ||
            ConfirmationResolver.resolve(text) == ConfirmationResolver.Answer.Affirmative
    }
}

/**
 * ¿Es [text] una petición directa de hacer algo? (puro, seis idiomas). Un verbo
 * de acción en el mensaje ("añade", "open", "instala", "mets", "starte",
 * "追加して"…), que no sea una pregunta sobre si se puede o qué hay ("¿qué
 * puedo añadir?", "should I add them?"). "¿Puedes añadirlos?" sí lo es.
 */
object DirectRequest {

    /** Raíces de verbos de acción (sin acentos, como las deja [ConfirmationResolver.normalize]). */
    private val VERBS = listOf(
        // es
        "anad", "agreg", "abre", "abrir", "lanza", "lanzar", "juega", "jugar", "pon", "poner", "cambia", "cambiar", "renombra",
        "llamalo", "instala", "instalar", "quita", "quitar", "oculta", "esconde", "actualiza", "crea", "crear", "haz", "hacer",
        "elige", "usa", "usar", "configura", "deshaz", "arranca", "inicia", "mete", "metelo",
        // en
        "add", "open", "launch", "play", "start", "run", "set", "change", "rename", "call it", "install", "remove", "hide",
        "update", "refresh", "create", "make", "use", "switch", "undo", "put", "turn",
        // pt
        "adiciona", "adicionar", "abra", "lanca", "joga", "jogar", "poe", "coloca", "muda", "mudar", "troca", "renomeia",
        "remove", "esconde", "atualiza", "cria", "inicia",
        // fr
        "ajoute", "ouvre", "lance", "joue", "mets", "change", "renomme", "installe", "enleve", "retire", "cache", "cree",
        "utilise", "demarre", "passe",
        // de
        "fuge", "hinzu", "offne", "starte", "spiel", "setz", "stell", "andere", "wechsel", "benenne", "installiere", "entferne",
        "versteck", "aktualisiere", "erstelle", "nimm", "mach",
        // ja (sin espacios: se busca dentro)
        "追加", "開いて", "起動", "遊ひ", "変えて", "変更", "インストール", "消して", "隠して", "更新", "作って", "使って", "入れて",
        "して",
    ).map(ConfirmationResolver::normalize)

    /** Palabras con las que empieza una pregunta (no una petición). */
    private val QUESTION_STARTS = listOf(
        "que", "cual", "cuales", "como", "cuanto", "cuantos", "donde", "deberia", "puedo", "hay",
        "what", "which", "how", "where", "should", "can i", "could i", "is there", "are there", "do i",
        "o que", "quais", "qual", "devo", "posso",
        "quel", "quels", "quelle", "comment", "est ce que je", "dois je",
        "was", "welche", "welcher", "wie", "wo", "soll ich", "kann ich", "gibt es",
        "何", "とれ", "なに", "とう",
    ).map(ConfirmationResolver::normalize)

    fun isDirect(text: String): Boolean {
        val t = ConfirmationResolver.normalize(text)
        if (t.isEmpty()) return false
        val question = text.contains('?') || text.contains('？')
        if (question && QUESTION_STARTS.any { t == it || t.startsWith("$it ") || (it.first().code > 0x3000 && t.startsWith(it)) }) return false
        val words = t.split(' ')
        return VERBS.any { v ->
            if (v.first().code > 0x3000) t.contains(v)
            else if (' ' in v) t.contains(v)
            else words.any { w -> w.startsWith(v) && (w.length - v.length) <= MAX_SUFFIX }
        }
    }

    /** "anad" vale para "añade", "añadelos", "añadir"; no para palabras largas que solo empiezan igual. */
    private const val MAX_SUFFIX = 5
}

/**
 * Qué juegos encajan con lo que dice el usuario (puro): el título exacto (sin
 * mayúsculas); si no, los que lo contienen; si no, el parecido más alto que
 * pase de [MIN_SIMILARITY]. Más de uno = ambiguo: Lucy pregunta.
 */
object TargetMatch {

    const val MIN_SIMILARITY = 0.7

    fun <T> candidates(query: String, items: List<T>, title: (T) -> String): List<T> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val exact = items.filter { title(it).equals(q, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        val contained = items.filter { title(it).contains(q, ignoreCase = true) }
        if (contained.isNotEmpty()) return contained
        return items.maxByOrNull { Names.similarity(q, title(it)) }
            ?.takeIf { Names.similarity(q, title(it)) >= MIN_SIMILARITY }
            ?.let { listOf(it) }
            .orEmpty()
    }
}
