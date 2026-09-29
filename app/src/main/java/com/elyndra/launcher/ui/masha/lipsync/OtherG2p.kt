package com.elyndra.launcher.ui.masha.lipsync

/*
 * Portugués, francés y alemán con reglas sencillas (lo que se ve en la boca:
 * vocales redondeadas o no, cierres de labios, labiodentales). No pretenden
 * ser exactas; lo que falla se corrige en parte con el alineado sobre el audio.
 */

/** Base: recorre la palabra con un "lector" de dígrafos; las subclases dicen qué suena. */
abstract class RuleG2p(private val numbersLang: String?) : G2p {

    protected class Reader(val w: String) {
        var i = 0
        val out = ArrayList<Phone>()
        var vowels = 0
        fun at(k: Int = 0): Char? = w.getOrNull(i + k)
        fun has(s: String) = w.startsWith(s, i)
        fun end(k: Int = 0) = i + k >= w.length
        fun v(ph: Ph) { out += Phone(ph); vowels++ }
        fun c(ph: Ph) { out += Phone(ph) }
    }

    override fun word(word: String, out: MutableList<Phone>) {
        val w = G2p.lower(word)
        if (w.isEmpty()) return
        if (w[0].isDigit()) {
            val words = numbersLang?.let { NumberWords.words(w, it) }
            if (words == null) G2p.generic(w, out) else words.forEach { word(it, out) }
            return
        }
        val r = Reader(w)
        while (r.i < w.length) {
            val used = step(r)
            r.i += used.coerceAtLeast(1)
        }
        if (r.out.isEmpty()) {
            G2p.generic(w, out)
            return
        }
        // Acento: la vocal que diga el idioma.
        val vi = r.out.indices.filter { r.out[it].ph.vowel }
        if (vi.isNotEmpty()) {
            val k = vi[stressedVowel(vi.size, w).coerceIn(0, vi.size - 1)]
            r.out[k] = r.out[k].copy(stress = true)
        }
        out += r.out
    }

    /** Lee lo que empieza en `r.i`, lo añade y devuelve cuántas letras gasta. */
    protected abstract fun step(r: Reader): Int

    /** Índice (entre las vocales) de la tónica. */
    protected abstract fun stressedVowel(count: Int, word: String): Int

    protected fun isVowel(c: Char?) = c != null && c in "aeiouyàáâãäåèéêëìíîïòóôõöùúûüœæ"
}

/** Portugués (Brasil y Portugal): v labiodental, -m final nasal (sin cierre), lh/nh, o/e finales. */
class PortugueseG2p : RuleG2p(null) {
    override fun step(r: Reader): Int {
        val c = r.at()!!
        val n = r.at(1)
        return when {
            r.has("lh") -> { r.c(Ph.L); r.c(Ph.JG); 2 }
            r.has("nh") -> { r.c(Ph.NY); 2 }
            r.has("ch") -> { r.c(Ph.SH); 2 }
            r.has("rr") -> { r.c(Ph.RUV); 2 }
            r.has("ss") -> { r.c(Ph.S); 2 }
            r.has("qu") && (r.at(2) == 'e' || r.at(2) == 'i') -> { r.c(Ph.K); 2 }
            r.has("gu") && (r.at(2) == 'e' || r.at(2) == 'i') -> { r.c(Ph.G); 2 }
            r.has("ão") -> { r.v(Ph.A); r.c(Ph.WG); 2 }
            r.has("õe") -> { r.v(Ph.O); r.c(Ph.JG); 2 }
            isVowel(c) && (n == 'm' || n == 'n') && (r.end(2) || !isVowel(r.at(2))) -> {
                // Vocal nasal: la m/n no cierra los labios.
                r.v(vowelOf(c)); 2
            }
            isVowel(c) -> {
                val last = r.end(1) || (r.end(2) && n == 's')
                r.v(
                    when {
                        c == 'o' && last -> Ph.U
                        c == 'e' && last -> Ph.I
                        else -> vowelOf(c)
                    },
                ); 1
            }
            else -> {
                when (c) {
                    'b' -> r.c(Ph.B); 'p' -> r.c(Ph.P); 'm' -> r.c(Ph.M)
                    'f' -> r.c(Ph.F); 'v' -> r.c(Ph.V)
                    't' -> r.c(Ph.T); 'd' -> r.c(Ph.D); 'n' -> r.c(Ph.N); 'l' -> r.c(Ph.L)
                    'c' -> r.c(if (n != null && n in "eiéí") Ph.S else Ph.K)
                    'ç' -> r.c(Ph.S)
                    'g' -> r.c(if (n != null && n in "eiéí") Ph.ZH else Ph.G)
                    'j' -> r.c(Ph.ZH)
                    'k', 'q' -> r.c(Ph.K)
                    'r' -> r.c(if (r.i == 0) Ph.RUV else Ph.RT)
                    's' -> r.c(if (r.i > 0 && isVowel(r.at(-1)) && isVowel(n)) Ph.Z else Ph.S)
                    'x' -> r.c(Ph.SH)
                    'z' -> r.c(Ph.Z)
                    'w' -> r.c(Ph.WG)
                    'y' -> r.c(Ph.JG)
                    'h' -> Unit
                }
                1
            }
        }
    }

