package com.elyndra.launcher.ui.masha.lipsync

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Canales de las curvas (100 por segundo). */
object Ch {
    /** 0..13: labios, por ordinal de [Vis]. */
    val LIPS = Vis.COUNT
    /** Peso de `jawOpen` (ya en unidades del morph). */
    val JAW = LIPS
    /** Nivel de la voz 0..1 (para los gestos del cuerpo y el brillo). */
    val ENV = LIPS + 1
    /** Acento de cejas 0..1 (receta BrowUp). */
    val BROW = LIPS + 2
    /** Cabeceo (rad, + = barbilla abajo; − = cabeza arriba al preguntar). */
    val NOD = LIPS + 3
    val COUNT = LIPS + 4
}

/**
 * Curvas de una frase, listas para el render: [frames] tramas de 10 ms ×
 * [Ch.COUNT] canales en [data]; [blinks] = tramas donde parpadear (comas y
 * puntos). Inmutable: el hilo de la voz publica una nueva cada vez que sabe más.
 */
class Track(val frames: Int, val data: FloatArray, val blinks: IntArray, val audioFrames: Int, val complete: Boolean) {
    fun at(frame: Int, ch: Int): Float = data[frame * Ch.COUNT + ch]

    companion object {
        val EMPTY = Track(0, FloatArray(0), IntArray(0), 0, false)
    }
}

/** Formantes de las vocales de la voz actual; se afinan solos con lo que se alinea con texto. */
class VowelProfile {
    // Voz femenina, español (Hz): a, e, i, o, u.
    val f1 = floatArrayOf(850f, 520f, 330f, 540f, 360f)
    val f2 = floatArrayOf(1450f, 2150f, 2650f, 1050f, 900f)
    var updates = 0
        private set

    fun reset() {
        floatArrayOf(850f, 520f, 330f, 540f, 360f).copyInto(f1)
        floatArrayOf(1450f, 2150f, 2650f, 1050f, 900f).copyInto(f2)
        updates = 0
    }

    /** Aprende de los núcleos vocálicos de una frase ya alineada. */
    fun learn(segs: List<Seg>, f: AudioFeatures) {
        for (s in segs) {
            val v = when (s.ph) { Ph.A -> 0; Ph.E -> 1; Ph.I -> 2; Ph.O -> 3; Ph.U -> 4; else -> -1 }
            if (v < 0 || s.t1 - s.t0 < 0.06f) continue
            val a = ((s.t0 + 0.25f * (s.t1 - s.t0)) / Aligner.FRAME).toInt()
            val b = ((s.t1 - 0.25f * (s.t1 - s.t0)) / Aligner.FRAME).toInt().coerceAtMost(f.count)
            var s1 = 0f; var s2 = 0f; var c = 0
            for (k in a until b) if (f.f1[k] > 0f && f.f2[k] > 0f) { s1 += f.f1[k]; s2 += f.f2[k]; c++ }
            if (c < 2) continue
            val k = 0.1f
            f1[v] += (s1 / c - f1[v]) * k
            f2[v] += (s2 / c - f2[v]) * k
            updates++
        }
    }
}

/**
 * Coarticulación (dominancia de Cohen–Massaro, 1993) y reglas duras.
 *
 * Por canal (labios y mandíbula por separado) el valor es la media de los
 * objetivos de cada segmento pesada por su dominancia
 * D(t) = α·exp(−θ·distancia al segmento) (α dentro): los fonemas vecinos se
 * mezclan en vez de saltar. Las vocales redondeadas dominan antes (θ⁻ pequeño
 * → los labios se redondean ~170 ms antes). Los consonantes de lengua (t, n,
 * k, r…) casi no pesan en los labios: toman la forma de la vocal de al lado.
 * Después, reglas duras: P/B/M cierran los labios del todo (≥ 60 ms, mandíbula
 * arriba), F/V llevan el labio a los dientes, S/CH cierran la mandíbula.
 * Topes: nada se queda en 1,0 (vocales 0,55–0,7; solo el cierre de P llega a 0,9).
 */
object Coarticulator {

    private const val WINDOW = 0.6f
    /** Duración máxima de un cierre de P/B/M (s); el resto de un segmento más largo es reposo. */
    private const val MAX_CLOSURE = 0.11f
    /** Energía mínima (z respecto a la frase) de una tónica para llevar un acento pequeño de cejas. */
    private const val MINOR_Z = 0.1f

