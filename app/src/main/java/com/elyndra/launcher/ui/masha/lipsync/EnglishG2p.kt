package com.elyndra.launcher.ui.masha.lipsync

import java.io.InputStream

/**
 * CMUdict compacto (`assets/lipsync/cmudict.txt`, generado por
 * `scripts/lipsync/build_cmudict.py`; licencia BSD-2 en
 * `assets/lipsync/CMUDICT_LICENSE.txt`). Se guarda tal cual (≈2 MB de bytes)
 * y se busca por bisección sobre las líneas: sin mapa de 126 000 entradas.
 */
class CmuDict private constructor(private val data: ByteArray, private val lines: IntArray) {

    val size: Int get() = lines.size

    /** Fonemas ARPAbet con acento ("HH", "AH0"…) de [word] en minúsculas, o null. */
    fun lookup(word: String): List<String>? {
        var lo = 0
        var hi = lines.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(lines[mid], word)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return decode(lines[mid] + word.length + 1)
            }
        }
        return null
    }

    /** Compara la palabra de la línea que empieza en [at] con [word] (bytes ASCII). */
    private fun compare(at: Int, word: String): Int {
        var i = 0
        while (true) {
            val b = data[at + i].toInt()
            val lineEnded = b == SPACE
            val wordEnded = i >= word.length
            if (lineEnded && wordEnded) return 0
            if (lineEnded) return -1
            if (wordEnded) return 1
            val w = word[i].code
            if (b != w) return b - w
            i++
        }
    }

    private fun decode(from: Int): List<String> {
        val out = ArrayList<String>(8)
        var i = from
        while (i < data.size && data[i].toInt() != NEWLINE) {
            val code = data[i] - 0x30
            out += if (code < VOWELS.size * 3) VOWELS[code / 3] + (code % 3) else CONSONANTS[code - VOWELS.size * 3]
            i++
        }
        return out
    }

    companion object {
        private const val SPACE = ' '.code
        private const val NEWLINE = '\n'.code

        /** Mismo orden que `build_cmudict.py`. */
        val VOWELS = arrayOf("AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW")
        val CONSONANTS = arrayOf(
            "B", "CH", "D", "DH", "F", "G", "HH", "JH", "K", "L", "M", "N", "NG", "P", "R",
            "S", "SH", "T", "TH", "V", "W", "Y", "Z", "ZH",
        )

        fun load(input: InputStream): CmuDict = fromBytes(input.use { it.readBytes() })

        fun fromBytes(data: ByteArray): CmuDict {
            var count = 0
            for (b in data) if (b.toInt() == NEWLINE) count++
            val lines = IntArray(count)
            var n = 0
            var start = 0
            for (i in data.indices) {
                if (data[i].toInt() == NEWLINE) {
                    if (i > start) lines[n++] = start
                    start = i + 1
                }
            }
            return CmuDict(data, if (n == count) lines else lines.copyOf(n))
        }
    }
}

/**
 * Inglés: CMUdict si está (y la palabra también); si no, reglas de letra a
 * sonido sencillas (dígrafos, "e" muda final, vocales largas y cortas).
 */
class EnglishG2p(private val dict: CmuDict?) : G2p {

    override fun word(word: String, out: MutableList<Phone>) {
        val w = G2p.lower(word)
        if (w.isEmpty()) return
        if (w[0].isDigit()) {
            val words = NumberWords.words(w, "en") ?: return G2p.generic(w, out)
            words.forEach { word(it, out) }
            return
        }
        val arpa = dict?.lookup(w) ?: (if (w.endsWith("'s")) dict?.lookup(w.dropLast(2))?.plus("Z") else null)
        if (arpa != null) {
            fromArpa(arpa, out)
            return
        }
        letterToSound(w.filter { it.isLetter() }, out)
    }