    private fun vowelOf(c: Char): Ph = when (c) {
        'a', 'á', 'â', 'ã', 'à' -> Ph.A
        'e', 'é', 'ê' -> Ph.E
        'i', 'í', 'y' -> Ph.I
        'o', 'ó', 'ô', 'õ' -> Ph.O
        else -> Ph.U
    }

    override fun stressedVowel(count: Int, word: String): Int {
        val accent = word.indexOfFirst { it in "áéíóúâêôãõ" }
        if (accent >= 0) return word.substring(0, accent).count { isVowel(it) }.coerceAtMost(count - 1)
        val last = word.last()
        return if (last in "aeos" || word.endsWith("em") || word.endsWith("am")) count - 2 else count - 1
    }
}

/** Francés: finales mudas, ou/au/eau/oi, vocales nasales sin cierre, r uvular, u = [y]. */
class FrenchG2p : RuleG2p(null) {
    override fun step(r: Reader): Int {
        val c = r.at()!!
        val n = r.at(1)
        // Consonantes finales mudas (salvo c, r, f, l) y "e" final muda.
        if (r.end(1) && c in "stdxzpg" && r.i > 0) return 1
        if (r.end(1) && c == 'e' && r.vowels > 0) return 1
        if (r.end(2) && c == 'e' && n == 's' && r.vowels > 0) return 2
        if (r.end(3) && r.has("ent") && r.vowels > 0) return 3
        return when {
            r.has("eau") -> { r.v(Ph.O); 3 }
            r.has("ou") -> { r.v(Ph.U); 2 }
            r.has("au") -> { r.v(Ph.O); 2 }
            r.has("oi") -> { r.c(Ph.WG); r.v(Ph.A); 2 }
            r.has("ai") || r.has("ei") -> { r.v(Ph.E); 2 }
            r.has("eu") || r.has("œu") -> { r.v(Ph.OE); 2 }
            r.has("ch") -> { r.c(Ph.SH); 2 }
            r.has("gn") -> { r.c(Ph.NY); 2 }
            r.has("qu") -> { r.c(Ph.K); 2 }
            r.has("ph") -> { r.c(Ph.F); 2 }
            r.has("ill") && r.i > 0 -> { r.v(Ph.I); r.c(Ph.JG); 3 }
            isVowel(c) && (n == 'n' || n == 'm') && !isVowel(r.at(2)) && r.at(2) != n -> {
                // Vocal nasal: sin cierre de labios.
                r.v(
                    when (c) {
                        'o' -> Ph.O
                        'i', 'y' -> Ph.E
                        'u' -> Ph.OE
                        else -> Ph.A
                    },
                ); 2
            }
            isVowel(c) -> {
                r.v(
                    when (c) {
                        'a', 'à', 'â' -> Ph.A
                        'é', 'è', 'ê', 'ë' -> Ph.E
                        'e' -> if (r.end(1)) Ph.AX else Ph.OE
                        'i', 'î', 'ï', 'y' -> Ph.I
                        'o', 'ô' -> Ph.O
                        'u', 'û', 'ü' -> Ph.YV
                        else -> Ph.AX
                    },
                ); 1
            }
            else -> {
                when (c) {
                    'b' -> r.c(Ph.B); 'p' -> r.c(Ph.P); 'm' -> r.c(Ph.M)
                    'f' -> r.c(Ph.F); 'v', 'w' -> r.c(Ph.V)
                    't' -> r.c(Ph.T); 'd' -> r.c(Ph.D); 'n' -> r.c(Ph.N); 'l' -> r.c(Ph.L)
                    'c' -> r.c(if (n != null && n in "eiyéè") Ph.S else Ph.K)
                    'ç' -> r.c(Ph.S)
                    'g' -> r.c(if (n != null && n in "eiyéè") Ph.ZH else Ph.G)
                    'j' -> r.c(Ph.ZH)
                    'k', 'q' -> r.c(Ph.K)
                    'r' -> r.c(Ph.RUV)
                    's' -> r.c(if (r.i > 0 && isVowel(r.at(-1)) && isVowel(n)) Ph.Z else Ph.S)
                    'x' -> { r.c(Ph.K); r.c(Ph.S) }
                    'z' -> r.c(Ph.Z)
                    'h' -> Unit
                }
                if (n == c) 2 else 1
            }
        }
    }

