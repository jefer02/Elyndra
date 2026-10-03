package com.elyndra.launcher.ui.masha

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/*
 * El carácter de la cara de Masha, sin Filament (se prueba en la JVM):
 * - [MoodFace]: cada ánimo como una combinación de morphs ARKit del GLB (la
 *   sonrisa de Duchenne al jugar, cejas arriba y ojos abiertos con curiosidad,
 *   una ceja abajo y los labios apretados al pensar…), no solo como color; más
 *   la atención al escuchar, la pupila y la postura de la cabeza de cada ánimo.
 * - [FaceExpression]: lo anima: un pico ("ápice") al cambiar de ánimo que luego
 *   se asienta, ruido lento e independiente por lado (la cara nunca está quieta
 *   del todo), microexpresiones, cejas del habla con el estilo del ánimo,
 *   párpados que siguen a la mirada y la pupila.
 * - [HeadGestures]: inclinaciones y cabeceos al escuchar (asiente en las pausas
 *   de quien le habla) y al hablar (la inclinación cambia en los acentos).
 * Nada crea objetos por fotograma.
 */

/**
 * Canales de expresión: un morph ARKit de `Masha_Head` cada uno (L/R = lado de
 * ella), salvo [LID], que baja el párpado superior y se suma al parpadeo.
 */
internal object Ex {
    const val SMILE_L = 0
    const val SMILE_R = 1
    const val CHEEK_L = 2
    const val CHEEK_R = 3
    const val SQUINT_L = 4
    const val SQUINT_R = 5
    const val WIDE_L = 6
    const val WIDE_R = 7
    const val BROW_INNER = 8
    const val BROW_OUTER_L = 9
    const val BROW_OUTER_R = 10
    const val BROW_DOWN_L = 11
    const val BROW_DOWN_R = 12
    const val FROWN_L = 13
    const val FROWN_R = 14
    const val PRESS = 15
    const val MOUTH_LEFT = 16
    const val MOUTH_RIGHT = 17
    const val DIMPLE = 18
    const val LID = 19
    const val COUNT = 20

    /** Morph de cada canal (el de [LID] no existe: va al parpadeo). */
    val MORPH: Array<String?> = arrayOf(
        "mouthSmileLeft", "mouthSmileRight", "cheekSquintLeft", "cheekSquintRight",
        "eyeSquintLeft", "eyeSquintRight", "eyeWideLeft", "eyeWideRight",
        "browInnerUp", "browOuterUpLeft", "browOuterUpRight", "browDownLeft", "browDownRight",
        "mouthFrownLeft", "mouthFrownRight", "mouthPress", "mouthLeft", "mouthRight", "mouthDimple",
        null,
    )
}

/**
 * Qué hace la cara en cada ánimo, en pesos de morph (estado asentado, sin ruido
 * ni microexpresiones). Pocos morphs por ánimo (2–8): cada morph activo cuesta
 * GPU en todos los vértices de la cabeza (ver `FaceMorphs.exprBudget`).
 *
 * | Ánimo | Morphs |
 * |---|---|
 * | Neutral | sonrisa leve, algo asimétrica |
 * | Analytical | cejas algo abajo, entrecerrar, labios apretados (concentrada) |
 * | Playful | sonrisa de Duchenne: mouthSmile + cheekSquint + eyeSquint, ceja exterior, hoyuelos |
 * | Warm | Duchenne suave + browInnerUp (ternura) |
 * | Curious | browInnerUp + browOuterUp (una más alta) + eyeWide, media sonrisa |
 * | Thinking | browDown de un lado + mouthPress + boca de lado, párpados algo bajos (la mirada se va a un lado: `Saccades`) |
 * | Concerned | browInnerUp + browDown (cejas de preocupación) + mouthFrown |
 *
 * Escuchar suma [ATTEND] encima del ánimo que haya.
 */
internal object MoodFace {
    private val n = MashaMood.entries.size
    private val targets = Array(n) { FloatArray(Ex.COUNT) }
    private val pupils = FloatArray(n)
    private val browInner = FloatArray(n)
    private val browOuter = FloatArray(n)
    private val tilts = FloatArray(n)
    private val pitches = FloatArray(n)

