package com.elyndra.launcher.domain.relationship

import java.text.Normalizer

/**
 * Puntuación local de un mensaje del usuario para [Affinity], sin IA: la que
 * se usa sin conexión, cuando la IA falla, cuando la respuesta sale de caché o
 * cuando el modelo no devolvió su marca (ver AffinityTag). Listas cortas de
 * palabras en los idiomas de la app; coste lineal en el largo del mensaje.
 *
 * Reglas (las del personaje original, sin nada sexual):
 * - Pedirle que haga algo (lanzar, planear, cambiar el acento…) no suma ni resta.
 * - Saludar suma 1 solo en [AffinityLevel.Distant] y [AffinityLevel.Curious].
 * - Interés por ella, amabilidad, contar algo personal, humor (desde
 *   [AffinityLevel.Friendly]) y cumplidos (más cuanto más cerca) suman.
 * - Grosería resta; repetir el mismo mensaje resta poco.
 * - Lo sexual no suma nunca ([guard] también recorta lo que diga la IA).
 */
object AffinityScorer {

    /**
     * [isAction]: el mensaje pide una acción (lo decide quien llama, con las
     * intenciones sin conexión). [previous]: el mensaje anterior del usuario.
     */
    fun score(message: String, level: AffinityLevel, isAction: Boolean = false, previous: String? = null): Int {
        val t = fold(message).trim()
        if (t.isEmpty()) return 0
        if (previous != null && fold(previous).trim() == t) return -1
        if (isSexual(t)) return 0
        if (has(t, RUDE)) return if (has(t, INSULTS)) -7 else -4
        if (isAction) return 0
        var points = 0
        if (has(t, COMPLIMENTS)) {
            points += when (level) {
                AffinityLevel.Distant, AffinityLevel.Curious -> 3
                AffinityLevel.Friendly -> 4
                AffinityLevel.Close -> 5
                AffinityLevel.Devoted -> 6
            }
        }
        if (has(t, KINDNESS)) points += 3
        if (has(t, CURIOUS_ABOUT_HER)) points += 2
        if (has(t, PERSONAL)) points += if (t.length > 80) 2 else 1
        if (level >= AffinityLevel.Friendly && has(t, HUMOUR)) points += 2
        if (points == 0 && (level == AffinityLevel.Distant || level == AffinityLevel.Curious) && has(t, GREETINGS)) points = 1
        return points.coerceIn(-Affinity.MAX_DELTA, Affinity.MAX_DELTA)
    }

    /** Lo que propone la IA, con la red de seguridad local: lo sexual nunca suma. */
    fun guard(message: String, modelDelta: Int): Int =
        if (isSexual(fold(message))) minOf(modelDelta, 0) else modelDelta

    fun isSexual(folded: String): Boolean = has(folded, SEXUAL)

    /**
     * El mensaje nombra algo que pone celosa a Lucy: otra chica, otro asistente
     * o chatbot, u otro launcher. No puntúa: decide si su respuesta sin
     * conexión lleva una línea de celos (la IA lo decide sola con el prompt).
     */
    fun isJealousyTrigger(message: String): Boolean = has(fold(message), JEALOUSY)

    /** Minúsculas y sin acentos. */
    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

    private val MARKS = Regex("""\p{M}+""")

    /** Alguna raíz como palabra o comienzo de palabra ("gracias", "thanks"…); en japonés, contenida. */
    private fun has(t: String, stems: List<Regex>) = stems.any { it.containsMatchIn(t) }

    private fun words(vararg stems: String): List<Regex> = stems.map { pattern(it, wholeWord = false) }

    /** Palabras enteras: las cortas ("oi", "sex") engañan dentro de otras ("oigo", "sexto"). */
    private fun whole(vararg stems: String): List<Regex> = stems.map { pattern(it, wholeWord = true) }

    private fun pattern(stem: String, wholeWord: Boolean): Regex {
        val s = fold(stem)
        if (s.any { it.code >= 0x3000 }) return Regex(Regex.escape(s))
        val end = if (wholeWord) """($|[^\p{L}\p{N}])""" else ""
        return Regex("""(^|[^\p{L}\p{N}])${Regex.escape(s)}$end""")
    }

    private val GREETINGS = whole(
        "hola", "buenas", "buenos dias", "hey", "hi", "hello", "good morning", "good evening", "ola", "oi", "bom dia",
        "salut", "coucou", "bonjour", "bonsoir", "hallo", "guten morgen", "guten abend", "servus", "こんにちは", "おはよう", "こんばんは", "やあ",
    )

