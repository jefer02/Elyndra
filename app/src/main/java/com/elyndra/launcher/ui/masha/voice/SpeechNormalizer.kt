package com.elyndra.launcher.ui.masha.voice

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Texto → texto que se puede leer en voz alta, antes de la voz neural.
 *
 * Supertonic lee carácter a carácter y no normaliza nada ("23 %" sale "23.200",
 * "2.1.4" es ruido). Aquí cifras, horas, fechas, monedas, unidades, siglas y
 * abreviaturas pasan a palabras del idioma. La MISMA cadena va a la voz y a los
 * labios (lipsync.Tokenizer): texto plano con la puntuación de frase
 * (. , ¿? ¡! …) intacta para las pausas.
 *
 * Decisiones:
 * - Separadores: un solo separador con 3 cifras detrás ("1.000", "1,000") lo
 *   decide el idioma/región ("…000" siempre son miles); con otro número de cifras
 *   es decimal sea cual sea el signo ("3.5 GB" en español = tres coma cinco).
 *   Varios puntos que no son miles = versión ("2.1.4" → dos punto uno punto cuatro).
 * - Fechas: día/mes/año; en inglés sin región o de EE. UU., mes/día cuando es
 *   ambiguo. Si una parte pasa de 12, esa es el día. AAAA-MM-DD es ISO.
 * - Romanos: solo II–XX tras una palabra con mayúscula ("Final Fantasy VII" →
 *   siete / Seven, "GTA V" → cinco), nunca "I" suelta, ni tras "Series", "Man"…
 *   (Xbox Series X, Mega Man X). Tras "siglo", "capítulo", "parte"… hasta XXXIX.
 * - Siglas sin vocales (DLC, FPS, NPC, PS5) se deletrean con los nombres de las
 *   letras (es "de ele ce", en "D L C"); las que se leen como palabra (RAM, MOBA)
 *   se dejan. AAA → "triple A"; GOTY → "goti" / "Game of the Year".
 * - Español: concordancia con el sustantivo que sigue ("una hora", "veintiún
 *   juegos", "doscientas partidas"). España: "uve", "uno de enero"; América:
 *   "ve", "primero de enero", "$" = pesos donde toca, decimal "punto" en MX/US.
 * - Japonés: numerales en kanji (十二時間四十五分), sin espacios.
 * - Idempotente: la salida no tiene cifras ni siglas que volver a expandir.
 * Nunca lanza: si una regla falla, ese trozo queda tal cual.
 */
object SpeechNormalizer {

    /** [lang]: "es", "en", "pt", "fr", "de", "ja"; [region]: "ES", "MX", "US", "BR"… o null. */
    fun normalize(text: String, lang: String, region: String? = null): String {
        if (text.isBlank()) return text
        return try {
            val sp = Speller.of(lang, region) ?: return text
            // Punto fijo: casos raros (URL pegada a "etc.", siglas pegadas) pueden cambiar
            // en una segunda pasada; se repite hasta que no cambie (normalmente 1–2 pasadas).
            var cur = Normalizer(sp, text).run()
            repeat(2) {
                val next = Normalizer(sp, cur).run()
                if (next == cur) return cur
                cur = next
            }
            cur
        } catch (_: Throwable) {
            text
        }
    }
}

internal enum class Gen { N, M, F }

/** Una cifra escrita, ya interpretada. */
internal sealed class Num {
    class Whole(val digits: String) : Num() {
        val value: Long? = if (digits.length > 15 || (digits.length > 1 && digits[0] == '0')) null else digits.toLongOrNull()
    }
    class Dec(val whole: String, val frac: String) : Num()
    /** Versión ("2.1.4", sep '.') o lista ("1,2,3", sep ','). */
    class Seq(val parts: List<String>, val sep: Char) : Num()
}

internal class Measure(val one: String, val many: String, val g: Gen = Gen.M)

internal class Cur(
    val one: String, val many: String, val centOne: String, val centMany: String,
    val g: Gen = Gen.M, val centG: Gen = Gen.M,
)

internal class Abbr(regex: String, val text: String, val canEnd: Boolean = false) {
    val pattern: Pattern = Pattern.compile("(?:$regex)(?![\\p{L}\\p{N}])")
}

private class Hit(val end: Int, val text: String)

/* ───────────────────────── Motor ───────────────────────── */

private class Normalizer(private val sp: Speller, text: String) {

    private val s = prepare(text)
    private val out = StringBuilder(s.length + 64)

    fun run(): String {
        var i = 0
        while (i < s.length) {
            val hit = try { step(i) } catch (_: Throwable) { null }
            if (hit != null && hit.end > i) {
                emit(hit)
                i = hit.end
            } else {
                out.append(s[i])
                i++
            }
        }
        return tidy(out)
    }

    /** Añade el reemplazo con los espacios justos alrededor (japonés: sin espacios). */
    private fun emit(h: Hit) {
        val r = h.text
        if (r.isEmpty()) return
        if (sp.gap.isEmpty()) { out.append(r); return }
        if (out.isNotEmpty() && out[out.length - 1].isLetterOrDigit() && r[0].isLetterOrDigit()) out.append(' ')
        out.append(r)
        if (h.end < s.length && s[h.end].isLetterOrDigit() && r[r.length - 1].isLetterOrDigit()) out.append(' ')
    }

    private fun step(i: Int): Hit? {
        val c = s[i]
        return when {
            c.isDigit() -> if (i > 0 && s[i - 1].isDigit()) null else number(i)
            // A la izquierda se mira lo ya escrito (no el original): así la segunda pasada
            // ve lo mismo que la primera y el resultado es idempotente.
            c.isLetter() -> out.lastOrNull().let { p -> if (p != null && (p.isLetter() || p == '\'' || p == '’')) null else word(i) }
            else -> symbol(i)
        }
    }

    private fun at(p: Pattern, i: Int): Matcher? {
        if (i > s.length) return null
        val m = p.matcher(s)
        m.region(i, s.length)
        m.useTransparentBounds(true)
        m.useAnchoringBounds(false)
        return if (m.lookingAt()) m else null
    }

    /* ── contexto ── */

    /** Palabra anterior ya escrita en la salida (salta espacios y comas). */
    private fun prevWord(): String = lastWord(skipCommas = true)

    /** Palabra anterior ya escrita, solo si la separan espacios. */
    private fun prevWordStrict(): String = lastWord(skipCommas = false)

    private fun lastWord(skipCommas: Boolean): String {
        var j = out.length - 1
        while (j >= 0 && (out[j] == ' ' || out[j] == '\u00A0' || (skipCommas && out[j] == ','))) j--
        val end = j + 1
        while (j >= 0 && out[j].isLetterOrDigit()) j--
        return out.substring(j + 1, end)
    }

    private fun wordEndFrom(e: Int): Pair<Int, Int> {
        var j = e
        while (j < s.length && (s[j] == ' ' || s[j] == '\u00A0')) j++
        val start = j
        while (j < s.length && (s[j].isLetter() || s[j] == '\'')) j++
        return start to j
    }

    private fun nextWord(e: Int): String = wordEndFrom(e).let { (a, b) -> s.substring(a, b) }

    private fun secondWord(e: Int): String = wordEndFrom(wordEndFrom(e).second).let { (a, b) -> s.substring(a, b) }

    /** ¿Acaba la frase en [e]? (fin del texto, o espacio + mayúscula / salto de línea). */
    private fun endsSentence(e: Int): Boolean {
        if (e >= s.length) return true
        if (!s[e].isWhitespace()) return false
        var j = e
        while (j < s.length && s[j].isWhitespace()) {
            if (s[j] == '\n') return true
            j++
        }
        return j >= s.length || s[j].isUpperCase() || s[j] == '¿' || s[j] == '¡'
    }

    private fun stop(txt: String, e: Int): String = if (e > 0 && s[e - 1] == '.' && endsSentence(e)) "$txt." else txt

    /* ── cifras ── */

    private fun number(i: Int): Hit? = date(i) ?: time(i) ?: pair(i) ?: plain(i)

    private fun dayMonth(a: Int, b: Int): Pair<Int, Int>? = when {
        a in 13..31 && b in 1..12 -> a to b
        b in 13..31 && a in 1..12 -> b to a
        a in 1..12 && b in 1..12 -> if (sp.monthFirst) b to a else a to b
        else -> null
    }

    private fun date(i: Int): Hit? {
        at(DMY, i)?.let { m ->
            val sep = m.group(2)
            val yr = m.group(4)
            if (sep == "/" || yr.length == 4) {
                val dm = dayMonth(m.group(1).toInt(), m.group(3).toInt())
                if (dm != null) {
                    val y = yr.toInt().let { if (yr.length == 2) (if (it < 50) 2000 + it else 1900 + it) else it }
                    return Hit(m.end(), sp.date(dm.first, dm.second, y, prevWord().lowercase()))
                }
            }
        }
        at(YMD, i)?.let { m ->
            val mo = m.group(3).toInt()
            val d = m.group(4).toInt()
            if (mo in 1..12 && d in 1..31) return Hit(m.end(), sp.date(d, mo, m.group(1).toInt(), prevWord().lowercase()))
        }
        if (sp.lang == "de") at(DM_DOT, i)?.let { m ->
            val prev = prevWord().lowercase()
            val d = m.group(1).toInt()
            val mo = m.group(2).toInt()
            if (prev in sp.dateCues && d in 1..31 && mo in 1..12) return Hit(m.end(), sp.date(d, mo, null, prev))
        }
        return null
    }

    private fun time(i: Int): Hit? {
        if (sp.lang == "fr") at(TIME_FR, i)?.let { m ->
            val h = m.group(1).toInt()
            val mi = m.group(2)?.toInt() ?: 0
            if (h <= 24 && mi < 60) return Hit(m.end(), sp.time(h, mi, null))
        }
        val m = at(TIME, i) ?: return null
        val h = m.group(1).toInt()
        val mi = m.group(2).toInt()
        val sec = m.group(3)?.toInt()
        if (mi > 59 || (sec != null && sec > 59)) return null
        if (sec != null) return Hit(m.end(), sp.duration(h, mi, sec))
        if (h > 24) return null
        var e = m.end()
        var ampm: Char? = null
        if (sp.ampm && h in 1..12) at(AMPM, e)?.let { a ->
            ampm = a.group(1)[0].lowercaseChar()
            e = a.end()
        }
        if (ampm == null) at(H_SUFFIX, e)?.let { e = it.end() }
        return Hit(e, stop(sp.time(h, mi, ampm), e))
    }

    /** "1920x1080", "27/09" con contexto, "1/2", "3:1". */
    private fun pair(i: Int): Hit? {
        at(RES, i)?.let { m ->
            val a = m.group(1).toLong()
            val b = m.group(2).toLong()
            return Hit(m.end(), sp.resNumber(a) + sp.gap + sp.by + sp.gap + sp.resNumber(b))
        }
        at(FRACTION, i)?.let { m ->
            val a = m.group(1).toInt()
            val b = m.group(2).toInt()
            val prev = prevWord().lowercase()
            if (prev in sp.dateCues) dayMonth(a, b)?.let { return Hit(m.end(), sp.date(it.first, it.second, null, prev)) }
            return Hit(m.end(), sp.fraction(a.toLong(), b.toLong()))
        }
        at(RATIO, i)?.let { m -> return Hit(m.end(), sp.ratio(m.group(1).toLong(), m.group(2).toLong())) }
        return null
    }

    private fun parse(raw0: String, versionCtx: Boolean): Num? {
        val raw = raw0.replace('\u00A0', ' ').replace('\u202F', ' ')
        if (' ' in raw) {
            val main = raw.replace(" ", "")
            val k = main.indexOfAny(charArrayOf(',', '.'))
            return if (k < 0) Num.Whole(main) else Num.Dec(main.substring(0, k), main.substring(k + 1))
        }
        val seps = raw.filter { it == '.' || it == ',' }
        if (seps.isEmpty()) return Num.Whole(raw)
        val groups = raw.split('.', ',')
        if (groups.any { it.isEmpty() }) return null
        if (versionCtx && seps.all { it == '.' }) return Num.Seq(groups, '.')
        if (seps.toSet().size == 2) {
            val last = seps.last()
            if (seps.dropLast(1).contains(last)) return Num.Seq(groups, '.')
            val intGroups = groups.dropLast(1)
            return if (thousands(intGroups)) Num.Dec(intGroups.joinToString(""), groups.last()) else Num.Seq(groups, '.')
        }
        val c = seps[0]
        if (seps.length >= 2) return if (thousands(groups)) Num.Whole(groups.joinToString("")) else Num.Seq(groups, c)
        val a = groups[0]
        val b = groups[1]
        if (b.length == 3 && thousands(groups) && (b == "000" || c != sp.decimalSep)) return Num.Whole(a + b)
        return Num.Dec(a, b)
    }

    private fun thousands(g: List<String>) = g[0].length in 1..3 && g[0][0] != '0' && g.drop(1).all { it.length == 3 }

    private fun numWords(v: Num, g: Gen): String = when (v) {
        is Num.Whole -> sp.intWords(v.digits, g)
        is Num.Dec -> sp.decimal(v.whole, v.frac, g)
        is Num.Seq -> if (v.sep == '.') {
            v.parts.joinToString(sp.gap + sp.dot + sp.gap) { sp.intWords(it, Gen.N) }
        } else {
            v.parts.joinToString(sp.listSep) { sp.intWords(it, Gen.N) }
        }
    }

    /** Palabra del rango: "de 10 a 20", pero "entre 10 y 20". */
    private var rangeWord = ""

    private fun words(a: Num, b: Num?, g: Gen) =
        if (b == null) numWords(a, g) else numWords(a, g) + sp.gap + rangeWord.ifEmpty { sp.rangeWord } + sp.gap + numWords(b, g)

    private fun plain(i: Int): Hit? {
        val m1 = at(sp.num, i) ?: return null
        val prev = prevWord()
        val prevLow = prev.lowercase()
        val v1 = parse(m1.group(), prevLow in VERSION_CUES) ?: return null
        rangeWord = if (prevLow == sp.between.first) sp.between.second else sp.rangeWord
        var e = m1.end()
        var v2: Num? = null
        if (v1 !is Num.Seq && (i == 0 || s[i - 1] != '-')) {
            val r = at(sp.range, e)
            if (r != null) {
                val m2 = at(sp.num, r.end())
                val p2 = m2?.let { parse(it.group(), false) }
                if (m2 != null && p2 != null && p2 !is Num.Seq) {
                    v2 = p2
                    e = m2.end()
                }
            }
        }
        val single = v2 == null
        val n1 = (v1 as? Num.Whole)?.value

        // Ordinal (1.º, 2ª, 3rd, 1er, 3. + sustantivo).
        if (single && n1 != null) sp.ordinalPattern?.let { op ->
            at(op, e)?.let { om ->
                sp.ordinalWords(n1, om.group(), prevLow, nextWord(om.end()))?.let { return Hit(om.end(), it) }
            }
        }
        at(PCT, e)?.let { return Hit(it.end(), words(v1, v2, Gen.N) + sp.gap + sp.percent) }
        at(CUR_SUFFIX, e)?.let { cm ->
            val cur = sp.currency(cm.group(1))
            if (cur != null) {
                val t = if (single) sp.money(v1, cur, 1) else words(v1, v2, cur.g) + sp.gap + cur.many
                if (t != null) return Hit(cm.end(), t)
            }
        }
        at(ATTACHED, e)?.let { am ->
            attached(v1, v2, am.group(1)[0], am.end())?.let { return Hit(am.end(), it) }
        }
        at(sp.unitPattern, e)?.let { um ->
            val u = sp.units[um.group(1)]
            if (u != null) {
                var t = words(v1, v2, u.g)
                if (single && n1 != null && sp.millionDe(n1)) t += sp.gap + sp.de
                return Hit(um.end(), t + sp.gap + (if (single && n1 == 1L) u.one else u.many))
            }
        }
        if (sp.ampm && single && n1 != null && n1 in 1..12) at(AMPM, e)?.let { a ->
            return Hit(a.end(), stop(sp.time(n1.toInt(), 0, a.group(1)[0].lowercaseChar()), a.end()))
        }
        at(PLUS_SUFFIX, e)?.let {
            val nx = nextWord(it.end()).lowercase()
            return Hit(it.end(), sp.moreThan(words(v1, v2, sp.gender(prevLow, nx, nextWord(it.end())))))
        }
        val nextRaw = nextWord(e)
        val next = nextRaw.lowercase()
        if (single && n1 != null) {
            sp.contextual(n1, m1.group().length, prevLow, next, secondWord(e).lowercase())?.let { return Hit(e, it) }
        }
        val g = sp.gender(prevLow, next, nextRaw)
        var t = words(v1, v2, g)
        if (single && n1 != null && g != Gen.N && sp.millionDe(n1)) t += sp.gap + sp.de
        return Hit(e, t)
    }

    /** Letra pegada a la cifra: 4x, 1080p, 4K, 10k, 5M, 5G, 90s. */
    private fun attached(a: Num, b: Num?, c: Char, end: Int): String? {
        val n = (a as? Num.Whole)?.value
        return when (c) {
            'x', 'X', '×' -> sp.times(words(a, b, sp.timesGender), b == null && n == 1L)
            'p' -> if (b == null && n != null && n in RES_P) sp.resP(n) else null
            'K' -> if (b == null && n != null && n in K_RES) sp.kRes(n) else scale(a, b, 1000, end)
            'k' -> scale(a, b, 1000, end)
            'M' -> scale(a, b, 1_000_000, end)
            'G' -> if (b == null && n != null && n in 2..6) sp.network(n) else null
            's' -> if (sp.lang == "en" && b == null && n != null && n >= 10 && n % 10 == 0L) sp.decade(n) else null
            else -> null
        }
    }

    private fun scale(a: Num, b: Num?, k: Long, end: Int): String? {
        if (b != null) return null
        val nx = nextWord(end)
        val g = sp.gender("", nx.lowercase(), nx)
        return when (a) {
            is Num.Whole -> a.value?.takeIf { it < 1_000_000_000L }?.let { v ->
                val n = v * k
                sp.cardinal(n, g) + if (g != Gen.N && sp.millionDe(n)) sp.gap + sp.de else ""
            }
            is Num.Dec -> sp.decimal(a.whole, a.frac, Gen.N) + sp.gap + sp.scaleWord(k) +
                if (k >= 1_000_000 && g != Gen.N && sp.de.isNotEmpty()) sp.gap + sp.de else ""
            is Num.Seq -> null
        }
    }

    /* ── palabras ── */

    private fun word(i: Int): Hit? =
        url(i) ?: curPrefix(i) ?: abbreviation(i) ?: roman(i) ?: prefixTimes(i) ?: version(i) ?: acronym(i)

    private fun url(i: Int): Hit? {
        val m = at(EMAIL, i) ?: at(URL, i) ?: return null
        var e = m.end()
        while (e > i && s[e - 1] in ".,;:!?)") e--
        val raw = s.substring(i, e).removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val parts = ArrayList<String>()
        var k = 0
        while (k < raw.length) {
            val c = raw[k]
            if (c.isLetterOrDigit()) {
                // Cifras y letras por separado ("b=11hora" → once, hora).
                var j = k
                val digit = c.isDigit()
                while (j < raw.length && raw[j].isLetterOrDigit() && raw[j].isDigit() == digit) j++
                val tok = raw.substring(k, j)
                parts += if (digit) sp.intWords(tok, Gen.N) else known(tok) ?: generic(tok) ?: tok
                k = j
            } else {
                when (c) {
                    '.' -> parts += sp.dot
                    '/' -> parts += sp.slash
                    '@' -> parts += sp.at
                    '-' -> parts += sp.dash
                    '_' -> parts += sp.underscore
                }
                k++
            }
        }
        if (parts.isEmpty()) return null
        return Hit(e, parts.joinToString(sp.gap))
    }

    private fun curPrefix(i: Int): Hit? {
        val m = at(CUR_PREFIX, i) ?: return null
        val cur = sp.currency(m.group(1)) ?: return null
        val nm = at(sp.num, m.end()) ?: return null
        val v = parse(nm.group(), false) ?: return null
        var e = nm.end()
        var k = 1L
        at(SCALE, e)?.let { sc ->
            k = if (sc.group(1) == "M") 1_000_000L else 1000L
            e = sc.end()
        }
        return Hit(e, sp.money(v, cur, k) ?: return null)
    }

    private fun abbreviation(i: Int): Hit? {
        for (a in sp.abbreviations) {
            val m = at(a.pattern, i) ?: continue
            return Hit(m.end(), if (a.canEnd) stop(a.text, m.end()) else a.text)
        }
        return null
    }

    private fun roman(i: Int): Hit? {
        if (sp.lang == "ja") return null
        if (sp.lang == "fr") at(ROMAN_FR_CENTURY, i)?.let { m ->
            romanValue(m.group(1))?.let { return Hit(m.end(), sp.ordinal(it.toLong(), Gen.M)) }
        }
        val m = at(ROMAN, i) ?: return null
        val r = m.group()
        val v = romanValue(r) ?: return null
        // "Play V R", "Double X P": deletreos, no romanos.
        val (a, b) = wordEndFrom(m.end())
        val spelled = b - a == 1 && s[a].isUpperCase()
        // Vale la palabra anterior original ("GTA") o la ya escrita ("ge te a" no, "Fantasy" sí).
        val ok = listOf(prevWordStrict(), sourcePrevWord(i)).any { romanAfter(it, r, v, spelled) }
        return if (ok) Hit(m.end(), sp.roman(v)) else null
    }

    private fun romanAfter(prev: String, r: String, v: Int, spelled: Boolean): Boolean {
        val low = prev.lowercase()
        if (low in ROMAN_CUES) return v <= 39
        if (v > 20 || r == "I" || spelled) return false
        if (prev.length < 2 || !prev[0].isUpperCase() || !prev.all { it.isLetterOrDigit() }) return false
        if (prev.all { it in "IVX" } || low in ROMAN_NOT_AFTER) return false
        return !(r.length == 1 && low in ROMAN_LETTER_AFTER)
    }

    /** Palabra anterior en el texto original, separada solo por espacios. */
    private fun sourcePrevWord(i: Int): String {
        var j = i - 1
        while (j >= 0 && (s[j] == ' ' || s[j] == ' ')) j--
        val end = j + 1
        while (j >= 0 && s[j].isLetterOrDigit()) j--
        return s.substring(j + 1, end)
    }

    private fun prefixTimes(i: Int): Hit? {
        if (s[i] != 'x' && s[i] != '×') return null
        val m = at(PREFIX_TIMES, i) ?: return null
        val v = parse(m.group(1), false) ?: return null
        return Hit(m.end(), sp.timesPrefix(numWords(v, Gen.N)))
    }

    private fun version(i: Int): Hit? {
        val m = at(VERSION, i) ?: return null
        val parts = m.group(1).split('.')
        val nums = numWords(Num.Seq(parts, '.'), Gen.N)
        // "versión v1.2": la palabra ya está.
        return Hit(m.end(), if (prevWord().lowercase() in VERSION_CUES) nums else sp.version + sp.gap + nums)
    }

    private fun acronym(i: Int): Hit? {
        val m = at(WORD, i) ?: return null
        val w = m.group()
        known(w)?.let { return Hit(i + w.length, it) }
        val k = w.indexOfAny(charArrayOf('-', '/'))
        val head = if (k > 0) w.substring(0, k) else w
        if (k > 0) known(head)?.let { return Hit(i + head.length, it) }
        generic(head)?.let { return Hit(i + head.length, it) }
        // Sigla pegada a otra cosa ("PS5a", "GB27", "Xbox10"): solo las letras; el resto, después.
        val letters = head.takeWhile { it.isLetter() }
        if (letters.isEmpty() || letters.length == head.length) return null
        return (known(letters) ?: generic(letters))?.let { Hit(i + letters.length, it) }
    }

    private fun known(w: String): String? {
        val v = sp.acronyms[w] ?: return null
        return if (v.startsWith("=")) sp.spell(v.substring(1)) else v
    }

    /** Siglas sin vocales (DLC, NPC) y sigla + número (PS5, N64, GTA5). */
    private fun generic(w: String): String? {
        val letters = w.takeWhile { it.isLetter() }
        val digits = w.substring(letters.length)
        if (letters.isEmpty() || !digits.all { it.isDigit() } || !letters.all { it in 'A'..'Z' }) return null
        val lettersText = known(letters) ?: if (
            letters.none { it in "AEIOU" } && !letters.all { it in "IVX" } && letters.length <= 6 &&
            (letters.length >= 2 || digits.isNotEmpty())
        ) sp.spell(letters) else null
        if (lettersText == null) return null
        if (digits.isEmpty()) return if (letters.length >= 2) lettersText else null
        if (digits.length > 4) return null
        return lettersText + sp.gap + sp.intWords(digits, Gen.N)
    }

    /* ── símbolos ── */

    private fun symbol(i: Int): Hit? {
        curPrefix(i)?.let { return it }
        val c = s[i]
        val next = s.getOrNull(i + 1)
        val prevC = out.lastOrNull()
        val prevAlnum = prevC != null && prevC.isLetterOrDigit()
        return when (c) {
            '-', '−', '+' -> when {
                next != null && next.isDigit() && !prevAlnum -> Hit(i + 1, if (c == '+') sp.plus else sp.minus)
                c == '+' -> Hit(i + 1, sp.plus)
                else -> null
            }
            '&' -> Hit(i + 1, sp.and)
            '=' -> Hit(i + 1, sp.equals)
            '@' -> Hit(i + 1, sp.at)
            '%', '％' -> Hit(i + 1, sp.percent)
            '#', '＃' -> Hit(i + 1, if (next != null && next.isDigit()) sp.number else "")
            '~', '≈', '～', '〜' -> {
                var j = i + 1
                while (j < s.length && s[j] == ' ') j++
                if (j < s.length && s[j].isDigit()) Hit(i + 1, sp.about) else Hit(i + 1, "")
            }
            '*' -> Hit(i + 1, "")
            '°' -> Hit(i + 1, sp.degrees)
            '/' -> {
                val before = out.lastOrNull()
                when {
                    before == null || next == null || !before.isLetterOrDigit() || sp.gap.isEmpty() -> null
                    next.isWhitespace() || next in ".,;:!?…)]}\"'»”/" -> null
                    before.isDigit() && next.isDigit() -> Hit(i + 1, sp.slash)
                    else -> Hit(i + 1, sp.or)
                }
            }
            else -> null
        }
    }

    companion object {
        val DMY: Pattern = Pattern.compile("""(\d{1,2})([/.\-])(\d{1,2})\2(\d{4}|\d{2})(?!\d)(?![.,]\d)""")
        val YMD: Pattern = Pattern.compile("""(\d{4})([/.\-])(\d{1,2})\2(\d{1,2})(?!\d)""")
        val DM_DOT: Pattern = Pattern.compile("""(\d{1,2})\.(\d{1,2})\.(?!\d)""")
        val TIME: Pattern = Pattern.compile("""(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)""")
        val TIME_FR: Pattern = Pattern.compile("""(\d{1,2}) ?h ?(\d{2})?(?![\p{L}\d])""")
        val H_SUFFIX: Pattern = Pattern.compile("""[ \u00A0]?(?:hs|hrs|h|Uhr)(?![\p{L}])""")
        val AMPM: Pattern = Pattern.compile("""[ \u00A0]?([aApP])\.?[ \u00A0]?[mM]\.?(?![\p{L}])""")
        val RES: Pattern = Pattern.compile("""(\d{1,5})(?:[x×]|[ \u00A0]?[×*][ \u00A0]?)(\d{1,5})(?!\d)""")
        val FRACTION: Pattern = Pattern.compile("""(\d{1,3})/(\d{1,3})(?![\d/])""")
        val RATIO: Pattern = Pattern.compile("""(\d{1,3}):(\d{1,3})(?![\d:])""")
        val PCT: Pattern = Pattern.compile("""[ \u00A0\u202F]?[%％]""")
        val CUR_SUFFIX: Pattern = Pattern.compile("""[ \u00A0\u202F]?(US\$|R\$|€|\$|£|¥|￥|円|EUR|USD|MXN|BRL|JPY|GBP)(?![\p{L}])""")
        val CUR_PREFIX: Pattern = Pattern.compile("""(US\$|R\$|MX\$|€|\$|£|¥|￥)[ \u00A0\u202F]?(?=\d)""")
        val SCALE: Pattern = Pattern.compile("""[ \u00A0]?([kKM])(?![\p{L}\p{N}])""")
        val ATTACHED: Pattern = Pattern.compile("""([xX×pkKMGs])(?![\p{L}\p{N}])""")
        val PLUS_SUFFIX: Pattern = Pattern.compile("""\+(?![\p{N}])""")
        val ROMAN: Pattern = Pattern.compile("""[IVX]{1,6}(?![\p{L}])""")
        /** "XXIe siècle" → vingt et unième. */
        val ROMAN_FR_CENTURY: Pattern = Pattern.compile("""([IVX]{1,6})(?:e|ème|er)(?=[ \u00A0]+siècle)""")
        val WORD: Pattern = Pattern.compile("""[A-Za-z0-9]+(?:[-/][A-Za-z0-9]+)*""")
        val PREFIX_TIMES: Pattern = Pattern.compile("""[x×](\d+(?:[.,]\d+)?)(?![\p{L}\p{N}])""")
        val VERSION: Pattern = Pattern.compile("""[vV](\d+(?:\.\d+)*)(?![\p{L}\p{N}])""")
        val EMAIL: Pattern = Pattern.compile("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+""")
        val URL: Pattern = Pattern.compile(
            """(?:(?:https?://|www\.)[^\s]+|[A-Za-z0-9][A-Za-z0-9\-]*(?:\.[A-Za-z0-9\-]+)*\.(?:com|net|org|io|gg|app|dev|tv)(?:/[^\s]*)?)(?![\p{L}\p{N}])""",
        )

        val RES_P = setOf(144L, 240L, 360L, 480L, 540L, 576L, 720L, 900L, 1080L, 1440L, 2160L, 4320L)
        val K_RES = setOf(1L, 2L, 4L, 5L, 6L, 8L, 10L, 12L, 16L)
        val VERSION_CUES = setOf(
            "versión", "version", "versão", "v", "update", "actualización", "atualização", "parche", "patch", "build",
            "firmware", "android", "ios", "mise", "aktualisierung",
        )
        val ROMAN_CUES = setOf(
            "siglo", "century", "parte", "part", "capítulo", "chapter", "episodio", "episode", "acto", "act", "tomo",
            "volumen", "volume", "vol", "libro", "book", "temporada", "season", "fase", "phase", "século", "livro",
            "episódio", "partie", "chapitre", "siècle", "teil", "kapitel", "band", "jahrhundert", "akt",
        )
        /** Tras estas no hay romano (artículos: "La X", "El V"…). */
        val ROMAN_NOT_AFTER = setOf("el", "la", "los", "las", "un", "una", "the", "a", "an", "le", "les", "der", "die", "das", "o", "os", "as", "yo", "tu", "mi")
        /** Tras estas, "X"/"V" son letra: Xbox Series X, Mega Man X, Malcolm X. */
        val ROMAN_LETTER_AFTER = setOf(
            "series", "man", "mega", "pro", "mark", "model", "modelo", "type", "tipo", "vitamin", "vitamina", "malcolm",
            "generation", "gen", "planet", "planeta", "project", "proyecto", "plan", "factor", "twitter", "space",
        )

        fun romanValue(r: String): Int? {
            var total = 0
            var prev = 0
            for (k in r.indices.reversed()) {
                val v = when (r[k]) { 'I' -> 1; 'V' -> 5; 'X' -> 10; else -> return null }
                if (v < prev) total -= v else { total += v; prev = v }
            }
            return if (total in 1..39 && toRoman(total) == r) total else null
        }

        private fun toRoman(n: Int): String {
            val sb = StringBuilder()
            var x = n
            for ((v, t) in listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
                while (x >= v) { sb.append(t); x -= v }
            }
            return sb.toString()
        }

        /** Cifras de ancho completo (japonés) → ASCII. */
        fun prepare(t: String): String {
            if (t.none { it in '０'..'９' }) return t
            val sb = StringBuilder(t.length)
            for (k in t.indices) {
                val c = t[k]
                val nearDigit = (k > 0 && (t[k - 1] in '０'..'９')) && (k + 1 < t.length && t[k + 1] in '０'..'９')
                sb.append(
                    when {
                        c in '０'..'９' -> '0' + (c - '０')
                        nearDigit && c == '：' -> ':'
                        nearDigit && c == '．' -> '.'
                        nearDigit && c == '，' -> ','
                        nearDigit && c == '／' -> '/'
                        else -> c
                    },
                )
            }
            return sb.toString()
        }

        /** Espacios: los no separables pasan a normales y no quedan dobles. */
        fun tidy(sb: CharSequence): String {
            val t = StringBuilder(sb.length)
            var space = false
            for (c in sb) {
                val ch = if (c == '\u00A0' || c == '\u202F') ' ' else c
                if (ch == ' ') {
                    if (!space) t.append(' ')
                    space = true
                } else {
                    t.append(ch)
                    space = false
                }
            }
            return t.toString()
        }
    }
}

/* ───────────────────────── Idiomas ───────────────────────── */

internal abstract class Speller(val region: String?) {
    abstract val lang: String
    open val gap = " "
    open val listSep = ", "
    open val decimalSep = ','
    open val ampm = false
    open val monthFirst = false
    abstract val comma: String
    abstract val dot: String
    abstract val percent: String
    abstract val rangeWord: String
    /** "entre 10-20" → "entre diez y veinte". */
    abstract val between: Pair<String, String>
    abstract val minus: String
    abstract val plus: String
    abstract val and: String
    abstract val or: String
    abstract val equals: String
    abstract val at: String
    abstract val number: String
    abstract val about: String
    abstract val degrees: String
    abstract val by: String
    abstract val version: String
    abstract val slash: String
    abstract val dash: String
    abstract val underscore: String
    /** "de" tras millones exactos ante sustantivo ("un millón de euros"); vacío si no se usa. */
    open val de = ""
    open val timesGender = Gen.N
    abstract val months: Array<String>
    val monthSet: Set<String> by lazy { months.map { it.lowercase() }.toSet() }
    open val dateCues: Set<String> = emptySet()
    abstract val letters: Array<String>
    abstract val units: Map<String, Measure>
    abstract val acronyms: Map<String, String>
    abstract val abbreviations: List<Abbr>
    open val ordinalPattern: Pattern? = null
    open val fractions: Map<Pair<Long, Long>, String> = emptyMap()

    abstract fun cardinal(n: Long, g: Gen = Gen.N): String
    abstract fun ordinal(n: Long, g: Gen = Gen.N): String
    abstract fun time(h: Int, m: Int, ampm: Char?): String
    abstract fun duration(h: Int, m: Int, s: Int): String
    abstract fun date(d: Int, m: Int, y: Int?, prev: String): String
    abstract fun times(x: String, one: Boolean): String
    abstract fun timesPrefix(x: String): String
    abstract fun moreThan(x: String): String
    abstract fun fractionOf(a: String, b: String): String
    abstract fun ratio(a: Long, b: Long): String
    abstract fun scaleWord(k: Long): String
    abstract fun currency(sym: String): Cur?
    abstract fun withCents(amount: String, c: Int, cur: Cur): String

    open fun year(n: Long) = cardinal(n)
    open fun digit(d: Int) = cardinal(d.toLong())
    fun digits(s: String) = s.map { digit(it - '0') }.joinToString(gap)
    fun intWords(s: String, g: Gen): String {
        val v = if (s.length > 15) null else s.toLongOrNull()
        return if (v == null || (s.length > 1 && s[0] == '0')) digits(s) else cardinal(v, g)
    }
    open fun frac(f: String) = if (f.length <= 2 && f[0] != '0') cardinal(f.toLong()) else digits(f)
    open fun decimal(w: String, f: String, g: Gen) = intWords(w, Gen.N) + gap + comma + gap + frac(f)
    open fun fraction(a: Long, b: Long) = fractions[a to b] ?: fractionOf(cardinal(a), cardinal(b))
    open fun resNumber(n: Long) = cardinal(n)
    open fun resP(n: Long) = resNumber(n) + gap + letter('P')
    open fun kRes(n: Long) = cardinal(n) + gap + letter('K')
    open fun network(n: Long) = cardinal(n) + gap + letter('G')
    open fun decade(n: Long) = cardinal(n)
    open fun roman(v: Int) = cardinal(v.toLong())
    fun letter(c: Char) = letters.getOrNull(c.uppercaseChar() - 'A') ?: c.toString()
    open fun spell(w: String) = w.map { letter(it) }.joinToString(gap)
    open fun gender(prev: String, next: String, nextRaw: String): Gen = Gen.N
    open fun contextual(n: Long, len: Int, prev: String, next: String, next2: String): String? = null
    open fun ordinalWords(n: Long, suffix: String, prev: String, next: String): String? = null
    open fun millionDe(n: Long) = de.isNotEmpty() && n >= 1_000_000 && n % 1_000_000 == 0L

    fun money(v: Num, cur: Cur, scale: Long): String? = when (v) {
        is Num.Whole -> v.value?.takeIf { it <= 999_999_999_999L / scale }?.let { amount(it * scale, cur) }
        is Num.Dec -> when {
            scale > 1 -> decimal(v.whole, v.frac, Gen.N) + gap + scaleWord(scale) +
                (if (scale >= 1_000_000 && de.isNotEmpty()) gap + de else "") + gap + cur.many
            v.frac.length > 2 -> decimal(v.whole, v.frac, Gen.N) + gap + cur.many
            else -> {
                val n = if (v.whole.length > 12) null else v.whole.toLongOrNull()
                val c = v.frac.padEnd(2, '0').toInt()
                when {
                    n == null -> null
                    c == 0 -> amount(n, cur)
                    n == 0L -> cardinal(c.toLong(), cur.centG) + gap + (if (c == 1) cur.centOne else cur.centMany)
                    else -> withCents(amount(n, cur), c, cur)
                }
            }
        }
        is Num.Seq -> null
    }

    open fun amount(n: Long, cur: Cur) =
        cardinal(n, cur.g) + (if (millionDe(n)) gap + de else "") + gap + (if (n == 1L) cur.one else cur.many)

    protected fun joinList(parts: List<String>, and: String): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " $and " + parts.last()
    }

    // Patrones que dependen del idioma.
    val num: Pattern by lazy {
        val sp = if (lang == "fr") " \\u00A0\\u202F" else "\\u00A0\\u202F"
        Pattern.compile("\\d{1,3}(?:[$sp]\\d{3})+(?:[.,]\\d+)?(?!\\d)|\\d+(?:[.,]\\d+)*")
    }
    val range: Pattern by lazy {
        val extra = if (lang == "ja") "|[~〜～]" else ""
        Pattern.compile("(?:-|–|—|[ \\u00A0][-–—][ \\u00A0]|[–—][ \\u00A0]|[ \\u00A0][–—]$extra)(?=\\d)")
    }
    val unitPattern: Pattern by lazy {
        val keys = units.keys.sortedByDescending { it.length }.joinToString("|") { Pattern.quote(it) }
        // En japonés la unidad va pegada a kana/kanji: solo corta otra letra latina.
        val tail = if (lang == "ja") "(?![A-Za-z0-9])" else "(?![\\p{L}\\p{N}])"
        Pattern.compile("[ \\u00A0\\u202F]?($keys)$tail")
    }

    companion object {
        private val cache = ConcurrentHashMap<String, Speller>()

        fun of(lang: String, region: String?): Speller? {
            val l = lang.trim().lowercase().substringBefore('-').substringBefore('_')
            val r = region?.trim()?.uppercase()?.substringAfterLast('-')?.substringAfterLast('_')?.takeIf { it.isNotEmpty() }
            val key = "$l-$r"
            cache[key]?.let { return it }
            val sp = when (l) {
                "es" -> EsSpeller(r)
                "en" -> EnSpeller(r)
                "pt" -> PtSpeller(r)
                "fr" -> FrSpeller(r)
                "de" -> DeSpeller(r)
                "ja" -> JaSpeller(r)
                else -> return null
            }
            cache[key] = sp
            return sp
        }
    }
}