    /** Atención (escuchar), encima del ánimo: cejas algo arriba, ojos abiertos, media sonrisa. */
    val ATTEND = FloatArray(Ex.COUNT).also {
        it[Ex.BROW_INNER] = 0.16f
        it[Ex.BROW_OUTER_L] = 0.10f
        it[Ex.BROW_OUTER_R] = 0.07f
        it[Ex.WIDE_L] = 0.12f
        it[Ex.WIDE_R] = 0.11f
        it[Ex.SMILE_L] = 0.06f
        it[Ex.SMILE_R] = 0.05f
    }

    /** Dilatación de la pupila al escuchar (se suma a la del ánimo). */
    const val ATTEND_PUPIL = 0.22f

    /**
     * @param pupil dilatación de la pupila (radio relativo: 0,2 = 20 % más ancha).
     * @param inner / [outer] cómo reparte el ánimo el acento de cejas del habla.
     * @param tilt inclinación de la cabeza (°, el lado se elige al entrar en el ánimo).
     * @param pitch cabeceo sostenido (°, + = barbilla abajo).
     */
    private fun def(m: MashaMood, pupil: Float, inner: Float, outer: Float, tilt: Float, pitch: Float, vararg w: Pair<Int, Float>) {
        val o = m.ordinal
        pupils[o] = pupil
        browInner[o] = inner
        browOuter[o] = outer
        tilts[o] = tilt
        pitches[o] = pitch
        for ((k, v) in w) targets[o][k] = v
    }

    init {
        def(MashaMood.Neutral, 0f, 0.7f, 0.5f, 0f, 0f, Ex.SMILE_L to 0.07f, Ex.SMILE_R to 0.055f)
        def(
            MashaMood.Analytical, -0.10f, 0.5f, 0.45f, 0f, 1f,
            Ex.BROW_DOWN_L to 0.16f, Ex.BROW_DOWN_R to 0.11f, Ex.SQUINT_L to 0.15f, Ex.SQUINT_R to 0.12f, Ex.PRESS to 0.10f,
        )
        def(
            MashaMood.Playful, 0.18f, 0.5f, 0.75f, 3f, -0.5f,
            Ex.SMILE_L to 0.58f, Ex.SMILE_R to 0.48f, Ex.CHEEK_L to 0.40f, Ex.CHEEK_R to 0.32f,
            Ex.SQUINT_L to 0.30f, Ex.SQUINT_R to 0.24f, Ex.BROW_OUTER_L to 0.14f, Ex.DIMPLE to 0.12f,
        )
        def(
            MashaMood.Warm, 0.20f, 0.8f, 0.4f, 2.5f, 0.5f,
            Ex.SMILE_L to 0.36f, Ex.SMILE_R to 0.31f, Ex.CHEEK_L to 0.22f, Ex.CHEEK_R to 0.19f,
            Ex.SQUINT_L to 0.17f, Ex.SQUINT_R to 0.15f, Ex.BROW_INNER to 0.14f,
        )
        def(
            MashaMood.Thinking, 0.12f, 0.6f, 0.4f, 3f, -2f,
            Ex.BROW_DOWN_L to 0.38f, Ex.BROW_INNER to 0.14f, Ex.SQUINT_L to 0.22f, Ex.SQUINT_R to 0.09f,
            Ex.PRESS to 0.30f, Ex.MOUTH_LEFT to 0.22f, Ex.LID to 0.07f,
        )
        def(
            MashaMood.Concerned, 0.06f, 1f, 0.15f, 2f, 1.5f,
            Ex.BROW_INNER to 0.58f, Ex.BROW_DOWN_L to 0.15f, Ex.BROW_DOWN_R to 0.13f,
            Ex.FROWN_L to 0.44f, Ex.FROWN_R to 0.40f, Ex.PRESS to 0.12f,
        )
        def(
            MashaMood.Curious, 0.28f, 0.6f, 0.7f, 4.5f, -0.5f,
            Ex.BROW_INNER to 0.32f, Ex.BROW_OUTER_L to 0.30f, Ex.BROW_OUTER_R to 0.20f,
            Ex.WIDE_L to 0.24f, Ex.WIDE_R to 0.20f, Ex.SMILE_L to 0.10f, Ex.SMILE_R to 0.07f,
        )
    }

    /** Pesos del ánimo (no modificar: es la tabla). */
    fun target(m: MashaMood): FloatArray = targets[m.ordinal]

