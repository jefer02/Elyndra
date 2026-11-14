package com.elyndra.launcher.masha

import java.text.Normalizer

/**
 * ¿Contesta el usuario "sí", "no" u otra cosa a lo que Lucy propuso? (puro)
 *
 * En los seis idiomas de la app (es, en, pt, fr, de, ja), por voz o por
 * escrito: "sí", "dale", "confirmo", "hazlo", "ok do it", "vas-y", "mach es",
 * "はい、お願い"… Un sí con algo más que no sea relleno ("sí, pero mejor
 * Okami") no es un sí: es otra petición y la contesta el modelo.
 */
object ConfirmationResolver {

    enum class Answer { Affirmative, Negative, Unrelated }

    /** Más palabras que esto ya no es una respuesta corta. */
    private const val MAX_WORDS = 7

    private val AFFIRMATIVE = listOf(
        // es
        "si", "vale", "dale", "ok", "okay", "okey", "claro", "claro que si", "confirmo", "confirmar", "confirma", "confirmado",
        "hazlo", "hazlo ya", "adelante", "de acuerdo", "venga", "por supuesto", "perfecto", "exacto", "va", "sale", "eso", "aja",
        "si hazlo", "anadelos", "anadelo", "instalalo", "abrelo", "ponlo", "lanzalo",
        // en
        "yes", "yeah", "yep", "yup", "sure", "confirm", "confirmed", "do it", "go ahead", "go for it", "please do", "sounds good",
        "absolutely", "of course", "alright", "all right", "lets do it", "lets go", "yes please", "do that", "ok do it",
        // pt
        "sim", "pode", "pode ser", "claro", "faz isso", "faca isso", "manda ver", "beleza", "combinado", "isso", "bora", "confirma",
        // fr
        "oui", "ouais", "daccord", "dac", "vas y", "allez", "allez y", "confirme", "confirmer", "fais le", "bien sur", "carrement",
        "parfait", "volontiers",
        // de
        "ja", "jawohl", "klar", "mach es", "mach das", "machs", "bestatige", "bestatigen", "gerne", "sicher", "auf jeden fall", "los",
        "passt", "genau", "na klar",
        // ja
        "はい", "うん", "ええ", "お願い", "お願いします", "おねがい", "いいよ", "いいね", "了解", "りょうかい", "やって", "どうぞ", "オッケー",
        "おっけー", "確認", "そうして",
    )

    private val NEGATIVE = listOf(
        // es
        "no", "nop", "cancela", "cancelar", "cancelalo", "mejor no", "dejalo", "ni hablar", "para", "nada", "olvidalo", "no gracias",
        // en
        "nope", "nah", "cancel", "dont", "stop", "never mind", "nevermind", "not now", "no thanks", "forget it",
        // pt
        "nao", "deixa", "deixa pra la", "esquece", "agora nao",
        // fr
        "non", "annule", "annuler", "laisse tomber", "pas maintenant", "non merci",
        // de
        "nein", "abbrechen", "lass es", "lieber nicht", "nicht", "nein danke",
        // ja
        "いいえ", "いや", "やめて", "やめ", "キャンセル", "だめ", "ダメ", "いらない",
    )

    /** Lo que puede acompañar a un sí o un no sin cambiarlo. */
    private val FILLER = listOf(
        "por favor", "porfa", "lucy", "gracias", "ya", "ahora", "lo", "eso", "pues", "entonces", "venga",
        "please", "thanks", "thank you", "now", "then", "it", "that", "just",
        "obrigado", "obrigada", "agora", "entao",
        "sil te plait", "sil vous plait", "merci", "maintenant", "alors",
        "bitte", "danke", "jetzt", "dann",
        "ね", "よ", "です", "ください",
    )

    private val affirmative = fold(AFFIRMATIVE)
    private val negative = fold(NEGATIVE)
    private val filler = fold(FILLER)

    fun resolve(text: String): Answer {
        val t = normalize(text)
        if (t.isEmpty()) return Answer.Unrelated
        if (t.count { it == ' ' } + 1 > MAX_WORDS) return Answer.Unrelated
        return when {
            consumes(t, negative) -> Answer.Negative
            consumes(t, affirmative) -> Answer.Affirmative
            else -> Answer.Unrelated
        }
    }

    /**
     * ¿Se puede leer [t] entero como frases de [main] y relleno, con al menos
     * una de [main] al principio (tras el relleno)? Se prueba la frase más larga primero.
     */
    private fun consumes(t: String, main: List<String>): Boolean {
        var rest = t
        var matched = false
        while (rest.isNotEmpty()) {
            val m = main.firstOrNull { startsWithPhrase(rest, it) }
            val f = if (m == null) filler.firstOrNull { startsWithPhrase(rest, it) } else null
            val hit = m ?: f ?: return false
            if (m != null) matched = true
            rest = rest.substring(hit.length).trimStart()
        }
        return matched
    }

    /** [phrase] al principio y entera: le sigue un espacio, el final o (en japonés) cualquier cosa. */
    private fun startsWithPhrase(s: String, phrase: String): Boolean {
        if (!s.startsWith(phrase)) return false
        if (s.length == phrase.length) return true
        val next = s[phrase.length]
        return next == ' ' || isCjk(phrase.last()) || isCjk(next)
    }

    private fun isCjk(c: Char): Boolean = c.code in 0x3040..0x30FF || c.code in 0x4E00..0x9FFF

    private fun fold(list: List<String>): List<String> = list.map(::normalize).distinct().sortedByDescending { it.length }

    /** Minúsculas, sin acentos ni apóstrofos, la puntuación como espacio. */
    internal fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("['’`]"), "")
            .replace(Regex("[\\s,.;:!?¡¿…、。！？~\"“”«»()\\-]+"), " ")
            .trim()
}
