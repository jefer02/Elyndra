package com.elyndra.launcher.ui.masha

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Capas procedurales del cuerpo y de la mirada (sin Filament; se prueban en
 * la JVM): respiración, balanceo con ruido suave, cambio de peso ocasional y
 * las sacadas de los ojos. Todo depende del tiempo absoluto y de sucesos con
 * hora fija (cada suceso programa el siguiente desde su propia hora, no desde
 * el fotograma): a 30 o a 60 fps dan lo mismo en los mismos instantes.
 */

/** Hash entero → −1..1 (ruido de valor). */
private fun lattice(i: Int, seed: Int): Float {
    var h = i * 374761393 + seed * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 32767.5f - 1f
}

/** Ruido de valor 1D suave (interpolación quíntica), −1..1. */
internal fun valueNoise(x: Float, seed: Int): Float {
    val fl = floor(x)
    val i = fl.toInt()
    val u = smootherstep(x - fl)
    val a = lattice(i, seed)
    return a + (lattice(i + 1, seed) - a) * u
}

/** Ruido suave de 2 octavas (−1..1), frecuencia de la octava baja ≈ 1 por unidad de [x]. */
internal fun noise2(x: Float, seed: Int): Float =
    (valueNoise(x, seed) + 0.5f * valueNoise(2f * x + 13.7f, seed + 101)) / 1.5f

/**
 * Curva de respiración 0..1 en la fase [p] (0..1): inspira en la fracción
 * [inhale] del ciclo y espira en el resto, con velocidad nula arriba y abajo.
 */
internal fun breathCurve(p: Float, inhale: Float): Float {
    val x = p - floor(p)
    return if (x < inhale) 0.5f - 0.5f * cos(PI.toFloat() * x / inhale)
    else 0.5f + 0.5f * cos(PI.toFloat() * (x - inhale) / (1f - inhale))
}

/**
 * Cambio de peso: cada [SwayConfig.shiftMin]–[SwayConfig.shiftMax] s, la
 * cadera va hacia un lado en 1,5–2 s, se queda un poco y vuelve. [value] da
 * −1..1 (el signo es el lado).
 */
internal class WeightShift(private val cfg: SwayConfig, private val random: Random) {
    private var start = -1e9f
    private var dur = 1f
    private var dir = 1f
    private var next = uniform(cfg.shiftMin, cfg.shiftMax)

    private fun uniform(a: Float, b: Float) = a + (b - a) * random.nextFloat()

    fun value(t: Float): Float {
        while (t >= next) {
            start = next
            dur = uniform(cfg.shiftDurMin, cfg.shiftDurMax)
            dir = if (random.nextBoolean()) 1f else -1f
            next = start + uniform(cfg.shiftMin, cfg.shiftMax)
        }
        val u = t - start
        val hold = 0.5f * dur
        return when {
            u < 0f -> 0f
            u < dur -> dir * smootherstep(u / dur)
            u < dur + hold -> dir
            u < 2f * dur + hold -> dir * (1f - smootherstep((u - dur - hold) / dur))
            else -> 0f
        }
    }
}

/**
 * Respiración, balanceo y cambio de peso: ángulos (rad) y desplazamiento (m)
 * que el animador suma a la pose de los clips, en ejes del personaje.
 */
internal class ProceduralLayer(private val cfg: MashaAnimConfig, seed: Long) {
    private val shift = WeightShift(cfg.sway, Random(seed))
    private val noiseSeed = (seed * 31).toInt()
    private var boost = 1f

    /** Respiración 0..1 (ya con el refuerzo). */
    var breath = 0f
        private set

    /* Salidas: rad salvo shiftX (m). Pitch + = hacia delante; roll + = hacia su izquierda. */
    var spine1Pitch = 0f; private set
    var spine2Pitch = 0f; private set
    var shoulderRaise = 0f; private set
    var hipsRoll = 0f; private set
    var spineRoll = 0f; private set
    var headYaw = 0f; private set
    var headPitch = 0f; private set
    var headRoll = 0f; private set
    var armL = 0f; private set
    var armR = 0f; private set
    var shiftX = 0f; private set