    /**
     * Rellena [out] ([frames] × [Ch.COUNT]) a partir de [segs] (ya alineados),
     * con las primeras [n] tramas de [f] para la energía. Añade silencio antes
     * y después. [words] y [question] dan las expresiones (cejas, cabeceos,
     * parpadeos), que van a [blinks].
     */
    fun render(
        aligned: List<Seg>,
        f: AudioFeatures,
        n: Int,
        frames: Int,
        cfg: LipSyncConfig,
        words: List<WordPhones>,
        question: Boolean,
        seed: Int,
        out: FloatArray,
        blinks: MutableList<Int>,
    ) {
        val segs = restInLongClosures(aligned)
        val ns = segs.size
        val thr = Aligner.voicedThreshold(f, n)
        // Estadística de la energía sonora (para JA/LI).
        var mean = 0.0; var sq = 0.0; var cnt = 0
        for (k in 0 until n) if (f.db[k] > thr) { mean += f.db[k]; sq += f.db[k] * f.db[k]; cnt++ }
        val mu = if (cnt > 0) (mean / cnt).toFloat() else -30f
        val sigma = if (cnt > 1) sqrt(max(0.0, sq / cnt - (mean / cnt) * (mean / cnt))).toFloat().coerceAtLeast(3f) else 6f
        // Ritmo: sílabas por segundo sonoro.
        val vowels = segs.count { it.ph?.vowel == true }
        val rate = if (cnt > 10) vowels / (cnt * Aligner.FRAME) else 0f
        val hypo = 1f - cfg.hypoArticulation * ((rate - cfg.fastSyllablesPerSecond) / 2f).coerceIn(0f, 1f)

        // Objetivos y dominancias por segmento (+ silencios en los extremos).
        val total = ns + 2
        val t0 = FloatArray(total); val t1 = FloatArray(total)
        val lipCh = IntArray(total) { -1 }; val lipV = FloatArray(total)
        val lipCh2 = IntArray(total) { -1 }; val lipV2 = FloatArray(total)
        val jawT = FloatArray(total)
        val la = FloatArray(total); val lPre = FloatArray(total); val lPost = FloatArray(total)
        val ja = FloatArray(total); val jTheta = FloatArray(total)
        val strength = FloatArray(total)
        val intensity = cfg.visemeIntensity
        fun capOf(v: Vis) = min(0.95f, v.cap * max(1f, intensity))
        for (i in 0 until total) {
            if (i == 0 || i == total - 1) {
                t0[i] = if (i == 0) -10f else (if (ns > 0) segs[ns - 1].t1 else 0f)
                t1[i] = if (i == 0) (if (ns > 0) segs[0].t0 else 0f) else 1e4f
                la[i] = 1f; lPre[i] = 12f; lPost[i] = 12f; ja[i] = 1f; jTheta[i] = 12f
                continue
            }
            val s = segs[i - 1]
            t0[i] = s.t0; t1[i] = s.t1
            val ph = s.ph
            if (ph == null) {
                la[i] = 0.8f; lPre[i] = 10f; lPost[i] = 10f; ja[i] = 0.8f; jTheta[i] = 10f
                continue
            }
            var li = 0.95f
            var jaAmt = 0.8f
            if (ph.vowel) {
                val ka = (s.t0 / Aligner.FRAME).toInt().coerceIn(0, max(0, n - 1))
                val kb = (s.t1 / Aligner.FRAME).toInt().coerceIn(ka + 1, max(ka + 1, n))
                var segDb = -120f
                for (k in ka until min(kb, n)) segDb = max(segDb, f.db[k])
                val z = if (segDb > -119f) (segDb - mu) / sigma else 0f
                jaAmt = (0.68f + 0.2f * z + if (s.stress) 0.12f else 0f).coerceIn(0.3f, 1f)
                li = (0.92f + 0.08f * z + if (s.stress) 0.08f else -0.04f).coerceIn(0.72f, 1.05f)
                strength[i] = if (s.stress) z else -9f
            }
            li *= hypo
            val v = ph.vis
            lipCh[i] = v.ordinal
            lipV[i] = min(capOf(v), ph.amount * v.cap * li * intensity)
            ph.vis2?.let { v2 ->
                lipCh2[i] = v2.ordinal
                lipV2[i] = min(capOf(v2), ph.amount2 * v2.cap * li * intensity)
            }
            jawT[i] = v.jaw * cfg.jawMax * jaAmt * cfg.jawAmount
            val rounded = v.rounded || (ph.vis2?.rounded == true && ph.amount2 >= 0.2f)
            var alpha = v.lipAlpha
            if (ph.cls == Cls.GLIDE) alpha *= 0.8f
            if (ph.vowel) alpha *= if (s.stress) 1.1f else 0.9f
            la[i] = alpha
            lPre[i] = if (rounded) min(v.thetaPre, cfg.roundThetaPre) else v.thetaPre
            lPost[i] = v.thetaPost
            ja[i] = v.jawAlpha
            // La mandíbula sube deprisa en las consonantes y vuelve a abrir en la vocal.
            jTheta[i] = if (ph.vowel) 14f else 28f
        }

        val lips = FloatArray(Ch.LIPS)
        var lo = 0
        for (k in 0 until frames) {
            val t = k * Aligner.FRAME
            while (lo < total - 1 && t1[lo] < t - WINDOW) lo++
            lips.fill(0f)
            var den = 0f
            var jawNum = 0f
            var jawDen = 0f
            var i = lo
            while (i < total && t0[i] <= t + WINDOW) {
                val d = if (t < t0[i]) t0[i] - t else if (t > t1[i]) t - t1[i] else 0f
                val pre = t < t0[i]
                val dl = la[i] * exp(-(if (pre) lPre[i] else lPost[i]) * d)
                val dj = ja[i] * exp(-jTheta[i] * d)
                den += dl
                if (lipCh[i] >= 0) lips[lipCh[i]] += dl * lipV[i]
                if (lipCh2[i] >= 0) lips[lipCh2[i]] += dl * lipV2[i]
                jawNum += dj * jawT[i]
                jawDen += dj
                i++
            }
            val o = k * Ch.COUNT
            for (c in 0 until Ch.LIPS) out[o + c] = if (den > 1e-6f) lips[c] / den else 0f
            out[o + Ch.JAW] = if (jawDen > 1e-6f) jawNum / jawDen else 0f
            out[o + Ch.ENV] = if (k < n) ((f.db[k] + 45f) / 35f).coerceIn(0f, 1f) else 0f
            out[o + Ch.BROW] = 0f
            out[o + Ch.NOD] = 0f
        }

        // Reglas duras.
        val ramp = 0.03f
        for (s in segs) {
            val ph = s.ph ?: continue
            val dur = s.t1 - s.t0
            when {
                ph.closure -> {
                    val h = max(cfg.closureMinMs / 2000f, 0.4f * dur)
                    val closed = min(0.9f, Vis.PP.cap * max(1f, intensity))
                    constraint(out, frames, s.mid, h, ramp) { o, w ->
                        for (c in 0 until Ch.LIPS) if (c != Vis.PP.ordinal) out[o + c] *= 1f - 0.85f * w
                        out[o + Vis.PP.ordinal] = max(out[o + Vis.PP.ordinal], closed * w)
                        out[o + Ch.JAW] *= 1f - w
                    }
                }
                ph.labiodental -> {
                    val h = max(0.025f, 0.35f * dur)
                    val target = min(capOf(Vis.FF), Vis.FF.cap * 0.95f * ph.amount * intensity)
                    val jawLimit = 0.1f * cfg.jawMax
                    constraint(out, frames, s.mid, h, ramp) { o, w ->
                        for (c in 0 until Ch.LIPS) if (c != Vis.FF.ordinal) out[o + c] *= 1f - 0.5f * w
                        out[o + Vis.FF.ordinal] = max(out[o + Vis.FF.ordinal], target * w)
                        val j = out[o + Ch.JAW]
                        out[o + Ch.JAW] = min(j, j + (jawLimit - j) * w)
                    }
                }
                ph.vis == Vis.SS || ph.vis == Vis.CH -> {
                    val h = max(0.02f, 0.3f * dur)
                    val jawLimit = 0.12f * cfg.jawMax
                    constraint(out, frames, s.mid, h, ramp) { o, w ->
                        val j = out[o + Ch.JAW]
                        out[o + Ch.JAW] = min(j, j + (jawLimit - j) * 0.7f * w)
                    }
                }
            }
        }

        expressions(segs, words, question, strength, f, n, frames, cfg, seed, out, blinks)
    }

