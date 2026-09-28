package com.elyndra.launcher.ui.masha.lipsync

import java.util.Locale

/**
 * Texto → fonemas (grafema a fonema), por palabra. Sin GPL: reglas propias y
 * CMUdict (BSD-2) para el inglés.
 */
interface G2p {
    /** Añade a [out] los fonemas de la palabra [word] (tal como viene en el texto). */
    fun word(word: String, out: MutableList<Phone>)

    companion object {
        /**
         * La G2P del idioma de la voz ([locale] BCP 47, p. ej. "es-ES", "es-MX",
         * "en-US"). Null = sin texto fiable (japonés…): la boca sale solo del audio.
         */
        fun forLocale(locale: Locale, dict: CmuDict?): G2p? = when (locale.language) {
            "es" -> SpanishG2p(
                distincion = locale.country.uppercase(Locale.ROOT) == "ES",
                sheismo = locale.country.uppercase(Locale.ROOT) in setOf("AR", "UY"),
            )
            "en" -> EnglishG2p(dict)
            "pt" -> PortugueseG2p()
            "fr" -> FrenchG2p()
            "de" -> GermanG2p()
            else -> null
        }

        internal fun lower(s: String) = s.lowercase(Locale.ROOT).replace('’', '\'')

        /** Palabra que no se sabe leer (símbolos, otra escritura): sílabas neutras, una por cada 2 letras. */
        internal fun generic(word: String, out: MutableList<Phone>) {
            val n = ((word.count { it.isLetterOrDigit() } + 1) / 2).coerceIn(1, 6)
            repeat(n) { k ->
                if (k > 0) out += Phone(Ph.D)
                out += Phone(if (k % 2 == 0) Ph.AH else Ph.E, stress = k == 0)
            }
        }
    }
}

/**
 * Español por reglas: la ortografía es casi fonémica.
 *
 * - [distincion] (España): c+e/i y z = /θ/ (TH); si no, seseo (SS).
 * - [sheismo] (Río de la Plata): ll/y = /ʃ/ (CH entero); si no, /ʝ/ (CH suave).
 * - b = v siempre (nunca FF): oclusiva [b] al principio y tras m/n, aproximante [β] entre vocales.
 * - h muda; ch; qu/gu (+e/i) con u muda; gü; x = /ks/ (/x/ en México, Oaxaca, Texas…);
 *   r inicial o tras n/l/s y rr = vibrante múltiple; n ante p/b/m = [m], ante k/g/x = [ŋ].
 * - d y g entre vocales, débiles ([ð], [ɣ]).
 * - Acento: la tilde; si no, llana si acaba en vocal, n o s; si no, aguda. Los
 *   monosílabos átonos (el, la, de, que…) sin acento.
 */
class SpanishG2p(private val distincion: Boolean, private val sheismo: Boolean) : G2p {

    override fun word(word: String, out: MutableList<Phone>) {
        val w = G2p.lower(word)
        if (w.isEmpty()) return
        if (w[0].isDigit()) {
            val words = NumberWords.words(w, "es") ?: return G2p.generic(w, out)
            words.forEach { word(it, out) }
            return
        }
        val s = spell(w)
        if (s.isEmpty()) return
        emit(s, w, out)
    }

