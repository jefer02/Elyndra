package com.elyndra.launcher.ui.masha.lipsync

/** Pausa (puntuación) que sigue a una palabra. */
enum class Pause { NONE, COMMA, STOP, QUESTION, EXCLAIM }

/** Una palabra del texto que se lee, con su rango de caracteres [start, end). */
class Token(val text: String, val start: Int, val end: Int, val pause: Pause) {
    val isNumber: Boolean get() = text.isNotEmpty() && text[0].isDigit()
    override fun toString() = "$text[$start,$end)$pause"
}

/**
 * Parte el texto en palabras con sus rangos (los mismos índices que
 * `onRangeStart` del motor de voz) y la puntuación que sigue a cada una.
 * Números con separadores ("3,5", "1.000") van en un solo token.
 */
object Tokenizer {

    private fun wordChar(c: Char) = c.isLetter() || c.isDigit() || c == '\'' || c == '’' || c.category == CharCategory.NON_SPACING_MARK

    fun tokens(s: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = s.length
        while (i < n) {
            if (!wordChar(s[i]) || s[i] == '\'' || s[i] == '’') {
                i++
                continue
            }
            val start = i
            val digits = s[i].isDigit()
            while (i < n) {
                val c = s[i]
                val ok = if (digits) {
                    c.isDigit() || ((c == '.' || c == ',') && i + 1 < n && s[i + 1].isDigit())
                } else {
                    (c.isLetter() || c.category == CharCategory.NON_SPACING_MARK) ||
                        ((c == '\'' || c == '’') && i + 1 < n && s[i + 1].isLetter())
                }
                if (!ok) break
                i++
            }
            // Pausa: lo que haya hasta la siguiente palabra.
            var j = i
            var pause = Pause.NONE
            while (j < n && !(s[j].isLetterOrDigit())) {
                val p = when (s[j]) {
                    '?', '？' -> Pause.QUESTION
                    '!', '！' -> Pause.EXCLAIM
                    '.', '…', ';', ':', '。' -> Pause.STOP
                    ',', '、', '，', '—', '–' -> Pause.COMMA
                    '\n' -> Pause.STOP
                    else -> Pause.NONE
                }
                if (p.ordinal > pause.ordinal) pause = p
                j++
            }
            out += Token(s.substring(start, i), start, i, pause)
        }
        return out
    }

    /** ¿Termina el texto en pregunta? (también "¿...?" sin cerrar al final). */
    fun isQuestion(s: String): Boolean {
        val t = s.trimEnd { !it.isLetterOrDigit() && it != '?' && it != '？' }
        return t.endsWith('?') || t.endsWith('？')
    }
}

/** Números en palabras (es, en) para la G2P: el motor los lee y la boca tiene que decirlos. */
object NumberWords {

    private val ES_UNITS = arrayOf(
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
        "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte",
        "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve",
    )
    private val ES_TENS = arrayOf("", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
    private val ES_HUNDREDS = arrayOf(
        "", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos", "setecientos", "ochocientos", "novecientos",
    )
    private val EN_UNITS = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val EN_TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** Palabras de un token numérico ("3,5" → tres coma cinco). Null si el idioma no está. */
    fun words(token: String, lang: String): List<String>? {
        if (lang != "es" && lang != "en") return null
        val out = ArrayList<String>()
        val groups = token.split('.', ',')
        // "1.000" / "1,000": separador de miles si todos los grupos tras el primero tienen 3 cifras.
        val thousands = groups.size > 1 && groups.drop(1).all { it.length == 3 }
        if (thousands) {
            number(groups.joinToString("").toLongOrNull() ?: return null, lang, out)
        } else {
            groups.forEachIndexed { k, g ->
                if (k > 0) out += if (lang == "es") "coma" else "point"
                number(g.toLongOrNull() ?: return null, lang, out)
            }
        }
        return out
    }

    private fun number(n: Long, lang: String, out: MutableList<String>) {
        if (n >= 1_000_000_000L) {
            // Demasiado grande: cifra a cifra.
            n.toString().forEach { number((it - '0').toLong(), lang, out) }
            return
        }
        if (lang == "es") es(n, out) else en(n, out)
    }

    private fun es(n: Long, out: MutableList<String>) {
        when {
            n < 30 -> out += ES_UNITS[n.toInt()]
            n < 100 -> {
                out += ES_TENS[(n / 10).toInt()]
                if (n % 10 != 0L) { out += "y"; out += ES_UNITS[(n % 10).toInt()] }
            }
            n == 100L -> out += "cien"
            n < 1000 -> {
                out += ES_HUNDREDS[(n / 100).toInt()]
                if (n % 100 != 0L) es(n % 100, out)
            }
            n < 1_000_000 -> {
                val k = n / 1000
                if (k > 1) es(k, out)
                out += "mil"
                if (n % 1000 != 0L) es(n % 1000, out)
            }
            else -> {
                val m = n / 1_000_000
                if (m == 1L) { out += "un"; out += "millón" } else { es(m, out); out += "millones" }
                if (n % 1_000_000 != 0L) es(n % 1_000_000, out)
            }
        }
    }

    private fun en(n: Long, out: MutableList<String>) {
        when {
            n < 20 -> out += EN_UNITS[n.toInt()]
            n < 100 -> {
                out += EN_TENS[(n / 10).toInt()]
                if (n % 10 != 0L) out += EN_UNITS[(n % 10).toInt()]
            }
            n < 1000 -> {
                out += EN_UNITS[(n / 100).toInt()]; out += "hundred"
                if (n % 100 != 0L) en(n % 100, out)
            }
            n < 1_000_000 -> {
                en(n / 1000, out); out += "thousand"
                if (n % 1000 != 0L) en(n % 1000, out)
            }
            else -> {
                en(n / 1_000_000, out); out += "million"
                if (n % 1_000_000 != 0L) en(n % 1_000_000, out)
            }
        }
    }
}