    /**
     * Un P/B/M que el alineado estira sobre una pausa (sin rangos de palabra, el
     * silencio es la "caída de energía" más profunda) se quedaba con los labios
     * apretados cientos de ms. El cierre real dura [MAX_CLOSURE] como mucho, pegado
     * a la vocal de al lado; el resto de la pausa es reposo (labios juntos, sin apretar).
     */
    private fun restInLongClosures(segs: List<Seg>): List<Seg> {
        if (segs.none { it.ph?.closure == true && it.t1 - it.t0 > MAX_CLOSURE + 0.04f }) return segs
        val out = ArrayList<Seg>(segs.size + 4)
        for ((i, s) in segs.withIndex()) {
            val ph = s.ph
            if (ph == null || !ph.closure || s.t1 - s.t0 <= MAX_CLOSURE + 0.04f) {
                out += s
                continue
            }
            val nextVowel = segs.getOrNull(i + 1)?.ph?.vowel == true
            val prevVowel = segs.getOrNull(i - 1)?.ph?.vowel == true
            if (nextVowel || !prevVowel) {
                out += Seg(null, false, s.t0, s.t1 - MAX_CLOSURE, s.word)
                out += Seg(ph, s.stress, s.t1 - MAX_CLOSURE, s.t1, s.word)
            } else {
                out += Seg(ph, s.stress, s.t0, s.t0 + MAX_CLOSURE, s.word)
                out += Seg(null, false, s.t0 + MAX_CLOSURE, s.t1, s.word)
            }
        }
        return out
    }

