package com.elyndra.launcher.ui.masha

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/*
 * Qué clips suenan y cuándo (sin Filament; se prueba en la JVM): los clips
 * que trae el modelo por nombre, la máquina de estados del cuerpo (reposo,
 * variaciones, escuchar, pensar, hablar con gestos, acciones puntuales) y lo
 * que la mueve: el habla con histéresis y la envolvente de la voz.
 */

/**
 * Los clips del modelo, por nombre. Contrato del equipo de animación:
 * "Base", "Idle", "Var_*", "Talk_*", "Wave", "Point", "Think" (bucle),
 * "Listen", "Nod", "Shrug", "React_*". Con el modelo de hoy (Idle, Talk,
 * Listen, Think, Explain, Wave) se degrada así: sin Var_* no hay variaciones;
 * sin Talk_* se habla con "Talk" en bucle; "Think" es un gesto de una vez
 * (no un bucle) salvo que el modelo ya siga el contrato; Point ↔ Explain.
 */
internal class ClipSet(val names: List<String>, val durations: FloatArray, config: MashaAnimConfig = MashaAnimConfig()) {

    private fun find(name: String) = names.indexOf(name)
    private fun prefixed(p: String) = names.indices.filter { names[it].startsWith(p) }.toIntArray()

    /** Clips del cuerpo (los anillos del holotanque van aparte). */
    val body: IntArray = names.indices.filter { !names[it].startsWith("RingSpin") }.toIntArray()

    val base = find("Base")
    val idle = find("Idle").let { if (it >= 0) it else if (base >= 0) base else body.firstOrNull() ?: -1 }
    val listen = find("Listen")
    val think = find("Think")
    val beats = prefixed("Talk_")
    val variations = prefixed("Var_")
    val talkLoop = if (beats.isEmpty()) find("Talk") else -1
    val wave = find("Wave")
    val point = find("Point").let { if (it >= 0) it else find("Explain") }
    val explain = find("Explain").let { if (it >= 0) it else point }
    val nod = find("Nod")
    val shrug = find("Shrug")
    val reactHappy = find("React_Happy")
    val reactSurprised = find("React_Surprised")

    /** ¿El modelo sigue ya el contrato nuevo? */
    val contract = base >= 0 || beats.isNotEmpty() || variations.isNotEmpty()

    /** "Think" es una pose que se mantiene en bucle (contrato) o un gesto de una vez (modelo de hoy). */
    val thinkLoops = contract

    /** Por clip: ¿da pasos (suelta los pies)?, cuánto deja mirar a la cámara, cuánta sonrisa. */
    val stepping = BooleanArray(names.size) { i -> config.feet.steppingPrefixes.any { names[i].startsWith(it) } }
    val lookAt = FloatArray(names.size) { i -> config.lookAtScale.entries.firstOrNull { names[i].startsWith(it.key) }?.value ?: 1f }
    val smile = FloatArray(names.size) { i -> config.smileClips.entries.firstOrNull { names[i].startsWith(it.key) }?.value ?: 0f }

    fun duration(i: Int): Float = if (i in durations.indices) durations[i] else 0f

    fun describe(): String =
        "contract=$contract idle=${names.getOrNull(idle)} base=${names.getOrNull(base)} listen=${names.getOrNull(listen)} " +
            "think=${names.getOrNull(think)}(loop=$thinkLoops) talkLoop=${names.getOrNull(talkLoop)} beats=${beats.size} " +
            "vars=${variations.size} wave=${names.getOrNull(wave)} point=${names.getOrNull(point)} explain=${names.getOrNull(explain)}"
}

/** Gestos que se pueden pedir. */
internal enum class Action { Wave, Point, Explain, Nod, Shrug, ReactHappy, ReactSurprised }

/**
 * "Hablando" con histéresis: sigue activo hasta [hold] s de silencio, así los
 * huecos entre frases del TTS no hacen parpadear el estado. [sentenceEnded]
 * marca el fotograma en que la voz bruta se calla (final de frase).
 */
internal class SpeechDebounce(private val hold: Float) {
    var active = false
        private set
    var sentenceEnded = false
        private set
    private var lastOn = -1e9f
    private var wasRaw = false

    fun update(t: Float, raw: Boolean): Boolean {
        sentenceEnded = wasRaw && !raw
        wasRaw = raw
        if (raw) {
            lastOn = t
            active = true
        } else if (active && t - lastOn >= hold) {
            active = false
        }
        return active
    }
}

/**
 * Bolsa barajada: saca todos antes de repetir y nunca da uno de los
 * [avoidLast] últimos (si hay bastantes para elegir). Sin objetos por sacada.
 */
