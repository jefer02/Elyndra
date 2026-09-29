package com.elyndra.launcher.ui.masha.lipsync

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Una palabra con sus fonemas y (si el motor lo dio) la muestra donde empieza. */
class WordPhones(val token: Token, val phones: List<Phone>) {
    /** Muestra (desde el inicio del audio de la frase) donde empieza, o −1. */
    var rangeSample = -1L

    /** Duración propia (unidades de [Cls.weight]; las tónicas pesan más). */
    val weight: Float = phones.sumOf { p -> (p.ph.cls.weight * if (p.stress) 1.3f else 1f).toDouble() }.toFloat().coerceAtLeast(0.3f)
}

/** Un fonema colocado en el tiempo (s desde el inicio del audio). [ph] null = silencio. */
class Seg(val ph: Ph?, val stress: Boolean, var t0: Float, var t1: Float, val word: Int) {
    val mid: Float get() = 0.5f * (t0 + t1)
    override fun toString() = "${ph ?: "sil"}${if (stress) "'" else ""}[${"%.3f".format(t0)},${"%.3f".format(t1)})"
}

/**
 * Coloca los fonemas en el audio (tramas de 10 ms de [AudioFeatures]).
 *
 * 1. Ventana de cada palabra: desde su `onRangeStart` hasta la siguiente
 *    (primario). Palabras sin rango entre dos con rango: repartidas por peso.
 *    Sin ningún rango (repuesto A): las palabras se reparten por peso sobre los
 *    tramos sonoros, con las pausas largas donde hay puntuación.
 * 2. Se recorta el silencio del final (y algo del principio) de cada ventana.
 * 3. Dentro de la ventana, programación dinámica monótona: vocales en los
 *    picos de energía, oclusivas (P/B/M…) en los valles, fricativas donde hay
 *    alta frecuencia, con un coste por alejarse de la duración esperada.
 * 4. Huecos ≥ 80 ms entre palabras = silencio; los menores se absorben.
 */
object Aligner {

    const val FRAME = 0.01f
    private const val PAUSE_FRAMES = 8
    private const val LAMBDA = 1.2f

    /** Umbral de sonoridad (dBFS): pico − 32 dB, nunca por debajo de −60. */
    fun voicedThreshold(f: AudioFeatures, n: Int): Float {
        var peak = -120f
        // Pico robusto: máximo de la media de 3 tramas.
        for (k in 1 until n - 1) peak = max(peak, (f.db[k - 1] + f.db[k] + f.db[k + 1]) / 3f)
        if (n in 1..2) peak = f.db[0]
        return max(peak - 32f, -60f)
    }

    /**
     * Alinea [words] con las [n] primeras tramas de [f]. [done] = el motor ya
     * terminó la frase (si no, la última palabra aún puede crecer).
     * [secPerWeight]: ritmo supuesto (s por unidad de peso) para lo que aún no
     * tiene audio. Devuelve los segmentos (sin silencios de los extremos).
     */
    fun align(words: List<WordPhones>, f: AudioFeatures, n: Int, done: Boolean, secPerWeight: Float): List<Seg> {
        val out = ArrayList<Seg>()
        if (words.isEmpty() || n <= 0) return out
        val thr = voicedThreshold(f, n)
        val peak = thr + 32f
        val windows = if (words.any { it.rangeSample >= 0 }) {
            rangeWindows(words, f, n, done, secPerWeight)
        } else {
            voicedWindows(words, f, n, thr, done, secPerWeight)
        }
        var lastEnd = -1f
        var lastWord = -1
        for (w in words.indices) {
            val ws = windows[2 * w]
            val we = windows[2 * w + 1]
            if (ws.isNaN() || we <= ws) continue
            // Recorte del silencio: final entero, principio hasta un 30 %.
            var a = ws
            var b = we
            val ka = floor(ws).toInt().coerceIn(0, n - 1)
            val kb = ceil(we).toInt().coerceIn(ka + 1, n)
            var lastVoiced = -1
            var firstVoiced = -1
            for (k in ka until kb) if (f.db[k] > thr) { if (firstVoiced < 0) firstVoiced = k; lastVoiced = k }
            if (lastVoiced >= 0) {
                b = min(we, lastVoiced + 1.5f)
                if (firstVoiced - ws < 0.3f * (we - ws)) a = max(ws, firstVoiced - 0.5f)
            }
            if (b - a < 2f) b = min(we, a + 2f)
            // Hueco con la palabra anterior: silencio si es largo, si no, se absorbe.
            if (lastWord >= 0 && out.isNotEmpty()) {
                if (a - lastEnd >= PAUSE_FRAMES) {
                    out += Seg(null, false, lastEnd * FRAME, a * FRAME, -1)
                } else {
                    out.last().t1 = a * FRAME
                }
            }
            placePhones(words[w], w, a, b, f, peak, out)
            lastEnd = b
            lastWord = w
        }
        return out
    }

