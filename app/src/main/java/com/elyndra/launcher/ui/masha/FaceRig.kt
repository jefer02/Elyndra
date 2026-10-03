package com.elyndra.launcher.ui.masha

import com.elyndra.launcher.ui.masha.lipsync.Vis
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/*
 * La cara de Masha sin Filament (se prueba en la JVM): canales semánticos
 * (visemas, sonrisa, cejas, parpadeo…) → pesos de los morph targets del
 * modelo cargado, el parpadeo y las constantes de mirada.
 *
 * Contrato: `face_contract.json` del equipo del modelo (v2: 47 morphs en
 * `Masha_Head`, visemas solo de labios y reglas de `mouthClose`, bloque
 * `lipsync`; ver `docs/MASHA_LIPSYNC.md`). Se resuelve todo por nombre, así que
 * el GLB anterior (31 morphs) sigue funcionando.
 */

/**
 * Lo que la cara expresa en este fotograma. El habla (labios, mandíbula) la
 * pone [LipSync]; la expresión (ánimo, atención, microexpresiones, cejas del
 * habla, párpados), [FaceExpression]. [FaceMorphs.write] lo traduce a morphs.
 */
internal class FaceChannels {
    /** Visemas de labios ya coarticulados y suavizados, por ordinal de [Vis] (sin mandíbula). */
    val lips = FloatArray(Vis.COUNT)
    /** Mandíbula del habla, en peso de `jawOpen`. */
    var jaw = 0f
    /** Expresión, en pesos de morph por canal de [Ex] (antes de ceder ante el habla). */
    val expr = FloatArray(Ex.COUNT)
    /** Párpado superior: parpadeo + [Ex.LID] + acoplamiento a la mirada (0 abierto, 1 cerrado). */
    var blinkL = 0f
    var blinkR = 0f

    fun clear() {
        lips.fill(0f)
        jaw = 0f
        expr.fill(0f)
        blinkL = 0f; blinkR = 0f
    }
}

/**
 * Capa de traducción de canales a morphs. Resuelve los índices por nombre una
 * vez ([forNames]); si falta un morph, su índice es −1 y se ignora (o se
 * sustituye, ver [V2]). Admite el modelo v2 (ARKit/visemas, con o sin los
 * visemas nuevos del contrato de lip-sync) y el antiguo (`MouthOpen`, `V_AA`…).
 * [write] no crea objetos.
 */
internal abstract class FaceMorphs(val names: List<String>) {

    protected fun i(name: String) = names.indexOf(name)

    /** Cuánto baja la sonrisa del ánimo en O/U/P/F (`LipSyncConfig.smileRoundingCut`). */
    var smileCut = 0.65f

    /**
     * Morphs de expresión activos a la vez como mucho (sin contar parpadeo ni boca: esos
     * tienen su propio tope). Por encima, se resta el siguiente en fuerza y se reescala, como
     * con los visemas: continuo, sin saltos. Sin límite por defecto; el rig lo pone según la calidad.
     */
    var exprBudget = Int.MAX_VALUE

    /** Escribe en [out] (tamaño = nº de morphs) los pesos para [c]. */
    abstract fun write(c: FaceChannels, out: FloatArray)

    /** ¿Hay algo que mover? */
    abstract val usable: Boolean