/* ── Español ── */

internal class EsSpeller(region: String?) : Speller(region) {
    override val lang = "es"
    private val america = region != null && region != "ES"
    private val pointDecimal = region in POINT_REGIONS
    override val decimalSep = if (pointDecimal) '.' else ','
    override val ampm = true
    override val comma = if (pointDecimal) "punto" else "coma"
    override val dot = "punto"
    override val percent = "por ciento"
    override val rangeWord = "a"
    override val between = ("entre" to "y")
    override val minus = "menos"
    override val plus = "más"
    override val and = "y"
    override val or = "o"
    override val equals = "igual a"
    override val at = "arroba"
    override val number = "número"
    override val about = "unos"
    override val degrees = "grados"
    override val by = "por"
    override val version = "versión"
    override val slash = "barra"
    override val dash = "guion"
    override val underscore = "guion bajo"
    override val de = "de"
    override val timesGender = Gen.F
    override val months = arrayOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    override val dateCues = setOf("el", "del", "al", "día", "hasta", "desde", "hoy", "fecha", "para", "a")
    override val letters = arrayOf(
        "a", "be", "ce", "de", "e", "efe", "ge", "hache", "i", "jota", "ka", "ele", "eme", "ene", "o", "pe", "cu", "erre", "ese",
        "te", "u", if (america) "ve" else "uve", if (america) "doble ve" else "uve doble", "equis", "ye", "zeta",
    )
    override val fractions = mapOf(
        (1L to 2L) to "medio", (1L to 3L) to "un tercio", (2L to 3L) to "dos tercios", (1L to 4L) to "un cuarto",
        (3L to 4L) to "tres cuartos", (24L to 7L) to "veinticuatro siete",
    )