    fun pupil(m: MashaMood): Float = pupils[m.ordinal]
    fun browInner(m: MashaMood): Float = browInner[m.ordinal]
    fun browOuter(m: MashaMood): Float = browOuter[m.ordinal]
    fun tiltDeg(m: MashaMood): Float = tilts[m.ordinal]
    fun pitchDeg(m: MashaMood): Float = pitches[m.ordinal]

    /** La expresión asentada de [m] con [listen] (0..1) de atención, en [out] (canales [Ex]). */
    fun pose(m: MashaMood, listen: Float, out: FloatArray) {
        val t = targets[m.ordinal]
        for (k in 0 until Ex.COUNT) out[k] = t[k] + listen * ATTEND[k]
    }
}

/** Lo que [FaceExpression] necesita saber en cada fotograma (lo rellena [HoloRig]). */
internal class ExpressionInput {
    var mood = MashaMood.Neutral
    var listening = false
    var speaking = false
    /** Acento de cejas del habla (`LipSync.Frame.brow`). */
    var speechBrow = 0f
    /** Sonrisa que añaden los clips (Var_GlanceSmile, React_Happy). */
    var clipSmile = 0f
    /** Pitch de los ojos respecto a la cabeza (°, + = arriba). */
    var eyePitch = 0f
    /** Curva de parpadeo de este fotograma ([Blink]), por ojo. */
    var blinkL = 0f
    var blinkR = 0f
}

/**
 * La expresión, fotograma a fotograma: escribe en [FaceChannels.expr] y en los
 * párpados ([FaceChannels.blinkL]/[FaceChannels.blinkR]) y deja la pupila en
 * [pupil] y la atención en [attention] (para el shader de los ojos). Depende
 * del tiempo y de sucesos con hora fija: casi no depende de los fps.
 * [noise] y [micros] se pueden apagar en las pruebas.
 */
internal class FaceExpression(seed: Long = 11L, private val noise: Float = NOISE, private val micros: Boolean = true) {
    private val rnd = Random(seed)
    private val noiseSeed = (seed * 131).toInt()
    private val smooth = FloatArray(Ex.COUNT)
    private val goal = FloatArray(Ex.COUNT)
    private val microWeights = FloatArray(MICRO_KINDS)

    /** Dilatación de la pupila (radio relativo). */
    var pupil = 0f
        private set

    /** Atención 0..1 (escuchar o curiosidad): aviva el anillo del iris y el brillo del ojo. */
    var attention = 0f
        private set

    private var listen = 0f
    private var curious = 0f
    private var mood: MashaMood? = null
    private var moodAt = -100f

    private var micro = -1
    private var microAt = -100f
    private var microDur = 1f
    private var microSide = 1f
    private var nextMicro = 2.5f

    /** Microexpresión en curso (−1 = ninguna), para pruebas y depuración. */
    val microKind: Int get() = micro