internal class ShuffleBag(private val items: IntArray, avoidLast: Int, private val random: Random) {
    private val avoid = min(avoidLast, max(items.size - 1, 0))
    private val bag = IntArray(items.size)
    private var left = 0
    private val recent = IntArray(max(avoid, 1)) { -1 }
    private var recentHead = 0

    val isEmpty: Boolean get() = items.isEmpty()

    private fun refill() {
        items.copyInto(bag)
        for (i in bag.size - 1 downTo 1) {
            val k = random.nextInt(i + 1)
            val x = bag[i]; bag[i] = bag[k]; bag[k] = x
        }
        left = bag.size
    }

    private fun isRecent(c: Int): Boolean {
        for (i in 0 until avoid) if (recent[i] == c) return true
        return false
    }

    fun next(): Int {
        if (items.isEmpty()) return -1
        repeat(2) {
            if (left == 0) refill()
            for (i in 0 until left) {
                val c = bag[i]
                if (!isRecent(c)) {
                    bag[i] = bag[left - 1]
                    left--
                    if (avoid > 0) {
                        recent[recentHead] = c
                        recentHead = (recentHead + 1) % avoid
                    }
                    return c
                }
            }
            left = 0
        }
        return items[0]
    }
}

/**
 * Envolvente de la voz (canal Open de la boca): ataque y caída de un polo,
 * más una media lenta para detectar los acentos (subidas en dB sobre ella).
 */
internal class VoiceEnvelope(private val cfg: TalkConfig) {
    var env = 0f
        private set
    var mean = 0f
        private set

    fun update(x: Float, dt: Float) {
        if (dt <= 0f) return
        val tau = if (x > env) cfg.attack else cfg.release
        env += (x - env) * (1f - exp(-dt / tau))
        mean += (env - mean) * (1f - exp(-dt / cfg.meanTau))
    }

    /** Cuántos dB está la envolvente sobre su media. */
    fun riseDb(): Float = 20f * log10((env + 1e-3f) / (mean + 1e-3f))

    fun reset() {
        env = 0f
        mean = 0f
    }
}

/** Lo que el cuerpo necesita saber de Masha en este fotograma. */
internal class MotionInput {
    var speaking = false
    var listening = false
    var thinking = false
    var mood = MashaMood.Neutral
    /** Canal Open de la boca (0..1) mientras suena la voz. */
    var voice = 0f
}

/**
 * Máquina de estados del cuerpo sobre la [BlendStack].
 *
 * Estado base (lo que suena debajo): Idle (bucle de reposo), Listen, Think,
 * Talk. Encima, a lo sumo: una variación de reposo (Var_*, cada 8–20 s de
 * reposo libre), una acción (saludo, señalar, asentir, reacción…) y los
 * golpes del habla (Talk_*, al compás de la voz). Pensar no bloquea hablar:
 * al empezar a hablar, sale de Think. Los bucles no vuelven a empezar al
 * volver a ellos: su fase es el reloj global.
 */
internal class MotionDirector(val clips: ClipSet, private val cfg: MashaAnimConfig, private val random: Random) {

    enum class State { Idle, Listen, Think, Talk }

    var state = State.Idle
        private set

    private val speech = SpeechDebounce(cfg.talk.silenceHold)
    val envelope = VoiceEnvelope(cfg.talk)
    private val variationBag = ShuffleBag(clips.variations, cfg.idle.avoidLast, random)
    private val beatBag = ShuffleBag(clips.beats, cfg.talk.avoidLast, random)

    private var baseClip = -1
    private var varId = 0
    private var actionId = 0
    private var actionOut = 0f
    private var beatId = 0
    private var beatOut = 0f
    private var thinkId = 0
    private var pending: Action? = null

    /** Segundos de reposo libre acumulados y cuándo toca la próxima variación. */
    private var freeIdle = 0f
    var nextVariation = 0f
        private set

    private var lastStroke = -1e9f
    private var strokeGap = 0f

    private var wasListening = false
    private var lastMood = MashaMood.Neutral

    /** Cuándo acabó el último gesto (para reforzar la respiración). */
    var lastGestureEnd = -1e9f
        private set

    /** Final de frase en este fotograma (parpadeo). */
    val sentenceEnded: Boolean get() = speech.sentenceEnded

    /** Para pruebas y registro: clips que se han lanzado, en orden. */
    var onPlay: ((clip: Int, kind: Int) -> Unit)? = null