    /**
     * [gesture]: hay o hubo hace poco un gesto (respiración ×boost).
     * [amount]: 0..1 de toda la capa.
     */
    fun update(t: Float, dt: Float, gesture: Boolean, amount: Float) {
        val b = cfg.breath
        val s = cfg.sway
        boost += ((if (gesture) b.boost else 1f) - boost) * (1f - exp(-dt / b.boostTau))
        val k = amount * boost
        breath = breathCurve(t * b.rateHz, b.inhale) * k
        // Al inspirar el pecho se abre: la columna se extiende (hacia atrás).
        spine1Pitch = -0.5f * b.spinePitch * DEG * breath
        spine2Pitch = -0.5f * b.spinePitch * DEG * breath
        shoulderRaise = b.shoulderRaise * DEG * breath

        val a = amount * DEG
        val sh = shift.value(t)
        shiftX = sh * s.shiftDistance * amount
        hipsRoll = s.hipsRoll * a * noise2(t * s.hipsHz, noiseSeed) + sh * s.shiftRoll * a
        // La columna compensa la mitad de la inclinación del cambio de peso (la cabeza sigue centrada).
        spineRoll = s.spine * a * noise2(t * s.spineHz, noiseSeed + 1) - 0.6f * sh * s.shiftRoll * a
        headYaw = s.head * a * noise2(t * s.headHz, noiseSeed + 2)
        headPitch = 0.7f * s.head * a * noise2(t * s.headHz, noiseSeed + 3)
        headRoll = 0.7f * s.head * a * noise2(t * s.headHz, noiseSeed + 4)
        armL = s.arms * a * noise2(t * s.armsHz, noiseSeed + 5)
        armR = s.arms * a * noise2(t * s.armsHz, noiseSeed + 6)
    }
}

/**
 * Movimiento balístico de los ojos entre dos puntos (yaw, pitch en grados):
 * duración de la secuencia principal, 21 ms + 2,2 ms/°; perfil smootherstep
 * (sin rebote, no es un muelle).
 */
internal class Ballistic {
    private var fromY = 0f
    private var fromP = 0f
    var toY = 0f; private set
    var toP = 0f; private set
    private var start = -1e9f
    var duration = 0f; private set

    fun yaw(t: Float) = fromY + (toY - fromY) * progress(t)
    fun pitch(t: Float) = fromP + (toP - fromP) * progress(t)

    private fun progress(t: Float): Float = if (duration <= 0f) 1f else smootherstep((t - start) / duration)

    /** Salta desde donde esté en [at] hacia (y, p). Devuelve la amplitud (°). */
    fun go(at: Float, y: Float, p: Float, cfg: LookConfig): Float {
        fromY = yaw(at); fromP = pitch(at)
        toY = y; toP = p
        start = at
        val amp = sqrt((toY - fromY) * (toY - fromY) + (toP - fromP) * (toP - fromP))
        duration = saccadeDuration(amp, cfg)
        return amp
    }

    companion object {
        fun saccadeDuration(ampDeg: Float, cfg: LookConfig): Float = cfg.saccadeBase + cfg.saccadePerDeg * ampDeg
    }
}

/**
 * Sacadas: fijaciones de 0,8–2,5 s que refijan 1–3° (dentro de la cara de
 * quien mira), microsacadas de menos de 0,5° (≈ 1/s) y, pensando, vistazos a
 * otro lado de 5–15° cada 4–8 s que duran 0,8–2 s. [yaw]/[pitch] son el
 * desvío de los ojos respecto al objetivo; [headYaw]/[headPitch], la parte del
 * vistazo que toma la cabeza. [bigShift] se enciende en el fotograma de una
 * sacada mayor que [LookConfig.blinkShift] (parpadeo).
 */
internal class Saccades(private val cfg: LookConfig, seed: Long) {
    // Un azar por canal: el orden de los sucesos de canales distintos puede
    // caer en fotogramas distintos según la frecuencia, y no debe cambiar nada.
    private val rFix = Random(seed)
    private val rMicro = Random(seed + 1)
    private val rAway = Random(seed + 2)
    private val fix = Ballistic()
    private val micro = Ballistic()
    private val away = Ballistic()
    private var nextFix = uniform(rFix, cfg.fixationMin, cfg.fixationMax)
    private var nextMicro = uniform(rMicro, cfg.microIntervalMin, cfg.microIntervalMax)
    private var nextAway = Float.MAX_VALUE
    private var awayBack = Float.MAX_VALUE
    private var wasThinking = false