    companion object {
        fun forNames(names: List<String>): FaceMorphs =
            if ("jawOpen" in names || "viseme_aa" in names) V2(names) else Legacy(names)

        /** Máximo de jawOpen en el habla (1.0 es la apertura máxima natural; el habla usa 0.15–0.5). */
        const val JAW_MAX = 0.55f
        /** La suma de los visemas de labios no pasa de esto (cada forma rebasada deshace el cierre de reposo). */
        const val LIP_SUM_MAX = 1.0f
        /** Suma de todas las formas de boca con el GLB anterior (contrato v1: ≤ ~1.1). */
        const val MOUTH_SUM_MAX = 1.1f
        /** Contrato v2: máximo recomendado de jawOpen. */
        const val JAW_MAX_V2 = 0.6f
        /** Contrato v2 (`rest_closure_rule`): mouthClose que devuelve un cierre de reposo (0,15 / 0,69). */
        const val REST_FIX_PER_UNIT = 0.15f / 0.69f
        /** Contrato v2: restUndo de jawOpen (las formas absolutas de labios valen 1; jawOpen, su ganancia 0,6). */
        const val JAW_REST_UNDO = 0.6f
        /** Contrato v2 (`seal_rule`): cuánto sellan los labios PP y FF sobre la mandíbula abierta. */
        const val SEAL_PP = 1f
        const val SEAL_FF = 0.6f
        /** Contrato v2: TH necesita algo de mandíbula para que la lengua asome entre los dientes. */
        const val TH_MIN_JAW = 0.12f
        /** Contrato v2: mandíbula máxima natural en F/V y en P/B/M. */
        const val FF_MAX_JAW = 0.15f
        const val PP_MAX_JAW = 0.2f
        /** Contrato v2: una O sin mandíbula parece un beso; va con jawOpen 0,2–0,35. */
        const val O_MIN_JAW = 0.2f
        /** Visemas activos a la vez como mucho (coste de GPU, ver `V2.keepStrongest`). */
        const val MAX_VISEMES = 3
        /** Por debajo de esto un visema no se ve: fuera. */
        const val VISEME_FLOOR = 0.03f
        /** Peso mínimo que se escribe en cualquier morph (menos no se ve y cuesta GPU). */
        const val MIN_WEIGHT = 0.015f
        /**
         * Peso mínimo de un morph de expresión. Cada morph activo cuesta lo mismo pese poco o mucho
         * (medido: ~0,25 ms de GPU por morph y fotograma en un Snapdragon 8 Gen 3 con la GPU al
         * límite; el doble en uno de gama media), y por debajo de esto casi no se ve.
         */
        const val EXPR_MIN_WEIGHT = 0.03f

        /** Nombre del morph de cada visema, por ordinal de [Vis]. */
        val VISEME_NAMES: List<String> = Vis.entries.map {
            "viseme_" + when (it) {
                Vis.AA -> "aa"
                Vis.KK -> "kk"
                Vis.NN -> "nn"
                else -> it.name
            }
        }

        /**
         * Mandíbula que ya traen los visemas del GLB anterior (con la mandíbula
         * horneada), en peso de jawOpen por unidad de visema: `sourceJawOpen` del
         * contrato v2 (mínimos cuadrados sobre los dientes inferiores): aa 0,86,
         * PP 0,20, O 0,19, E 0,14, DD 0,04; I, U, FF, SS y CH no la mueven.
         */
        val BAKED_JAW = FloatArray(Vis.COUNT).also {
            it[Vis.AA.ordinal] = 0.86f
            it[Vis.PP.ordinal] = 0.2f
            it[Vis.O.ordinal] = 0.19f
            it[Vis.E.ordinal] = 0.14f
            it[Vis.DD.ordinal] = 0.04f
        }
    }

    /**
     * Modelo v2. Dos variantes, según lo que traiga el GLB (por nombre):
     * - **Contrato de lip-sync v2** (hay `viseme_kk`/`viseme_nn`…): visemas solo
     *   de labios (ya llevan su pucker/funnel/press/roll horneados); la mandíbula
     *   sale solo de `jawOpen`. Reglas del contrato (`face_contract.json`,
     *   bloque `lipsync`): cierre de reposo exacto (U = suma de visemas +
     *   mouthPucker + 0,6·jawOpen; si U > 1, mouthClose += 0,2174·(U − 1)),
     *   sellado (mouthClose += jawOpen·(PP + 0,6·FF)) y jawOpen ≥ 0,12 en TH.
     * - **GLB anterior** (visemas con la mandíbula horneada, sin kk/nn/RR/TH ni
     *   mouthClose): `jawOpen` = mandíbula pedida − la que ya ponen los visemas
     *   (sin contarla dos veces), los visemas que faltan se sustituyen por los
     *   parecidos, y como no hay mouthClose, la suma se acota (cada forma
     *   rebasada deshace el cierre de reposo: sumarlas lo desharía varias veces).
     * En los dos, la suma de visemas no pasa de 1.
     */
    class V2(names: List<String>) : FaceMorphs(names) {
        private val blinkL = i("eyeBlinkLeft")
        private val blinkR = i("eyeBlinkRight")
        private val jaw = i("jawOpen")
        private val pucker = i("mouthPucker")
        private val close = i("mouthClose")
        private val vis = IntArray(Vis.COUNT) { i(VISEME_NAMES[it]) }
        /** Morph de cada canal de [Ex] (−1 si el GLB no lo trae, o [Ex.LID], que no es un morph). */
        private val ex = IntArray(Ex.COUNT) { k -> Ex.MORPH[k]?.let { i(it) } ?: -1 }
        /** Expresión final de este fotograma (ya cedida ante el habla y con el presupuesto). */
        private val ew = FloatArray(Ex.COUNT)
        private val exSorted = FloatArray(Ex.COUNT)