    fun start(stack: BlendStack, t: Float) {
        stack.clear()
        baseClip = -1
        pushBase(stack, clips.idle, 0f, t)
        nextVariation = uniform(cfg.idle.minInterval, cfg.idle.maxInterval)
        strokeGap = uniform(cfg.talk.minGap, cfg.talk.maxGap)
    }

    /** Pide un gesto (se lanza en el próximo [update]). */
    fun cue(a: Action) {
        pending = a
    }

    fun update(t: Float, dt: Float, input: MotionInput, stack: BlendStack) {
        val talking = speech.update(t, input.speaking)
        envelope.update(if (input.speaking) input.voice else 0f, dt)

        val next = when {
            input.listening -> State.Listen
            talking -> State.Talk
            input.thinking -> State.Think
            else -> State.Idle
        }
        if (next != state) transition(next, t, stack)

        // Reacciones solas: asentir al terminar de escuchar; alegría si el ánimo se vuelve juguetón.
        if (wasListening && !input.listening && clips.nod >= 0 && random.nextFloat() < cfg.idle.nodAfterListen) {
            if (pending == null) pending = Action.Nod
        }
        wasListening = input.listening
        if (input.mood != lastMood) {
            if (input.mood == MashaMood.Playful && cfg.idle.reactToPlayful && clips.reactHappy >= 0 && !input.listening && pending == null) {
                pending = Action.ReactHappy
            }
            lastMood = input.mood
        }
        pending?.let { startAction(it, t, stack) }
        pending = null

        tickAction(t, stack)
        tickThink(t, stack)
        tickVariation(t, dt, stack)
        tickBeats(t, stack)
    }

    /* ── estados base ── */

    private fun clipFor(s: State): Int = when (s) {
        State.Idle -> clips.idle
        State.Listen -> if (clips.listen >= 0) clips.listen else clips.idle
        State.Think -> if (clips.thinkLoops && clips.think >= 0) clips.think else clips.idle
        State.Talk -> if (clips.talkLoop >= 0) clips.talkLoop else clips.idle
    }

    private fun pushBase(stack: BlendStack, clip: Int, fade: Float, t: Float) {
        if (clip < 0 || clip == baseClip) return
        val d = clips.duration(clip)
        stack.push(clip, d, if (d > 0f) t % d else 0f, loop = true, fade = fade, base = true, tag = KIND_BASE)
        baseClip = clip
        onPlay?.invoke(clip, KIND_BASE)
    }

    private fun transition(next: State, t: Float, stack: BlendStack) {
        val f = cfg.fades
        val prev = state
        val fade = when {
            next == State.Talk -> f.talkIn
            prev == State.Talk -> f.talkOut
            next == State.Listen || next == State.Think -> f.holdIn
            else -> f.holdOut
        }
        // Una variación se corta en seco (con inercialización) si hay que atender.
        if (next != State.Idle && varId != 0) {
            stack.remove(varId)
            stack.inertiaRequested = true
            varId = 0
        }
        if (prev == State.Talk && beatId != 0) {
            stack.fadeOut(beatId, f.talkOut)
            beatId = 0
            lastGestureEnd = t
        }
        if (prev == State.Think && thinkId != 0) {
            stack.fadeOut(thinkId, if (next == State.Talk) f.talkIn else f.holdOut)
            thinkId = 0
        }
        state = next
        freeIdle = 0f
        pushBase(stack, clipFor(next), fade, t)
        // Modelo de hoy: pensar es un gesto de una vez encima del reposo.
        if (next == State.Think && !clips.thinkLoops && clips.think >= 0 && thinkId == 0 && actionId == 0) {
            thinkId = stack.push(clips.think, clips.duration(clips.think), 0f, loop = false, fade = f.holdIn, base = false, tag = KIND_THINK)
            onPlay?.invoke(clips.think, KIND_THINK)
        }
    }

    /* ── acciones ── */

    private fun clipOf(a: Action): Int = when (a) {
        Action.Wave -> clips.wave
        Action.Point -> clips.point
        Action.Explain -> if (clips.explain >= 0) clips.explain else if (clips.beats.isNotEmpty()) beatBag.next() else -1
        Action.Nod -> clips.nod
        Action.Shrug -> clips.shrug
        Action.ReactHappy -> clips.reactHappy
        Action.ReactSurprised -> clips.reactSurprised
    }

