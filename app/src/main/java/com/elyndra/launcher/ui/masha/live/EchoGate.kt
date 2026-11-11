package com.elyndra.launcher.ui.masha.live

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ¿Lo que oye el micrófono es el usuario o es Lucy saliendo por el altavoz?
 * (pura). Sin modo llamada, el móvil no siempre le quita a la grabación su
 * propia voz; pero esa voz la reproducimos nosotros, así que sabemos qué nivel
 * tiene en cada instante ([refDb]: lo que suena ahora, ver PlaybackEnvelope).
 *
 * Por cada tramo de [frameMs]:
 * - **Retardo:** el eco llega al micrófono con un retraso (latencia de salida +
 *   de entrada + el aire). Se estima cruzando las envolventes (dB por tramo) de
 *   lo que suena y lo que se oye, en ventanas de [HISTORY] tramos mientras ella
 *   habla con variación suficiente; el mejor desfase de 0 a [MAX_LAG] tramos.
 * - **Eco esperado:** lo que sonó hace `lag` tramos (con una cola de
 *   reverberación que cae [TAIL_DECAY_DB] por tramo) más el acoplamiento
 *   altavoz→micrófono aprendido ([couplingDb], dB de micro por dB de salida).
 * - **Doble habla:** hay voz del usuario si el micro supera el eco esperado en
 *   más de [marginDb]. Si además la envolvente del micro sigue a la de ella
 *   (correlación ≥ [FOLLOW_CORR] en los últimos [LOCAL] tramos), se le da
 *   [slackDb] más de margen: es su voz, distorsionada a volumen alto.
 * - **Aprendizaje:** el acoplamiento sube deprisa y baja despacio con los
 *   tramos que parecen eco; los primeros [warmupMs] de voz de ella aprende con
 *   todos (nadie suele interrumpir en el primer segundo).
 *
 * Con ella callada (nada sonando ni en la cola), la puerta está abierta: decide
 * el VAD.
 */
