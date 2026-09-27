package com.elyndra.launcher.ui.masha

import kotlin.math.min

/*
 * La cara de Masha sin Filament (se prueba en la JVM): canales semánticos
 * (visemas, sonrisa, cejas, parpadeo…) → pesos de los morph targets del
 * modelo cargado, el parpadeo y las constantes de mirada.
 *
 * Contrato: `face_contract.json` del equipo del modelo (31 morphs en
 * `Masha_Head`, recetas de expresión, tabla antiguo → nuevo).
 */

/**
 * Lo que la cara expresa en este fotograma, en unidades semánticas 0..1. El
 * rig lo rellena (con su suavizado) y [FaceMorphs.write] lo traduce a morphs.
 */
internal class FaceChannels {
    /** Visemas ya suavizados, por ordinal de [LipSync.V]. */
    val mouth = FloatArray(LipSync.V.entries.size)
    /** Receta "smile" (sonrisa por ánimo + microsonrisas). */
    var smile = 0f
    /** Sonrisa extra por lado (media sonrisa asimétrica, sesgo en reposo). */
    var smileL = 0f
    var smileR = 0f
    /** Receta antigua "BrowUp" (microexpresión, acento de ceja). */
    var browUp = 0f
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
        mouth.fill(0f)
        smile = 0f; smileL = 0f; smileR = 0f
        browUp = 0f; frown = 0f; listen = 0f; think = 0f; squint = 0f
        blinkL = 0f; blinkR = 0f; wideL = 0f; wideR = 0f
    }
}

/**
 * Capa de traducción de canales a morphs. Resuelve los índices por nombre una
 * vez ([forNames]); si falta un morph, su índice es −1 y se ignora. Admite el
 * modelo v2 (31 morphs ARKit/visemas) y el antiguo (`MouthOpen`, `V_AA`…).
 * [write] no crea objetos.
 */
internal abstract class FaceMorphs(val names: List<String>) {

    protected fun i(name: String) = names.indexOf(name)

    /** Escribe en [out] (tamaño = nº de morphs) los pesos para [c]. */
    abstract fun write(c: FaceChannels, out: FloatArray)

    /** ¿Hay algo que mover? */
    abstract val usable: Boolean

    companion object {
        fun forNames(names: List<String>): FaceMorphs =
            if ("jawOpen" in names || "viseme_aa" in names) V2(names) else Legacy(names)

        /** Máximo de jawOpen en el habla (1.0 es la apertura máxima natural; el habla usa 0.15–0.5). */
        const val JAW_MAX = 0.5f
        /** Mandíbula por unidad del canal Open (contrato: MouthOpen → jawOpen 0.55). */
        const val JAW_GAIN = 0.55f
        /** Ganancia de los visemas (poses absolutas: 1.0 es la pose entera). */
        const val VISEME_GAIN = 0.9f
        /** La suma de las formas de boca no pasa de esto (contrato: ≤ ~1.1). */
        const val MOUTH_SUM_MAX = 1.1f
    }

    /** Modelo v2: los 31 morphs del contrato. */
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
        private val vAA = i("viseme_aa")
        private val vE = i("viseme_E")
        private val vI = i("viseme_I")
        private val vO = i("viseme_O")
        private val vU = i("viseme_U")
        private val vPP = i("viseme_PP")
        private val vFF = i("viseme_FF")
        private val vSS = i("viseme_SS")
        private val vDD = i("viseme_DD")
        private val vCH = i("viseme_CH")

        override val usable = jaw >= 0 || vAA >= 0 || blinkL >= 0

        private fun set(out: FloatArray, index: Int, w: Float) {
            if (index >= 0) out[index] = w.coerceIn(0f, 1f)
        }