        /** ¿Visemas solo de labios (contrato de lip-sync)? */
        val lipOnly = vis[Vis.KK.ordinal] >= 0 || vis[Vis.NN.ordinal] >= 0 || vis[Vis.RR.ordinal] >= 0 || vis[Vis.TH.ordinal] >= 0

        override val usable = jaw >= 0 || vis[Vis.AA.ordinal] >= 0 || blinkL >= 0

        private val w = FloatArray(Vis.COUNT)

        private fun set(out: FloatArray, index: Int, v: Float) {
            // Por debajo de MIN_WEIGHT no se ve (≈0,2 mm) y cada morph activo cuesta GPU.
            if (index >= 0) out[index] = if (v < MIN_WEIGHT) 0f else min(v, 1f)
        }

        private val sorted = FloatArray(Vis.COUNT)

        /**
         * Deja activos solo los [MAX_VISEMES] visemas más fuertes: la coarticulación deja
         * colas pequeñas en muchos a la vez, y en GPU de gama media cada morph activo cuesta
         * (medido en un Snapdragon 7s Gen 2: 14 morphs pequeños → 38 fps; 3 → 50). Resta el
         * siguiente en fuerza y reescala: es continuo cuando dos visemas se cruzan en el orden,
         * así que nada salta. Lo que queda por debajo de [VISEME_FLOOR] no se ve y se quita.
         */
        private fun keepStrongest() {
            w.copyInto(sorted)
            sorted.sortDescending()
            val thr = sorted[MAX_VISEMES]
            if (thr in 1e-6f..0.99f) {
                for (v in 0 until Vis.COUNT) w[v] = (w[v] - thr).coerceAtLeast(0f) / (1f - thr)
            }
            for (v in 0 until Vis.COUNT) if (w[v] < VISEME_FLOOR) w[v] = 0f
        }

        /** Pasa el peso de un visema que no está en el modelo a otro parecido. */
        private fun fold(from: Vis, to: Vis, k: Float) {
            if (vis[from.ordinal] >= 0) return
            w[to.ordinal] += k * w[from.ordinal]
        }