    /** Letras → símbolos fonémicos intermedios (una letra por sonido). */
    private fun spell(w: String): String {
        val sb = StringBuilder(w.length + 2)
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val n = w.getOrNull(i + 1)
            val n2 = w.getOrNull(i + 2)
            when (c) {
                'h' -> Unit
                'c' -> when {
                    n == 'h' -> { sb.append('C'); i++ }
                    frontVowel(n) -> sb.append(if (distincion) 'Θ' else 's')
                    else -> sb.append('k')
                }
                'q' -> { sb.append('k'); if (n == 'u') i++ }
                'k' -> sb.append('k')
                'g' -> when {
                    n == 'u' && frontVowel(n2) -> { sb.append('g'); i++ }
                    n == 'ü' -> { sb.append('g'); sb.append('u'); i++ }
                    frontVowel(n) -> sb.append('x')
                    else -> sb.append('g')
                }
                'j' -> sb.append('x')
                'z' -> sb.append(if (distincion) 'Θ' else 's')
                'l' -> if (n == 'l') { sb.append('Y'); i++ } else sb.append('l')
                'r' -> when {
                    n == 'r' -> { sb.append('R'); i++ }
                    sb.isEmpty() || sb.last() in "nls" -> sb.append('R')
                    else -> sb.append('r')
                }
                'y' -> when {
                    vowelLetter(n) -> sb.append('Y')
                    else -> sb.append('ÿ')
                }
                'v', 'b' -> sb.append('b')
                'x' -> when {
                    i == 0 -> sb.append('s')
                    MEXICAN_X.any { w.startsWith(it) } -> sb.append('x')
                    else -> { sb.append('k'); sb.append('s') }
                }
                'ñ' -> sb.append('N')
                'w' -> sb.append('w')
                'ü' -> sb.append('u')
                '\'' -> Unit
                else -> if (c.isLetter()) sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun emit(s: String, original: String, out: MutableList<Phone>) {
        // Núcleos: en cada racha de vocales, las fuertes (a, e, o, í, ú) son núcleo y las
        // débiles (i, u, y final) semivocales; si solo hay débiles, la última es el núcleo.
        val nucleus = BooleanArray(s.length)
        var i = 0
        while (i < s.length) {
            if (!vowelSym(s[i])) { i++; continue }
            var j = i
            while (j < s.length && vowelSym(s[j])) j++
            var strong = false
            for (k in i until j) if (s[k] !in WEAK) { nucleus[k] = true; strong = true }
            // "muy", "hoy": la y final es semivocal, el núcleo va delante.
            if (!strong) nucleus[if (s[j - 1] == 'ÿ' && j - 1 > i) j - 2 else j - 1] = true
            i = j
        }
        val nuclei = (s.indices).filter { nucleus[it] }
        // Un "y" suelto (la conjunción) es una vocal.
        if (nuclei.isEmpty()) {
            if (s == "ÿ") { out += Phone(Ph.I); return }
        }
        val stressed = stressIndex(s, original, nuclei)

        val start = out.size
        for (k in s.indices) {
            val c = s[k]
            val prev = if (k > 0) s[k - 1] else ' '
            val next = s.getOrNull(k + 1)
            val initial = k == 0
            if (vowelSym(c)) {
                if (nucleus[k]) {
                    val ph = when (c) {
                        'a', 'á' -> Ph.A
                        'e', 'é' -> Ph.E
                        'i', 'í', 'ÿ' -> Ph.I
                        'o', 'ó' -> Ph.O
                        else -> Ph.U
                    }
                    out += Phone(ph, stress = k == stressed)
                } else {
                    out += Phone(if (c == 'u' || c == 'ú') Ph.WG else Ph.JG)
                }
                continue
            }
            val intervocalic = !initial && (vowelSym(prev) || prev in "lr") && next != null && (vowelSym(next) || next in "lr")
            val ph: Ph? = when (c) {
                'p' -> Ph.P
                'b' -> if (initial || prev in "mn") Ph.B else Ph.BH
                't' -> Ph.T
                'd' -> if (initial || prev in "nl") Ph.D else Ph.DHW
                'k' -> Ph.K
                'g' -> if (initial || prev == 'n') Ph.G else if (intervocalic) Ph.GH else Ph.G
                'f' -> Ph.F
                's' -> Ph.S
                'Θ' -> Ph.TH
                'x' -> Ph.X
                'C' -> Ph.CH
                'Y' -> if (sheismo) Ph.SH else Ph.YC
                'm' -> Ph.M
                'n' -> when (next) {
                    'p', 'b', 'm' -> Ph.M
                    'k', 'g', 'x' -> Ph.NG
                    else -> Ph.N
                }
                'N' -> Ph.NY
                'l' -> Ph.L
                'r' -> Ph.RT
                'R' -> Ph.RR
                'w' -> Ph.WG
                else -> null
            }
            if (ph != null) out += Phone(ph)
        }
        if (out.size == start) G2p.generic(original, out)
    }

    private fun stressIndex(s: String, original: String, nuclei: List<Int>): Int {
        if (nuclei.isEmpty()) return -1
        for (k in nuclei) if (s[k] in ACCENTED) return k
        if (nuclei.size == 1) return if (original in UNSTRESSED) -1 else nuclei[0]
        val last = original.lastOrNull { it.isLetter() } ?: return nuclei.last()
        val llana = last in "aeiouáéíóún" || last == 's'
        return if (llana) nuclei[nuclei.size - 2] else nuclei.last()
    }

    private companion object {
        const val WEAK = "iuÿ"
        const val ACCENTED = "áéíóú"
        val MEXICAN_X = listOf("méxic", "mexic", "oaxac", "texas", "xavier", "ximena")
        val UNSTRESSED = setOf(
            "el", "la", "los", "las", "lo", "le", "les", "un", "de", "del", "al", "a", "en", "y", "e", "o", "u", "que",
            "se", "me", "te", "nos", "os", "su", "sus", "mi", "mis", "tu", "tus", "por", "con", "sin", "si", "ni", "pero",
            "mas",
        )

        fun frontVowel(c: Char?) = c != null && c in "eiéí"
        fun vowelLetter(c: Char?) = c != null && c in "aeiouáéíóúü"
        fun vowelSym(c: Char) = c in "aeiouáéíóúÿ"
    }
}