    override fun cardinal(n: Long, g: Gen): String {
        if (n < 0) return "menos " + cardinal(-n, g)
        if (n == 0L) return "cero"
        val parts = ArrayList<String>(4)
        val bill = n / 1_000_000_000_000L
        val mill = (n / 1_000_000L) % 1_000_000L
        val rest = n % 1_000_000L
        if (bill > 0) parts += if (bill == 1L) "un billón" else below1M(bill, Gen.M) + " billones"
        if (mill > 0) parts += if (mill == 1L) "un millón" else below1M(mill, Gen.M) + " millones"
        if (rest > 0) parts += below1M(rest, g)
        return parts.joinToString(" ")
    }

    private fun below1M(n: Long, g: Gen): String {
        val th = (n / 1000).toInt()
        val r = (n % 1000).toInt()
        val parts = ArrayList<String>(2)
        if (th > 0) parts += if (th == 1) "mil" else below1000(th, if (g == Gen.F) Gen.F else Gen.M) + " mil"
        if (r > 0) parts += below1000(r, g)
        return parts.joinToString(" ")
    }

    private fun below1000(n: Int, g: Gen): String {
        if (n < 100) return below100(n, g)
        if (n == 100) return "cien"
        val h = HUNDREDS[n / 100].let { if (g == Gen.F && n >= 200) it.dropLast(2) + "as" else it }
        return if (n % 100 == 0) h else h + " " + below100(n % 100, g)
    }