    /** Aplica [body] a las tramas cerca de [center] con peso 1 en ±[half] y rampa suave de [ramp] s. */
    private inline fun constraint(out: FloatArray, frames: Int, center: Float, half: Float, ramp: Float, body: (Int, Float) -> Unit) {
        val a = ((center - half - ramp) / Aligner.FRAME).toInt().coerceAtLeast(0)
        val b = ((center + half + ramp) / Aligner.FRAME).toInt() + 1
        for (k in a until min(b, frames)) {
            val d = abs(k * Aligner.FRAME - center)
            val w = if (d <= half) 1f else smooth(1f - (d - half) / ramp)
            if (w > 0f) body(k * Ch.COUNT, w)
        }
    }

    private fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /* ── expresión al hablar ── */

    /** Campana de coseno alzado: sube de [a] a [peak] y baja hasta [b]. */
    private fun bump(t: Float, a: Float, peak: Float, b: Float): Float = when {
        t <= a || t >= b -> 0f
        t < peak -> (0.5f - 0.5f * cos(PI.toFloat() * (t - a) / (peak - a)))
        else -> (0.5f + 0.5f * cos(PI.toFloat() * (t - peak) / (b - peak)))
    }

    private fun expressions(
        segs: List<Seg>, words: List<WordPhones>, question: Boolean, strength: FloatArray, f: AudioFeatures, n: Int,
        frames: Int, cfg: LipSyncConfig, seed: Int, out: FloatArray, blinks: MutableList<Int>,
    ) {
        val ex = cfg.expressionIntensity
        if (segs.isEmpty()) return
        // Acentos: vocal tónica de palabra con contenido y energía por encima de la media.
        // Las tónicas que no llegan a acento fuerte quedan como candidatas a uno pequeño.
        data class Accent(val t: Float, val s: Float)
        val cands = ArrayList<Accent>()
        val minorCands = ArrayList<Accent>()
        for (i in segs.indices) {
            val s = segs[i]
            if (s.ph?.vowel != true || !s.stress || s.word < 0) continue
            val w = words.getOrNull(s.word) ?: continue
            val content = w.token.text.length >= 4 || w.phones.count { it.ph.vowel } >= 2
            val z = strength[i + 1]
            val bang = w.token.pause == Pause.EXCLAIM
            if (!content && !bang) continue
            if (z < 0.6f && !bang) {
                if (z >= MINOR_Z) minorCands += Accent(s.mid, (0.5f + 0.4f * z).coerceIn(0.4f, 1f))
                continue
            }
            cands += Accent(s.mid, if (bang) max(0.7f, (0.4f + 0.4f * z).coerceIn(0.4f, 1f)) else (0.4f + 0.4f * z).coerceIn(0.4f, 1f))
        }
        val accepted = ArrayList<Accent>()
        for (c in cands.sortedByDescending { it.s }) {
            if (accepted.none { abs(it.t - c.t) < cfg.accentGap }) accepted += c else minorCands += c.copy(s = 0.7f)
        }
        val nod = cfg.nodDeg * (PI.toFloat() / 180f) * ex
        for (a in accepted) {
            paint(out, frames, a.t - 0.12f, a.t + 0.45f) { t -> Ch.BROW to cfg.browAccent * ex * a.s * bump(t, a.t - 0.12f, a.t + 0.05f, a.t + 0.45f) }
            paint(out, frames, a.t - 0.05f, a.t + 0.42f) { t -> Ch.NOD to nod * a.s * bump(t, a.t - 0.05f, a.t + 0.12f, a.t + 0.42f) }
        }
        // Acentos pequeños (solo cejas): el resto de tónicas con algo de énfasis, separadas entre sí y de los fuertes.
        if (cfg.minorBrowAccent > 0f) {
            val minors = ArrayList<Accent>()
            for (c in minorCands.sortedByDescending { it.s }) {
                if (accepted.any { abs(it.t - c.t) < cfg.minorAccentGap } || minors.any { abs(it.t - c.t) < cfg.minorAccentGap }) continue
                minors += c
            }
            for (a in minors) {
                paint(out, frames, a.t - 0.1f, a.t + 0.32f) { t -> Ch.BROW to cfg.minorBrowAccent * ex * a.s * bump(t, a.t - 0.1f, a.t + 0.04f, a.t + 0.32f) }
            }
        }
        val lastWord = segs.lastOrNull { it.word >= 0 }?.word ?: return
        val lastSegs = segs.filter { it.word == lastWord }
        val wordStart = lastSegs.first().t0
        val end = segs.last().t1
        if (question) {
            // Pregunta: la cabeza sube y las cejas se alzan en la última palabra, y vuelven despacio.
            val lift = cfg.questionLiftDeg * (PI.toFloat() / 180f) * ex
            paint(out, frames, wordStart - 0.15f, end + 0.6f) { t ->
                val up = smooth((t - (wordStart - 0.15f)) / 0.4f) * (1f - smooth((t - end - 0.1f) / 0.5f))
                Ch.NOD to -lift * up
            }
            paint(out, frames, wordStart - 0.15f, end + 0.6f) { t ->
                val up = smooth((t - (wordStart - 0.15f)) / 0.4f) * (1f - smooth((t - end - 0.1f) / 0.5f))
                Ch.BROW to 0.35f * ex * up
            }
        } else if (accepted.none { it.t > wordStart - 0.2f }) {
            // Final afirmativo: un cabeceo pequeño en la última tónica.
            val tonic = lastSegs.firstOrNull { it.stress && it.ph?.vowel == true } ?: lastSegs.first()
            val c = tonic.mid
            paint(out, frames, c - 0.05f, c + 0.45f) { t -> Ch.NOD to 0.5f * nod * bump(t, c - 0.05f, c + 0.15f, c + 0.45f) }
        }
        // Parpadeos en comas y puntos (no en la última palabra), con probabilidad fija por frase.
        for (w in 0 until lastWord) {
            val p = words.getOrNull(w)?.token?.pause ?: continue
            if (p == Pause.NONE) continue
            if (hash01(seed, w) >= cfg.punctuationBlink) continue
            val wEnd = segs.lastOrNull { it.word == w }?.t1 ?: continue
            val k = (wEnd / Aligner.FRAME).toInt()
            if (k in 0 until frames) blinks += k
        }
    }