    private fun startAction(a: Action, t: Float, stack: BlendStack) {
        val clip = clipOf(a)
        if (clip < 0) return
        val f = cfg.fades
        val fadeIn = when (a) {
            Action.Wave -> f.waveIn
            Action.ReactHappy, Action.ReactSurprised -> f.reactIn
            else -> f.gestureIn
        }
        actionOut = when (a) {
            Action.Wave -> f.waveOut
            Action.ReactHappy, Action.ReactSurprised -> f.reactOut
            else -> f.gestureOut
        }
        if (actionId != 0) stack.fadeOut(actionId, fadeIn)
        if (varId != 0) { stack.fadeOut(varId, fadeIn); varId = 0; freeIdle = 0f }
        if (beatId != 0) { stack.fadeOut(beatId, fadeIn); beatId = 0 }
        if (thinkId != 0) { stack.fadeOut(thinkId, fadeIn); thinkId = 0 }
        actionId = stack.push(clip, clips.duration(clip), 0f, loop = false, fade = fadeIn, base = false, tag = KIND_ACTION)
        onPlay?.invoke(clip, KIND_ACTION)
    }

    /**
     * Saca la entrada [id] cuando le queda [out] s para acabar. Devuelve false
     * si ya no está en la pila.
     */
    private fun autoOut(stack: BlendStack, id: Int, out: Float): Boolean {
        val e = stack.find(id) ?: return false
        if (!e.fading && !e.loop) {
            val remaining = (e.duration - e.time) / max(e.speed, 1e-3f)
            if (remaining <= out) stack.fadeOut(id, out)
        }
        return true
    }

    private fun tickAction(t: Float, stack: BlendStack) {
        if (actionId != 0 && !autoOut(stack, actionId, actionOut)) {
            actionId = 0
            lastGestureEnd = t
        }
    }

    private fun tickThink(t: Float, stack: BlendStack) {
        if (thinkId != 0 && !autoOut(stack, thinkId, cfg.fades.holdOut)) thinkId = 0
    }

    /* ── variaciones en reposo ── */

    private fun tickVariation(t: Float, dt: Float, stack: BlendStack) {
        val f = cfg.fades
        if (varId != 0) {
            if (!autoOut(stack, varId, f.idleVariation)) {
                varId = 0
                freeIdle = 0f
                nextVariation = uniform(cfg.idle.minInterval, cfg.idle.maxInterval)
                lastGestureEnd = t
            }
            return
        }
        val free = state == State.Idle && actionId == 0 && thinkId == 0 && !variationBag.isEmpty
        if (!free) {
            freeIdle = 0f
            return
        }
        freeIdle += dt
        if (freeIdle >= nextVariation) {
            val clip = variationBag.next()
            varId = stack.push(clip, clips.duration(clip), 0f, loop = false, fade = f.idleVariation, base = false, tag = KIND_VARIATION)
            onPlay?.invoke(clip, KIND_VARIATION)
        }
    }

    /* ── golpes del habla ── */

    private fun tickBeats(t: Float, stack: BlendStack) {
        val tc = cfg.talk
        if (beatId != 0 && !autoOut(stack, beatId, beatOut)) {
            beatId = 0
            lastGestureEnd = t
        }
        if (state != State.Talk || clips.beats.isEmpty() || actionId != 0) return
        if (t - lastStroke < strokeGap) return
        val env = envelope
        if (env.env < tc.minLevel || (env.riseDb() < tc.riseDb && env.env < tc.strongLevel)) return
        // El golpe anterior tiene que haber hecho lo suyo.
        stack.find(beatId)?.let { e -> if (e.time < e.duration * tc.minOverlap) return }
        val loud = (env.env / tc.loudLevel).coerceIn(0f, 1f)
        val amp = tc.minAmp + (tc.maxAmp - tc.minAmp) * loud
        val speed = 1f + uniform(-tc.timeJitter, tc.timeJitter)
        val clip = beatBag.next()
        if (beatId != 0) stack.fadeOut(beatId, cfg.fades.gestureIn)
        beatId = stack.push(clip, clips.duration(clip), 0f, loop = false, fade = cfg.fades.gestureIn, base = false, amp = amp, speed = speed, tag = KIND_BEAT)
        beatOut = cfg.fades.gestureOut
        lastStroke = t
        strokeGap = uniform(tc.minGap, tc.maxGap)
        onPlay?.invoke(clip, KIND_BEAT)
    }

    /** ¿Hay un gesto (acción, variación o golpe) sonando? */
    val gesturing: Boolean get() = actionId != 0 || varId != 0 || beatId != 0

    private fun uniform(a: Float, b: Float) = a + (b - a) * random.nextFloat()

    companion object {
        const val KIND_BASE = 1
        const val KIND_VARIATION = 2
        const val KIND_ACTION = 3
        const val KIND_BEAT = 4
        const val KIND_THINK = 5
    }
}