    private fun below100(n: Int, g: Gen): String = when {
        n == 1 -> when (g) { Gen.N -> "uno"; Gen.M -> "un"; Gen.F -> "una" }
        n == 21 -> when (g) { Gen.N -> "veintiuno"; Gen.M -> "veintiún"; Gen.F -> "veintiuna" }
        n < 30 -> UNITS[n]
        n % 10 == 0 -> TENS[n / 10]
        else -> TENS[n / 10] + " y " + below100(n % 10, g)
    }

    override fun ordinal(n: Long, g: Gen): String {
        if (n <= 0 || n > 100) return cardinal(n, g)
        val k = n.toInt()
        val w = when {
            k == 100 -> "centésimo"
            k < 10 -> ORD1[k]
            k == 10 -> "décimo"
            k == 11 -> "undécimo"
            k == 12 -> "duodécimo"
            k == 18 -> "decimoctavo"
            k < 20 -> "decimo" + ORD1[k - 10]
            else -> ORD10[k / 10] + if (k % 10 != 0) " " + ORD1[k % 10] else ""
        }
        return when (g) {
            Gen.F -> w.split(' ').joinToString(" ") { if (it.endsWith("o")) it.dropLast(1) + "a" else it }
            Gen.M -> w.removeSuffix("primero").let { if (it != w) it + "primer" else w }.let { x ->
                x.removeSuffix("tercero").let { if (it != x) it + "tercer" else x }
            }
            Gen.N -> w
        }
    }

    override val ordinalPattern: Pattern = Pattern.compile(
        """\.?[ºª]|\.?(?:er|ra|ro|do|da|to|ta|vo|va|no|na|mo|ma)(?![\p{L}\p{N}])|°(?=[ \u00A0]?\p{L})""",
    )

    override fun ordinalWords(n: Long, suffix: String, prev: String, next: String): String? {
        val sfx = suffix.trimStart('.')
        val nx = next.lowercase()
        if (sfx == "°" && (n > 10 || nx in DEGREE_NEXT)) return null
        val g = when {
            sfx == "ª" || (sfx.length == 2 && sfx.endsWith("a")) -> Gen.F
            sfx == "er" -> Gen.M
            nx.isNotEmpty() && nx !in NOT_NOUN -> Gen.M
            else -> Gen.N
        }
        return ordinal(n, g)
    }

    override fun gender(prev: String, next: String, nextRaw: String): Gen = when {
        next in FEM -> Gen.F
        prev in FEM_DET -> Gen.F
        next.isNotEmpty() && next !in NOT_NOUN && next[0].isLetter() -> Gen.M
        else -> Gen.N
    }

    override fun contextual(n: Long, len: Int, prev: String, next: String, next2: String): String? {
        if (n == 1L && america && next == "de" && next2 in monthSet) return "primero"
        return null
    }

    override fun time(h: Int, m: Int, ampm: Char?): String {
        val hw = if (h == 0) "cero" else cardinal(h.toLong(), Gen.F)
        if (ampm != null) {
            val part = when {
                ampm == 'a' && h == 12 -> "de la noche"
                ampm == 'a' && h < 6 -> "de la madrugada"
                ampm == 'a' -> "de la mañana"
                h == 12 -> "del mediodía"
                h < 8 -> "de la tarde"
                else -> "de la noche"
            }
            return "$hw${minutes12(m)} $part"
        }
        if (h in 1..12) return if (m == 0) "$hw en punto" else "$hw${minutes12(m)}"
        if (m == 0) return "$hw horas"
        return hw + (if (m < 10) " cero " else " ") + cardinal(m.toLong())
    }

    private fun minutes12(m: Int) = when (m) {
        0 -> ""
        15 -> " y cuarto"
        30 -> " y media"
        else -> " y " + cardinal(m.toLong())
    }

    override fun duration(h: Int, m: Int, s: Int): String = joinList(
        listOfNotNull(
            h.takeIf { it > 0 }?.let { cardinal(it.toLong(), Gen.F) + if (it == 1) " hora" else " horas" },
            m.takeIf { it > 0 }?.let { cardinal(it.toLong(), Gen.M) + if (it == 1) " minuto" else " minutos" },
            s.takeIf { it > 0 }?.let { cardinal(it.toLong(), Gen.M) + if (it == 1) " segundo" else " segundos" },
        ).ifEmpty { listOf("cero segundos") },
        "y",
    )

    override fun date(d: Int, m: Int, y: Int?, prev: String): String {
        val dw = if (d == 1 && america) "primero" else cardinal(d.toLong())
        return "$dw de ${months[m - 1]}" + (y?.let { " de " + cardinal(it.toLong()) } ?: "")
    }

    override fun times(x: String, one: Boolean) = if (one) "una vez" else "$x veces"
    override fun timesPrefix(x: String) = "por $x"
    override fun moreThan(x: String) = "más de $x"
    override fun fractionOf(a: String, b: String) = "$a de $b"
    override fun ratio(a: Long, b: Long) = cardinal(a) + " a " + cardinal(b)
    override fun scaleWord(k: Long) = if (k >= 1_000_000) "millones" else "mil"

    override fun currency(sym: String): Cur? = when (sym) {
        "€", "EUR" -> Cur("euro", "euros", "céntimo", "céntimos")
        "US$", "USD" -> DOLLAR
        "$" -> if (region in PESO_REGIONS) PESO else DOLLAR
        "MX$", "MXN" -> PESO
        "R$", "BRL" -> Cur("real", "reales", "centavo", "centavos")
        "¥", "￥", "円", "JPY" -> Cur("yen", "yenes", "sen", "sen")
        "£", "GBP" -> Cur("libra", "libras", "penique", "peniques", Gen.F)
        else -> null
    }

    override fun withCents(amount: String, c: Int, cur: Cur) = "$amount con ${cardinal(c.toLong())}"

    override val units: Map<String, Measure> = buildMap {
        for (k in listOf("GB", "gb", "Gb")) put(k, Measure("giga", "gigas"))
        for (k in listOf("MB", "mb", "Mb")) put(k, Measure("mega", "megas"))
        for (k in listOf("KB", "kB", "kb")) put(k, Measure("kilobyte", "kilobytes"))
        for (k in listOf("TB", "tb")) put(k, Measure("tera", "teras"))
        for (k in listOf("GHz", "ghz", "Ghz")) put(k, Measure("gigahercio", "gigahercios"))
        for (k in listOf("MHz", "mhz", "Mhz")) put(k, Measure("megahercio", "megahercios"))
        put("kHz", Measure("kilohercio", "kilohercios"))
        for (k in listOf("Hz", "hz")) put(k, Measure("hercio", "hercios"))
        for (k in listOf("FPS", "fps")) put(k, Measure("efe pe ese", "efe pe ese"))
        put("ms", Measure("milisegundo", "milisegundos"))
        for (k in listOf("h", "hs", "hrs")) put(k, Measure("hora", "horas", Gen.F))
        for (k in listOf("min", "mins")) put(k, Measure("minuto", "minutos"))
        for (k in listOf("s", "seg")) put(k, Measure("segundo", "segundos"))
        for (k in listOf("°C", "ºC", "°")) put(k, Measure("grado", "grados"))
        for (k in listOf("°F", "ºF")) put(k, Measure("grado Fahrenheit", "grados Fahrenheit"))
        put("mAh", Measure("miliamperio hora", "miliamperios hora"))
        put("W", Measure("vatio", "vatios"))
        put("kW", Measure("kilovatio", "kilovatios"))
        put("mm", Measure("milímetro", "milímetros"))
        put("cm", Measure("centímetro", "centímetros"))
        put("km", Measure("kilómetro", "kilómetros"))
        put("m", Measure("metro", "metros"))
        for (k in listOf("km/h", "kmh")) put(k, Measure("kilómetro por hora", "kilómetros por hora"))
        put("mph", Measure("milla por hora", "millas por hora", Gen.F))
        put("kg", Measure("kilo", "kilos"))
        put("g", Measure("gramo", "gramos"))
        put("px", Measure("píxel", "píxeles"))
        put("MP", Measure("megapíxel", "megapíxeles"))
        put("Mbps", Measure("megabit por segundo", "megabits por segundo"))
        put("Gbps", Measure("gigabit por segundo", "gigabits por segundo"))
        put("MB/s", Measure("mega por segundo", "megas por segundo"))
        put("GB/s", Measure("giga por segundo", "gigas por segundo"))
    }

    override val acronyms = mapOf(
        "AAA" to "triple a", "GOTY" to "goti", "MMO" to "=MMO", "MMORPG" to "=MMORPG", "PvP" to "=PVP", "PVP" to "=PVP",
        "PvE" to "=PVE", "PVE" to "=PVE", "AFK" to "=AFK", "USB" to "=USB", "USB-C" to "=USBC", "CPU" to "=CPU",
        "GPU" to "=GPU", "API" to "=API", "UI" to "=UI", "IA" to "=IA", "AMD" to "=AMD", "HDMI" to "=HDMI", "QTE" to "=QTE",
        "GTA" to "=GTA", "EA" to "=EA", "OLED" to "o led", "Xbox" to "equis box", "XBOX" to "equis box", "Wi-Fi" to "wifi",
        "WiFi" to "wifi", "WIFI" to "wifi", "co-op" to "cooperativo", "Co-op" to "cooperativo", "iOS" to "i o ese",
        "K/D" to "=KD", "EEUU" to "Estados Unidos", "X" to "equis", "xp" to "=XP", "hp" to "=HP", "ok" to "ok",
    )

    override val abbreviations = listOf(
        Abbr("""etc\.""", "etcétera", canEnd = true),
        Abbr("""p\.[ \u00A0]?ej\.""", "por ejemplo"),
        Abbr("""Dra\.""", "doctora"),
        Abbr("""Dr\.""", "doctor"),
        Abbr("""Srta\.""", "señorita"),
        Abbr("""Sra\.""", "señora"),
        Abbr("""Sr\.""", "señor"),
        Abbr("""Uds\.""", "ustedes"),
        Abbr("""Ud\.""", "usted"),
        Abbr("""[vV][sS]\.?""", "versus"),
        Abbr("""aprox\.""", "aproximadamente", canEnd = true),
        Abbr("""núm\.""", "número"),
        Abbr("""[nN]\.?[ \u00A0]?[º°]""", "número"),
        Abbr("""págs\.""", "páginas"),
        Abbr("""pág\.""", "página"),
        Abbr("""EE\.[ \u00A0]?UU\.""", "Estados Unidos", canEnd = true),
        Abbr("""a\.[ \u00A0]?C\.""", "antes de Cristo", canEnd = true),
        Abbr("""d\.[ \u00A0]?C\.""", "después de Cristo", canEnd = true),
        Abbr("""máx\.""", "máximo", canEnd = true),
        Abbr("""mín\.""", "mínimo", canEnd = true),
        Abbr("""tel\.""", "teléfono"),
        Abbr("""cap\.""", "capítulo"),
        Abbr("""vol\.""", "volumen"),
        Abbr("""[lL][vV][lL]""", "nivel"),
        Abbr("""[lL][vV]\.?(?=[ \u00A0]?\d)""", "nivel"),
        Abbr("""y/o""", "y o"),
    )