        override fun write(c: FaceChannels, out: FloatArray) {
            val m = c.mouth
            val g = VISEME_GAIN
            var aa = m[LipSync.V.AA.ordinal] * g
            // "EE" (canal antiguo e/i) → 0.6 E + 0.4 I, como dice el contrato.
            var e = (m[LipSync.V.E.ordinal] + 0.6f * m[LipSync.V.EE.ordinal]) * g
            var ii = (m[LipSync.V.I.ordinal] + 0.4f * m[LipSync.V.EE.ordinal]) * g
            var o = m[LipSync.V.O.ordinal] * g
            var u = m[LipSync.V.U.ordinal] * g
            var pp = m[LipSync.V.MBP.ordinal] * g
            var ff = m[LipSync.V.FV.ordinal] * g
            var ss = m[LipSync.V.SS.ordinal] * g
            var dd = m[LipSync.V.DD.ordinal] * g
            var ch = m[LipSync.V.CH.ordinal] * g
            var j = min(m[LipSync.V.Open.ordinal] * JAW_GAIN, JAW_MAX)
            val speech = aa + e + ii + o + u + pp + ff + ss + dd + ch
            // Al hablar, la boca de "pensar" (labios apretados, de lado) se va.
            val talk = min(1f, speech * 2f)
            val pr = 0.35f * c.think * (1f - talk)
            val left = 0.3f * c.think * (1f - talk)
            // Normaliza la mezcla de la boca (visemas + mandíbula + labios apretados).
            val sum = speech + j + pr
            if (sum > MOUTH_SUM_MAX) {
                val k = MOUTH_SUM_MAX / sum
                aa *= k; e *= k; ii *= k; o *= k; u *= k; pp *= k; ff *= k; ss *= k; dd *= k; ch *= k; j *= k
            }
            set(out, vAA, aa); set(out, vE, e); set(out, vI, ii); set(out, vO, o); set(out, vU, u)
            set(out, vPP, pp); set(out, vFF, ff); set(out, vSS, ss); set(out, vDD, dd); set(out, vCH, ch)
            set(out, jaw, min(j, JAW_MAX))
            set(out, press, pr)
            set(out, mouthLeft, left)
            set(out, pucker, 0f)

            // Recetas: smile, BrowUp, BrowFrown, listening, thoughtful, soft_squint.
            val s = c.smile
            set(out, smileL, 0.6f * s + 0.18f * c.listen + c.smileL)
            set(out, smileR, 0.6f * s + 0.18f * c.listen + c.smileR)
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
            set(out, browInnerUp, 0.7f * c.browUp + 0.3f * c.listen + 0.25f * c.think)
            set(out, browOuterL, 0.5f * c.browUp + 0.12f * c.listen)
            set(out, browOuterR, 0.5f * c.browUp + 0.12f * c.listen)
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
            val m = c.mouth
            set(out, open, m[LipSync.V.Open.ordinal] * 0.85f)
            set(out, aa, m[LipSync.V.AA.ordinal] * 0.7f)
            set(out, o, maxOf(m[LipSync.V.O.ordinal], 0.8f * m[LipSync.V.U.ordinal]))
            set(out, ee, maxOf(m[LipSync.V.EE.ordinal], m[LipSync.V.E.ordinal], m[LipSync.V.I.ordinal]))
            set(out, fv, m[LipSync.V.FV.ordinal])
            set(out, mbp, m[LipSync.V.MBP.ordinal])
            set(out, smile, c.smile + 0.5f * (c.smileL + c.smileR) + 0.18f * c.listen)
            // Como antes: escuchar sube las cejas 0.5 y pensar 0.3.
            set(out, browUp, c.browUp + 0.5f * c.listen + 0.3f * c.think)
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
    /** Microsacadas: 0,3–0,8° cada 0,4–1,2 s. */
    const val MICRO_MIN = 0.3f
    const val MICRO_MAX = 0.8f
    /** Refijación: pequeño salto dentro de la cara del usuario cada 0,8–2,5 s. */
    const val FIXATION_MIN = 0.8f
    const val FIXATION_MAX = 2.5f
    const val REFIX_YAW = 1.5f
    const val REFIX_PITCH = 1f
    /** Pensando: mira a otro lado (±15°, 5–12° arriba) durante 0,8–2 s. */
    const val AWAY_YAW = 15f
    const val AWAY_PITCH_MIN = 5f
    const val AWAY_PITCH_MAX = 12f
    /** Acoplamiento de párpados: mirar abajo cierra, arriba abre. */
    const val LID_DOWN = 0.45f
    const val LID_UP = 0.35f

    /** eyeBlink extra al mirar abajo ([pitch] en grados, + = arriba). */
    fun lidDown(pitch: Float) = LID_DOWN * (-pitch / EYE_PITCH_DOWN).coerceIn(0f, 1f)

    /** eyeWide extra al mirar arriba. */
    fun lidUp(pitch: Float) = LID_UP * (pitch / EYE_PITCH_UP).coerceIn(0f, 1f)
}