class EchoGate(
    val frameMs: Int = 16,
    initialCouplingDb: Float = DEFAULT_COUPLING_DB,
    val marginDb: Float = MARGIN_DB,
    val slackDb: Float = SLACK_DB,
    val warmupMs: Int = WARMUP_MS,
) {
    private val ref = FloatArray(HISTORY) { SILENT }
    private val mic = FloatArray(HISTORY) { SILENT }
    private var t = 0L

    /** Desfase estimado del eco, en tramos. */
    var lag = DEFAULT_LAG
        private set

    /** Confianza del último desfase (correlación de 0 a 1). */
    var lagConfidence = 0f
        private set

    /** Acoplamiento aprendido: dB del eco en el micro respecto a lo que suena. */
    var couplingDb = initialCouplingDb.coerceIn(MIN_COUPLING_DB, MAX_COUPLING_DB)
        private set

    private var warmFrames = 0

    /** Eco esperado en el último tramo (dBFS), o [SILENT] sin voz de ella. */
    var predictedDb = SILENT
        private set

    /**
     * Mete un tramo: [micDb] (lo que oye el micrófono) y [refDb] (lo que suena
     * ahora mismo, sin desfase). Devuelve si hay voz que **no** es la de ella.
     */
    fun accept(micDb: Float, refDb: Float): Boolean {
        val i = (t % HISTORY).toInt()
        ref[i] = refDb.coerceAtLeast(SILENT)
        mic[i] = micDb.coerceAtLeast(SILENT)
        t++
        if (t % ESTIMATE_EVERY == 0L) estimateLag()

        val raw = echoSource()
        if (raw <= REF_FLOOR) {
            predictedDb = SILENT
            return true
        }
        val predicted = raw + couplingDb
        predictedDb = predicted
        val residual = micDb - predicted
        val follows = localCorrelation() >= FOLLOW_CORR
        val echo = residual <= marginDb || (follows && residual <= marginDb + slackDb)
        val warming = warmFrames * frameMs < warmupMs
        // Se aprende solo con su voz de lleno en el desfase justo y con el micro siguiéndola:
        // si el usuario habla encima, la correlación cae y su voz no infla el acoplamiento.
        val direct = at(lag)
        if (direct > LEARN_FLOOR && micDb > MIC_FLOOR && (warming || (residual <= marginDb && follows))) learn(micDb - direct)
        if (direct > LEARN_FLOOR) warmFrames++
        return !warming && !echo
    }

    /** Lo que sonó hace [lag] tramos, con la cola de reverberación (dBFS de salida). */
    private fun echoSource(): Float {
        var best = SILENT
        // Un tramo antes también: el desfase estimado puede quedarse corto por uno.
        for (k in -1..TAIL) {
            if (lag + k < 0) continue
            val v = at(lag + k) - k.coerceAtLeast(0) * TAIL_DECAY_DB
            if (v > best) best = v
        }
        return best
    }

    private fun learn(d: Float) {
        val c = d.coerceIn(MIN_COUPLING_DB, MAX_COUPLING_DB)
        couplingDb += (c - couplingDb) * if (c > couplingDb) UP_RATE else DOWN_RATE
    }

    /** Valor de [arr] hace [back] tramos (0 = el último). */
    private fun at(back: Int, arr: FloatArray = ref): Float {
        if (back >= t || back >= HISTORY) return SILENT
        return arr[((t - 1 - back) % HISTORY).toInt()]
    }

    private fun localCorrelation(): Float {
        if (t < LOCAL + lag) return 0f
        return pearson(LOCAL, { k -> at(k + lag) }, { k -> at(k, mic) })
    }

    private fun estimateLag() {
        val n = HISTORY - MAX_LAG
        if (t < HISTORY) return
        // Solo con ella hablando con variación: un tono plano no dice nada del retardo.
        var active = 0
        var sum = 0f
        var sq = 0f
        for (k in 0 until n) {
            val r = at(k)
            if (r > REF_FLOOR) active++
            sum += r
            sq += r * r
        }
        val mean = sum / n
        val std = sqrt((sq / n - mean * mean).coerceAtLeast(0f))
        if (active < n * 0.3f || std < MIN_REF_STD_DB) return
        var bestLag = lag
        var bestCorr = -1f
        var current = 0f
        for (l in 0..MAX_LAG) {
            val c = pearson(n, { k -> at(k + l) }, { k -> at(k, mic) })
            if (l == lag) current = c
            if (c > bestCorr) {
                bestCorr = c
                bestLag = l
            }
        }
        // Con el usuario hablando encima la correlación se ensucia: solo se cambia por uno claramente mejor.
        if (bestCorr >= MIN_LAG_CORR && (lagConfidence == 0f || bestCorr > current + LAG_SWITCH)) {
            lag = bestLag
            lagConfidence = bestCorr
        } else if (current > 0f) {
            lagConfidence = current
        }
    }

    private inline fun pearson(n: Int, xs: (Int) -> Float, ys: (Int) -> Float): Float {
        var sx = 0f; var sy = 0f; var sxx = 0f; var syy = 0f; var sxy = 0f
        for (k in 0 until n) {
            val x = xs(k)
            val y = ys(k)
            sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
        }
        val vx = sxx - sx * sx / n
        val vy = syy - sy * sy / n
        if (vx <= 1e-3f || vy <= 1e-3f) return 0f
        val r = (sxy - sx * sy / n) / sqrt(vx * vy)
        return if (r.isNaN() || abs(r) > 1.0001f) 0f else r
    }

    companion object {
        const val SILENT = -100f

        /** Por debajo, de ella no sale nada que pueda colarse (dBFS de salida). */
        const val REF_FLOOR = -55f
        private const val LEARN_FLOOR = -45f
        private const val MIC_FLOOR = -75f

        const val DEFAULT_COUPLING_DB = -10f
        const val MIN_COUPLING_DB = -60f
        const val MAX_COUPLING_DB = 6f
        const val MARGIN_DB = 5f
        const val SLACK_DB = 4f
        const val WARMUP_MS = 1_000
        private const val UP_RATE = 0.06f
        private const val DOWN_RATE = 0.06f

        /** 1,6 s de historia a 16 ms; desfases de hasta 384 ms. */
        const val HISTORY = 100
        const val MAX_LAG = 24
        const val DEFAULT_LAG = 8
        private const val ESTIMATE_EVERY = 8L
        private const val MIN_REF_STD_DB = 4f
        private const val MIN_LAG_CORR = 0.6f
        private const val LAG_SWITCH = 0.1f

        private const val TAIL = 8
        private const val TAIL_DECAY_DB = 2.5f
        private const val LOCAL = 14
        private const val FOLLOW_CORR = 0.85f
    }
}