    companion object {
        val POINT_REGIONS = setOf("MX", "US", "PR", "DO", "GT", "HN", "NI", "PA", "SV", "CU")
        val PESO_REGIONS = setOf("MX", "AR", "CO", "CL", "UY", "DO", "CU", "PH")
        val DOLLAR = Cur("dólar", "dólares", "centavo", "centavos")
        val PESO = Cur("peso", "pesos", "centavo", "centavos")
        val UNITS = arrayOf(
            "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece",
            "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidós",
            "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve",
        )
        val TENS = arrayOf("", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
        val HUNDREDS = arrayOf(
            "", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos", "setecientos",
            "ochocientos", "novecientos",
        )
        val ORD1 = arrayOf("", "primero", "segundo", "tercero", "cuarto", "quinto", "sexto", "séptimo", "octavo", "noveno")
        val ORD10 = arrayOf(
            "", "décimo", "vigésimo", "trigésimo", "cuadragésimo", "quincuagésimo", "sexagésimo", "septuagésimo",
            "octogésimo", "nonagésimo",
        )
        val DEGREE_NEXT = setOf("c", "f", "de", "celsius", "fahrenheit", "centígrados", "grados")
        val FEM_DET = setOf("la", "las", "una", "unas", "esas", "estas", "aquellas", "otras", "ambas")

        /** Sustantivos femeninos frecuentes (y su plural) tras una cifra. */
        val FEM: Set<String> = (
            "hora horas partida partidas semana semanas persona personas vez veces actualización actualizaciones vida vidas " +
                "misión misiones estrella estrellas moneda monedas gema gemas pantalla pantallas página páginas noche noches " +
                "tarde tardes mañana mañanas jugadora jugadoras carta cartas ronda rondas temporada temporadas victoria " +
                "victorias derrota derrotas muerte muertes batalla batallas aplicación aplicaciones app apps notificación " +
                "notificaciones canción canciones imagen imágenes foto fotos captura capturas descarga descargas cuenta " +
                "cuentas consola consolas pista pistas vuelta vueltas fase fases etapa etapas zona zonas plataforma plataformas " +
                "tarjeta tarjetas clase clases edición ediciones versión versiones sesión sesiones entrega entregas saga sagas " +
                "palabra palabras letra letras línea líneas mejora mejoras recompensa recompensas llave llaves caja cajas " +
                "jornada jornadas década décadas cosa cosas opción opciones tarea tareas meta metas unidad unidades jugada " +
                "jugadas pieza piezas habilidad habilidades gráfica gráficas tienda tiendas oferta ofertas reseña reseñas nota " +
                "notas puntuación puntuaciones calificación calificaciones amiga amigas mujer mujeres chica chicas niña niñas " +
                "generación generaciones entrada entradas película películas serie series escena escenas batería baterías " +
                "región regiones ciudad ciudades mano manos pregunta preguntas respuesta respuestas idea ideas noticia noticias " +
                "novedad novedades función funciones característica características historia historias aventura aventuras " +
                "carrera carreras liga ligas copa copas medalla medallas insignia insignias skin skins partícula partículas " +
                "mazmorra mazmorras isla islas espada espadas flecha flechas poción pociones semana hora mesa mesas puerta " +
                "puertas máquina máquinas expansión expansiones misión quest quests persona gente cámara cámaras"
            ).split(' ').filter { it.isNotEmpty() }.toSet()

        /** Palabras tras una cifra que no son sustantivos: la cifra va sin apócope ("uno de", "1 y 2"). */
        val NOT_NOUN: Set<String> = (
            "y e o u ni de del a al en con por para sin sobre entre hasta desde hacia según que como cuando donde si no es " +
                "son era eran fue fueron será serán está están estaba hay había más menos solo sola tan muy ya también " +
                "disponible disponibles gratis restante restantes pendiente pendientes seguido seguidos seguidas vs versus x " +
                "le les lo la los las se me te nos mil millón millones coma punto"
            ).split(' ').toSet() - setOf("mil", "millón", "millones")
    }
}

/* ── Inglés ── */

internal class EnSpeller(region: String?) : Speller(region) {
    override val lang = "en"
    private val gbAnd = region in setOf("GB", "UK", "AU", "NZ", "IE", "IN", "ZA")
    override val decimalSep = '.'
    override val ampm = true
    override val monthFirst = region == null || region in setOf("US", "PH", "CA")
    override val comma = "point"
    override val dot = "point"
    override val percent = "percent"
    override val rangeWord = "to"
    override val between = ("between" to "and")
    override val minus = "minus"
    override val plus = "plus"
    override val and = "and"
    override val or = "or"
    override val equals = "equals"
    override val at = "at"
    override val number = "number"
    override val about = "about"
    override val degrees = "degrees"
    override val by = "by"
    override val version = "version"
    override val slash = "slash"
    override val dash = "dash"
    override val underscore = "underscore"
    override val months = arrayOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )
    override val dateCues = setOf("on", "by", "until", "since", "from", "date", "the", "till", "before", "after")
    override val letters = Array(26) { ('A' + it).toString() }
    override val fractions = mapOf(
        (1L to 2L) to "one half", (1L to 3L) to "one third", (2L to 3L) to "two thirds", (1L to 4L) to "one quarter",
        (3L to 4L) to "three quarters", (24L to 7L) to "twenty-four seven",
    )

    override fun cardinal(n: Long, g: Gen): String {
        if (n < 0) return "minus " + cardinal(-n)
        if (n == 0L) return "zero"
        val parts = ArrayList<String>()
        var rest = n
        for ((v, w) in SCALES) {
            if (rest >= v) {
                parts += below1000((rest / v).toInt()) + " " + w
                rest %= v
            }
        }
        if (rest > 0) parts += (if (gbAnd && parts.isNotEmpty() && rest < 100) "and " else "") + below1000(rest.toInt())
        return parts.joinToString(" ")
    }

    private fun below100(n: Int): String = if (n < 20) UNITS[n] else TENS[n / 10] + if (n % 10 != 0) "-" + UNITS[n % 10] else ""

    private fun below1000(n: Int): String {
        val h = n / 100
        val r = n % 100
        if (h == 0) return below100(r)
        return UNITS[h] + " hundred" + if (r > 0) (if (gbAnd) " and " else " ") + below100(r) else ""
    }

    override fun ordinal(n: Long, g: Gen): String {
        val c = cardinal(n)
        val k = maxOf(c.lastIndexOf(' '), c.lastIndexOf('-')) + 1
        val last = c.substring(k)
        val o = ORD_IRREGULAR[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        return c.substring(0, k) + o
    }

    override fun year(n: Long): String {
        if (n !in 1000..2099 || n in 2000..2009) return cardinal(n)
        val hi = n / 100
        val lo = n % 100
        return when {
            lo == 0L -> cardinal(hi) + " hundred"
            lo < 10 -> cardinal(hi) + " oh " + cardinal(lo)
            else -> cardinal(hi) + " " + cardinal(lo)
        }
    }

    override fun resNumber(n: Long): String = when {
        n in 1000..9999 && n % 100 != 0L -> year(n).takeIf { n < 2000 || n >= 2010 } ?: (cardinal(n / 100) + " " + cardinal(n % 100))
        n in 100..999 && n % 100 != 0L -> UNITS[(n / 100).toInt()] + " " + (if (n % 100 < 10) "oh " else "") + cardinal(n % 100)
        else -> cardinal(n)
    }

    override fun resP(n: Long) = resNumber(n) + " p"

    override fun decade(n: Long): String {
        val w = if (n >= 1000) year(n) else cardinal(n)
        return if (w.endsWith("y")) w.dropLast(1) + "ies" else w + "s"
    }

    override fun roman(v: Int) = cardinal(v.toLong()).replaceFirstChar { it.uppercaseChar() }

    override val ordinalPattern: Pattern = Pattern.compile("""(?i:st|nd|rd|th)(?![\p{L}\p{N}])""")

    override fun ordinalWords(n: Long, suffix: String, prev: String, next: String) = ordinal(n)

    override fun contextual(n: Long, len: Int, prev: String, next: String, next2: String): String? {
        if (len == 4 && n in 1100..2099 &&
            (prev in YEAR_CUES || prev in monthSet || (prev.isNotEmpty() && prev.all { it.isDigit() }) || next.isEmpty())
        ) return year(n)
        if (n in 1..31 && len <= 2 && (prev in monthSet || next in monthSet)) return ordinal(n)
        return null
    }

    override fun time(h: Int, m: Int, ampm: Char?): String {
        val hw = cardinal(h.toLong())
        val mw = when {
            m == 0 -> null
            m < 10 -> "oh " + cardinal(m.toLong())
            else -> cardinal(m.toLong())
        }
        if (ampm != null) return listOfNotNull(hw, mw, if (ampm == 'a') "A M" else "P M").joinToString(" ")
        if (mw == null) return if (h in 1..12) "$hw o'clock" else "$hw hundred"
        return "$hw $mw"
    }

    override fun duration(h: Int, m: Int, s: Int): String = joinList(
        listOfNotNull(
            h.takeIf { it > 0 }?.let { cardinal(it.toLong()) + if (it == 1) " hour" else " hours" },
            m.takeIf { it > 0 }?.let { cardinal(it.toLong()) + if (it == 1) " minute" else " minutes" },
            s.takeIf { it > 0 }?.let { cardinal(it.toLong()) + if (it == 1) " second" else " seconds" },
        ).ifEmpty { listOf("zero seconds") },
        "and",
    )

    override fun date(d: Int, m: Int, y: Int?, prev: String): String =
        months[m - 1] + " " + ordinal(d.toLong()) + (y?.let { ", " + year(it.toLong()) } ?: "")

    override fun times(x: String, one: Boolean) = if (one) "once" else if (x == "two") "twice" else "$x times"
    override fun timesPrefix(x: String) = "times $x"
    override fun moreThan(x: String) = "more than $x"
    override fun fractionOf(a: String, b: String) = "$a out of $b"
    override fun ratio(a: Long, b: Long) = cardinal(a) + " to " + cardinal(b)
    override fun scaleWord(k: Long) = if (k >= 1_000_000) "million" else "thousand"

    override fun currency(sym: String): Cur? = when (sym) {
        "€", "EUR" -> Cur("euro", "euros", "cent", "cents")
        "$", "US$", "USD" -> Cur("dollar", "dollars", "cent", "cents")
        "MX$", "MXN" -> Cur("peso", "pesos", "centavo", "centavos")
        "R$", "BRL" -> Cur("real", "reais", "centavo", "centavos")
        "¥", "￥", "円", "JPY" -> Cur("yen", "yen", "sen", "sen")
        "£", "GBP" -> Cur("pound", "pounds", "penny", "pence")
        else -> null
    }

    override fun withCents(amount: String, c: Int, cur: Cur) =
        "$amount and ${cardinal(c.toLong())} ${if (c == 1) cur.centOne else cur.centMany}"

    override val units: Map<String, Measure> = buildMap {
        for (k in listOf("GB", "gb", "Gb")) put(k, Measure("gigabyte", "gigabytes"))
        for (k in listOf("MB", "mb", "Mb")) put(k, Measure("megabyte", "megabytes"))
        for (k in listOf("KB", "kB", "kb")) put(k, Measure("kilobyte", "kilobytes"))
        for (k in listOf("TB", "tb")) put(k, Measure("terabyte", "terabytes"))
        for (k in listOf("GHz", "ghz", "Ghz")) put(k, Measure("gigahertz", "gigahertz"))
        for (k in listOf("MHz", "mhz", "Mhz")) put(k, Measure("megahertz", "megahertz"))
        put("kHz", Measure("kilohertz", "kilohertz"))
        for (k in listOf("Hz", "hz")) put(k, Measure("hertz", "hertz"))
        for (k in listOf("FPS", "fps")) put(k, Measure("F P S", "F P S"))
        put("ms", Measure("millisecond", "milliseconds"))
        for (k in listOf("h", "hr", "hrs")) put(k, Measure("hour", "hours"))
        for (k in listOf("min", "mins")) put(k, Measure("minute", "minutes"))
        for (k in listOf("s", "sec", "secs")) put(k, Measure("second", "seconds"))
        for (k in listOf("°C", "ºC")) put(k, Measure("degree Celsius", "degrees Celsius"))
        for (k in listOf("°F", "ºF")) put(k, Measure("degree Fahrenheit", "degrees Fahrenheit"))
        put("°", Measure("degree", "degrees"))
        put("mAh", Measure("milliamp hour", "milliamp hours"))
        put("W", Measure("watt", "watts"))
        put("kW", Measure("kilowatt", "kilowatts"))
        put("mm", Measure("millimeter", "millimeters"))
        put("cm", Measure("centimeter", "centimeters"))
        put("km", Measure("kilometer", "kilometers"))
        put("m", Measure("meter", "meters"))
        for (k in listOf("km/h", "kmh", "kph")) put(k, Measure("kilometer per hour", "kilometers per hour"))
        put("mph", Measure("mile per hour", "miles per hour"))
        put("kg", Measure("kilogram", "kilograms"))
        put("g", Measure("gram", "grams"))
        put("px", Measure("pixel", "pixels"))
        put("MP", Measure("megapixel", "megapixels"))
        put("Mbps", Measure("megabit per second", "megabits per second"))
        put("Gbps", Measure("gigabit per second", "gigabits per second"))
        put("MB/s", Measure("megabyte per second", "megabytes per second"))
        put("GB/s", Measure("gigabyte per second", "gigabytes per second"))
    }

    override val acronyms = mapOf(
        "AAA" to "triple A", "GOTY" to "Game of the Year", "MMO" to "=MMO", "MMORPG" to "=MMORPG", "PvP" to "=PVP",
        "PVP" to "=PVP", "PvE" to "=PVE", "PVE" to "=PVE", "AFK" to "=AFK", "USB" to "=USB", "USB-C" to "=USBC",
        "CPU" to "=CPU", "GPU" to "=GPU", "API" to "=API", "UI" to "=UI", "AI" to "=AI", "AMD" to "=AMD", "HDMI" to "=HDMI",
        "QTE" to "=QTE", "GTA" to "=GTA", "EA" to "=EA", "iOS" to "eye O S", "K/D" to "=KD", "xp" to "=XP", "hp" to "=HP",
    )

    override val abbreviations = listOf(
        Abbr("""e\.[ \u00A0]?g\.""", "for example"),
        Abbr("""i\.[ \u00A0]?e\.""", "that is"),
        Abbr("""approx\.""", "approximately", canEnd = true),
        Abbr("""Mrs\.""", "Missus"),
        Abbr("""Mr\.""", "Mister"),
        Abbr("""Ms\.""", "Miz"),
        Abbr("""Dr\.""", "Doctor"),
        Abbr("""Jr\.""", "Junior", canEnd = true),
        Abbr("""Sr\.""", "Senior", canEnd = true),
        Abbr("""[vV][sS]\.?""", "versus"),
        Abbr("""etc\.""", "et cetera", canEnd = true),
        Abbr("""No\.(?=[ \u00A0]?\d)""", "number"),
        Abbr("""[lL][vV][lL]""", "level"),
        Abbr("""[lL][vV]\.?(?=[ \u00A0]?\d)""", "level"),
        Abbr("""[Ff]eat\.|ft\.(?=[ \u00A0]\p{Lu})""", "featuring"),
        Abbr("""w/o""", "without"),
        Abbr("""w/""", "with"),
        Abbr("""and/or""", "and or"),
    )

    companion object {
        val SCALES = listOf(
            1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1000L to "thousand",
        )
        val UNITS = arrayOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
        val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        val ORD_IRREGULAR = mapOf(
            "one" to "first", "two" to "second", "three" to "third", "five" to "fifth", "eight" to "eighth",
            "nine" to "ninth", "twelve" to "twelfth",
        )
        val YEAR_CUES = setOf(
            "in", "since", "from", "of", "year", "until", "till", "by", "before", "after", "around", "circa", "early",
            "late", "mid", "summer", "winter", "spring", "fall", "autumn", "during",
        )
    }
}

/* ── Portugués (pt-BR por defecto; pt-PT con región PT) ── */

internal class PtSpeller(region: String?) : Speller(region) {
    override val lang = "pt"
    private val portugal = region == "PT"
    override val comma = "vírgula"
    override val dot = "ponto"
    override val percent = "por cento"
    override val rangeWord = "a"
    override val between = ("entre" to "e")
    override val minus = "menos"
    override val plus = "mais"
    override val and = "e"
    override val or = "ou"
    override val equals = "igual a"
    override val at = "arroba"
    override val number = "número"
    override val about = "cerca de"
    override val degrees = "graus"
    override val by = "por"
    override val version = "versão"
    override val slash = "barra"
    override val dash = "hífen"
    override val underscore = "sublinhado"
    override val de = "de"
    override val timesGender = Gen.F
    override val months = arrayOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )
    override val dateCues = setOf("dia", "em", "até", "desde", "de", "o", "no", "ao")
    override val letters = arrayOf(
        "a", "bê", "cê", "dê", "é", "efe", "gê", "agá", "i", "jota", "cá", "ele", "eme", "ene", "ó", "pê", "quê", "erre", "esse",
        "tê", "u", "vê", "dáblio", "xis", "ípsilon", "zê",
    )
    override val fractions = mapOf(
        (1L to 2L) to "meio", (1L to 3L) to "um terço", (2L to 3L) to "dois terços", (1L to 4L) to "um quarto",
        (3L to 4L) to "três quartos",
    )
    private val units20 = arrayOf(
        "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze", "doze", "treze", "catorze",
        "quinze", if (portugal) "dezasseis" else "dezesseis", if (portugal) "dezassete" else "dezessete", "dezoito",
        if (portugal) "dezanove" else "dezenove",
    )

