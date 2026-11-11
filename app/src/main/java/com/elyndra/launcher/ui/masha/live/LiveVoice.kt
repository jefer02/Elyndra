package com.elyndra.launcher.ui.masha.live

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.elyndra.launcher.ui.MashaController
import com.elyndra.launcher.ui.masha.MashaEars
import com.elyndra.launcher.ui.masha.MashaPresence
import com.elyndra.launcher.ui.masha.MashaVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La conversación en vivo con Lucy: el micrófono escucha siempre (también
 * mientras ella habla) y los turnos van solos, como en una conversación.
 *
 * Une [LiveTurns] (quién tiene la palabra) con las piezas de Android:
 * - [LiveMic]: el micrófono abierto, que avisa cuando el usuario empieza y
 *   termina de hablar sin confundirse con la voz de ella.
 * - El reconocedor ([MashaEars]): en Android 13+ escucha nuestro audio por un
 *   pipe (con el último segundo, así no se pierde el principio); si no puede,
 *   "relevo": se suelta el micrófono y escucha él (se pierde algo del
 *   principio, ver docs/MASHA_VOICE.md).
 * - La voz ([MashaVoice]): pausa al instante cuando el usuario habla encima;
 *   [HoldDecision] decide si era una muletilla (sigue) o va en serio (se corta
 *   y lo dicho queda en el hilo como interrumpido).
 * - [MashaController]: la misma conversación del chat, con herramientas y sin conexión.
 *
 * Con [voiceBargeIn] apagado (ajuste "Interrumpir con la voz por el altavoz",
 * sin auriculares), lo que se oye mientras ella habla no la corta: solo el botón.
 * Todo en el hilo principal.
 */