        override fun write(c: FaceChannels, out: FloatArray) {
            c.lips.copyInto(w)
            // Visemas que faltan → los parecidos (tabla del contrato v1: g/k/n/l/r ≈ DD, th ≈ DD + algo de FF).
            fold(Vis.KK, Vis.DD, 0.6f)
            fold(Vis.NN, Vis.DD, 0.8f)
            fold(Vis.RR, Vis.DD, 0.6f)
            fold(Vis.TH, Vis.DD, 0.6f)
            if (vis[Vis.TH.ordinal] < 0) w[Vis.FF.ordinal] += 0.15f * c.lips[Vis.TH.ordinal]
            var sum = 0f
            for (v in 0 until Vis.COUNT) {
                if (vis[v] < 0) w[v] = 0f
                sum += w[v]
            }
            keepStrongest()
            sum = 0f
            for (v in 0 until Vis.COUNT) sum += w[v]
            if (sum > LIP_SUM_MAX) {
                val k = LIP_SUM_MAX / sum
                for (v in 0 until Vis.COUNT) w[v] *= k
                sum = LIP_SUM_MAX
            }
            // Mandíbula: con el GLB anterior, la que ya ponen los visemas no se vuelve a sumar.
            var j = c.jaw
            if (!lipOnly) {
                var baked = 0f
                for (v in 0 until Vis.COUNT) baked += w[v] * BAKED_JAW[v]
                j = min((j - baked).coerceAtLeast(0f), JAW_MAX)
            } else {
                // Límites del contrato v2, en proporción al peso de cada visema:
                // F/V y P/B/M con poca mandíbula; O con algo (si no, parece un beso);
                // TH con la justa para que la lengua pase entre los dientes (lo último: manda).
                j = min(j, JAW_MAX_V2)
                val ffN = (w[Vis.FF.ordinal] / 0.4f).coerceIn(0f, 1f)
                val ppN = (w[Vis.PP.ordinal] / 0.4f).coerceIn(0f, 1f)
                j = min(j, FF_MAX_JAW + (JAW_MAX_V2 - FF_MAX_JAW) * (1f - ffN))
                j = min(j, PP_MAX_JAW + (JAW_MAX_V2 - PP_MAX_JAW) * (1f - ppN))
                val oN = ((w[Vis.O.ordinal] - 0.15f) / 0.3f).coerceIn(0f, 1f)
                j = maxOf(j, O_MIN_JAW * oN)
                val th = (w[Vis.TH.ordinal] / 0.3f).coerceIn(0f, 1f)
                j = maxOf(j, TH_MIN_JAW * th)
            }
            // Al hablar, la boca de la expresión (labios apretados, de lado, hoyuelos) cede.
            val talk = min(1f, (sum + j) * 2f)
            val quiet = 1f - talk
            val e = c.expr
            var pr = e[Ex.PRESS] * quiet
            val pp = w[Vis.PP.ordinal]
            val ff = w[Vis.FF.ordinal]
            if (lipOnly) {
                // Cierre de reposo exacto y sellado de P/B/M y F sobre la mandíbula (contrato v2).
                val undo = sum + JAW_REST_UNDO * j
                var cl = j * (SEAL_PP * pp + SEAL_FF * ff)
                if (undo > 1f) cl += REST_FIX_PER_UNIT * (undo - 1f)
                set(out, close, cl)
            } else {
                // Sin mouthClose: un poco de presión en P/B/M y la suma total acotada.
                pr += 0.25f * pp
                val total = sum + j + pr
                if (total > MOUTH_SUM_MAX) {
                    val k = MOUTH_SUM_MAX / total
                    for (v in 0 until Vis.COUNT) w[v] *= k
                    j *= k
                    pr *= k
                }
            }
            set(out, pucker, 0f)
            for (v in 0 until Vis.COUNT) set(out, vis[v], w[v])
            set(out, jaw, j)
            val o = w[Vis.O.ordinal]
            val u = w[Vis.U.ordinal]

            // Expresión (ver FaceExpression). La sonrisa y los hoyuelos ceden en O/U/P/F: si no,
            // se comen el redondeo y el cierre. Al cerrar el párpado, entrecerrar y abrir de más ceden.
            val round = ((o + u) / 0.6f + pp / 0.7f + ff / 0.8f).coerceIn(0f, 1f)
            val keep = 1f - smileCut * round
            e.copyInto(ew)
            ew[Ex.SMILE_L] *= keep
            ew[Ex.SMILE_R] *= keep
            ew[Ex.DIMPLE] *= keep * (1f - 0.5f * talk)
            ew[Ex.SQUINT_L] *= 1f - c.blinkL
            ew[Ex.SQUINT_R] *= 1f - c.blinkR
            ew[Ex.WIDE_L] *= 1f - c.blinkL
            ew[Ex.WIDE_R] *= 1f - c.blinkR
            ew[Ex.FROWN_L] *= 1f - 0.4f * talk
            ew[Ex.FROWN_R] *= 1f - 0.4f * talk
            ew[Ex.PRESS] = pr
            ew[Ex.MOUTH_LEFT] *= quiet
            ew[Ex.MOUTH_RIGHT] *= quiet
            budget()
            for (k in 0 until Ex.COUNT) set(out, ex[k], if (ew[k] < EXPR_MIN_WEIGHT) 0f else ew[k])
            set(out, blinkL, c.blinkL)
            set(out, blinkR, c.blinkR)
        }