    private fun unit(n: Int, g: Gen) = when {
        g == Gen.F && n == 1 -> "uma"
        g == Gen.F && n == 2 -> "duas"
        else -> units20[n]
    }

    private fun below100(n: Int, g: Gen): String = if (n < 20) unit(n, g) else TENS[n / 10] + if (n % 10 == 0) "" else " e " + unit(n % 10, g)

    private fun below1000(n: Int, g: Gen): String {
        if (n < 100) return below100(n, g)
        if (n == 100) return "cem"
        val h = HUNDREDS[n / 100].let { if (g == Gen.F && n >= 200) it.dropLast(2) + "as" else it }
        return if (n % 100 == 0) h else "$h e ${below100(n % 100, g)}"
    }

    /** Une grupos: "e" antes del último si es < 100 o centena redonda ("mil e cem", "mil duzentos e trinta"). */
    private fun join(groups: List<Pair<String, Long>>): String {
        val sb = StringBuilder()
        groups.forEachIndexed { k, (t, v) ->
            if (k > 0) sb.append(if (k == groups.size - 1 && (v < 100 || v % 100 == 0L)) " e " else " ")
            sb.append(t)
        }
        return sb.toString()
    }

    override fun cardinal(n: Long, g: Gen): String {
        if (n < 0) return "menos " + cardinal(-n, g)
        if (n == 0L) return "zero"
        val groups = ArrayList<Pair<String, Long>>()
        val tri = n / 1_000_000_000_000L
        if (tri > 0) groups += (if (portugal) (if (tri == 1L) "um bilião" else below1000(tri.toInt(), Gen.M) + " biliões")
        else (if (tri == 1L) "um trilhão" else below1000(tri.toInt(), Gen.M) + " trilhões")) to 1000L
        if (portugal) {
            val mi = (n / 1_000_000L) % 1_000_000L
            if (mi > 0) groups += (if (mi == 1L) "um milhão" else below1M(mi, Gen.M) + " milhões") to 1000L
        } else {
            val bi = (n / 1_000_000_000L) % 1000
            val mi = (n / 1_000_000L) % 1000
            if (bi > 0) groups += (if (bi == 1L) "um bilhão" else below1000(bi.toInt(), Gen.M) + " bilhões") to 1000L
            if (mi > 0) groups += (if (mi == 1L) "um milhão" else below1000(mi.toInt(), Gen.M) + " milhões") to 1000L
        }
        val th = (n / 1000) % 1000
        val r = n % 1000
        if (th > 0) groups += (if (th == 1L) "mil" else below1000(th.toInt(), g) + " mil") to 1000L
        if (r > 0) groups += below1000(r.toInt(), g) to r
        return join(groups)
    }

    private fun below1M(n: Long, g: Gen): String {
        val th = n / 1000
        val r = n % 1000
        val groups = ArrayList<Pair<String, Long>>()
        if (th > 0) groups += (if (th == 1L) "mil" else below1000(th.toInt(), g) + " mil") to 1000L
        if (r > 0) groups += below1000(r.toInt(), g) to r
        return join(groups)
    }

    override fun ordinal(n: Long, g: Gen): String {
        if (n <= 0 || n >= 100) return cardinal(n, g)
        val k = n.toInt()
        val w = if (k < 10) ORD1[k] else ORD10[k / 10] + if (k % 10 != 0) " " + ORD1[k % 10] else ""
        return if (g == Gen.F) w.split(' ').joinToString(" ") { it.dropLast(1) + "a" } else w
    }

    override val ordinalPattern: Pattern = Pattern.compile("""\.?[ºª]|°(?=[ \u00A0]?\p{L})""")

    override fun ordinalWords(n: Long, suffix: String, prev: String, next: String): String? {
        if (suffix == "°" && (n > 10 || next.lowercase() in setOf("c", "f", "de", "celsius"))) return null
        return ordinal(n, if (suffix.endsWith("ª")) Gen.F else Gen.M)
    }

    override fun gender(prev: String, next: String, nextRaw: String): Gen = when {
        next in FEM -> Gen.F
        prev in setOf("a", "as", "às", "à", "uma", "das", "nas", "pelas") -> Gen.F
        else -> Gen.N
    }

    override fun contextual(n: Long, len: Int, prev: String, next: String, next2: String): String? =
        if (n == 1L && next == "de" && next2 in monthSet) "primeiro" else null

    override fun time(h: Int, m: Int, ampm: Char?): String {
        val hw = if (h == 0) "zero" else cardinal(h.toLong(), Gen.F)
        if (m == 0) return hw + if (h == 1) " hora" else " horas"
        return "$hw e ${cardinal(m.toLong())}"
    }

    override fun duration(h: Int, m: Int, s: Int): String = joinList(
        listOfNotNull(
            h.takeIf { it > 0 }?.let { cardinal(it.toLong(), Gen.F) + if (it == 1) " hora" else " horas" },
            m.takeIf { it > 0 }?.let { cardinal(it.toLong()) + if (it == 1) " minuto" else " minutos" },
            s.takeIf { it > 0 }?.let { cardinal(it.toLong()) + if (it == 1) " segundo" else " segundos" },
        ).ifEmpty { listOf("zero segundos") },
        "e",
    )

    override fun date(d: Int, m: Int, y: Int?, prev: String): String {
        val dw = if (d == 1) "primeiro" else cardinal(d.toLong())
        return "$dw de ${months[m - 1]}" + (y?.let { " de " + cardinal(it.toLong()) } ?: "")
    }

    override fun times(x: String, one: Boolean) = if (one) "uma vez" else "$x vezes"
    override fun timesPrefix(x: String) = "vezes $x"
    override fun moreThan(x: String) = "mais de $x"
    override fun fractionOf(a: String, b: String) = "$a de $b"
    override fun ratio(a: Long, b: Long) = cardinal(a) + " para " + cardinal(b)
    override fun scaleWord(k: Long) = if (k >= 1_000_000) "milhões" else "mil"

    override fun currency(sym: String): Cur? = when (sym) {
        "R$", "BRL" -> Cur("real", "reais", "centavo", "centavos")
        "€", "EUR" -> if (portugal) Cur("euro", "euros", "cêntimo", "cêntimos") else Cur("euro", "euros", "centavo", "centavos")
        "$", "US$", "USD" -> Cur("dólar", "dólares", "centavo", "centavos")
        "MX$", "MXN" -> Cur("peso", "pesos", "centavo", "centavos")
        "¥", "￥", "円", "JPY" -> Cur("iene", "ienes", "sen", "sen")
        "£", "GBP" -> Cur("libra", "libras", "pêni", "pence", Gen.F)
        else -> null
    }

    override fun withCents(amount: String, c: Int, cur: Cur) =
        "$amount e ${cardinal(c.toLong(), cur.centG)} ${if (c == 1) cur.centOne else cur.centMany}"

    override val units: Map<String, Measure> = buildMap {
        for (k in listOf("GB", "gb")) put(k, Measure("giga", "gigas"))
        for (k in listOf("MB", "mb")) put(k, Measure("mega", "megas"))
        for (k in listOf("KB", "kB", "kb")) put(k, Measure("kilobyte", "kilobytes"))
        for (k in listOf("TB", "tb")) put(k, Measure("tera", "teras"))
        for (k in listOf("GHz", "ghz")) put(k, Measure("gigahertz", "gigahertz"))
        for (k in listOf("MHz", "mhz")) put(k, Measure("megahertz", "megahertz"))
        for (k in listOf("Hz", "hz")) put(k, Measure("hertz", "hertz"))
        for (k in listOf("FPS", "fps")) put(k, Measure("efe pê esse", "efe pê esse"))
        put("ms", Measure("milissegundo", "milissegundos"))
        for (k in listOf("h", "hs")) put(k, Measure("hora", "horas", Gen.F))
        for (k in listOf("min", "mins")) put(k, Measure("minuto", "minutos"))
        for (k in listOf("s", "seg")) put(k, Measure("segundo", "segundos"))
        for (k in listOf("°C", "ºC", "°")) put(k, Measure("grau", "graus"))
        put("mAh", Measure("miliampere-hora", "miliamperes-hora"))
        put("W", Measure("watt", "watts"))
        put("mm", Measure("milímetro", "milímetros"))
        put("cm", Measure("centímetro", "centímetros"))
        put("km", Measure(if (portugal) "quilómetro" else "quilômetro", if (portugal) "quilómetros" else "quilômetros"))
        put("m", Measure("metro", "metros"))
        put("km/h", Measure(if (portugal) "quilómetro por hora" else "quilômetro por hora", if (portugal) "quilómetros por hora" else "quilômetros por hora"))
        put("kg", Measure("quilo", "quilos"))
        put("g", Measure("grama", "gramas"))
        put("px", Measure("pixel", "pixels"))
        put("Mbps", Measure("megabit por segundo", "megabits por segundo"))
    }

    override val acronyms = mapOf("AAA" to "triplo A", "PvP" to "=PVP", "PvE" to "=PVE", "GTA" to "=GTA", "USB" to "=USB", "CPU" to "=CPU", "GPU" to "=GPU")

    override val abbreviations = listOf(
        Abbr("""etc\.""", "et cetera", canEnd = true),
        Abbr("""Sra\.""", "senhora"),
        Abbr("""Sr\.""", "senhor"),
        Abbr("""Dra\.""", "doutora"),
        Abbr("""Dr\.""", "doutor"),
        Abbr("""p\.[ \u00A0]?ex\.""", "por exemplo"),
        Abbr("""aprox\.""", "aproximadamente", canEnd = true),
        Abbr("""[nN]\.?[ \u00A0]?[º°]""", "número"),
        Abbr("""[vV][sS]\.?""", "versus"),
        Abbr("""pág\.""", "página"),
        Abbr("""[lL][vV][lL]""", "nível"),
    )

    companion object {
        val TENS = arrayOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
        val HUNDREDS = arrayOf(
            "", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos",
        )
        val ORD1 = arrayOf("", "primeiro", "segundo", "terceiro", "quarto", "quinto", "sexto", "sétimo", "oitavo", "nono")
        val ORD10 = arrayOf(
            "", "décimo", "vigésimo", "trigésimo", "quadragésimo", "quinquagésimo", "sexagésimo", "septuagésimo", "octogésimo", "nonagésimo",
        )
        val FEM: Set<String> = (
            "hora horas semana semanas vez vezes pessoa pessoas partida partidas vida vidas missão missões estrela estrelas " +
                "moeda moedas atualização atualizações noite noites tela telas página páginas conta contas fase fases versão " +
                "versões carta cartas rodada rodadas temporada temporadas vitória vitórias derrota derrotas jogadora jogadoras " +
                "conquista conquistas música músicas foto fotos imagem imagens notificação notificações mensagem mensagens " +
                "opção opções coisa coisas loja lojas oferta ofertas nota notas plataforma plataformas sessão sessões edição " +
                "edições mulher mulheres amiga amigas cidade cidades ideia ideias arma armas batalha batalhas"
            ).split(' ').toSet()
    }
}

/* ── Francés ── */