    private inline fun paint(out: FloatArray, frames: Int, a: Float, b: Float, value: (Float) -> Pair<Int, Float>) {
        val ka = (a / Aligner.FRAME).toInt().coerceAtLeast(0)
        val kb = min(frames, (b / Aligner.FRAME).toInt() + 1)
        for (k in ka until kb) {
            val (ch, v) = value(k * Aligner.FRAME)
            out[k * Ch.COUNT + ch] += v
        }
    }

    /** Número pseudoaleatorio 0..1 determinista (mismo resultado en cada reconstrucción). */
    fun hash01(seed: Int, i: Int): Float {
        var h = seed * 0x27d4eb2d + i * 0x165667b1
        h = h xor (h ushr 15)
        h *= 0x2c1b3c6d
        h = h xor (h ushr 12)
        return (h ushr 8).toFloat() / (1 shl 24)
    }
}

/**
 * Repuesto B: solo audio (japonés, texto sin G2P). No es "abrir y cerrar con
 * el volumen": la vocal sale de los formantes (F1/F2 contra el perfil de la
 * voz, idea de uLipSync —MIT— hecha aquí con LPC), la mandíbula de la apertura
 * de esa vocal × energía, S/CH de la alta frecuencia y los cierres de labios
 * (P/B/M) de los valles cortos de energía dentro del habla. Después, un
 * suavizado de fase cero (ida y vuelta) que además anticipa un poco.
 */
