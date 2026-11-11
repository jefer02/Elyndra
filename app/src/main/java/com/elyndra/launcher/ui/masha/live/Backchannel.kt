package com.elyndra.launcher.ui.masha.live

import java.text.Normalizer

/**
 * Las muletillas de quien escucha ("mm", "ajá", "vale", "うん"): se dicen
 * mientras Lucy habla y no son para cortarla. Una frase con cualquier otra
 * palabra sí lo es ("ajá, pero ¿y Okami?").
 *
 * Una lista para todos los idiomas de la app (en, es, pt, fr, de, ja): el
 * reconocedor a veces escribe una muletilla de otro idioma, y ninguna de estas
 * es una petición en ninguno.
 */
object Backchannel {

    private val RAW = listOf(
        // en
        "yeah", "yea", "yep", "yes", "mm", "mmm", "mhm", "mm-hmm", "uh-huh", "uhhuh", "uh", "huh", "hm", "hmm", "ok", "okay", "right", "sure", "cool", "nice", "wow", "oh", "ah", "aha",
        // es
        "aja", "si", "vale", "claro", "ya", "bueno", "eh", "ajam", "uhum", "sip", "venga", "anda",
        // pt
        "aham", "sim", "ta", "uhum", "pois", "certo", "e",
        // fr
        "ouais", "oui", "d'accord", "dac", "ok", "bon", "voila", "euh",
        // de
        "ja", "jo", "jep", "genau", "achso", "okay", "aha", "na",
        // ja
        "うん", "うんうん", "はい", "はいはい", "へえ", "へー", "そう", "そうか", "そうだね", "なるほど", "ええ", "あー", "ああ", "ふーん",
    )

    /** Pasadas por [fold], igual que lo que se compara (el japonés también pierde los diacríticos). */
    private val TOKENS: Set<String> = RAW.mapTo(HashSet(), ::fold)

    /** Cuántas palabras puede tener una muletilla ("ok ok", "sí, sí, claro"). */
    private const val MAX_WORDS = 3

    /** ¿Es [text] solo muletilla (o nada)? */
    fun isOnly(text: String?): Boolean {
        val words = words(text ?: return true)
        if (words.isEmpty()) return true
        return words.size <= MAX_WORDS && words.all { it in TOKENS }
    }

    /** ¿Hay en [text] alguna palabra que no sea muletilla? */
    fun hasContent(text: String?): Boolean = !isOnly(text)

    private fun words(text: String): List<String> {
        val folded = fold(text)
        // Japonés: sin espacios; se parte por la puntuación (、。！？) y cada trozo es una "palabra".
        return folded.split(Regex("[\\s,.;:!?¡¿…、。！？~\"“”«»()]+"))
            .map { it.trim('-', '\'') }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith("m") && it.all { c -> c == 'm' || c == 'h' }) "mm" else it }
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