internal class FrSpeller(region: String?) : Speller(region) {
    override val lang = "fr"
    override val comma = "virgule"
    override val dot = "point"
    override val percent = "pour cent"
    override val rangeWord = "à"
    override val between = ("entre" to "et")
    override val minus = "moins"
    override val plus = "plus"
    override val and = "et"
    override val or = "ou"
    override val equals = "égal"
    override val at = "arobase"
    override val number = "numéro"
    override val about = "environ"
    override val degrees = "degrés"
    override val by = "par"
    override val version = "version"
    override val slash = "slash"
    override val dash = "tiret"
    override val underscore = "tiret bas"
    override val de = "de"
    override val timesGender = Gen.F
    override val months = arrayOf(
        "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )
    override val dateCues = setOf("le", "du", "au", "jusqu'au", "depuis")
    override val letters = arrayOf(
        "a", "bé", "cé", "dé", "e", "effe", "gé", "ache", "i", "ji", "ka", "elle", "emme", "enne", "o", "pé", "ku", "erre",
        "esse", "té", "u", "vé", "double-vé", "ixe", "i grec", "zède",
    )
    override val fractions = mapOf(
        (1L to 2L) to "un demi", (1L to 3L) to "un tiers", (2L to 3L) to "deux tiers", (1L to 4L) to "un quart",
        (3L to 4L) to "trois quarts",
    )

    private fun un(g: Gen) = if (g == Gen.F) "une" else "un"

    private fun below100(n: Int, g: Gen): String {
        if (n == 1) return un(g)
        if (n < 20) return UNITS[n]
        val t = n / 10
        val u = n % 10
        return when {
            t <= 6 -> TENS[t] + when (u) { 0 -> ""; 1 -> " et " + un(g); else -> "-" + UNITS[u] }
            t == 7 -> if (u == 1) "soixante et onze" else "soixante-" + UNITS[10 + u]
            t == 8 -> if (u == 0) "quatre-vingts" else "quatre-vingt-" + (if (u == 1) un(g) else UNITS[u])
            else -> "quatre-vingt-" + UNITS[10 + u]
        }
    }

    private fun below1000(n: Int, g: Gen, last: Boolean): String {
        val h = n / 100
        val r = n % 100
        val head = when (h) {
            0 -> ""
            1 -> "cent"
            else -> UNITS[h] + " cent" + if (r == 0 && last) "s" else ""
        }
        var body = if (r == 0) "" else below100(r, g)
        if (!last && body == "quatre-vingts") body = "quatre-vingt"
        return listOf(head, body).filter { it.isNotEmpty() }.joinToString(" ")
    }

    override fun cardinal(n: Long, g: Gen): String {
        if (n < 0) return "moins " + cardinal(-n, g)
        if (n == 0L) return "zéro"
        val parts = ArrayList<String>()
        val bn = n / 1_000_000_000_000L
        val md = (n / 1_000_000_000L) % 1000
        val mi = (n / 1_000_000L) % 1000
        val th = (n / 1000) % 1000
        val r = n % 1000
        if (bn > 0) parts += below1000(bn.toInt(), Gen.M, true) + if (bn == 1L) " billion" else " billions"
        if (md > 0) parts += below1000(md.toInt(), Gen.M, true) + if (md == 1L) " milliard" else " milliards"
        if (mi > 0) parts += below1000(mi.toInt(), Gen.M, true) + if (mi == 1L) " million" else " millions"
        if (th > 0) parts += if (th == 1L) "mille" else below1000(th.toInt(), g, false) + " mille"
        if (r > 0) parts += below1000(r.toInt(), g, true)
        return parts.joinToString(" ")
    }

    override fun ordinal(n: Long, g: Gen): String {
        if (n == 1L) return if (g == Gen.F) "première" else "premier"
        val c = cardinal(n)
        val k = maxOf(c.lastIndexOf(' '), c.lastIndexOf('-')) + 1
        val last = c.substring(k)
        val o = when (last) {
            "un" -> "unième"
            "cinq" -> "cinquième"
            "neuf" -> "neuvième"
            "vingts" -> "vingtième"
            "cents" -> "centième"
            else -> if (last.endsWith("e")) last.dropLast(1) + "ième" else last + "ième"
        }
        return c.substring(0, k) + o
    }

    override val ordinalPattern: Pattern = Pattern.compile("""(?:ers|er|res|re|ères|ère|èmes|ème|eme|es|e|nde|nd)(?![\p{L}\p{N}])""")

    override fun ordinalWords(n: Long, suffix: String, prev: String, next: String): String? = when (suffix) {
        "er", "ers" -> if (n == 1L) "premier" else ordinal(n)
        "re", "res", "ère", "ères" -> if (n == 1L) "première" else ordinal(n)
        "nd" -> if (n == 2L) "second" else ordinal(n)
        "nde" -> if (n == 2L) "seconde" else ordinal(n)
        else -> ordinal(n)
    }

    override fun gender(prev: String, next: String, nextRaw: String): Gen = if (next in FEM) Gen.F else Gen.N

    override fun contextual(n: Long, len: Int, prev: String, next: String, next2: String): String? =
        if (n == 1L && next in monthSet) "premier" else null

    override fun time(h: Int, m: Int, ampm: Char?): String {
        val hw = if (h == 0) "zéro" else cardinal(h.toLong(), Gen.F)
        val hs = if (h <= 1) "heure" else "heures"
        return if (m == 0) "$hw $hs" else "$hw $hs ${cardinal(m.toLong(), Gen.F)}"
    }

    override fun duration(h: Int, m: Int, s: Int): String = joinList(
        listOfNotNull(
            h.takeIf { it > 0 }?.let { cardinal(it.toLong(), Gen.F) + if (it == 1) " heure" else " heures" },
            m.takeIf { it > 0 }?.let { cardinal(it.toLong(), Gen.F) + if (it == 1) " minute" else " minutes" },
            s.takeIf { it > 0 }?.let { cardinal(it.toLong(), Gen.F) + if (it == 1) " seconde" else " secondes" },
        ).ifEmpty { listOf("zéro seconde") },
        "et",
    )

    override fun date(d: Int, m: Int, y: Int?, prev: String): String {
        val dw = if (d == 1) "premier" else cardinal(d.toLong())
        return "$dw ${months[m - 1]}" + (y?.let { " " + cardinal(it.toLong()) } ?: "")
    }

    override fun times(x: String, one: Boolean) = if (one) "une fois" else "$x fois"
    override fun timesPrefix(x: String) = "fois $x"
    override fun moreThan(x: String) = "plus de $x"
    override fun fractionOf(a: String, b: String) = "$a sur $b"
    override fun ratio(a: Long, b: Long) = cardinal(a) + " contre " + cardinal(b)
    override fun scaleWord(k: Long) = if (k >= 1_000_000) "millions" else "mille"

    override fun currency(sym: String): Cur? = when (sym) {
        "€", "EUR" -> Cur("euro", "euros", "centime", "centimes")
        "$", "US$", "USD" -> Cur("dollar", "dollars", "cent", "cents")
        "MX$", "MXN" -> Cur("peso", "pesos", "centavo", "centavos")
        "R$", "BRL" -> Cur("réal", "réais", "centavo", "centavos")
        "¥", "￥", "円", "JPY" -> Cur("yen", "yens", "sen", "sen")
        "£", "GBP" -> Cur("livre", "livres", "penny", "pence", Gen.F)
        else -> null
    }

    override fun withCents(amount: String, c: Int, cur: Cur) = "$amount ${cardinal(c.toLong())}"

    override val units: Map<String, Measure> = buildMap {
        for (k in listOf("GB", "Go", "go")) put(k, Measure("gigaoctet", "gigaoctets"))
        for (k in listOf("MB", "Mo")) put(k, Measure("mégaoctet", "mégaoctets"))
        for (k in listOf("KB", "ko", "Ko")) put(k, Measure("kilooctet", "kilooctets"))
        for (k in listOf("TB", "To")) put(k, Measure("téraoctet", "téraoctets"))
        for (k in listOf("GHz", "ghz")) put(k, Measure("gigahertz", "gigahertz"))
        for (k in listOf("MHz", "mhz")) put(k, Measure("mégahertz", "mégahertz"))
        for (k in listOf("Hz", "hz")) put(k, Measure("hertz", "hertz"))
        for (k in listOf("FPS", "fps", "ips")) put(k, Measure("image par seconde", "images par seconde", Gen.F))
        put("ms", Measure("milliseconde", "millisecondes", Gen.F))
        put("min", Measure("minute", "minutes", Gen.F))
        put("s", Measure("seconde", "secondes", Gen.F))
        for (k in listOf("°C", "ºC", "°")) put(k, Measure("degré", "degrés"))
        put("mAh", Measure("milliampère-heure", "milliampères-heure"))
        put("W", Measure("watt", "watts"))
        put("mm", Measure("millimètre", "millimètres"))
        put("cm", Measure("centimètre", "centimètres"))
        put("km", Measure("kilomètre", "kilomètres"))
        put("m", Measure("mètre", "mètres"))
        put("km/h", Measure("kilomètre-heure", "kilomètres-heure"))
        put("kg", Measure("kilo", "kilos"))
        put("g", Measure("gramme", "grammes"))
        put("px", Measure("pixel", "pixels"))
    }

    override val acronyms = mapOf("AAA" to "triple a", "PvP" to "=PVP", "PvE" to "=PVE", "GTA" to "=GTA", "USB" to "=USB", "CPU" to "=CPU", "GPU" to "=GPU")

    override val abbreviations = listOf(
        Abbr("""etc\.""", "et cetera", canEnd = true),
        Abbr("""M\.(?=[ \u00A0]\p{Lu})""", "monsieur"),
        Abbr("""Mmes""", "mesdames"),
        Abbr("""Mme""", "madame"),
        Abbr("""Mlle""", "mademoiselle"),
        Abbr("""Dr\.?(?=[ \u00A0]\p{Lu})""", "docteur"),
        Abbr("""p\.[ \u00A0]?ex\.""", "par exemple"),
        Abbr("""env\.""", "environ"),
        Abbr("""[nN][°º]""", "numéro"),
        Abbr("""[vV][sS]\.?""", "contre"),
        Abbr("""c\.-à-d\.""", "c'est-à-dire"),
        Abbr("""[lL][vV][lL]""", "niveau"),
    )

    companion object {
        val UNITS = arrayOf(
            "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze", "treize",
            "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf",
        )
        val TENS = arrayOf("", "", "vingt", "trente", "quarante", "cinquante", "soixante")
        val FEM: Set<String> = (
            "heure heures minute minutes seconde secondes semaine semaines personne personnes fois partie parties vie vies " +
                "mission missions étoile étoiles pièce pièces mise mises version versions carte cartes manche manches saison " +
                "saisons victoire victoires nuit nuits journée journées page pages application applications notification " +
                "notifications image images photo photos chanson chansons plateforme plateformes console consoles édition " +
                "éditions session sessions arme armes quête quêtes récompense récompenses zone zones chose choses année années " +
                "idée idées amie amies femme femmes ville villes"
            ).split(' ').toSet()
    }
}

/* ── Alemán ── */

internal class DeSpeller(region: String?) : Speller(region) {
    override val lang = "de"
    override val comma = "Komma"
    override val dot = "Punkt"
    override val percent = "Prozent"
    override val rangeWord = "bis"
    override val between = ("zwischen" to "und")
    override val minus = "minus"
    override val plus = "plus"
    override val and = "und"
    override val or = "oder"
    override val equals = "gleich"
    override val at = "at"
    override val number = "Nummer"
    override val about = "etwa"
    override val degrees = "Grad"
    override val by = "mal"
    override val version = "Version"
    override val slash = "Schrägstrich"
    override val dash = "Bindestrich"
    override val underscore = "Unterstrich"
    override val months = arrayOf(
        "Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember",
    )
    override val dateCues = setOf("am", "vom", "zum", "bis", "ab", "seit", "den", "dem", "der")
    override val letters = arrayOf(
        "a", "be", "ce", "de", "e", "ef", "ge", "ha", "i", "jot", "ka", "el", "em", "en", "o", "pe", "ku", "er", "es", "te",
        "u", "vau", "we", "ix", "ypsilon", "zett",
    )
    override val fractions = mapOf(
        (1L to 2L) to "ein halb", (1L to 3L) to "ein Drittel", (2L to 3L) to "zwei Drittel", (1L to 4L) to "ein Viertel",
        (3L to 4L) to "drei Viertel", (24L to 7L) to "vierundzwanzig sieben",
    )

    private fun below100(n: Int): String = when {
        n < 20 -> UNITS[n]
        n % 10 == 0 -> TENS[n / 10]
        else -> (if (n % 10 == 1) "ein" else UNITS[n % 10]) + "und" + TENS[n / 10]
    }

    private fun below1000(n: Int): String {
        val h = n / 100
        val r = n % 100
        return (if (h > 0) (if (h == 1) "ein" else UNITS[h]) + "hundert" else "") + (if (r > 0) below100(r) else "")
    }

    /** Como multiplicador ("einundzwanzigtausend", "hunderteintausend"). */
    private fun mult(n: Int) = below1000(n).let { if (it.endsWith("eins")) it.dropLast(1) else it }

    override fun cardinal(n: Long, g: Gen): String {
        if (n < 0) return "minus " + cardinal(-n)
        if (n == 0L) return "null"
        if (n == 1L) return when (g) { Gen.M -> "ein"; Gen.F -> "eine"; Gen.N -> "eins" }
        val parts = ArrayList<String>()
        val bn = n / 1_000_000_000_000L
        val md = (n / 1_000_000_000L) % 1000
        val mi = (n / 1_000_000L) % 1000
        val low = n % 1_000_000L
        if (bn > 0) parts += if (bn == 1L) "eine Billion" else mult(bn.toInt()) + " Billionen"
        if (md > 0) parts += if (md == 1L) "eine Milliarde" else mult(md.toInt()) + " Milliarden"
        if (mi > 0) parts += if (mi == 1L) "eine Million" else mult(mi.toInt()) + " Millionen"
        if (low > 0) {
            val th = (low / 1000).toInt()
            val r = (low % 1000).toInt()
            parts += (if (th > 0) mult(th) + "tausend" else "") + (if (r > 0) below1000(r) else "")
        }
        return parts.joinToString(" ")
    }

    private fun ordStem(n: Long): String {
        val r = (n % 100).toInt()
        if (r in 1..19) {
            val head = if (n >= 100) cardinal(n - r) else ""
            val st = when (r) { 1 -> "erst"; 3 -> "dritt"; 7 -> "siebt"; 8 -> "acht"; else -> UNITS[r] + "t" }
            return head + st
        }
        return cardinal(n) + "st"
    }