    override fun stressedVowel(count: Int, word: String) = count - 1
}

/** Alemán: sch/ch/ei/ie/eu/au, w = [v], v = [f], z = [ts], st/sp iniciales = [ʃt]/[ʃp], ü/ö redondeadas. */
class GermanG2p : RuleG2p(null) {
    override fun step(r: Reader): Int {
        val c = r.at()!!
        val n = r.at(1)
        return when {
            r.has("sch") -> { r.c(Ph.SH); 3 }
            r.i == 0 && (r.has("st") || r.has("sp")) -> { r.c(Ph.SH); 1 }
            r.has("ch") -> { r.c(Ph.X); 2 }
            r.has("ck") -> { r.c(Ph.K); 2 }
            r.has("pf") -> { r.c(Ph.P); r.c(Ph.F); 2 }
            r.has("qu") -> { r.c(Ph.K); r.c(Ph.V); 2 }
            r.has("ng") -> { r.c(Ph.NG); 2 }
            r.has("ei") || r.has("ai") -> { r.v(Ph.A); r.c(Ph.JG); 2 }
            r.has("ie") -> { r.v(Ph.I); 2 }
            r.has("eu") || r.has("äu") -> { r.v(Ph.O); r.c(Ph.JG); 2 }
            r.has("au") -> { r.v(Ph.A); r.c(Ph.WG); 2 }
            r.has("er") && r.end(2) && r.vowels > 0 -> { r.v(Ph.AX); 2 }
            isVowel(c) -> {
                r.v(
                    when (c) {
                        'a' -> Ph.A
                        'e' -> if (r.end(1) || (r.end(2) && n in listOf('n', 'l'))) Ph.AX else Ph.E
                        'ä' -> Ph.E
                        'i', 'y' -> Ph.I
                        'o' -> Ph.O
                        'ö' -> Ph.OE
                        'u' -> Ph.U
                        'ü' -> Ph.YV
                        else -> Ph.AX
                    },
                )
                // Vocal larga con h muda detrás (Kuh, sehr).
                if (n == 'h') 2 else if (n == c) 2 else 1
            }
            else -> {
                when (c) {
                    'b' -> r.c(Ph.B); 'p' -> r.c(Ph.P); 'm' -> r.c(Ph.M)
                    'f', 'v' -> r.c(Ph.F); 'w' -> r.c(Ph.V)
                    't' -> r.c(Ph.T); 'd' -> r.c(Ph.D); 'n' -> r.c(Ph.N); 'l' -> r.c(Ph.L)
                    'c', 'k', 'q' -> r.c(Ph.K)
                    'g' -> r.c(Ph.G)
                    'j' -> r.c(Ph.JG)
                    'h' -> r.c(Ph.HH)
                    'r' -> r.c(Ph.RUV)
                    's' -> r.c(if (r.i == 0 && isVowel(n)) Ph.Z else Ph.S)
                    'ß' -> r.c(Ph.S)
                    'x' -> { r.c(Ph.K); r.c(Ph.S) }
                    'z' -> { r.c(Ph.T); r.c(Ph.S) }
                }
                if (n == c) 2 else 1
            }
        }
    }

    override fun stressedVowel(count: Int, word: String): Int {
        // Prefijos átonos: la tónica es la siguiente.
        val unstressedPrefix = listOf("be", "ge", "er", "ver", "zer", "ent", "emp").any { word.startsWith(it) && word.length > it.length + 2 }
        return if (unstressedPrefix && count > 1) 1 else 0
    }
}