    /** Ventanas [inicio, fin) en tramas (flotantes) a partir de los rangos del motor. NaN = aún no. */
    private fun rangeWindows(words: List<WordPhones>, f: AudioFeatures, n: Int, done: Boolean, spw: Float): FloatArray {
        val m = words.size
        val start = FloatArray(m) { Float.NaN }
        // Anclas válidas (monótonas y dentro del audio conocido).
        var prev = -1f
        for (w in 0 until m) {
            val r = words[w].rangeSample
            if (r < 0) continue
            val fr = r.toFloat() / f.hop
            if (fr < prev || fr > n + 1) continue
            start[w] = fr
            prev = fr
        }
        val firstAnchor = start.indexOfFirst { !it.isNaN() }
        // Antes del primer ancla: desde 0, por peso.
        if (firstAnchor > 0) fill(words, start, 0, firstAnchor, 0f, start[firstAnchor])
        // Entre anclas: por peso.
        var w = firstAnchor
        while (w in 0 until m) {
            var next = w + 1
            while (next < m && start[next].isNaN()) next++
            if (next < m) {
                if (next > w + 1) fill(words, start, w, next, start[w], start[next], includeFirst = false)
                w = next
            } else break
        }
        val lastAnchor = w
        val out = FloatArray(2 * m) { Float.NaN }
        for (i in 0 until m) {
            if (start[i].isNaN()) continue
            out[2 * i] = start[i]
            var j = i + 1
            while (j < m && start[j].isNaN()) j++
            out[2 * i + 1] = if (j < m) start[j] else Float.NaN
        }
        // Tras el último ancla: con la frase terminada, hasta el final del audio (repartido);
        // si no, solo la palabra anclada, con su duración esperada (acotada al audio que hay).
        if (lastAnchor >= 0) {
            if (done) {
                if (lastAnchor < m - 1) {
                    fill(words, start, lastAnchor, m, start[lastAnchor], n.toFloat(), includeFirst = false)
                    for (i in lastAnchor until m) {
                        out[2 * i] = start[i]
                        out[2 * i + 1] = if (i + 1 < m) start[i + 1] else n.toFloat()
                    }
                } else {
                    out[2 * lastAnchor + 1] = n.toFloat()
                }
            } else {
                val expected = words[lastAnchor].weight * spw / FRAME * 1.4f
                out[2 * lastAnchor + 1] = min(n.toFloat(), start[lastAnchor] + expected)
            }
        }
        return out
    }

    /** Reparte por peso las palabras [from, to) entre las tramas [a, b); [includeFirst] = asigna también la primera. */
    private fun fill(words: List<WordPhones>, start: FloatArray, from: Int, to: Int, a: Float, b: Float, includeFirst: Boolean = true) {
        var total = 0f
        for (i in from until to) total += words[i].weight
        var acc = 0f
        for (i in from until to) {
            if (i > from || includeFirst) start[i] = a + (b - a) * acc / total
            acc += words[i].weight
        }
    }

    /** Repuesto A: sin rangos. Palabras por peso sobre el eje de tramas sonoras. */
    private fun voicedWindows(words: List<WordPhones>, f: AudioFeatures, n: Int, thr: Float, done: Boolean, spw: Float): FloatArray {
        val m = words.size
        val out = FloatArray(2 * m) { Float.NaN }
        // Tramos sonoros, uniendo huecos < 120 ms.
        val runs = ArrayList<IntArray>()
        var k = 0
        while (k < n) {
            if (f.db[k] <= thr) { k++; continue }
            var e = k
            while (e < n && f.db[e] > thr) e++
            if (runs.isNotEmpty() && k - runs.last()[1] < 12) runs.last()[1] = e else runs += intArrayOf(k, e)
            k = e
        }
        if (runs.isEmpty()) return out
        val voiced = runs.sumOf { it[1] - it[0] }.toFloat()
        var totalW = 0f
        for (wp in words) totalW += wp.weight
        // Eje total: lo sonoro si ya terminó; si no, lo que se espera (≥ lo que hay).
        val axis = if (done) voiced else max(voiced, totalW * spw / FRAME * 0.8f)
        fun toFrame(v: Float): Float {
            var left = v
            for (r in runs) {
                val len = (r[1] - r[0]).toFloat()
                if (left <= len) return r[0] + left
                left -= len
            }
            return Float.NaN
        }
        var acc = 0f
        for (w in 0 until m) {
            val s = toFrame(axis * acc / totalW)
            acc += words[w].weight
            var e = toFrame(min(axis * acc / totalW, voiced))
            if (s.isNaN()) break
            if (e.isNaN() || e <= s) e = min(n.toFloat(), s + 2f)
            // Una palabra no cruza una pausa larga: si su tramo acaba antes, se corta ahí.
            val run = runs.firstOrNull { s >= it[0] && s < it[1] }
            if (run != null && e > run[1] && words[w].token.pause != Pause.NONE) e = run[1].toFloat()
            out[2 * w] = s
            out[2 * w + 1] = e
        }
        return out
    }