    override fun ordinal(n: Long, g: Gen) = ordStem(n) + "e"

    private fun ending(prev: String) = when (prev) {
        in DATIVE -> "en"
        "der", "die", "das" -> "e"
        else -> "er"
    }

    override val ordinalPattern: Pattern = Pattern.compile("""\.(?=[ \u00A0]+\p{L})""")

    override fun ordinalWords(n: Long, suffix: String, prev: String, next: String): String? {
        if (n > 999) return null
        val ok = prev in ARTICLES || prev in DATIVE || next.lowercase() in monthSet
        return if (ok) ordStem(n) + ending(prev) else null
    }

    override fun year(n: Long): String =
        if (n in 1100..1999) below100((n / 100).toInt()) + "hundert" + (if (n % 100 != 0L) below100((n % 100).toInt()) else "") else cardinal(n)

    override fun gender(prev: String, next: String, nextRaw: String): Gen = when {
        nextRaw.isNotEmpty() && nextRaw[0].isUpperCase() -> if (nextRaw in FEM) Gen.F else Gen.M
        else -> Gen.N
    }

    override fun contextual(n: Long, len: Int, prev: String, next: String, next2: String): String? {
        if (len == 4 && n in 1100..1999 && (prev in YEAR_CUES || prev in monthSet || next.isEmpty())) return year(n)
        return null
    }

    override fun time(h: Int, m: Int, ampm: Char?): String {
        val hw = if (h == 1) "ein" else cardinal(h.toLong())
        return if (m == 0) "$hw Uhr" else "$hw Uhr ${cardinal(m.toLong())}"
    }

    override fun duration(h: Int, m: Int, s: Int): String = joinList(
        listOfNotNull(
            h.takeIf { it > 0 }?.let { if (it == 1) "eine Stunde" else cardinal(it.toLong()) + " Stunden" },
            m.takeIf { it > 0 }?.let { if (it == 1) "eine Minute" else cardinal(it.toLong()) + " Minuten" },
            s.takeIf { it > 0 }?.let { if (it == 1) "eine Sekunde" else cardinal(it.toLong()) + " Sekunden" },
        ).ifEmpty { listOf("null Sekunden") },
        "und",
    )

    override fun date(d: Int, m: Int, y: Int?, prev: String): String =
        ordStem(d.toLong()) + ending(prev) + " " + months[m - 1] + (y?.let { " " + year(it.toLong()) } ?: "")

    override fun times(x: String, one: Boolean) = if (one) "einmal" else "${x}mal"
    override fun timesPrefix(x: String) = "mal $x"
    override fun moreThan(x: String) = "über $x"
    override fun fractionOf(a: String, b: String) = "$a von $b"
    override fun ratio(a: Long, b: Long) = cardinal(a) + " zu " + cardinal(b)
    override fun scaleWord(k: Long) = if (k >= 1_000_000) "Millionen" else "tausend"

    override fun currency(sym: String): Cur? = when (sym) {
        "€", "EUR" -> Cur("Euro", "Euro", "Cent", "Cent")
        "$", "US$", "USD" -> Cur("Dollar", "Dollar", "Cent", "Cent")
        "MX$", "MXN" -> Cur("Peso", "Pesos", "Centavo", "Centavos")
        "R$", "BRL" -> Cur("Real", "Reais", "Centavo", "Centavos")
        "¥", "￥", "円", "JPY" -> Cur("Yen", "Yen", "Sen", "Sen")
        "£", "GBP" -> Cur("Pfund", "Pfund", "Penny", "Pence")
        else -> null
    }

    override fun withCents(amount: String, c: Int, cur: Cur) = "$amount ${cardinal(c.toLong())}"

    override val units: Map<String, Measure> = buildMap {
        for (k in listOf("GB", "gb")) put(k, Measure("Gigabyte", "Gigabyte"))
        for (k in listOf("MB", "mb")) put(k, Measure("Megabyte", "Megabyte"))
        for (k in listOf("KB", "kB", "kb")) put(k, Measure("Kilobyte", "Kilobyte"))
        for (k in listOf("TB", "tb")) put(k, Measure("Terabyte", "Terabyte"))
        for (k in listOf("GHz", "ghz")) put(k, Measure("Gigahertz", "Gigahertz"))
        for (k in listOf("MHz", "mhz")) put(k, Measure("Megahertz", "Megahertz"))
        for (k in listOf("Hz", "hz")) put(k, Measure("Hertz", "Hertz"))
        for (k in listOf("FPS", "fps")) put(k, Measure("Bild pro Sekunde", "Bilder pro Sekunde"))
        put("ms", Measure("Millisekunde", "Millisekunden", Gen.F))
        for (k in listOf("h", "Std.", "Std")) put(k, Measure("Stunde", "Stunden", Gen.F))
        for (k in listOf("min", "Min.", "Min")) put(k, Measure("Minute", "Minuten", Gen.F))
        for (k in listOf("s", "Sek.", "Sek")) put(k, Measure("Sekunde", "Sekunden", Gen.F))
        for (k in listOf("°C", "ºC", "°")) put(k, Measure("Grad", "Grad"))
        put("mAh", Measure("Milliamperestunde", "Milliamperestunden", Gen.F))
        put("W", Measure("Watt", "Watt"))
        put("mm", Measure("Millimeter", "Millimeter"))
        put("cm", Measure("Zentimeter", "Zentimeter"))
        put("km", Measure("Kilometer", "Kilometer"))
        put("m", Measure("Meter", "Meter"))
        put("km/h", Measure("Kilometer pro Stunde", "Kilometer pro Stunde"))
        put("kg", Measure("Kilogramm", "Kilogramm"))
        put("g", Measure("Gramm", "Gramm"))
        put("px", Measure("Pixel", "Pixel"))
    }

    override val acronyms = mapOf("AAA" to "Triple-A", "PvP" to "=PVP", "PvE" to "=PVE", "GTA" to "=GTA", "USB" to "=USB", "CPU" to "=CPU", "GPU" to "=GPU")

    override val abbreviations = listOf(
        Abbr("""z\.[ \u00A0]?B\.""", "zum Beispiel"),
        Abbr("""usw\.""", "und so weiter", canEnd = true),
        Abbr("""bzw\.""", "beziehungsweise"),
        Abbr("""d\.[ \u00A0]?h\.""", "das heißt"),
        Abbr("""ca\.""", "circa"),
        Abbr("""Nr\.""", "Nummer"),
        Abbr("""Dr\.""", "Doktor"),
        Abbr("""[vV][sS]\.?""", "versus"),
        Abbr("""evtl\.""", "eventuell"),
        Abbr("""inkl\.""", "inklusive"),
        Abbr("""ggf\.""", "gegebenenfalls"),
        Abbr("""u\.[ \u00A0]?a\.""", "unter anderem", canEnd = true),
        Abbr("""etc\.""", "et cetera", canEnd = true),
        Abbr("""[lL][vV][lL]""", "Level"),
    )

    companion object {
        val UNITS = arrayOf(
            "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwölf", "dreizehn",
            "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn",
        )
        val TENS = arrayOf("", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig")
        val ARTICLES = setOf("der", "die", "das", "des", "ihr", "sein", "mein", "dein", "unser", "jeder", "jede", "jedes")
        val DATIVE = setOf("am", "im", "vom", "zum", "zur", "beim", "dem", "den", "ab", "bis", "seit")
        val YEAR_CUES = setOf("im", "jahr", "jahre", "seit", "von", "bis", "um", "ab", "anno", "vor", "nach")
        val FEM = setOf(
            "Stunde", "Minute", "Sekunde", "Woche", "Person", "Partie", "Runde", "Mission", "Version", "Karte", "Nacht", "Seite",
            "App", "Nachricht", "Datei", "Folge", "Staffel", "Welt", "Sache", "Aufgabe", "Belohnung", "Münze", "Waffe", "Stadt",
            "Frau", "Idee", "Aktualisierung", "Konsole", "Plattform", "Szene", "Serie", "Liga", "Million", "Milliarde",
        )
    }
}

/* ── Japonés (kanji, sin espacios) ── */

internal class JaSpeller(region: String?) : Speller(region) {
    override val lang = "ja"
    override val gap = ""
    override val listSep = "、"
    override val decimalSep = '.'
    override val comma = "点"
    override val dot = "点"
    override val percent = "パーセント"
    override val rangeWord = "から"
    override val between = ("" to "から")
    override val minus = "マイナス"
    override val plus = "プラス"
    override val and = "アンド"
    override val or = "または"
    override val equals = "イコール"
    override val at = "アット"
    override val number = "ナンバー"
    override val about = "約"
    override val degrees = "度"
    override val by = "かける"
    override val version = "バージョン"
    override val slash = "スラッシュ"
    override val dash = "ハイフン"
    override val underscore = "アンダーバー"
    override val months = Array(12) { cardinal(it + 1L) + "月" }
    override val letters = arrayOf(
        "エー", "ビー", "シー", "ディー", "イー", "エフ", "ジー", "エイチ", "アイ", "ジェー", "ケー", "エル", "エム", "エヌ", "オー", "ピー",
        "キュー", "アール", "エス", "ティー", "ユー", "ブイ", "ダブリュー", "エックス", "ワイ", "ゼット",
    )

    private fun below10000(n: Int): String {
        val sb = StringBuilder()
        val th = n / 1000
        val h = n / 100 % 10
        val t = n / 10 % 10
        val u = n % 10
        if (th > 0) sb.append(if (th == 1) "" else D[th]).append('千')
        if (h > 0) sb.append(if (h == 1) "" else D[h]).append('百')
        if (t > 0) sb.append(if (t == 1) "" else D[t]).append('十')
        if (u > 0) sb.append(D[u])
        return sb.toString()
    }

    override fun cardinal(n: Long, g: Gen): String {
        if (n < 0) return "マイナス" + cardinal(-n)
        if (n == 0L) return "ゼロ"
        val sb = StringBuilder()
        val cho = n / 1_000_000_000_000L
        val oku = (n / 100_000_000L) % 10000
        val man = (n / 10000) % 10000
        val r = n % 10000
        if (cho > 0) sb.append(below10000(cho.toInt())).append('兆')
        if (oku > 0) sb.append(below10000(oku.toInt())).append('億')
        if (man > 0) sb.append(if (man == 1L) "一" else below10000(man.toInt())).append('万')
        if (r > 0) sb.append(below10000(r.toInt()))
        return sb.toString()
    }

    override fun ordinal(n: Long, g: Gen) = "第" + cardinal(n)
    override fun frac(f: String) = digits(f)
    override fun digit(d: Int) = if (d == 0) "ゼロ" else D[d]
    override fun time(h: Int, m: Int, ampm: Char?) = cardinal(h.toLong()) + "時" + if (m > 0) cardinal(m.toLong()) + "分" else ""
    override fun duration(h: Int, m: Int, s: Int) =
        (if (h > 0) cardinal(h.toLong()) + "時間" else "") + (if (m > 0) cardinal(m.toLong()) + "分" else "") +
            (if (s > 0) cardinal(s.toLong()) + "秒" else "")
    override fun date(d: Int, m: Int, y: Int?, prev: String) =
        (y?.let { cardinal(it.toLong()) + "年" } ?: "") + cardinal(m.toLong()) + "月" + cardinal(d.toLong()) + "日"
    override fun times(x: String, one: Boolean) = x + "倍"
    override fun timesPrefix(x: String) = "かける$x"
    override fun moreThan(x: String) = x + "以上"
    override fun fractionOf(a: String, b: String) = b + "分の" + a
    override fun ratio(a: Long, b: Long) = cardinal(a) + "対" + cardinal(b)
    override fun scaleWord(k: Long) = if (k >= 1_000_000) "百万" else "千"
    override fun resP(n: Long) = cardinal(n) + "ピー"
    override fun kRes(n: Long) = cardinal(n) + "ケー"
    override fun network(n: Long) = cardinal(n) + "ジー"

    override fun currency(sym: String): Cur? = when (sym) {
        "¥", "￥", "円", "JPY" -> Cur("円", "円", "銭", "銭")
        "$", "US$", "USD" -> Cur("ドル", "ドル", "セント", "セント")
        "€", "EUR" -> Cur("ユーロ", "ユーロ", "セント", "セント")
        "£", "GBP" -> Cur("ポンド", "ポンド", "ペンス", "ペンス")
        "R$", "BRL" -> Cur("レアル", "レアル", "センターボ", "センターボ")
        "MX$", "MXN" -> Cur("ペソ", "ペソ", "センターボ", "センターボ")
        else -> null
    }

    override fun withCents(amount: String, c: Int, cur: Cur) = amount + cardinal(c.toLong()) + cur.centMany

    override val units: Map<String, Measure> = buildMap {
        fun u(k: String, w: String) = put(k, Measure(w, w))
        for (k in listOf("GB", "gb")) u(k, "ギガバイト")
        for (k in listOf("MB", "mb")) u(k, "メガバイト")
        for (k in listOf("KB", "kB", "kb")) u(k, "キロバイト")
        for (k in listOf("TB", "tb")) u(k, "テラバイト")
        for (k in listOf("GHz", "ghz")) u(k, "ギガヘルツ")
        for (k in listOf("MHz", "mhz")) u(k, "メガヘルツ")
        for (k in listOf("Hz", "hz")) u(k, "ヘルツ")
        for (k in listOf("FPS", "fps")) u(k, "エフピーエス")
        u("ms", "ミリ秒")
        u("h", "時間")
        u("min", "分")
        u("s", "秒")
        for (k in listOf("°C", "ºC", "°", "℃")) u(k, "度")
        u("mAh", "ミリアンペアアワー")
        u("W", "ワット")
        u("mm", "ミリ")
        u("cm", "センチ")
        u("km", "キロ")
        u("m", "メートル")
        u("km/h", "キロ毎時")
        u("kg", "キロ")
        u("g", "グラム")
        u("px", "ピクセル")
    }

    override val acronyms = emptyMap<String, String>()
    override val abbreviations = emptyList<Abbr>()

    companion object {
        val D = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    }
}