    companion object {
        /** ARPAbet (con dígito de acento) → fonemas; los diptongos se parten en vocal + semivocal. */
        fun fromArpa(arpa: List<String>, out: MutableList<Phone>) {
            for (p in arpa) {
                val digit = p.last()
                val stress = digit == '1'
                val base = if (digit.isDigit()) p.dropLast(1) else p
                when (base) {
                    "AA" -> out += Phone(Ph.A, stress)
                    "AE" -> out += Phone(Ph.AE, stress)
                    "AH" -> out += Phone(if (digit == '0') Ph.AX else Ph.AH, stress)
                    "AO" -> out += Phone(Ph.AO, stress)
                    "AW" -> { out += Phone(Ph.A, stress); out += Phone(Ph.WG) }
                    "AY" -> { out += Phone(Ph.A, stress); out += Phone(Ph.JG) }
                    "EH" -> out += Phone(Ph.E, stress)
                    "ER" -> out += Phone(Ph.ER, stress)
                    "EY" -> { out += Phone(Ph.E, stress); out += Phone(Ph.JG) }
                    "IH" -> out += Phone(Ph.IH, stress)
                    "IY" -> out += Phone(Ph.I, stress)
                    "OW" -> { out += Phone(Ph.O, stress); out += Phone(Ph.WG) }
                    "OY" -> { out += Phone(Ph.O, stress); out += Phone(Ph.JG) }
                    "UH" -> out += Phone(Ph.UH, stress)
                    "UW" -> out += Phone(Ph.U, stress)
                    "B" -> out += Phone(Ph.B)
                    "CH" -> out += Phone(Ph.CH)
                    "D" -> out += Phone(Ph.D)
                    "DH" -> out += Phone(Ph.DH)
                    "F" -> out += Phone(Ph.F)
                    "G" -> out += Phone(Ph.G)
                    "HH" -> out += Phone(Ph.HH)
                    "JH" -> out += Phone(Ph.JH)
                    "K" -> out += Phone(Ph.K)
                    "L" -> out += Phone(Ph.L)
                    "M" -> out += Phone(Ph.M)
                    "N" -> out += Phone(Ph.N)
                    "NG" -> out += Phone(Ph.NG)
                    "P" -> out += Phone(Ph.P)
                    "R" -> out += Phone(Ph.REN)
                    "S" -> out += Phone(Ph.S)
                    "SH" -> out += Phone(Ph.SH)
                    "T" -> out += Phone(Ph.T)
                    "TH" -> out += Phone(Ph.TH)
                    "V" -> out += Phone(Ph.V)
                    "W" -> out += Phone(Ph.WG)
                    "Y" -> out += Phone(Ph.JG)
                    "Z" -> out += Phone(Ph.Z)
                    "ZH" -> out += Phone(Ph.ZH)
                }
            }
        }

        private fun vowel(c: Char?) = c != null && c in "aeiouy"

        /** Reglas de letra a sonido para palabras fuera del diccionario (nombres, jerga). */
        fun letterToSound(w: String, out: MutableList<Phone>) {
            if (w.isEmpty()) return
            val start = out.size
            // "e" final muda (make, time) si hay otra vocal antes.
            val silentE = w.length > 2 && w.last() == 'e' && !vowel(w[w.length - 2]) && w.dropLast(1).any { vowel(it) }
            val s = if (silentE) w.dropLast(1) else w
            var firstVowel = true
            var i = 0
            fun v(ph: Ph) {
                out += Phone(ph, stress = firstVowel)
                firstVowel = false
            }
            while (i < s.length) {
                val c = s[i]
                val n = s.getOrNull(i + 1)
                val n2 = s.getOrNull(i + 2)
                val two = if (n != null) "$c$n" else ""
                var used = 1
                when {
                    s.startsWith("igh", i) -> { v(Ph.A); out += Phone(Ph.JG); used = 3 }
                    two == "th" -> { out += Phone(Ph.TH); used = 2 }
                    two == "sh" -> { out += Phone(Ph.SH); used = 2 }
                    two == "ch" -> { out += Phone(Ph.CH); used = 2 }
                    two == "ph" -> { out += Phone(Ph.F); used = 2 }
                    two == "wh" -> { out += Phone(Ph.WG); used = 2 }
                    two == "ck" -> { out += Phone(Ph.K); used = 2 }
                    two == "ng" -> { out += Phone(Ph.NG); used = 2 }
                    two == "qu" -> { out += Phone(Ph.K); out += Phone(Ph.WG); used = 2 }
                    two == "ee" || two == "ea" || two == "ie" -> { v(Ph.I); used = 2 }
                    two == "oo" -> { v(Ph.U); used = 2 }
                    two == "ou" || two == "ow" -> { v(Ph.A); out += Phone(Ph.WG); used = 2 }
                    two == "oa" -> { v(Ph.O); out += Phone(Ph.WG); used = 2 }
                    two == "ai" || two == "ay" || two == "ei" || two == "ey" -> { v(Ph.E); out += Phone(Ph.JG); used = 2 }
                    two == "au" || two == "aw" -> { v(Ph.AO); used = 2 }
                    two == "oi" || two == "oy" -> { v(Ph.O); out += Phone(Ph.JG); used = 2 }
                    c == n && !vowel(c) -> used = 1 // consonante doble: una sola
                    else -> {
                        // Vocal larga si le sigue consonante + vocal (o la e muda); si no, corta.
                        val long = !vowel(n) && n != null && (vowel(n2) || (silentE && i + 2 >= s.length))
                        when (c) {
                            'a' -> if (long) { v(Ph.E); out += Phone(Ph.JG) } else v(Ph.AE)
                            'e' -> if (long) v(Ph.I) else v(Ph.E)
                            'i' -> if (long) { v(Ph.A); out += Phone(Ph.JG) } else v(Ph.IH)
                            'o' -> if (long) { v(Ph.O); out += Phone(Ph.WG) } else v(Ph.A)
                            'u' -> if (long) v(Ph.U) else v(Ph.AH)
                            'y' -> if (i == 0) out += Phone(Ph.JG) else v(Ph.I)
                            'b' -> out += Phone(Ph.B)
                            'c' -> out += Phone(if (n != null && n in "eiy") Ph.S else Ph.K)
                            'd' -> out += Phone(Ph.D)
                            'f' -> out += Phone(Ph.F)
                            'g' -> out += Phone(if (n != null && n in "eiy") Ph.JH else Ph.G)
                            'h' -> out += Phone(Ph.HH)
                            'j' -> out += Phone(Ph.JH)
                            'k' -> if (!(i == 0 && n == 'n')) out += Phone(Ph.K)
                            'l' -> out += Phone(Ph.L)
                            'm' -> out += Phone(Ph.M)
                            'n' -> out += Phone(Ph.N)
                            'p' -> out += Phone(Ph.P)
                            'q' -> out += Phone(Ph.K)
                            'r' -> out += Phone(Ph.REN)
                            's' -> out += Phone(if (i > 0 && vowel(s[i - 1]) && vowel(n)) Ph.Z else Ph.S)
                            't' -> out += Phone(Ph.T)
                            'v' -> out += Phone(Ph.V)
                            'w' -> out += Phone(Ph.WG)
                            'x' -> { out += Phone(Ph.K); out += Phone(Ph.S) }
                            'z' -> out += Phone(Ph.Z)
                        }
                    }
                }
                i += used
            }
            if (out.size == start) G2p.generic(w, out)
        }
    }
}
