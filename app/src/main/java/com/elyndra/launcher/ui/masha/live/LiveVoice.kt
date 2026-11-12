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
 *   termina de hablar sin confundirse con la voz de ella. Mientras nadie
 *   habla solo trabaja el VAD (barato); si el micrófono falla, se reabre solo.
 * - El reconocedor ([MashaEars]): solo mientras el usuario habla. En Android
 *   13+ escucha nuestro audio por un pipe (con el último segundo, así no se
 *   pierde el principio); si no puede, "relevo": se suelta el micrófono y
 *   escucha él (se pierde algo del principio, ver docs/MASHA_VOICE.md). Sus
 *   errores nunca terminan la sesión: se vuelve a escuchar, con una espera
 *   creciente ([RecognizerBackoff]) si fallan seguidos.
 * - La voz ([MashaVoice]): pausa al instante cuando el usuario habla encima;
 *   [HoldDecision] decide si era una muletilla (sigue) o va en serio (se corta
 *   y lo dicho queda en el hilo como interrumpido). Cuando ella habla sin turno
 *   de voz (algo escrito o tocado), la sesión pasa igual a "habla".
 * - [MashaController]: la misma conversación del chat, con herramientas y sin conexión.
 *
 * Cada transición y cada fin se apuntan en el log con la etiqueta `LucyLive`
 * (`adb logcat -s LucyLive`), así se ve por qué terminó una sesión.
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
    private val hasPermission: () -> Boolean,
    private val onEnded: (LiveTurns.EndReason) -> Unit,
) {
    private val turns = LiveTurns()
    private var watcher: Job? = null
    private var holdJob: Job? = null
    private var voiceJob: Job? = null
    private var openJob: Job? = null
    private val backoff = RecognizerBackoff()

    /** Antes de esto no se vuelve a abrir el reconocedor (tras fallos seguidos). */
    private var recognizerReadyAt = 0L

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
    private var errors = 0
    private var speechEndAt = 0L

    fun start() {
        if (active) return
        if (!hasPermission()) {
            Log.i(TAG, "start refused reason=Permission")
            return
        }
        dispatch(LiveTurns.Event.Start)
    }

    /** El usuario bloquea el micrófono. */
    fun lock() = dispatch(LiveTurns.Event.Lock)

    /** La app se va al fondo o se apaga la pantalla: se suelta el micrófono. */
    fun background() = dispatch(LiveTurns.Event.Background)

    /** El botón mantenido mientras ella habla o piensa: se corta ya. */
    fun bargeIn() = dispatch(LiveTurns.Event.Commit)

    /** Se sale de la pantalla de Lucy. */
    fun release() {
        dispatch(LiveTurns.Event.Leave)
        mic.stop()
    }

    /* ── lo que llega del micrófono ── */

    fun onSignal(signal: SpeechSegmenter.Signal) {
        if (!active || handoff) return
        when (signal) {
            SpeechSegmenter.Signal.Started -> {
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
        if (speechEndAt > 0) Log.i(TAG, "latency speechEnd→final=${SystemClock.elapsedRealtime() - speechEndAt}ms")
        backoff.reset()
        if (state == LiveTurns.State.Held) {
            // Terminó dentro de la pausa: se decide con el texto final (el reconocedor aún cuenta como abierto).
            partial = text
            decideHold(final = true)
        }
        recognizerDone(clearTurn = true)
        if (state == LiveTurns.State.UserSpeaking) dispatch(LiveTurns.Event.Heard(text))
        partial = ""
    }

    fun onEarsFailure(failure: MashaEars.Failure, code: Int) {
        if (!active) return
        errors++
        Log.i(TAG, "recognizer error code=$code (${RecognizerErrors.name(code)}) failure=$failure state=$state")
        if (failure == MashaEars.Failure.StreamUnsupported) {
            Log.i(TAG, "recognizer refuses our stream: handoff from now on")
            streamUnsupported = true
            hearing = false
            mic.closeStream()
            if (state == LiveTurns.State.UserSpeaking) startHandoff()
            return
        }
        // Lo único del reconocedor que termina la sesión: que de verdad falte el permiso del micrófono.
        if (failure == MashaEars.Failure.Permission && !hasPermission()) {
            recognizerDone(clearTurn = true)
            dispatch(LiveTurns.Event.PermissionMissing)
            return
        }
        if (state == LiveTurns.State.Held) {
            partial = ""
            decideHold(final = true)
        }
        if (failure == MashaEars.Failure.NoMatch) {
            recognizerDone(clearTurn = true)
            dispatch(LiveTurns.Event.HeardNothing)
        } else {
            // Si sigue hablando, el turno sigue abierto y se vuelve a escuchar (RecoverRecognizer).
            recognizerDone(clearTurn = false)
            dispatch(LiveTurns.Event.RecognizerError(code))
        }
    }

    /** El reconocedor terminó (bien o mal): el turno se cierra y, en relevo, vuelve nuestro micrófono. */
    private fun recognizerDone(clearTurn: Boolean) {
        hearing = false
        openJob?.cancel()
        mic.closeStream()
        if (clearTurn) mic.clearTurn()
        if (handoff) {
            handoff = false
            if (active) mic.start()
        }
    }

    /* ── turnos ── */

    private fun dispatch(event: LiveTurns.Event) {
        val from = turns.state
        val effects = turns.on(event)
        state = turns.state
        if (from != turns.state || effects.isNotEmpty()) Log.i(TAG, LiveTurns.describe(event, from, turns.state, effects))
        effects.forEach(::run)
    }

    private fun run(effect: LiveTurns.Effect) {
        when (effect) {
            LiveTurns.Effect.OpenMic -> {
                holds = 0; resumes = 0; commits = 0; errors = 0
                backoff.reset()
                mic.start()
                followVoice()
            }
            LiveTurns.Effect.CloseMic -> {
                voiceJob?.cancel()
                openJob?.cancel()
                mic.stop()
                presence.listening = false
                Log.i(TAG, "session stats holds=$holds resumes=$resumes commits=$commits recognizerErrors=$errors; ${mic.report()}")
            }
            LiveTurns.Effect.OpenRecognizer -> openRecognizer()
            LiveTurns.Effect.FinishRecognizer -> if (hearing && !handoff) {
                mic.closeStream()
                ears.finish()
            }
            LiveTurns.Effect.CancelRecognizer -> {
                hearing = false
                openJob?.cancel()
                mic.closeStream()
                ears.stop()
                mic.clearTurn()
                partial = ""
                if (handoff) {
                    handoff = false
                    if (active) mic.start()
                }
            }
            is LiveTurns.Effect.RecoverRecognizer -> {
                val wait = backoff.next()
                recognizerReadyAt = SystemClock.elapsedRealtime() + wait
                Log.i(TAG, "recover code=${effect.code} retryInMs=$wait consecutiveFailures=${backoff.failures} stillTalking=${mic.inTurn}")
                // Sigue hablando: se vuelve a escuchar (tras la espera); si no, el próximo turno abre solo.
                if (mic.inTurn) dispatch(LiveTurns.Event.SpeechStart) else mic.clearTurn()
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
            LiveTurns.Effect.WatchVoice -> watchDrain()
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
                voiceJob?.cancel()
                openJob?.cancel()
                hearing = false
                handoff = false
                partial = ""
                Log.i(TAG, "end reason=${effect.reason}")
                onEnded(effect.reason)
            }
        }
    }

    private fun openRecognizer() {
        if (hearing) return
        val wait = recognizerReadyAt - SystemClock.elapsedRealtime()
        if (wait > 0) {
            // Tras fallos seguidos: se espera un poco (el pipe guarda el último segundo, no se pierde el principio).
            openJob?.cancel()
            openJob = scope.launch {
                delay(wait)
                if (state == LiveTurns.State.UserSpeaking || state == LiveTurns.State.Held) openRecognizerNow()
            }
            return
        }
        openRecognizerNow()
    }

    private fun openRecognizerNow() {
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
                Log.i(TAG, "hold decision=Resume heldMs=$heldMs partialChars=${partial.length}")
                dispatch(LiveTurns.Event.Resume)
            }
            HoldDecision.Decision.Commit -> {
                Log.i(TAG, "hold decision=Commit heldMs=$heldMs partialChars=${partial.length}")
                dispatch(LiveTurns.Event.Commit)
                // Si ya terminó de hablar dentro de la pausa (y el reconocedor sigue), el turno se cierra ya.
                if (!final && !mic.inTurn && !handoff) dispatch(LiveTurns.Event.TurnEnded)
            }
        }
    }

    /** Toda la sesión: si la voz empieza a sonar con la sesión esperando, ella "habla" (ReplyStarted). */
    private fun followVoice() {
        voiceJob?.cancel()
        voiceJob = scope.launch {
            snapshotFlow { presence.speaking }.collect { speaking ->
                if (speaking && state == LiveTurns.State.Waiting) dispatch(LiveTurns.Event.ReplyStarted)
            }
        }
    }

    /** Sigue la respuesta de un turno de voz: cuándo empieza a sonar y cuándo ha terminado del todo. */
    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            launch {
                snapshotFlow { presence.speaking }.first { it }
                if (speechEndAt > 0) Log.i(TAG, "latency speechEnd→firstWord=${SystemClock.elapsedRealtime() - speechEndAt}ms")
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

    /** Ella habla sin turno de voz: se espera a que termine la respuesta y se calle. */
    private fun watchDrain() {
        watcher?.cancel()
        watcher = scope.launch {
            snapshotFlow { masha.busy }.first { !it }
            delay(SETTLE_MS)
            val drained = withTimeoutOrNull(MAX_SPEAK_MS) {
                snapshotFlow { voice.queued || voice.paused || presence.speaking }.first { !it }
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