    fun update(t: Float, dt: Float, input: ExpressionInput, out: FaceChannels) {
        val m = input.mood
        if (m != mood) {
            // El primer ánimo entra sin pico (al abrir la pantalla); los cambios, con él.
            if (mood != null) moodAt = t
            mood = m
        }
        val kAttend = 1f - exp(-dt / ATTEND_TAU)
        listen += ((if (input.listening) 1f else 0f) - listen) * kAttend
        curious += ((if (m == MashaMood.Curious) 1f else 0f) - curious) * kAttend
        attention = max(listen, curious)

        // 1. Objetivo: el ánimo (con su ápice al cambiar) + la atención + la sonrisa de los clips.
        val apex = 1f + APEX * apexEnvelope(t - moodAt)
        val base = MoodFace.target(m)
        for (k in 0 until Ex.COUNT) goal[k] = base[k] * apex + listen * MoodFace.ATTEND[k]
        val cs = input.clipSmile
        if (cs > 0f) {
            // También de Duchenne: los ojos acompañan.
            goal[Ex.SMILE_L] += 0.6f * cs; goal[Ex.SMILE_R] += 0.6f * cs
            goal[Ex.CHEEK_L] += 0.3f * cs; goal[Ex.CHEEK_R] += 0.3f * cs
            goal[Ex.SQUINT_L] += 0.15f * cs; goal[Ex.SQUINT_R] += 0.15f * cs
        }

        // 2. Aparece deprisa y se va despacio.
        val kRise = 1f - exp(-dt / RISE_TAU)
        val kFall = 1f - exp(-dt / FALL_TAU)
        for (k in 0 until Ex.COUNT) {
            val g = goal[k]
            smooth[k] += (g - smooth[k]) * (if (g > smooth[k]) kRise else kFall)
        }

        // 3. Nunca quieta del todo: ruido lento, multiplicativo (no enciende morphs apagados) e
        // independiente por lado, así la asimetría también vive.
        val e = out.expr
        for (k in 0 until Ex.COUNT) {
            val v = smooth[k]
            e[k] = if (v > 1e-3f) v * (1f + noise * noise2(t * NOISE_HZ + 7.31f * k, noiseSeed + k)) else 0f
        }

        // 4. Microexpresiones.
        if (micros && t >= nextMicro) startMicro(t, m, input)
        if (micro >= 0) applyMicro(t, e)

        // 5. Cejas del habla (acentos), con el estilo del ánimo; una ceja manda un poco, y va cambiando.
        val sb = input.speechBrow
        if (sb > 1e-3f) {
            val bias = 0.3f * noise2(t * 0.17f, noiseSeed + 77)
            val outer = MoodFace.browOuter(m) * sb
            e[Ex.BROW_INNER] += MoodFace.browInner(m) * sb
            e[Ex.BROW_OUTER_L] += outer * (1f + bias)
            e[Ex.BROW_OUTER_R] += outer * (1f - bias)
        }

        // 6. Los párpados siguen a la mirada: abajo baja el superior; arriba lo abre y alza algo las cejas.
        val pitch = input.eyePitch
        val up = GazeConfig.lidUp(pitch)
        e[Ex.WIDE_L] += up
        e[Ex.WIDE_R] += up
        val lift = GazeConfig.browLift(pitch)
        if (lift > 0f) {
            e[Ex.BROW_INNER] += lift
            e[Ex.BROW_OUTER_L] += 0.6f * lift
            e[Ex.BROW_OUTER_R] += 0.6f * lift
        }
        for (k in 0 until Ex.COUNT) e[k] = e[k].coerceIn(0f, 1f)
        val lid = min(1f, e[Ex.LID] + GazeConfig.lidDown(pitch))
        out.blinkL = input.blinkL + (1f - input.blinkL) * lid
        out.blinkR = input.blinkR + (1f - input.blinkR) * lid

        // 7. Pupila: el ánimo y la atención la abren; un vaivén lento (hippus); se cierra más rápido que se abre.
        val hippus = HIPPUS * noise2(t * 0.28f, noiseSeed + 91)
        val pGoal = MoodFace.pupil(m) + MoodFace.ATTEND_PUPIL * listen + (if (input.speaking) 0.04f else 0f) + hippus
        pupil += (pGoal - pupil) * (1f - exp(-dt / (if (pGoal > pupil) PUPIL_OPEN_TAU else PUPIL_CLOSE_TAU)))
    }

    /** 0 → 1 en 0,25 s y vuelve a 0 en ~2 s: el pico de un cambio de ánimo. */
    private fun apexEnvelope(x: Float): Float = when {
        x < 0f -> 0f
        x < APEX_RISE -> smootherstep(x / APEX_RISE)
        else -> exp(-(x - APEX_RISE) / APEX_DECAY)
    }

    private fun startMicro(t: Float, m: MashaMood, input: ExpressionInput) {
        // Hablando, solo la parte de arriba de la cara (la boca es de los visemas).
        var sum = 0f
        for (k in 0 until MICRO_KINDS) {
            var w = MICRO_BIAS[m.ordinal * MICRO_KINDS + k]
            if (input.speaking && (k == M_SMIRK || k == M_PRESS)) w = 0f
            microWeights[k] = w
            sum += w
        }
        val gap = when {
            input.listening -> uniform(2f, 5f)
            input.speaking -> uniform(3f, 8f)
            else -> uniform(2.5f, 7f)
        }
        nextMicro = t + gap
        if (sum <= 0f) return
        var r = rnd.nextFloat() * sum
        var kind = MICRO_KINDS - 1
        for (k in 0 until MICRO_KINDS) {
            r -= microWeights[k]
            if (r <= 0f) { kind = k; break }
        }
        micro = kind
        microAt = t
        microDur = MICRO_DUR[kind] * uniform(0.85f, 1.2f)
        microSide = if (rnd.nextBoolean()) 1f else -1f
    }