        /**
         * Deja como mucho [exprBudget] morphs de expresión por encima de [EXPR_MIN_WEIGHT]: resta el
         * siguiente en fuerza y reescala (como [keepStrongest]); continuo cuando dos se cruzan.
         */
        private fun budget() {
            if (exprBudget >= Ex.COUNT) return
            var n = 0
            for (k in 0 until Ex.COUNT) if (ex[k] >= 0 && ew[k] >= EXPR_MIN_WEIGHT) exSorted[n++] = ew[k]
            if (n <= exprBudget) return
            exSorted.sortDescending(0, n)
            val thr = exSorted[exprBudget]
            if (thr >= 0.99f) return
            for (k in 0 until Ex.COUNT) ew[k] = ((ew[k] - thr) / (1f - thr)).coerceAtLeast(0f)
        }
    }

    /** Modelo antiguo (`build_masha.py`): 11 morphs. Se conserva por compatibilidad. */
    class Legacy(names: List<String>) : FaceMorphs(names) {
        private val open = i("MouthOpen")
        private val aa = i("V_AA")
        private val o = i("V_O")
        private val ee = i("V_EE")
        private val fv = i("V_FV")
        private val mbp = i("V_MBP")
        private val smile = i("Smile")
        private val browUp = i("BrowUp")
        private val frown = i("BrowFrown")
        private val blinkL = i("Blink_L")
        private val blinkR = i("Blink_R")

        override val usable = open >= 0 || blinkL >= 0

        private fun set(out: FloatArray, index: Int, w: Float) {
            if (index >= 0) out[index] = w.coerceIn(0f, 1f)
        }

        override fun write(c: FaceChannels, out: FloatArray) {
            val m = c.lips
            set(out, open, c.jaw / JAW_MAX * 0.85f)
            set(out, aa, m[Vis.AA.ordinal])
            set(out, o, maxOf(m[Vis.O.ordinal], 0.8f * m[Vis.U.ordinal]))
            set(out, ee, maxOf(m[Vis.E.ordinal], m[Vis.I.ordinal]))
            set(out, fv, m[Vis.FF.ordinal])
            set(out, mbp, m[Vis.PP.ordinal])
            // Los canales ARKit de la expresión, a las tres recetas del modelo antiguo.
            val e = c.expr
            set(out, smile, (e[Ex.SMILE_L] + e[Ex.SMILE_R]) / 1.2f)
            set(out, browUp, e[Ex.BROW_INNER] / 0.7f + 0.5f * (e[Ex.BROW_OUTER_L] + e[Ex.BROW_OUTER_R]))
            set(out, frown, (e[Ex.BROW_DOWN_L] + e[Ex.BROW_DOWN_R]) / 1.6f + 0.5f * (e[Ex.FROWN_L] + e[Ex.FROWN_R]))
            set(out, blinkL, c.blinkL)
            set(out, blinkR, c.blinkR)
        }
    }
}

/**
 * Parpadeo. Uno normal (contrato): cierra en 70 ms (ease-in), se queda
 * 20–30 ms, abre en 110–150 ms (ease-out); el ojo derecho va 0–15 ms detrás.
 * El ritmo y el estilo dependen de lo que hace ([Mode]): intervalos de
 * distribución sesgada (muchos cortos, alguno largo: no un metrónomo), dobles
 * ocasionales (el segundo empieza 120 ms después de cerrar el primero),
 * parpadeos incompletos y, pensando, medios parpadeos; cálida, alguno lento.
 * [rnd] es inyectable para las pruebas (un `Random` y no una lambda: devuelve
 * primitivos, sin objetos).
 */
internal class Blink(private val rnd: Random) {