    private val COMPLIMENTS = words(
        "eres preciosa", "eres guapa", "que guapa", "eres genial", "eres increible", "me encantas", "eres adorable", "eres la mejor", "eres muy lista",
        "you're pretty", "you are pretty", "you're beautiful", "you are beautiful", "you're cute", "you are cute", "you're amazing", "you are amazing",
        "you're the best", "you are the best", "you're so smart", "love your", "i like your", "adorable",
        "voce e linda", "voce e incrivel", "voce e a melhor", "tu es belle", "tu es mignonne", "tu es geniale", "t'es la meilleure",
        "du bist suss", "du bist toll", "du bist die beste", "du bist wunderschon", "かわいい", "可愛い", "すごいね", "最高", "きれい",
    )

    private val KINDNESS = words(
        "gracias", "te lo agradezco", "eres un sol", "thank", "thanks", "appreciate", "obrigad", "merci", "danke", "ありがとう", "感謝",
    )

    private val CURIOUS_ABOUT_HER = words(
        "te gusta", "tu favorit", "que te gusta", "hablame de ti", "como estas", "que tal estas", "como te va", "que haces",
        "do you like", "your favorite", "your favourite", "tell me about you", "how are you", "what do you like", "about yourself",
        "voce gosta", "seu favorit", "sua favorit", "como voce esta", "tu aimes", "ton prefere", "ta preferee", "comment tu vas", "ca va",
        "magst du", "dein lieblings", "wie geht", "好き?", "好きな", "元気",
    )

    private val PERSONAL = words(
        "hoy he", "hoy me", "mi dia", "me siento", "estoy cansad", "estoy triste", "estoy content", "mi trabajo", "mis amigos", "sueno con", "suenio",
        "today i", "my day", "i feel", "i'm tired", "i am tired", "i'm sad", "i'm happy", "my job", "my friends", "i dream",
        "hoje eu", "meu dia", "estou cansad", "aujourd'hui j", "ma journee", "je suis fatigu", "je me sens", "heute hab", "mein tag", "ich bin mude", "ich fuhle",
        "今日は", "疲れた", "嬉しい", "悲しい",
    )

    private val HUMOUR = words("jaja", "jeje", "haha", "hehe", "lol", "xd", "mdr", "kkk", "rsrs", "😂", "🤣", "笑", "ｗｗ")

    private val RUDE = words(
        "callate", "idiota", "estupida", "inutil", "tonta", "pesada", "odio", "shut up", "stupid", "idiot", "useless", "dumb", "annoying", "hate you",
        "cala a boca", "burra", "inutil", "ta gueule", "idiote", "nulle", "debile", "halt die klappe", "dumm", "nutzlos", "blod",
        "うるさい", "バカ", "ばか", "黙れ", "使えない",
        "fuck", "puta", "mierda", "joder", "gilipollas", "merde", "scheisse", "porra",
    )

    /** Lo de [RUDE] que va directo contra ella: resta más. */
    private val INSULTS = words("idiota", "estupida", "gilipollas", "stupid", "idiot", "burra", "idiote", "debile", "dumm", "バカ", "ばか", "puta", "fuck you")

    private val JEALOUSY = whole(
        // Otros asistentes y chatbots.
        "siri", "alexa", "ok google", "hey google", "google assistant", "chatgpt", "chat gpt", "gemini", "copilot", "claude",
        "cortana", "bixby", "replika", "character.ai", "otra ia", "another ai", "other ai", "outra ia", "autre ia", "andere ki", "他のai",
        // Otras chicas.
        "mi novia", "otra chica", "una chica", "mi crush", "my girlfriend", "another girl", "a girl", "my crush",
        "minha namorada", "outra garota", "uma garota", "ma copine", "une fille", "autre fille", "meine freundin", "ein madchen",
        "anderes madchen", "彼女", "女の子",
        // Otros launchers.
        "es-de", "daijisho", "pegasus", "launchbox", "playnite", "otro launcher", "another launcher", "other launcher",
        "outro launcher", "autre launcher", "anderer launcher", "他のランチャー",
    )

    private val SEXUAL = words(
        "desnud", "tetas", "follar", "cachond", "naked", "boobs", "horny", "nsfw", "lingerie", "undress",
        "tesao", "toute nue", "nackt", "titten", "エロ", "裸", "セックス",
    ) + whole("sexo", "sexy", "sex", "nude", "nudes", "pelada", "baiser", "seins")
}