    /* ── fonemas dentro de la palabra ── */

    private const val C_VOW = 0
    private const val C_STOP = 1
    private const val C_NAS = 2
    private const val C_SIB = 3
    private const val C_WFRIC = 4
    private const val C_APPROX = 5
    private const val NC = 6

    private fun costClass(ph: Ph): Int = when (ph.cls) {
        Cls.VOWEL -> C_VOW
        Cls.STOP -> C_STOP
        Cls.NASAL -> C_NAS
        Cls.FRIC, Cls.AFFR -> if (ph.vis == Vis.SS || ph.vis == Vis.CH) C_SIB else C_WFRIC
        else -> C_APPROX
    }

    private fun frameCost(c: Int, en: Float, hfn: Float): Float = when (c) {
        C_VOW -> (1f - en) + 0.6f * hfn
        C_STOP -> en
        C_NAS -> abs(en - 0.55f) + 0.5f * hfn
        C_SIB -> 1f - hfn
        C_WFRIC -> 0.5f * (1f - hfn) + 0.3f * abs(en - 0.4f)
        else -> abs(en - 0.75f) + 0.3f * hfn
    }

    private fun placePhones(word: WordPhones, index: Int, a: Float, b: Float, f: AudioFeatures, peak: Float, out: MutableList<Seg>) {
        val ph = word.phones
        val np = ph.size
        if (np == 0) return
        val w = FloatArray(np) { i -> ph[i].ph.cls.weight * if (ph[i].stress) 1.3f else 1f }
        val total = w.sum()
        val ia = floor(a).toInt()
        val ib = ceil(b).toInt().coerceAtMost(f.count)
        val len = ib - ia
        if (len < 2 * np + 1 || ia < 0) {
            // Poco sitio: proporcional al peso.
            var t = a
            for (i in 0 until np) {
                val d = (b - a) * w[i] / total
                out += Seg(ph[i].ph, ph[i].stress, t * FRAME, (t + d) * FRAME, index)
                t += d
            }
            return
        }
        // Costes acumulados por clase.
        val pre = Array(NC) { FloatArray(len + 1) }
        for (k in 0 until len) {
            val fr = ia + k
            val en = ((f.db[fr] - (peak - 40f)) / 40f).coerceIn(0f, 1f)
            val hfn = (f.hf[fr] / 0.3f).coerceIn(0f, 1f)
            for (c in 0 until NC) pre[c][k + 1] = pre[c][k] + frameCost(c, en, hfn)
        }
        val cls = IntArray(np) { costClass(ph[it].ph) }
        val expect = FloatArray(np) { len * w[it] / total }
        val inf = Float.MAX_VALUE / 4
        // best[i][y]: fonemas 0..i ocupando [0, y); back = inicio del fonema i.
        val best = Array(np) { FloatArray(len + 1) { inf } }
        val back = Array(np) { IntArray(len + 1) }
        for (i in 0 until np) {
            val minY = i + 1
            val maxY = len - (np - 1 - i)
            for (y in minY..maxY) {
                val fromX = if (i == 0) 0 else i
                val toX = if (i == 0) 0 else y - 1
                var bestV = inf
                var bestX = fromX
                for (x in fromX..toX) {
                    val before = if (i == 0) 0f else best[i - 1][x]
                    if (before >= inf) continue
                    val d = (y - x).toFloat()
                    val r = ln(d / expect[i])
                    val v = before + pre[cls[i]][y] - pre[cls[i]][x] + LAMBDA * r * r
                    if (v < bestV) { bestV = v; bestX = x }
                }
                best[i][y] = bestV
                back[i][y] = bestX
            }
        }
        val bounds = IntArray(np + 1)
        bounds[np] = len
        for (i in np - 1 downTo 0) bounds[i] = if (i == 0) 0 else back[i][bounds[i + 1]]
        // Los extremos respetan los valores fraccionarios de la ventana.
        for (i in 0 until np) {
            val t0 = if (i == 0) a else (ia + bounds[i]).toFloat()
            val t1 = if (i == np - 1) b else (ia + bounds[i + 1]).toFloat()
            out += Seg(ph[i].ph, ph[i].stress, t0 * FRAME, max(t1, t0 + 0.5f) * FRAME, index)
        }
    }
}