    private fun applyMicro(t: Float, e: FloatArray) {
        val x = t - microAt
        if (x >= microDur) {
            micro = -1
            return
        }
        // Sube en 0,12 s, se mantiene y se suelta en 0,3 s.
        val env = smootherstep(x / 0.12f) * (1f - smootherstep((x - (microDur - 0.3f)) / 0.3f))
        val left = microSide > 0f
        when (micro) {
            M_BROW_FLASH -> {
                e[Ex.BROW_INNER] += 0.16f * env
                e[Ex.BROW_OUTER_L] += 0.2f * env
                e[Ex.BROW_OUTER_R] += 0.2f * env
            }
            M_ONE_BROW -> e[if (left) Ex.BROW_OUTER_L else Ex.BROW_OUTER_R] += 0.26f * env
            M_SMIRK -> {
                e[if (left) Ex.SMILE_L else Ex.SMILE_R] += 0.15f * env
                e[if (left) Ex.CHEEK_L else Ex.CHEEK_R] += 0.07f * env
            }
            M_SQUINT -> {
                e[Ex.SQUINT_L] += 0.2f * env
                e[Ex.SQUINT_R] += 0.2f * env
            }
            M_PRESS -> e[Ex.PRESS] += 0.18f * env
        }
    }

    private fun uniform(a: Float, b: Float) = a + (b - a) * rnd.nextFloat()

    companion object {
        /** Tiempo de subida y bajada de la expresión (s). */
        const val RISE_TAU = 0.16f
        const val FALL_TAU = 0.42f
        /** Entrar y salir de la atención (escuchar, curiosidad). */
        const val ATTEND_TAU = 0.35f
        /** Pico al cambiar de ánimo: +35 % que sube en 0,25 s y se asienta en ~2 s. */
        const val APEX = 0.35f
        const val APEX_RISE = 0.25f
        const val APEX_DECAY = 0.9f
        /** Ruido de la expresión: ±14 % a ~0,23 Hz por canal. */
        const val NOISE = 0.14f
        const val NOISE_HZ = 0.23f
        /** Vaivén de la pupila y sus tiempos (se dilata despacio, se contrae antes). */
        const val HIPPUS = 0.035f
        const val PUPIL_OPEN_TAU = 0.55f
        const val PUPIL_CLOSE_TAU = 0.3f

        const val M_BROW_FLASH = 0
        const val M_ONE_BROW = 1
        const val M_SMIRK = 2
        const val M_SQUINT = 3
        const val M_PRESS = 4
        const val MICRO_KINDS = 5

        /** Duración de cada microexpresión (s). */
        private val MICRO_DUR = floatArrayOf(0.5f, 0.8f, 0.9f, 0.7f, 0.6f)

        /**
         * Qué microexpresiones salen en cada ánimo (pesos por ordinal de [MashaMood]):
         * ráfaga de cejas, una ceja, media sonrisa, entrecerrar, apretar labios.
         */
        private val MICRO_BIAS = FloatArray(MashaMood.entries.size * MICRO_KINDS).also {
            fun row(m: MashaMood, vararg w: Float) = w.copyInto(it, m.ordinal * MICRO_KINDS)
            row(MashaMood.Neutral, 2f, 2f, 2f, 2f, 1f)
            row(MashaMood.Analytical, 1f, 3f, 0.5f, 3f, 2f)
            row(MashaMood.Playful, 2f, 2f, 4f, 1.5f, 0f)
            row(MashaMood.Warm, 2f, 1f, 3f, 2f, 0.5f)
            row(MashaMood.Thinking, 0f, 3f, 0f, 2f, 3f)
            row(MashaMood.Concerned, 1f, 1f, 0f, 2f, 3f)
            row(MashaMood.Curious, 3f, 3f, 1f, 0.5f, 0f)
        }
    }
}

/**
 * Cabeceos e inclinaciones de cabeza que el animador suma después de la mirada
 * ([MotionInput.headNod]/[MotionInput.headRoll]/[MotionInput.headYaw], rad):
 * - el ánimo inclina un poco la cabeza (curiosa, la que más) y la sube o baja;
 * - escuchando: inclinación atenta (5°) hacia un lado y cabeceos breves en las
 *   pausas de quien habla (señal de "te sigo"), como mucho uno cada 2,2 s;
 * - hablando: cada acento fuerte cambia de lado la inclinación (1–2,5°) y la
 *   cabeza deriva un poco en yaw.
 * Muelles críticos: nada salta y casi no depende de los fps.
 */
