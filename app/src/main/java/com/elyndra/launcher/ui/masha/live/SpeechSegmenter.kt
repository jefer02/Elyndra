package com.elyndra.launcher.ui.masha.live

/**
 * Cuándo empieza y cuándo termina de hablar el usuario, tramo a tramo (pura).
 *
 * Recibe, por cada tramo de [frameMs], si hay voz suya (ya filtrada: VAD y
 * puerta de eco) y avisa:
 * - [Signal.Started] tras [minSpeechMs] de voz (con huecos de menos de
 *   [gapMs] dentro: entre sílabas también hay silencio). Un ruido más corto no
 *   abre turno. Con ella hablando (`overLucy`) hacen falta [minSpeechOverLucyMs]:
 *   lo que se cuele de su voz por el altavoz no la corta.
 * - [Signal.Paused] una vez, a los [pauseMs] de silencio dentro del turno: para
 *   decidir pronto si fue una muletilla ("mm", "ajá").
 * - [Signal.Ended] a los [endSilenceMs] de silencio seguido: fin del turno.
 * - [Signal.Capped] si el turno pasa de [maxTurnMs] (se cierra igual).
 *
 * [speechMs] es la voz acumulada del turno en curso.
 */
class SpeechSegmenter(
    val frameMs: Int = 16,
    val minSpeechMs: Int = MIN_SPEECH_MS,
    val minSpeechOverLucyMs: Int = MIN_SPEECH_OVER_LUCY_MS,
    val endSilenceMs: Int = END_SILENCE_MS,
    val pauseMs: Int = PAUSE_MS,
    val gapMs: Int = GAP_MS,
    val maxTurnMs: Int = MAX_TURN_MS,
) {
    enum class Signal { Started, Paused, Ended, Capped }

    /** Dentro de un turno (desde Started hasta Ended/Capped). */
    var inTurn = false
        private set

    var speechMs = 0
        private set

    private var onsetMs = 0
    private var gapRun = 0
    private var silenceMs = 0
    private var turnMs = 0
    private var pausedSent = false

    fun accept(voiced: Boolean, overLucy: Boolean = false): Signal? {
        if (!inTurn) {
            if (voiced) {
                onsetMs += frameMs + gapRun
                gapRun = 0
            } else if (onsetMs > 0) {
                gapRun += frameMs
                if (gapRun > gapMs) reset()
            }
            if (onsetMs >= if (overLucy) minSpeechOverLucyMs else minSpeechMs) {
                inTurn = true
                speechMs = onsetMs
                turnMs = onsetMs
                silenceMs = 0
                pausedSent = false
                onsetMs = 0
                gapRun = 0
                return Signal.Started
            }
            return null
        }
        turnMs += frameMs
        if (voiced) {
            speechMs += frameMs
            silenceMs = 0
            pausedSent = false
        } else {
            silenceMs += frameMs
        }
        return when {
            turnMs >= maxTurnMs -> { end(); Signal.Capped }
            silenceMs >= endSilenceMs -> { end(); Signal.Ended }
            silenceMs >= pauseMs && !pausedSent -> { pausedSent = true; Signal.Paused }
            else -> null
        }
    }

    /** Silencio actual dentro del turno. */
    val silence: Int get() = silenceMs

    fun reset() {
        onsetMs = 0
        gapRun = 0
        if (!inTurn) speechMs = 0
    }

    /** Olvida el turno en curso (el reconocedor se cerró por otra vía). */
    fun clear() {
        inTurn = false
        speechMs = 0
        silenceMs = 0
        turnMs = 0
        onsetMs = 0
        gapRun = 0
        pausedSent = false
    }

    private fun end() {
        inTurn = false
        silenceMs = 0
        turnMs = 0
        onsetMs = 0
        gapRun = 0
    }

    companion object {
        const val MIN_SPEECH_MS = 250
        /** Con ella sonando (medido en la tablet: con 280 ms el arranque de sus sílabas aún la pausaba). */
        const val MIN_SPEECH_OVER_LUCY_MS = 300
        const val END_SILENCE_MS = 700
        const val PAUSE_MS = 250
        const val GAP_MS = 160
        const val MAX_TURN_MS = 20_000
    }
}
