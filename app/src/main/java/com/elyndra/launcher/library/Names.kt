package com.elyndra.launcher.library

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Nombres de juego: limpieza de nombres de archivo para mostrarlos y
 * normalización para comparar con los títulos de los servicios.
 */
object Names {

    // Android compila las regex con ICU, que no acepta "[^]]" como Java: todo escapado.
    private val BRACKETS = Regex("""\([^)]*\)|\[[^\]]*\]|\{[^}]*\}""")
    private val VERSION_SUFFIX = Regex("""\s+v\d+(\.\d+)*[a-z]?$""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")
    private val TRAILING_ARTICLE = Regex("""^(.+?), (The|A|An|El|La|Los|Las|Le|Les|Der|Die|Das)(\s*[-:].*)?$""")
    private val DIACRITICS = Regex("""\p{M}+""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")
    private val ROMAN = mapOf(
        "ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6",
        "vii" to "7", "viii" to "8", "ix" to "9",
    )

    /**
     * "Legend of Zelda, The - A Link to the Past (USA) [!].sfc" →
     * "The Legend of Zelda - A Link to the Past".
     */
    fun cleanTitle(fileName: String, stripExtension: Boolean = true): String {
        var s = if (stripExtension && fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
        val original = s
        s = s.replace('_', ' ')
        s = BRACKETS.replace(s, " ")
        s = SPACES.replace(s, " ").trim().trimEnd('-', ' ', '.').trim()
        s = VERSION_SUFFIX.replace(s, "").trim()
        TRAILING_ARTICLE.matchEntire(s)?.let { m ->
            s = "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}"
        }
        return s.ifBlank { original.trim() }
    }

    /** Clave de comparación: minúsculas, sin acentos, sin artículos iniciales ni puntuación. */
    fun normalize(title: String): String {
        var s = Normalizer.normalize(title, Normalizer.Form.NFD)
        s = DIACRITICS.replace(s, "").lowercase()
        s = BRACKETS.replace(s, " ")
        s = s.replace("&", " and ")
        // "legend of zelda, the - a link…" → "legend of zelda - a link…"
        TRAILING_ARTICLE_LOWER.matchEntire(s.trim())?.let { m ->
            s = "${m.groupValues[1]}${m.groupValues[3]}"
        }
        val tokens = NON_ALNUM.replace(s, " ").trim().split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (tokens.size > 1 && tokens.first() == "the") tokens.removeAt(0)
        return tokens.mapIndexed { i, t -> if (i > 0) ROMAN[t] ?: t else t }.joinToString(" ")
    }

    private val TRAILING_ARTICLE_LOWER =
        Regex("""^(.+?), (the|a|an|el|la|los|las|le|les|der|die|das)(\s*[-:].*)?$""")

    /** Parecido entre dos títulos, 0…1 (1 = idénticos una vez normalizados). */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a).replace(" ", "")
        val y = normalize(b).replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val lev = 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length)
        val shorter = if (x.length <= y.length) x else y
        val longer = if (x.length <= y.length) y else x
        val contained = if (shorter.length >= 5 && longer.contains(shorter)) {
            0.7 + 0.3 * shorter.length / longer.length
        } else 0.0
        return max(lev, contained)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}