internal class HeadGestures(seed: Long = 23L) {
    private val rnd = Random(seed)
    private val noiseSeed = (seed * 97).toInt()
    private val rollS = CritSpring()
    private val pitchS = CritSpring()
    private val yawS = CritSpring()

    /** Rad, + = barbilla abajo. */
    var nod = 0f
        private set
    /** Rad, + = hacia su izquierda. */
    var roll = 0f
        private set
    var yaw = 0f
        private set

    private var mood: MashaMood? = null
    private var moodSide = 1f
    private var wasListening = false
    private var listenSide = 1f
    private var mic = 0f
    private var micOn = 0f
    private var lastNod = -100f
    private var nodAt = -100f
    private var nodAmp = 0f
    private var nodDouble = false
    private var speakRoll = 0f
    private var speakSide = 1f
    private var lastBrow = 0f

    /** Cabeceos de escucha lanzados (para pruebas). */
    var backchannels = 0
        private set

    fun update(t: Float, dt: Float, m: MashaMood, speaking: Boolean, listening: Boolean, micLevel: Float, speechBrow: Float) {
        if (m != mood) {
            mood = m
            moodSide = if (rnd.nextBoolean()) 1f else -1f
        }
        var rollGoal = MoodFace.tiltDeg(m) * moodSide
        val pitchGoal = MoodFace.pitchDeg(m)
        var yawGoal = 0f

        if (listening && !wasListening) {
            listenSide = if (rnd.nextBoolean()) 1f else -1f
            micOn = 0f
        }
        wasListening = listening
        if (listening) {
            rollGoal = LISTEN_TILT * listenSide
            // Envolvente de la voz de quien habla: sube rápido, baja despacio.
            mic += (micLevel - mic) * (1f - exp(-dt / (if (micLevel > mic) 0.05f else 0.25f)))
            if (mic > MIC_ON) {
                micOn += dt
            } else if (mic < MIC_OFF) {
                // Pausa tras una frase: "te sigo".
                if (micOn > 0.5f && t - lastNod > NOD_GAP && rnd.nextFloat() < NOD_P) startNod(t)
                micOn = 0f
            }
        } else {
            mic = 0f
            micOn = 0f
        }

        if (speaking) {
            // Cada acento fuerte (subida de las cejas del habla) cambia de lado la inclinación.
            if (speechBrow > ACCENT && lastBrow <= ACCENT) {
                speakSide = -speakSide
                speakRoll = speakSide * (1f + 1.5f * rnd.nextFloat())
            }
            yawGoal = 1.2f * noise2(t * 0.15f, noiseSeed)
        } else {
            speakRoll = 0f
        }
        lastBrow = speechBrow
        rollGoal += speakRoll

        roll = rollS.update(rollGoal * DEG, 0.35f, dt)
        nod = pitchS.update(pitchGoal * DEG, 0.4f, dt) + nodBump(t) * DEG
        yaw = yawS.update(yawGoal * DEG, 0.5f, dt)
    }

    private fun startNod(t: Float) {
        nodAt = t
        lastNod = t
        nodAmp = 1.6f + 0.8f * rnd.nextFloat()
        nodDouble = rnd.nextFloat() < 0.3f
        backchannels++
    }

    /** Cabeceo (°): baja y vuelve en 0,55 s; a veces doble ("ajá"), el segundo más pequeño. */
    private fun nodBump(t: Float): Float {
        val x = t - nodAt
        var v = bump(x, 0.55f) * nodAmp
        if (nodDouble) v += bump(x - 0.42f, 0.45f) * 0.6f * nodAmp
        return v
    }

    private fun bump(x: Float, d: Float): Float = if (x <= 0f || x >= d) 0f else 0.5f - 0.5f * cos(2f * PI.toFloat() * x / d)

    companion object {
        /** Inclinación atenta al escuchar (°). */
        const val LISTEN_TILT = 5f
        /** Nivel del micrófono: hablando / pausa. */
        const val MIC_ON = 0.35f
        const val MIC_OFF = 0.2f
        const val NOD_P = 0.65f
        const val NOD_GAP = 2.2f
        /** Acento de cejas del habla que cuenta como fuerte. */
        const val ACCENT = 0.12f
    }
}