    /**
     * Ritmo (s entre parpadeos: mínimo, media, máximo) y estilo de cada estado.
     * [partialP] = parte de parpadeos incompletos, de profundidad [partialMin]–[partialMax].
     */
    enum class Mode(
        val minGap: Float,
        val meanGap: Float,
        val maxGap: Float,
        val partialP: Float,
        val partialMin: Float,
        val partialMax: Float,
        val doubleP: Float,
        val slowP: Float,
    ) {
        /** En reposo: ~16 por minuto. */
        Rest(1.2f, 3.8f, 9f, 0.10f, 0.65f, 0.85f, 0.15f, 0f),
        /** Hablando se parpadea más (~22/min), sobre todo en las pausas (ver [trigger]). */
        Speaking(0.8f, 2.7f, 6.5f, 0.12f, 0.65f, 0.85f, 0.18f, 0f),
        /** Escuchando, con atención: menos. */
        Listening(1.5f, 4.6f, 10f, 0.08f, 0.65f, 0.85f, 0.10f, 0f),
        /** Pensando: la mitad son medios parpadeos (el párpado baja y vuelve, "procesando"). */
        Thinking(1.0f, 3.0f, 7.5f, 0.55f, 0.40f, 0.62f, 0.08f, 0f),
        /** Cálida: de vez en cuando uno lento, de complicidad. */
        Warm(1.3f, 4.2f, 9f, 0.10f, 0.65f, 0.85f, 0.12f, 0.3f),
    }

    private var start = -10f
    private var close = CLOSE
    private var hold = 0.025f
    private var open = 0.13f
    private var depth = 1f
    private var double = false
    private var offsetR = 0f
    private var next = 1.2f
    private var mode = Mode.Rest

    private fun random() = rnd.nextFloat()

    /** Profundidad del parpadeo en curso (1 = completo), para pruebas. */
    val currentDepth: Float get() = depth

    /** Parpadea ya (cambio grande de mirada, final de una frase, coma) si no está parpadeando: completo. */
    fun trigger(t: Float) {
        if (t - start < total() + 0.25f) return
        begin(t, Mode.Rest, full = true)
    }

    private fun total() = close + hold + open + if (double) DOUBLE_GAP + close + hold + open else 0f

    private fun begin(t: Float, m: Mode, full: Boolean) {
        start = t
        close = CLOSE
        hold = 0.02f + 0.01f * random()
        open = 0.11f + 0.04f * random()
        depth = 1f
        val r = random()
        when {
            full -> Unit
            r < m.slowP -> {
                // Lento: cierra despacio, se queda y abre aún más despacio.
                close = 0.12f
                hold = 0.12f + 0.08f * random()
                open = 0.26f + 0.08f * random()
            }
            r < m.slowP + m.partialP -> {
                depth = m.partialMin + (m.partialMax - m.partialMin) * random()
                hold = 0.01f + 0.02f * random()
                open = 0.09f + 0.03f * random()
            }
        }
        double = depth >= 1f && close == CLOSE && random() < m.doubleP
        offsetR = 0.015f * random()
    }

    /** Avanza el reloj con el ritmo y el estilo de [m]. */
    fun update(t: Float, m: Mode) {
        if (m != mode) {
            // Al cambiar a un estado más parpadeador no espera el intervalo largo del anterior.
            mode = m
            next = minOf(next, t + m.meanGap * 0.5f)
        }
        if (t >= next) {
            begin(t, m, full = false)
            next = t + gap(m)
        }
    }

    /** Intervalo: mínimo + exponencial (media [Mode.meanGap]), acotado a [Mode.maxGap]. */
    private fun gap(m: Mode): Float {
        val u = random().coerceIn(0f, 0.999f)
        return (m.minGap - (m.meanGap - m.minGap) * ln(1f - u)).coerceAtMost(m.maxGap)
    }

    fun left(t: Float): Float = eye(t - start)
    fun right(t: Float): Float = eye(t - start - offsetR)

    private fun eye(x: Float): Float {
        var w = curve(x, close, hold, open)
        if (double) w = maxOf(w, curve(x - (close + hold + DOUBLE_GAP), close, hold, open))
        return w * depth
    }