class LiveVoice(
    private val ears: MashaEars,
    private val voice: MashaVoice,
    private val presence: MashaPresence,
    private val masha: MashaController,
    private val mic: LiveMic,
    private val scope: CoroutineScope,
    private val language: () -> String,
    private val voiceBargeIn: () -> Boolean,
    private val onEnded: (LiveTurns.EndReason) -> Unit,
) {
    private val turns = LiveTurns()
    private var watcher: Job? = null
    private var holdJob: Job? = null
    private var idleJob: Job? = null

    var state by mutableStateOf(LiveTurns.State.Idle)
        private set

    val active: Boolean get() = state != LiveTurns.State.Idle

    /** Lo que va entendiendo el reconocedor del turno en curso (para el subtítulo del usuario). */
    var partial by mutableStateOf("")
        private set

    /** El reconocedor escucha (pipe o relevo). */
    private var hearing = false

    /** Relevo: el reconocedor tiene el micrófono (el nuestro está suelto). */
    private var handoff = false

    /** El reconocedor de este móvil no acepta audio de fuera: relevo hasta reiniciar la app. */
    private var streamUnsupported = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    private var holdStarted = 0L
    private var holds = 0
    private var resumes = 0
    private var commits = 0
    private var speechEndAt = 0L

    fun toggle() = dispatch(if (active) LiveTurns.Event.Stop else LiveTurns.Event.Start)

    fun stop() = dispatch(LiveTurns.Event.Stop)

    /** La app se va al fondo, se apaga la pantalla o se sale: se suelta el micrófono. */
    fun background() = dispatch(LiveTurns.Event.Background)

    /** El botón mantenido mientras ella habla o piensa: se corta ya. */
    fun bargeIn() = dispatch(LiveTurns.Event.Commit)

    fun release() {
        stop()
        mic.stop()
    }

    /* ── lo que llega del micrófono ── */

    fun onSignal(signal: SpeechSegmenter.Signal) {
        if (!active || handoff) return
        when (signal) {
            SpeechSegmenter.Signal.Started -> {
                restartIdle()
                if (state == LiveTurns.State.Speaking && !voiceBargeIn()) {
                    // Por el altavoz sin interrumpir con la voz: lo que se oye mientras habla no cuenta.
                    mic.clearTurn()
                    return
                }
                dispatch(LiveTurns.Event.SpeechStart)
            }
            // La pausa la mira decideHold (silencio del turno), cada 40 ms.
            SpeechSegmenter.Signal.Paused -> Unit
            SpeechSegmenter.Signal.Ended, SpeechSegmenter.Signal.Capped -> {
                speechEndAt = SystemClock.elapsedRealtime()
                dispatch(LiveTurns.Event.TurnEnded)
            }
        }
    }

    /* ── lo que llega del reconocedor ── */

    fun onPartial(text: String) {
        if (!active) return
        partial = text
        if (state == LiveTurns.State.UserSpeaking) masha.updateDraft(text)
    }

    fun onHeard(text: String) {
        if (!active) return
        if (speechEndAt > 0) Log.i(TAG, "fin de voz → texto final: ${SystemClock.elapsedRealtime() - speechEndAt} ms")
        if (state == LiveTurns.State.Held) {
            // Terminó dentro de la pausa: se decide con el texto final (el reconocedor aún cuenta como abierto).
            partial = text
            decideHold(final = true)
        }
        recognizerDone()
        if (state == LiveTurns.State.UserSpeaking) dispatch(LiveTurns.Event.Heard(text))
        partial = ""
    }

    fun onEarsFailure(failure: MashaEars.Failure) {
        if (!active) return
        if (failure == MashaEars.Failure.StreamUnsupported) {
            Log.i(TAG, "el reconocedor no acepta audio de fuera: relevo")
            streamUnsupported = true
            hearing = false
            mic.closeStream()
            if (state == LiveTurns.State.UserSpeaking) startHandoff()
            return
        }
        when (failure) {
            MashaEars.Failure.NoMatch, MashaEars.Failure.Busy, MashaEars.Failure.StreamUnsupported -> {
                if (state == LiveTurns.State.Held) {
                    partial = ""
                    decideHold(final = true)
                }
                recognizerDone()
                dispatch(LiveTurns.Event.HeardNothing)
            }
            MashaEars.Failure.Unavailable, MashaEars.Failure.Permission, MashaEars.Failure.Network -> {
                recognizerDone()
                dispatch(LiveTurns.Event.Failed)
            }
        }
    }

    /** El reconocedor terminó (bien o mal): el turno se cierra y, en relevo, vuelve nuestro micrófono. */
    private fun recognizerDone() {
        hearing = false
        mic.closeStream()
        mic.clearTurn()
        if (handoff) {
            handoff = false
            if (active) mic.start()
        }
    }

    /* ── turnos ── */

    private fun dispatch(event: LiveTurns.Event) {
        val effects = turns.on(event)
        state = turns.state
        effects.forEach(::run)
        if (state == LiveTurns.State.Waiting) restartIdle()
    }

    private fun run(effect: LiveTurns.Effect) {
        when (effect) {
            LiveTurns.Effect.OpenMic -> {
                holds = 0; resumes = 0; commits = 0
                mic.start()
                restartIdle()
            }
            LiveTurns.Effect.CloseMic -> {
                idleJob?.cancel()
                mic.stop()
                presence.listening = false
                Log.i(TAG, "sesión: pausas=$holds, siguió=$resumes, cortes=$commits; ${mic.report()}")
            }
            LiveTurns.Effect.OpenRecognizer -> openRecognizer()
            LiveTurns.Effect.FinishRecognizer -> if (hearing && !handoff) {
                mic.closeStream()
                ears.finish()
            }
            LiveTurns.Effect.CancelRecognizer -> {
                hearing = false
                mic.closeStream()
                ears.stop()
                mic.clearTurn()
                partial = ""
                if (handoff) {
                    handoff = false
                    if (active) mic.start()
                }
            }
            LiveTurns.Effect.PauseVoice -> {
                holds++
                holdStarted = SystemClock.elapsedRealtime()
                voice.pause()
                watchHold()
            }
            LiveTurns.Effect.ResumeVoice -> {
                resumes++
                holdJob?.cancel()
                voice.resume()
            }
            LiveTurns.Effect.StopVoice -> {
                holdJob?.cancel()
                voice.stop()
            }
            LiveTurns.Effect.StopReply -> {
                watcher?.cancel()
                masha.stop()
            }
            LiveTurns.Effect.Interrupt -> {
                commits++
                holdJob?.cancel()
                watcher?.cancel()
                masha.interrupt(voice.spokenSoFar())
            }
            is LiveTurns.Effect.Send -> {
                masha.updateDraft(effect.text)
                masha.send(language())
                watch()
            }
            is LiveTurns.Effect.End -> {
                watcher?.cancel()
                holdJob?.cancel()
                hearing = false
                handoff = false
                partial = ""
                onEnded(effect.reason)
            }
        }
    }

    private fun openRecognizer() {
        if (hearing) return
        partial = ""
        presence.listening = true
        if (!streamUnsupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val source = mic.openStream()
            if (source != null) {
                hearing = true
                ears.startStream(language(), source)
                return
            }
        }
        // Relevo: en la pausa no (aún no se sabe si va en serio); al confirmar, sí.
        if (state == LiveTurns.State.Held) return
        startHandoff()
    }

    private fun startHandoff() {
        hearing = true
        handoff = true
        mic.stop()
        ears.start(language())
    }

    /** Mientras está en pausa: cada 40 ms, ¿muletilla o va en serio? */
    private fun watchHold() {
        holdJob?.cancel()
        holdJob = scope.launch {
            while (state == LiveTurns.State.Held) {
                delay(HOLD_TICK_MS)
                decideHold(final = false)
            }
        }
    }

    private fun decideHold(final: Boolean) {
        if (state != LiveTurns.State.Held) return
        val heldMs = SystemClock.elapsedRealtime() - holdStarted
        val lastQuestion = !masha.busy && voice.sayingLast && HoldDecision.isQuestion(voice.nowSaying)
        val decision = when {
            heldMs > MAX_HOLD_MS -> HoldDecision.Decision.Commit
            else -> HoldDecision.decide(
                speechMs = mic.speechMs.takeIf { it > 0 } ?: SpeechSegmenter.MIN_SPEECH_MS,
                paused = final || !mic.inTurn || mic.silenceMs >= SpeechSegmenter.PAUSE_MS,
                partial = if (handoff || streamUnsupported) null else partial,
                lastIsQuestion = lastQuestion,
            )
        }
        when (decision) {
            HoldDecision.Decision.Wait -> Unit
            HoldDecision.Decision.Resume -> {
                Log.i(TAG, "muletilla (\"$partial\"): sigue tras $heldMs ms")
                dispatch(LiveTurns.Event.Resume)
            }
            HoldDecision.Decision.Commit -> {
                Log.i(TAG, "interrumpida (\"$partial\") a los $heldMs ms de la pausa")
                dispatch(LiveTurns.Event.Commit)
                // Si ya terminó de hablar dentro de la pausa (y el reconocedor sigue), el turno se cierra ya.
                if (!final && !mic.inTurn && !handoff) dispatch(LiveTurns.Event.TurnEnded)
            }
        }
    }

    private fun restartIdle() {
        idleJob?.cancel()
        if (!active) return
        idleJob = scope.launch {
            delay(LiveTurns.IDLE_TIMEOUT_MS)
            dispatch(LiveTurns.Event.IdleTimeout)
        }
    }

    /** Sigue la respuesta: cuándo empieza a sonar y cuándo ha terminado del todo. */
    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            launch {
                snapshotFlow { presence.speaking }.first { it }
                if (speechEndAt > 0) Log.i(TAG, "fin de voz → primera palabra de Lucy: ${SystemClock.elapsedRealtime() - speechEndAt} ms")
                dispatch(LiveTurns.Event.ReplyStarted)
            }
            snapshotFlow { masha.busy }.first { !it }
            // La última frase se pone en cola un instante después de terminar la respuesta.
            delay(SETTLE_MS)
            // Con frases en cola que no llegan a sonar (sin motor de voz), no se espera un minuto.
            val sounding = presence.speaking || !voice.queued ||
                withTimeoutOrNull(NO_VOICE_MS) { snapshotFlow { presence.speaking }.first { it } } != null
            val drained = sounding && withTimeoutOrNull(MAX_SPEAK_MS) {
                snapshotFlow { voice.queued || voice.paused }.first { !it }
            } != null
            if (!drained) voice.stop()
            dispatch(LiveTurns.Event.ReplyFinished)
        }
    }

    private companion object {
        const val TAG = "LucyLive"
        const val SETTLE_MS = 450L
        const val MAX_SPEAK_MS = 60_000L
        const val NO_VOICE_MS = 8_000L
        const val HOLD_TICK_MS = 40L

        /** Una pausa no dura más: si a los 2,5 s no se sabe, va en serio. */
        const val MAX_HOLD_MS = 2_500L
    }
}
