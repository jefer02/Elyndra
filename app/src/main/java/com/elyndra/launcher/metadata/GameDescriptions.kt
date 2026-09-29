package com.elyndra.launcher.metadata

/* ─────────────────────────────────────────────────────────────
   La descripción de un juego, en el idioma de la app.

   La sinopsis guardada (ScreenScraper, IGDB…) está en el idioma que
   tenía la app al descargar los metadatos —IGDB solo la da en
   inglés—, y cambiar de idioma no la vuelve a pedir. Así que al
   enseñarla se decide aquí qué texto va:

     1. La guardada, si ya está en el idioma de la app.
     2. Si no, la sinopsis de la Wikipedia de ese idioma.
     3. Si tampoco hay, la del idioma por defecto (inglés): la
        guardada tal cual o, a falta de ella, la Wikipedia inglesa.

   El idioma de un texto guardado se reconoce con [LanguageGuess],
   sin red ni dependencias.
   ───────────────────────────────────────────────────────────── */

object GameDescriptions {

    /** El idioma al que se vuelve cuando no hay traducción. */
    const val DEFAULT_LANG = "en"

    suspend fun resolve(title: String?, stored: String?, lang: String, short: Boolean): String? {
        val saved = stored?.trim()?.takeIf { it.isNotEmpty() }
        fun fit(text: String) = if (short) Wikipedia.shorten(text) else text

        if (saved != null && LanguageGuess.matches(saved, lang)) return fit(saved)
        val name = title?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null) Wikipedia.summary(name, lang, short)?.let { return it }
        // Sin traducción: el idioma por defecto.
        if (saved != null) return fit(saved)
        if (name != null && lang != DEFAULT_LANG) return Wikipedia.summary(name, DEFAULT_LANG, short)
        return null
    }
}

/**
 * Reconoce el idioma de un texto entre los que tiene la app (es, en, pt, fr,
 * de, ja) contando palabras muy frecuentes y propias de cada uno. Para una
 * sinopsis de un par de frases basta y sobra; si no está claro, dice null.
 */
object LanguageGuess {

    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "en" to setOf("the", "and", "of", "to", "is", "it", "with", "for", "as", "on", "his", "her", "by", "from", "that", "are", "an", "which", "game", "players", "was", "you", "their", "this"),
        "es" to setOf("el", "los", "las", "del", "por", "con", "una", "y", "es", "su", "sus", "al", "juego", "jugador", "se", "como", "pero", "más", "para", "este", "esta"),
        "pt" to setOf("o", "os", "do", "da", "dos", "das", "em", "um", "uma", "não", "é", "com", "jogo", "jogador", "seu", "sua", "ao", "no", "na", "pelo", "pela", "mais"),
        "fr" to setOf("le", "les", "et", "une", "du", "est", "qui", "dans", "pour", "sur", "avec", "au", "aux", "jeu", "joueur", "il", "elle", "ce", "cette", "sont", "par"),
        "de" to setOf("der", "die", "das", "und", "ist", "ein", "eine", "den", "dem", "mit", "von", "zu", "im", "auf", "für", "sich", "nicht", "spiel", "spieler", "wird", "auch"),
    )

    fun detect(text: String): String? {
        val sample = text.take(600)
        // Japonés: kana o kanji en buena proporción.
        val cjk = sample.count { it in '぀'..'ヿ' || it in '一'..'鿿' }
        if (cjk > 0 && cjk >= sample.count { !it.isWhitespace() } * 0.2f) return "ja"
        val words = sample.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }.take(120)
        if (words.isEmpty()) return null
        val scores = STOPWORDS.mapValues { (_, set) -> words.count { it in set } }
        val sorted = scores.entries.sortedByDescending { it.value }
        val best = sorted[0]
        val second = sorted.getOrNull(1)?.value ?: 0
        // Hace falta un mínimo de pistas y ventaja clara sobre el segundo.
        return if (best.value >= 2 && best.value >= second * 1.4f) best.key else null
    }

    fun matches(text: String, lang: String): Boolean = detect(text) == lang
}