    companion object {
        const val CLOSE = 0.07f
        const val DOUBLE_GAP = 0.12f

        /** Curva de un parpadeo en el instante [x] (s desde que empieza). */
        fun curve(x: Float, close: Float, hold: Float, open: Float): Float = when {
            x < 0f -> 0f
            x < close -> (x / close).let { it * it }
            x < close + hold -> 1f
            x < close + hold + open -> {
                val k = (x - close - hold) / open
                (1f - k) * (1f - k)
            }
            else -> 0f
        }
    }
}

/** Ajustes de la mirada (contrato: `eye_bones.limits_deg`, `idle.gaze`). */
internal object GazeConfig {
    const val EYE_YAW_MAX = 25f
    const val EYE_PITCH_UP = 15f
    const val EYE_PITCH_DOWN = 20f
    /** Parte de un desvío grande que toma la cabeza (el resto, los ojos). */
    const val HEAD_SHARE = 0.35f
    const val HEAD_SHARE_REDUCED = 0.2f
    const val HEAD_YAW_MAX = 18f
    const val HEAD_PITCH_MAX = 10f
    /** Muelle críticamente amortiguado de la cabeza (≈ 250 ms de retraso). */
    const val HEAD_OMEGA = 8f
    /** Reparto del giro de la cabeza entre cuello y cabeza. */
    const val NECK_SHARE = 0.4f
    /** Velocidad de sacada (°/s). */
    const val SACCADE_SPEED = 400f
    /** Sacada balística (secuencia principal): 21 ms + 2,2 ms por grado. */
    const val SACCADE_BASE = 0.021f
    const val SACCADE_PER_DEG = 0.0022f
    /** Microsacadas: 0,2–0,5° cada 0,6–1,4 s (≈ 1 por segundo). */
    const val MICRO_MIN = 0.2f
    const val MICRO_MAX = 0.5f
    const val MICRO_INTERVAL_MIN = 0.6f
    const val MICRO_INTERVAL_MAX = 1.4f
    /** Refijación: salto de 1–3° dentro de la cara del usuario cada 0,8–2,5 s. */
    const val FIXATION_MIN = 0.8f
    const val FIXATION_MAX = 2.5f
    const val REFIX_MIN = 1f
    const val REFIX_MAX = 3f
    const val REFIX_YAW = 1.5f
    const val REFIX_PITCH = 1f
    /** Pensando: cada 4–8 s mira a otro lado (5–15° de lado, 5–12° arriba) durante 0,8–2 s. */
    const val AWAY_MIN = 5f
    const val AWAY_YAW = 15f
    const val AWAY_PITCH_MIN = 5f
    const val AWAY_PITCH_MAX = 12f
    const val AWAY_INTERVAL_MIN = 4f
    const val AWAY_INTERVAL_MAX = 8f
    const val AWAY_HOLD_MIN = 0.8f
    const val AWAY_HOLD_MAX = 2f
    /**
     * Acoplamiento de párpados (el GLB no trae eyeLookUp/Down: la mirada es de huesos, así que
     * su pitch mueve los párpados): mirar abajo baja el superior (sigue al iris casi 1:1, como en
     * las personas: a 20° el iris baja ~4 mm, la mitad del recorrido del parpadeo), mirar arriba
     * lo abre y, pasados unos grados, alza un poco las cejas.
     */
    const val LID_DOWN = 0.5f
    const val LID_UP = 0.4f
    const val BROW_LIFT = 0.12f
    /** Grados hacia arriba desde los que las cejas acompañan. */
    const val BROW_LIFT_FROM = 6f

    /** eyeBlink extra al mirar abajo ([pitch] en grados, + = arriba). */
    fun lidDown(pitch: Float) = LID_DOWN * (-pitch / EYE_PITCH_DOWN).coerceIn(0f, 1f)

    /** eyeWide extra al mirar arriba. */
    fun lidUp(pitch: Float) = LID_UP * (pitch / EYE_PITCH_UP).coerceIn(0f, 1f)

    /** browInnerUp extra al mirar muy arriba (la frente ayuda). */
    fun browLift(pitch: Float) = BROW_LIFT * ((pitch - BROW_LIFT_FROM) / (EYE_PITCH_UP - BROW_LIFT_FROM)).coerceIn(0f, 1f)
}