object AudioOnly {

    private val VOWELS = arrayOf(Vis.AA, Vis.E, Vis.I, Vis.O, Vis.U)

    fun render(f: AudioFeatures, n: Int, frames: Int, cfg: LipSyncConfig, profile: VowelProfile, out: FloatArray) {
        if (frames <= 0) return
        val thr = Aligner.voicedThreshold(f, n)
        val peak = thr + 32f
        val post = FloatArray(5)
        val lastPost = floatArrayOf(0.5f, 0.5f, 0f, 0f, 0f)
        val intensity = cfg.visemeIntensity
        for (k in 0 until frames) {
            val o = k * Ch.COUNT
            for (c in 0 until Ch.COUNT) out[o + c] = 0f
            if (k >= n) continue
            val en = ((f.db[k] - (peak - 30f)) / 30f).coerceIn(0f, 1f)
            out[o + Ch.ENV] = ((f.db[k] + 45f) / 35f).coerceIn(0f, 1f)
            if (f.db[k] <= thr) continue
            val hfn = (f.hf[k] / 0.3f).coerceIn(0f, 1f)
            if (f.f1[k] > 0f && f.f2[k] > 0f) {
                val l1 = ln(f.f1[k]); val l2 = ln(f.f2[k])
                var sum = 0f
                for (v in 0..4) {
                    val d1 = (l1 - ln(profile.f1[v])) / 0.2f
                    val d2 = (l2 - ln(profile.f2[v])) / 0.25f
                    post[v] = exp(-0.5f * (d1 * d1 + d2 * d2))
                    sum += post[v]
                }
                if (sum > 1e-4f) for (v in 0..4) { post[v] /= sum; lastPost[v] = post[v] } else lastPost.copyInto(post)
            } else {
                lastPost.copyInto(post)
            }
            val vowelness = 1f - hfn
            var jaw = 0f
            for (v in 0..4) {
                val vis = VOWELS[v]
                out[o + vis.ordinal] = min(vis.cap, post[v] * vis.cap * (0.55f + 0.45f * en) * vowelness * intensity)
                jaw += post[v] * vis.jaw
            }
            out[o + Ch.JAW] = jaw * cfg.jawMax * cfg.jawAmount * (0.3f + 0.7f * en) * vowelness
            if (hfn > 0.6f) out[o + Vis.SS.ordinal] = Vis.SS.cap * 0.8f * ((hfn - 0.6f) / 0.4f).coerceIn(0f, 1f) * intensity
        }
        // Cierres: valle ≥ 10 dB por debajo de los máximos a ambos lados (±80 ms), dentro del habla.
        for (k in 3 until n - 3) {
            if (f.db[k] > thr + 20f) continue
            var left = -120f; var right = -120f
            for (d in 2..8) {
                if (k - d >= 0) left = max(left, f.db[k - d])
                if (k + d < n) right = max(right, f.db[k + d])
            }
            if (left < thr + 6f || right < thr + 6f) continue
            if (min(left, right) - f.db[k] < 10f) continue
            if (f.db[k] > f.db[k - 1] || f.db[k] > f.db[k + 1]) continue
            for (d in -2..2) {
                val kk = k + d
                if (kk !in 0 until frames) continue
                val w = if (abs(d) <= 1) 1f else 0.5f
                val o = kk * Ch.COUNT
                for (c in 0 until Ch.LIPS) if (c != Vis.PP.ordinal) out[o + c] *= 1f - 0.8f * w
                out[o + Vis.PP.ordinal] = max(out[o + Vis.PP.ordinal], 0.75f * w * Vis.PP.cap)
                out[o + Ch.JAW] *= 1f - w
            }
        }
        // Suavizado de fase cero (τ ≈ 35 ms): sin saltos y sin retraso.
        val a = 1f - exp(-Aligner.FRAME / 0.035f)
        for (c in 0..Ch.JAW) {
            var y = out[c]
            for (k in 0 until frames) { y += (out[k * Ch.COUNT + c] - y) * a; out[k * Ch.COUNT + c] = y }
            y = out[(frames - 1).coerceAtLeast(0) * Ch.COUNT + c]
            for (k in frames - 1 downTo 0) { y += (out[k * Ch.COUNT + c] - y) * a; out[k * Ch.COUNT + c] = y }
        }
    }
}
