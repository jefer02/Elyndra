package com.elyndra.launcher.ui.masha

import com.elyndra.launcher.ui.masha.lipsync.Vis
import kotlin.math.min

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
 * Lo que la cara expresa en este fotograma, en unidades semánticas 0..1. El
 * rig lo rellena (con su suavizado) y [FaceMorphs.write] lo traduce a morphs.
 */
internal class FaceChannels {
    /** Visemas de labios ya coarticulados y suavizados, por ordinal de [Vis] (sin mandíbula). */
    val lips = FloatArray(Vis.COUNT)
    /** Mandíbula del habla, en peso de `jawOpen`. */
    var jaw = 0f
    /** Receta "smile" (sonrisa por ánimo + microsonrisas). */
    var smile = 0f
    /** Sonrisa extra por lado (media sonrisa asimétrica, sesgo en reposo). */
    var smileL = 0f
    var smileR = 0f
    /** Receta antigua "BrowUp" (microexpresión). */
    var browUp = 0f
    /** Acento de cejas del habla (palabras enfáticas, preguntas). */
    var speechBrow = 0f
    /** Receta antigua "BrowFrown" (preocupada / analítica). */
    var frown = 0f
    /** Receta "listening" y "thoughtful" (0..1 de cada una). */
    var listen = 0f
    var think = 0f
    /** Entrecerrar suave (microexpresión "soft_squint"). */
    var squint = 0f
    /** Párpados, ya con el acoplamiento a la mirada. */
    var blinkL = 0f
    var blinkR = 0f
    var wideL = 0f
    var wideR = 0f

    fun clear() {
        lips.fill(0f)
        jaw = 0f
        smile = 0f; smileL = 0f; smileR = 0f
        browUp = 0f; speechBrow = 0f; frown = 0f; listen = 0f; think = 0f; squint = 0f
        blinkL = 0f; blinkR = 0f; wideL = 0f; wideR = 0f
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
        private val squintL = i("eyeSquintLeft")
        private val squintR = i("eyeSquintRight")
        private val wideL = i("eyeWideLeft")
        private val wideR = i("eyeWideRight")
        private val browInnerUp = i("browInnerUp")
        private val browDownL = i("browDownLeft")
        private val browDownR = i("browDownRight")
        private val browOuterL = i("browOuterUpLeft")
        private val browOuterR = i("browOuterUpRight")
        private val cheekL = i("cheekSquintLeft")
        private val cheekR = i("cheekSquintRight")
        private val smileL = i("mouthSmileLeft")
        private val smileR = i("mouthSmileRight")
        private val frownL = i("mouthFrownLeft")
        private val frownR = i("mouthFrownRight")
        private val jaw = i("jawOpen")
        private val pucker = i("mouthPucker")
        private val press = i("mouthPress")
        private val mouthLeft = i("mouthLeft")
        private val close = i("mouthClose")
        private val vis = IntArray(Vis.COUNT) { i(VISEME_NAMES[it]) }

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
            // Al hablar, la boca de "pensar" (labios apretados, de lado) se va.
            val talk = min(1f, (sum + j) * 2f)
            var pr = 0.35f * c.think * (1f - talk)
            val left = 0.3f * c.think * (1f - talk)
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
            set(out, press, pr)
            set(out, mouthLeft, left)
            val o = w[Vis.O.ordinal]
            val u = w[Vis.U.ordinal]

            // Recetas: smile, BrowUp, BrowFrown, listening, thoughtful, soft_squint.
            // La sonrisa cede en O/U/P/F: si no, se come el redondeo y el cierre.
            val round = ((o + u) / 0.6f + pp / 0.7f + ff / 0.8f).coerceIn(0f, 1f)
            val keep = 1f - smileCut * round
            val s = c.smile * keep
            set(out, smileL, 0.6f * s + 0.18f * c.listen + c.smileL * keep)
            set(out, smileR, 0.6f * s + 0.18f * c.listen + c.smileR * keep)
            set(out, cheekL, 0.35f * s + 0.1f * c.squint)
            set(out, cheekR, 0.35f * s + 0.1f * c.squint)
            val sqL = 0.2f * s + 0.25f * c.think + 0.15f * c.squint
            val sqR = 0.2f * s + 0.15f * c.think + 0.15f * c.squint
            // Al cerrar el párpado, el entrecerrar y el abrir de más ceden.
            set(out, squintL, sqL * (1f - c.blinkL))
            set(out, squintR, sqR * (1f - c.blinkR))
            set(out, wideL, (c.wideL + 0.1f * c.listen) * (1f - c.blinkL))
            set(out, wideR, (c.wideR + 0.1f * c.listen) * (1f - c.blinkR))
            set(out, blinkL, c.blinkL)
            set(out, blinkR, c.blinkR)
            val brow = c.browUp + c.speechBrow
            set(out, browInnerUp, 0.7f * brow + 0.3f * c.listen + 0.25f * c.think)
            set(out, browOuterL, 0.5f * brow + 0.12f * c.listen)
            set(out, browOuterR, 0.5f * brow + 0.12f * c.listen)
            set(out, browDownL, 0.8f * c.frown + 0.3f * c.think)
            set(out, browDownR, 0.8f * c.frown)
            set(out, frownL, 0.2f * c.frown)
            set(out, frownR, 0.2f * c.frown)
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
            set(out, smile, c.smile + 0.5f * (c.smileL + c.smileR) + 0.18f * c.listen)
            // Como antes: escuchar sube las cejas 0.5 y pensar 0.3.
            set(out, browUp, c.browUp + c.speechBrow + 0.5f * c.listen + 0.3f * c.think)
            set(out, frown, c.frown)
            set(out, blinkL, c.blinkL)
            set(out, blinkR, c.blinkR)
        }
    }
}