    var yaw = 0f; private set
    var pitch = 0f; private set
    var headYaw = 0f; private set
    var headPitch = 0f; private set
    var bigShift = false; private set

    /** Registro de sacadas (tipo, hora, amplitud, duración) para pruebas. */
    var onSaccade: ((kind: Int, at: Float, amp: Float, duration: Float) -> Unit)? = null

    private fun uniform(r: Random, a: Float, b: Float) = a + (b - a) * r.nextFloat()

    private fun emit(kind: Int, at: Float, amp: Float, b: Ballistic) {
        if (amp > cfg.blinkShift) bigShift = true
        onSaccade?.invoke(kind, at, amp, b.duration)
    }

    fun update(t: Float, thinking: Boolean) {
        bigShift = false
        while (t >= nextFix) {
            val at = nextFix
            val py = fix.toY; val pp = fix.toP
            val r = sqrt(py * py + pp * pp)
            // Lejos del centro, el salto vuelve hacia él (±60°); si no, a cualquier lado.
            val dir = if (r > 0.5f * cfg.refixMax) atan2(-pp, -py) + uniform(rFix, -1.05f, 1.05f) else uniform(rFix, 0f, 2f * PI.toFloat())
            val jump = uniform(rFix, cfg.refixMin, cfg.refixMax)
            emit(KIND_FIX, at, fix.go(at, py + jump * cos(dir), pp + jump * sin(dir), cfg), fix)
            nextFix = at + uniform(rFix, cfg.fixationMin, cfg.fixationMax)
        }
        while (t >= nextMicro) {
            val at = nextMicro
            val my = micro.toY; val mp = micro.toP
            val dir = uniform(rMicro, 0f, 2f * PI.toFloat())
            val j = uniform(rMicro, cfg.microMin, cfg.microMax)
            var ny = my + j * cos(dir)
            var np = mp + j * sin(dir)
            if (sqrt(ny * ny + np * np) > cfg.microMax) { ny = my - j * cos(dir); np = mp - j * sin(dir) }
            emit(KIND_MICRO, at, micro.go(at, ny, np, cfg), micro)
            nextMicro = at + uniform(rMicro, cfg.microIntervalMin, cfg.microIntervalMax)
        }
        if (thinking && !wasThinking) nextAway = t + uniform(rAway, 0.8f, 2f)
        if (!thinking && wasThinking) {
            nextAway = Float.MAX_VALUE
            if (awayBack != Float.MAX_VALUE) {
                awayBack = Float.MAX_VALUE
                emit(KIND_AWAY, t, away.go(t, 0f, 0f, cfg), away)
            }
        }
        wasThinking = thinking
        while (t >= awayBack || t >= nextAway) {
            if (awayBack <= nextAway) {
                val at = awayBack
                awayBack = Float.MAX_VALUE
                emit(KIND_AWAY, at, away.go(at, 0f, 0f, cfg), away)
            } else {
                val at = nextAway
                val side = if (rAway.nextBoolean()) 1f else -1f
                val y = side * uniform(rAway, cfg.awayMin, cfg.awayMax)
                val p = uniform(rAway, cfg.awayPitchMin, cfg.awayPitchMax)
                emit(KIND_AWAY, at, away.go(at, y, p, cfg), away)
                awayBack = at + uniform(rAway, cfg.awayHoldMin, cfg.awayHoldMax)
                nextAway = at + uniform(rAway, cfg.awayIntervalMin, cfg.awayIntervalMax)
            }
        }
        val ay = away.yaw(t)
        val ap = away.pitch(t)
        yaw = fix.yaw(t) + micro.yaw(t) + ay
        pitch = fix.pitch(t) + micro.pitch(t) + ap
        headYaw = ay * cfg.awayHeadShare
        headPitch = ap * cfg.awayHeadShare
    }

    companion object {
        const val KIND_FIX = 0
        const val KIND_MICRO = 1
        const val KIND_AWAY = 2
    }
}
