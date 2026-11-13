package com.elyndra.launcher.ui.masha.live

import java.util.Locale

/**
 * ¿Lucy oye de verdad al usuario? (puro)
 *
 * El micrófono y el VAD pueden ir bien y aun así no llegar ningún texto: el
 * reconocedor no lee nuestro audio, oye silencio porque otro tiene el
 * micrófono, o no entiende el idioma. Sin esto cada turno vacío vuelve a
 * escuchar sin decir nada y parece que Lucy ignora al usuario.
 * - [onTurn]: un turno del usuario con al menos [MIN_SPEECH_MS] de voz (según
 *   el VAD) que no dio texto suma un fallo; uno con texto los borra.
 * - Tras [HANDOFF_AFTER] fallos seguidos escuchando por el pipe se aconseja el
 *   relevo (que el reconocedor use él el micrófono).
 * - [struggling] (la interfaz dice "No te estoy escuchando bien") tras
 *   [STRUGGLE_AFTER] fallos seguidos, o con el micrófono dando silencio
 *   digital (otra app lo tiene) más de [SILENCED_LIMIT_MS].
 */
class HearingHealth {

    enum class Advice { None, UseHandoff }

    /** Turnos seguidos con voz y sin texto. */
    var misses = 0
        private set

    /** Lo que lleva seguido el micrófono dando ceros. */
    var micSilencedMs = 0
        private set

    val struggling: Boolean get() = misses >= STRUGGLE_AFTER || micSilencedMs >= SILENCED_LIMIT_MS

    /** Un turno del usuario terminó: [gotText] si dio texto; [speechMs], la voz que oyó el VAD; [viaStream], si el reconocedor escuchaba el pipe. */
    fun onTurn(gotText: Boolean, speechMs: Int, viaStream: Boolean): Advice {
        if (gotText) {
            misses = 0
            return Advice.None
        }
        // Un golpe o una tos que abrió turno no dice nada del reconocedor.
        if (speechMs < MIN_SPEECH_MS) return Advice.None
        misses++
        return if (viaStream && misses >= HANDOFF_AFTER) Advice.UseHandoff else Advice.None
    }

    fun onMicSilenced(ms: Int) {
        micSilencedMs = ms.coerceAtLeast(0)
    }

    fun reset() {
        misses = 0
        micSilencedMs = 0
    }

    companion object {
        const val MIN_SPEECH_MS = 400
        const val HANDOFF_AFTER = 2
        const val STRUGGLE_AFTER = 3
        const val SILENCED_LIMIT_MS = 5_000
    }
}

/**
 * ¿El reconocedor escucha de verdad el audio que le pasamos por el pipe? (puro)
 *
 * Algunos reconocedores aceptan EXTRA_AUDIO_SOURCE sin fallar y lo ignoran:
 * - abren su propio micrófono; Android le da el audio a uno solo y al otro
 *   ceros, así que el nuestro se queda mudo ([Verdict.RecognizerTookMic]);
 * - o no leen el pipe y lo que le mandamos se amontona ([Verdict.NotReading]).
 * En los dos casos lo que sirve es el relevo: soltar nuestro micrófono y que
 * escuche él.
 *
 * Los ceros solo cuentan si empezaron con el reconocedor ya abierto
 * ([silencedAtOpen] 0) y con el usuario aún hablando ([inTurn]): un supresor
 * de ruido que deja ceros en una habitación callada no es el reconocedor.
 */
object StreamCheck {

    enum class Verdict { Ok, RecognizerTookMic, NotReading }

    /** Silencio digital seguido en nuestro micrófono con el reconocedor abierto. */
    const val SILENCED_MS = 300

    /** Audio sin leer en la cola del pipe (el pipe del sistema ya va lleno: ~2 s más). */
    const val BACKLOG_MS = 600

    fun verdict(silencedMs: Int, backlogMs: Int, silencedAtOpen: Int = 0, inTurn: Boolean = true): Verdict = when {
        silencedMs >= SILENCED_MS && silencedAtOpen == 0 && inTurn -> Verdict.RecognizerTookMic
        backlogMs >= BACKLOG_MS -> Verdict.NotReading
        else -> Verdict.Ok
    }
}

/**
 * Resumen por segundo de lo que oye el micrófono en vivo, para el log
 * (puro): nivel, probabilidad de voz, lo que suena de Lucy, el eco que se
 * esperaba y cuántos tramos contaron como voz del usuario. Así, si el VAD
 * nunca salta, el log dice por qué (nivel bajo, probabilidad baja, todo
 * atribuido al eco, o ceros porque otro tiene el micrófono).
 */
class MicMeter(val frameMs: Int = 16, val periodMs: Int = 1_000) {

    data class Summary(
        val frames: Int,
        val micAvgDb: Float,
        val micMaxDb: Float,
        val probAvg: Float,
        val probMax: Float,
        val refMaxDb: Float,
        val echoMaxDb: Float,
        val voiced: Int,
        val echoRejected: Int,
        val silenced: Int,
    ) {
        fun line(vad: String): String {
            val lucy = if (refMaxDb <= EchoGate.REF_FLOOR) "silent" else f(refMaxDb) + " dBFS"
            val echo = if (echoMaxDb <= EchoGate.SILENT) "none" else f(echoMaxDb) + " dBFS"
            return "mic 1s: level avg=${f(micAvgDb)} max=${f(micMaxDb)} dBFS, $vad p avg=${f2(probAvg)} max=${f2(probMax)} (on≥${LiveEar.ON}), " +
                "lucy=$lucy, expectedEcho=$echo (+${EchoGate.MARGIN_DB.toInt()} dB to count), voiced=$voiced echoRejected=$echoRejected silenced=$silenced/$frames"
        }

        private fun f(v: Float) = String.format(Locale.ROOT, "%.0f", v)
        private fun f2(v: Float) = String.format(Locale.ROOT, "%.2f", v)
    }

    private var frames = 0
    private var dbSum = 0f
    private var dbMax = EchoGate.SILENT
    private var pSum = 0f
    private var pMax = 0f
    private var refMax = EchoGate.SILENT
    private var echoMax = EchoGate.SILENT
    private var voiced = 0
    private var echo = 0
    private var silenced = 0

    /** Un tramo; devuelve el resumen al completar [periodMs] (y empieza otro). */
    fun add(micDb: Float, prob: Float, refDb: Float, expectedEchoDb: Float, voiced: Boolean, echoRejected: Boolean): Summary? {
        frames++
        dbSum += micDb
        dbMax = maxOf(dbMax, micDb)
        pSum += prob
        pMax = maxOf(pMax, prob)
        refMax = maxOf(refMax, refDb)
        echoMax = maxOf(echoMax, expectedEchoDb)
        if (voiced) this.voiced++
        if (echoRejected) echo++
        if (micDb <= SILENT_DB) silenced++
        if (frames * frameMs < periodMs) return null
        val s = Summary(frames, dbSum / frames, dbMax, pSum / frames, pMax, refMax, echoMax, this.voiced, echo, silenced)
        frames = 0; dbSum = 0f; dbMax = EchoGate.SILENT; pSum = 0f; pMax = 0f
        refMax = EchoGate.SILENT; echoMax = EchoGate.SILENT; this.voiced = 0; echo = 0; silenced = 0
        return s
    }

    companion object {
        /** VoiceActivity.levelDb da −100 con silencio digital (ceros: Android le da el micrófono a otro). */
        const val SILENT_DB = -100f
    }
}