/**
 * Parpadeo del contrato: cierra en 70 ms (ease-in), se queda 20–30 ms,
 * abre en 110–150 ms (ease-out); cada 2,5–6 s (5–9 s pensando); 15 % dobles
 * (el segundo empieza 120 ms después de cerrar el primero); el ojo derecho va
 * 0–15 ms detrás. [random] da valores 0..1 (inyectable para las pruebas).
 */
internal class Blink(private val random: () -> Float) {
    private var start = -10f
    private var hold = 0.025f
    private var open = 0.13f
    private var double = false
    private var offsetR = 0f
    private var next = 1.2f

    /** Parpadea ya (cambio grande de mirada, final de una frase) si no está parpadeando. */
    fun trigger(t: Float) {
        if (t - start < total() + 0.25f) return
        begin(t)
    }

    private fun total() = CLOSE + hold + open

    private fun begin(t: Float) {
        start = t
        hold = 0.02f + 0.01f * random()
        open = 0.11f + 0.04f * random()
        double = random() < DOUBLE_P
        offsetR = 0.015f * random()
    }

    /** Avanza el reloj; [slow] = pensando (intervalo más largo). */
    fun update(t: Float, slow: Boolean) {
        if (t >= next) {
            begin(t)
            next = t + if (slow) 5f + 4f * random() else 2.5f + 3.5f * random()
        }
    }

    fun left(t: Float): Float = eye(t - start)
    fun right(t: Float): Float = eye(t - start - offsetR)

    private fun eye(x: Float): Float {
        var w = curve(x, hold, open)
        if (double) w = maxOf(w, curve(x - (CLOSE + hold + DOUBLE_GAP), hold, open))
        return w
    }

    companion object {
        const val CLOSE = 0.07f
        const val DOUBLE_P = 0.15f
        const val DOUBLE_GAP = 0.12f

        /** Curva de un parpadeo en el instante [x] (s desde que empieza). */
        fun curve(x: Float, hold: Float, open: Float): Float = when {
            x < 0f -> 0f
            x < CLOSE -> (x / CLOSE).let { it * it }
            x < CLOSE + hold -> 1f
            x < CLOSE + hold + open -> {
                val k = (x - CLOSE - hold) / open
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
    /** Acoplamiento de párpados: mirar abajo cierra, arriba abre. */
    const val LID_DOWN = 0.45f
    const val LID_UP = 0.35f

    /** eyeBlink extra al mirar abajo ([pitch] en grados, + = arriba). */
    fun lidDown(pitch: Float) = LID_DOWN * (-pitch / EYE_PITCH_DOWN).coerceIn(0f, 1f)

    /** eyeWide extra al mirar arriba. */
    fun lidUp(pitch: Float) = LID_UP * (pitch / EYE_PITCH_UP).coerceIn(0f, 1f)
}
