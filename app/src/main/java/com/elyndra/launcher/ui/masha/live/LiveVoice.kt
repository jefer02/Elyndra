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
 * Une [LiveTurns] (quién tiene la palabra) con las piezas de Android, a
 * través de [Ears], [Mic], [Voice], [Presence] y [Chat] (en la app, [create];
 * en las pruebas, dobles):
 * - [LiveMic]: el micrófono abierto, que avisa cuando el usuario empieza y
 *   termina de hablar sin confundirse con la voz de ella. Mientras nadie
 *   habla solo trabaja el VAD (barato); si el micrófono falla, se reabre solo.
 * - El reconocedor ([MashaEars]): solo mientras el usuario habla. En Android
 *   13+ escucha nuestro audio por un pipe (con el último segundo, así no se
 *   pierde el principio); si no puede, "relevo": se suelta el micrófono y
 *   escucha él (se pierde algo del principio, ver docs/MASHA_VOICE.md). Sus
 *   errores nunca terminan la sesión: se vuelve a escuchar, con una espera
 *   creciente ([RecognizerBackoff]) si fallan seguidos.
 * - Hay reconocedores que aceptan el pipe y lo ignoran: abren su propio
 *   micrófono (el nuestro pasa a dar ceros) o no lo leen. [StreamCheck] lo ve
 *   mientras escucha y [HearingHealth] lo ve por los turnos sin texto; en los
 *   dos casos se pasa al relevo. Si aun así no llega texto, [struggling] lo
 *   dice en pantalla ("No te estoy escuchando bien"): nunca falla en silencio.
 * - La voz ([MashaVoice]): pausa al instante cuando el usuario habla encima;
 *   [HoldDecision] decide si era una muletilla (sigue) o va en serio (se corta
 *   y lo dicho queda en el hilo como interrumpido). Cuando ella habla sin turno
 *   de voz (algo escrito o tocado), la sesión pasa igual a "habla".
 * - [MashaController]: la misma conversación del chat, con herramientas y sin
 *   conexión. Lo dicho va directo ([Chat.send]), nunca por el campo de texto.
 *   Si contestaba otra cosa cuando llega lo dicho, se corta; si a los
 *   [GATE_MS] sigue "ocupada", se manda igual (se suelta a la fuerza).
 * - **Todo turno acaba en un envío** ([endTurn]), haga lo que haga el
 *   reconocedor: el fin de turno llega por nuestro silencio, por su
 *   onEndOfSpeech, por su resultado o, si deja de dar parciales
 *   [PARTIAL_TIMEOUT_MS] (relevo: nuestro micrófono no oye el final), por
 *   tiempo. Si el resultado final no llega en [FINAL_WAIT_MS], o llega un
 *   error, va el último parcial. Una línea por turno:
 *   `turn end via=… source=… → sent|queued(…)|dropped(…)`.
 *
 * Cada transición, cada escucha y cada fin se apuntan en el log con la
 * etiqueta `LucyLive` (`adb logcat -s LucyLive`), sin el texto del usuario.
 *
 * Con [voiceBargeIn] apagado (ajuste "Interrumpir con la voz por el altavoz",
 * sin auriculares), lo que se oye mientras ella habla no la corta: solo el botón.
 * Igual, solo durante esa respuesta, si ya la pausó [NO_TEXT_RESUMES_LIMIT]
 * veces sin texto (era su propia voz o ruido: si no, se trabaría a cada rato).
 * Cada pausa y cada vuelta se apuntan con su motivo y los niveles del micrófono.
 * Todo en el hilo principal.
 */
class LiveVoice(
    private val ears: Ears,
    private val voice: Voice,
    private val presence: Presence,
    private val chat: Chat,
    private val mic: Mic,
    private val scope: CoroutineScope,
    private val language: () -> String,
    private val voiceBargeIn: () -> Boolean,
    private val hasPermission: () -> Boolean,
    private val onEnded: (LiveTurns.EndReason) -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    /** El reconocedor de voz. */
    interface Ears {
        /** Escucha nuestro audio por un pipe; false si aquí no se puede (antes de Android 13, sin pipe). */
        fun listenToStream(language: String): Boolean

        /** Escucha él el micrófono (relevo: el nuestro ya está suelto). */
        fun listenToMic(language: String)

        /** Deja de escuchar; lo dicho hasta ahora aún llega como resultado. */
        fun finish()

        /** Lo suelta ya, sin resultado. */
        fun stop()
    }

    /** El micrófono siempre abierto ([LiveMic]). */
    interface Mic {
        fun start()
        fun stop()

        /** Cierra el pipe hacia el reconocedor (ve el final del audio). */
        fun closeStream()

        /** El turno en curso se da por cerrado por otra vía. */
        fun clearTurn()
        val inTurn: Boolean
        val speechMs: Int
        val silenceMs: Int

        /** Lo que lleva seguido dando silencio digital (ceros: Android le da el micrófono a otro). */
        val silencedMs: Int

        /** Audio para el reconocedor que aún no ha leído (ms). */
        val backlogMs: Int
        fun report(): String

        /** Los niveles del último tramo (micro, lo que suena, eco esperado), para el log. */
        fun levels(): String
    }

    /** Su voz ([MashaVoice]). */
    interface Voice {
        val queued: Boolean
        val paused: Boolean
        val sayingLast: Boolean
        val nowSaying: String?
        fun pause()
        fun resume()
        fun stop()
        fun spokenSoFar(): String
    }

    interface Presence {
        val speaking: Boolean
        var listening: Boolean
    }

    /** La conversación ([MashaController]). */
    interface Chat {
        val busy: Boolean

        /**
         * Manda lo dicho directo (nunca por el campo de texto), como lo escrito.
         * Con [force], si aún contesta otra cosa, la corta y manda igual. false si no se mandó.
         */
        fun send(text: String, language: String, force: Boolean): Boolean
        fun stop()
        fun interrupt(spoken: String)
    }

    private val turns = LiveTurns()
    private val backoff = RecognizerBackoff()
    private val health = HearingHealth()
    private var watcher: Job? = null
    private var holdJob: Job? = null
    private var voiceJob: Job? = null
    private var openJob: Job? = null
    private var monitorJob: Job? = null
    private var sendJob: Job? = null

    /** Tras el fin de turno, la espera al resultado final (si no llega, va el último parcial). */
    private var finalJob: Job? = null

    /** Sin parciales nuevos en [PARTIAL_TIMEOUT_MS] con texto ya entendido: fin de turno. */
    private var partialJob: Job? = null

    /** Por dónde llegó el fin del turno en curso (null = aún no ha llegado). */
    private var endVia: TurnEnd? = null

    /** Cómo acabó el último turno que se mandó (para la línea de log del envío). */
    private var sentVia = TurnEnd.Results
    private var sentSource = "final"

    /** Antes de esto no se vuelve a abrir el reconocedor (tras fallos seguidos). */
    private var recognizerReadyAt = 0L

    var state by mutableStateOf(LiveTurns.State.Idle)
        private set

    val active: Boolean get() = state != LiveTurns.State.Idle

    /** Lo que va entendiendo el reconocedor del turno en curso (subtítulo del usuario; nunca va al campo de texto). */
    var partial by mutableStateOf("")
        private set

    /** Turnos seguidos sin texto, o el micrófono mudo: la interfaz dice "No te estoy escuchando bien". */
    var struggling by mutableStateOf(false)
        private set

    /** El reconocedor falla seguido por la red: la interfaz dice "No te escucho: sin conexión" hasta que vuelva. */
    var offline by mutableStateOf(false)
        private set

    /** El reconocedor escucha (pipe o relevo). */
    private var hearing = false

    /** Relevo: el reconocedor tiene el micrófono (el nuestro está suelto). */
    private var handoff = false

    /** Este turno el reconocedor escucha nuestro pipe. */
    private var viaStream = false

    /** El reconocedor de este móvil no usa nuestro audio: relevo mientras viva esta pantalla. */
    private var streamUnsupported = false

    /** Voz del usuario en el turno que escucha el reconocedor (ms, según el VAD). */
    private var turnSpeechMs = 0

    /** Silencio digital que ya traía nuestro micrófono al abrir el pipe (si no es 0, no es cosa del reconocedor). */
    private var silencedAtOpen = 0

    private var holdStarted = 0L

    /** En esta respuesta suya: pausas que acabaron sin texto, y si ya no se la pausa con la voz. */
    private var noTextResumes = 0
    private var bargeInMuted = false

    /** En la pausa en curso el reconocedor entendió algo. */
    private var heardInHold = false

    /** Por qué sigue ella tras la pausa (para el log). */
    private var resumeReason = "recognizerNothing"
    private var holds = 0
    private var resumes = 0
    private var commits = 0
    private var errors = 0
    private var speechEndAt = 0L

    fun start() {
        if (active) return
        if (!hasPermission()) {
            log("start refused reason=Permission")
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
                if (state == LiveTurns.State.Speaking && (!voiceBargeIn() || bargeInMuted)) {
                    // Por el altavoz sin interrumpir con la voz (o ya se trabó en esta respuesta): no cuenta.
                    if (bargeInMuted) log("speech over Lucy ignored: voice barge-in off for this reply (${mic.levels()})")
                    mic.clearTurn()
                    return
                }
                dispatch(LiveTurns.Event.SpeechStart)
            }
            // La pausa la mira decideHold (silencio del turno), cada 40 ms.
            SpeechSegmenter.Signal.Paused -> Unit
            SpeechSegmenter.Signal.Ended, SpeechSegmenter.Signal.Capped -> {
                speechEndAt = clock()
                turnSpeechMs = maxOf(turnSpeechMs, mic.speechMs)
                dispatch(LiveTurns.Event.TurnEnded)
            }
        }
    }

    /* ── lo que llega del reconocedor ── */

    fun onPartial(text: String) {
        if (!active) return
        partial = text
        if (state == LiveTurns.State.Held && text.isNotBlank()) heardInHold = true
        if (state == LiveTurns.State.UserSpeaking && endVia == null && text.isNotBlank()) watchPartials()
    }

    /** El reconocedor cree que el usuario terminó: fin de turno (su resultado se espera un poco). */
    fun onEndOfSpeech() {
        if (!active) return
        awaitFinal(TurnEnd.EndOfSpeech)
    }

    fun onHeard(text: String) {
        if (!active) return
        if (speechEndAt > 0) log("latency speechEnd→final=${clock() - speechEndAt}ms")
        backoff.reset()
        val from = state
        if (state == LiveTurns.State.Held) {
            // Terminó dentro de la pausa: se decide con el texto final (el reconocedor aún cuenta como abierto).
            partial = text
            decideHold(final = true)
        }
        if (state == LiveTurns.State.UserSpeaking) {
            endTurn(endVia ?: TurnEnd.Results, text, recognizerEnded = true)
        } else {
            recognizerDone(clearTurn = true)
            log("heard ignored chars=${text.length} state=$state from=$from")
        }
        partial = ""
    }

    fun onEarsFailure(failure: MashaEars.Failure, code: Int) {
        if (!active) return
        errors++
        log("recognizer error code=$code (${RecognizerErrors.name(code)}) failure=$failure state=$state via=${via()}")
        // Ya había entendido algo: no se pierde, va lo último que entendió.
        if (state == LiveTurns.State.UserSpeaking && partial.isNotBlank() && failure != MashaEars.Failure.Permission) {
            endTurn(endVia ?: TurnEnd.Error, "", recognizerEnded = true)
            return
        }
        if (code == HearingHealth.NO_MATCH) health.onRecognizerWorked() else health.onRecognizerError(code)
        updateStruggling()
        if (failure == MashaEars.Failure.StreamUnsupported) {
            log("recognizer refuses our stream: handoff from now on")
            streamUnsupported = true
            hearing = false
            viaStream = false
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
        val speech = turnSpeech()
        val wasStream = viaStream
        val userTurn = state == LiveTurns.State.UserSpeaking
        if (failure == MashaEars.Failure.NoMatch) {
            recognizerDone(clearTurn = true)
            if (userTurn) noteTurn(gotText = false, speech, wasStream)
            dispatch(LiveTurns.Event.HeardNothing)
        } else {
            // Si sigue hablando, el turno sigue abierto y se vuelve a escuchar (RecoverRecognizer).
            recognizerDone(clearTurn = false)
            if (userTurn) noteTurn(gotText = false, speech, wasStream)
            dispatch(LiveTurns.Event.RecognizerError(code))
        }
    }

    /** El reconocedor terminó (bien o mal): el turno se cierra y, en relevo, vuelve nuestro micrófono. */
    private fun recognizerDone(clearTurn: Boolean) {
        clearTurnEnd()
        hearing = false
        viaStream = false
        openJob?.cancel()
        mic.closeStream()
        if (clearTurn) mic.clearTurn()
        if (handoff) {
            handoff = false
            if (active) mic.start()
        }
    }

    /**
     * La voz del turno que acaba de escuchar el reconocedor. En relevo nuestro
     * micrófono estaba suelto y no la midió: se abrió con voz de verdad, así que cuenta.
     */
    private fun turnSpeech(): Int =
        if (handoff) maxOf(turnSpeechMs, HearingHealth.MIN_SPEECH_MS) else maxOf(turnSpeechMs, mic.speechMs)

    /** Un turno del usuario terminó con o sin texto: ¿el reconocedor oye de verdad? */
    private fun noteTurn(gotText: Boolean, speechMs: Int, wasStream: Boolean) {
        if (gotText) health.onRecognizerWorked()
        val advice = health.onTurn(gotText, speechMs, wasStream)
        log("turn result text=$gotText speechMs=$speechMs via=${if (wasStream) "stream" else "mic"} missesInARow=${health.misses}")
        if (advice == HearingHealth.Advice.UseHandoff && !streamUnsupported) {
            streamUnsupported = true
            log("no text from the stream ${health.misses} turns in a row: handoff from now on")
        }
        updateStruggling()
    }

    private fun updateStruggling() {
        if (health.offline != offline) {
            offline = health.offline
            log("hearing offline=$offline networkFailuresInARow=${health.networkFailures}")
        }
        val now = health.struggling
        if (now == struggling) return
        struggling = now
        log("hearing struggling=$now missesInARow=${health.misses} micSilencedMs=${health.micSilencedMs}")
    }

    /* ── fin de turno: siempre acaba en un envío ── */

    /** Por dónde llegó el fin de un turno (el nombre va al log). */
    enum class TurnEnd(val label: String) {
        Silence("silence"),
        EndOfSpeech("endOfSpeech"),
        Results("results"),
        PartialTimeout("partialTimeout"),
        Error("error"),
    }

    /**
     * Llegó el fin del turno (nuestro silencio o su onEndOfSpeech): se espera
     * el resultado final [FINAL_WAIT_MS]; si no llega, va el último parcial.
     * Sin ningún texto, a los [NO_TEXT_MS] se vuelve a escuchar (nunca se queda colgado).
     */
    private fun awaitFinal(via: TurnEnd) {
        if (state != LiveTurns.State.UserSpeaking || endVia != null) return
        endVia = via
        if (via != TurnEnd.Silence) speechEndAt = clock()
        partialJob?.cancel()
        finalJob?.cancel()
        finalJob = scope.launch {
            delay(FINAL_WAIT_MS)
            val deadline = clock() + NO_TEXT_MS - FINAL_WAIT_MS
            while (partial.isBlank() && clock() < deadline) delay(HOLD_TICK_MS)
            log("no final result ${clock() - speechEndAt}ms after the turn ended (partialChars=${partial.length})")
            endTurn(via, "", recognizerEnded = false)
        }
    }

    /**
     * Con texto ya entendido, si el reconocedor pasa [PARTIAL_TIMEOUT_MS] sin
     * parciales nuevos el turno se da por terminado (en relevo nuestro
     * micrófono no oye el silencio). Mientras nuestro micrófono aún oiga su voz, no.
     */
    private fun watchPartials() {
        partialJob?.cancel()
        partialJob = scope.launch {
            delay(PARTIAL_TIMEOUT_MS)
            while (state == LiveTurns.State.UserSpeaking && ourMicHearsVoice()) delay(HOLD_TICK_MS)
            if (state == LiveTurns.State.UserSpeaking && endVia == null && partial.isNotBlank()) {
                log("no new partial for ${PARTIAL_TIMEOUT_MS}ms")
                endTurn(TurnEnd.PartialTimeout, "", recognizerEnded = false)
            }
        }
    }

    private fun ourMicHearsVoice(): Boolean = !handoff && mic.inTurn && mic.silenceMs < SpeechSegmenter.PAUSE_MS

    private fun clearTurnEnd() {
        finalJob?.cancel()
        partialJob?.cancel()
        endVia = null
    }

    /**
     * Cierra el turno del usuario con [text] (el resultado final) o, si está
     * vacío, con el último parcial; con texto se manda, sin texto se vuelve a
     * escuchar. Si el reconocedor aún no terminó, se suelta: lo oído va ya.
     */
    private fun endTurn(via: TurnEnd, text: String, recognizerEnded: Boolean) {
        if (state != LiveTurns.State.UserSpeaking) {
            clearTurnEnd()
            return
        }
        val words = text.trim().ifEmpty { partial.trim() }
        val source = if (text.isNotBlank()) "final" else "partial"
        val speech = turnSpeech()
        val wasStream = viaStream
        if (!recognizerEnded) ears.stop()
        recognizerDone(clearTurn = true)
        partial = ""
        noteTurn(words.isNotEmpty(), speech, wasStream)
        if (words.isEmpty()) {
            log("turn end via=${via.label} source=none → dropped(noText)")
            dispatch(LiveTurns.Event.HeardNothing)
            return
        }
        sentVia = via
        sentSource = source
        dispatch(LiveTurns.Event.Heard(words))
    }

    /* ── turnos ── */

    private fun dispatch(event: LiveTurns.Event) {
        val from = turns.state
        val effects = turns.on(event)
        state = turns.state
        // Fuera de una respuesta suya (o empieza otra), la voz vuelve a poder pausarla.
        if (state != LiveTurns.State.Speaking && state != LiveTurns.State.Held) {
            noTextResumes = 0
            bargeInMuted = false
        }
        if (from != turns.state || effects.isNotEmpty()) log(LiveTurns.describe(event, from, turns.state, effects))
        effects.forEach(::run)
    }

    private fun run(effect: LiveTurns.Effect) {
        when (effect) {
            LiveTurns.Effect.OpenMic -> {
                holds = 0; resumes = 0; commits = 0; errors = 0
                backoff.reset()
                mic.start()
                followVoice()
                monitor()
            }
            LiveTurns.Effect.CloseMic -> {
                voiceJob?.cancel()
                openJob?.cancel()
                monitorJob?.cancel()
                mic.stop()
                presence.listening = false
                log("session stats holds=$holds resumes=$resumes commits=$commits recognizerErrors=$errors; ${mic.report()}")
            }
            LiveTurns.Effect.OpenRecognizer -> openRecognizer()
            LiveTurns.Effect.FinishRecognizer -> {
                if (hearing && !handoff) {
                    mic.closeStream()
                    ears.finish()
                }
                awaitFinal(TurnEnd.Silence)
            }
            LiveTurns.Effect.CancelRecognizer -> {
                clearTurnEnd()
                hearing = false
                viaStream = false
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
                recognizerReadyAt = clock() + wait
                log("recover code=${effect.code} retryInMs=$wait consecutiveFailures=${backoff.failures} stillTalking=${mic.inTurn}")
                // Sigue hablando: se vuelve a escuchar (tras la espera); si no, el próximo turno abre solo.
                if (mic.inTurn) dispatch(LiveTurns.Event.SpeechStart) else mic.clearTurn()
            }
            LiveTurns.Effect.PauseVoice -> {
                holds++
                holdStarted = clock()
                heardInHold = false
                resumeReason = "recognizerNothing"
                log("pause voice reason=userSpeech speechMs=${mic.speechMs} levels=[${mic.levels()}] pausesThisReply=${noTextResumes + 1}")
                voice.pause()
                watchHold()
            }
            LiveTurns.Effect.ResumeVoice -> {
                resumes++
                holdJob?.cancel()
                val noText = !heardInHold
                if (noText) noTextResumes++
                log("resume voice reason=${if (noText) "noText" else resumeReason} heldMs=${clock() - holdStarted} noTextResumes=$noTextResumes")
                if (noText && noTextResumes >= NO_TEXT_RESUMES_LIMIT && !bargeInMuted) {
                    bargeInMuted = true
                    log("voice barge-in off for this reply: $noTextResumes pauses without text (the button still interrupts)")
                }
                voice.resume()
            }
            LiveTurns.Effect.StopVoice -> {
                holdJob?.cancel()
                voice.stop()
            }
            LiveTurns.Effect.WatchVoice -> watchDrain()
            LiveTurns.Effect.StopReply -> {
                watcher?.cancel()
                dropQueued("userKeptTalking")
                chat.stop()
            }
            LiveTurns.Effect.Interrupt -> {
                commits++
                holdJob?.cancel()
                watcher?.cancel()
                chat.interrupt(voice.spokenSoFar())
            }
            is LiveTurns.Effect.Send -> send(effect.text)
            is LiveTurns.Effect.End -> {
                watcher?.cancel()
                holdJob?.cancel()
                voiceJob?.cancel()
                openJob?.cancel()
                monitorJob?.cancel()
                clearTurnEnd()
                dropQueued("sessionEnded")
                hearing = false
                handoff = false
                viaStream = false
                partial = ""
                health.reset()
                struggling = false
                offline = false
                log("end reason=${effect.reason}")
                onEnded(effect.reason)
            }
        }
    }

    /**
     * Lo dicho va a la conversación, directo. Si ella aún contestaba otra cosa
     * (algo escrito, el saludo, una confirmación), el chat no aceptaría el
     * mensaje: se corta esa respuesta y se manda en cuanto quede libre; si a
     * los [GATE_MS] sigue ocupada, se manda igual (se suelta a la fuerza).
     */
    private fun send(text: String) {
        val turn = "turn end via=${sentVia.label} source=$sentSource chars=${text.length}"
        dropQueued("replaced")
        if (!chat.busy) {
            deliver(text, turn, force = false)
            return
        }
        log("$turn → queued(chatBusy)")
        chat.stop()
        val queuedAt = clock()
        sendJob = scope.launch {
            val free = withTimeoutOrNull(GATE_MS) { snapshotFlow { chat.busy }.first { !it } } != null
            sendJob = null
            deliver(text, "send dequeued waitedMs=${clock() - queuedAt} chatFreed=$free force=${!free}", force = !free)
        }
    }

    private fun deliver(text: String, line: String, force: Boolean) {
        if (chat.send(text, language(), force)) {
            log("$line → sent")
            watch()
        } else {
            log("$line → dropped(chatRefused)")
            dispatch(LiveTurns.Event.ReplyFinished)
        }
    }

    /** Lo que esperaba a que el chat quedara libre ya no va. */
    private fun dropQueued(reason: String) {
        val job = sendJob ?: return
        sendJob = null
        if (job.isActive) log("queued send dropped($reason)")
        job.cancel()
    }

    private fun openRecognizer() {
        if (hearing) return
        val wait = recognizerReadyAt - clock()
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
        clearTurnEnd()
        partial = ""
        turnSpeechMs = mic.speechMs
        presence.listening = true
        if (!streamUnsupported) {
            silencedAtOpen = mic.silencedMs
            if (ears.listenToStream(language())) {
                hearing = true
                viaStream = true
                log("recognizer open via=stream state=$state")
                return
            }
            log("no audio stream on this device: handoff from now on")
            streamUnsupported = true
        }
        // Relevo: en la pausa no (aún no se sabe si va en serio); al confirmar, sí.
        if (state == LiveTurns.State.Held) return
        startHandoff()
    }

    private fun startHandoff() {
        hearing = true
        handoff = true
        viaStream = false
        mic.stop()
        log("recognizer open via=mic (handoff) state=$state")
        ears.listenToMic(language())
    }

    /**
     * Toda la sesión, cada [MONITOR_MS]: con el reconocedor en el pipe, si de
     * verdad lo escucha ([StreamCheck]); y si nuestro micrófono lleva rato
     * dando ceros (otra app lo tiene).
     */
    private fun monitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (active) {
                delay(MONITOR_MS)
                if (!active) break
                if (hearing && mic.inTurn) turnSpeechMs = maxOf(turnSpeechMs, mic.speechMs)
                if (hearing && viaStream && !handoff) checkStream()
                // Con el reconocedor en el pipe, los ceros los mira checkStream; en relevo, el nuestro está suelto.
                health.onMicSilenced(if (hearing) 0 else mic.silencedMs)
                updateStruggling()
            }
        }
    }

    private fun checkStream() {
        when (StreamCheck.verdict(mic.silencedMs, mic.backlogMs, silencedAtOpen, mic.inTurn)) {
            StreamCheck.Verdict.Ok -> Unit
            StreamCheck.Verdict.RecognizerTookMic -> {
                // Escucha él el micrófono de verdad: se le deja (relevo) y el nuestro se suelta.
                log("recognizer took the mic (ours gets silence for ${mic.silencedMs}ms): it ignores our stream, handoff from now on")
                streamUnsupported = true
                viaStream = false
                handoff = true
                mic.stop()
            }
            StreamCheck.Verdict.NotReading -> {
                log("recognizer isn't reading our stream (backlog ${mic.backlogMs}ms): handoff from now on")
                streamUnsupported = true
                val talking = mic.inTurn
                clearTurnEnd()
                hearing = false
                viaStream = false
                openJob?.cancel()
                ears.stop()
                mic.closeStream()
                if (talking && state == LiveTurns.State.UserSpeaking) {
                    startHandoff()
                } else {
                    val userTurn = state == LiveTurns.State.UserSpeaking
                    val speech = turnSpeech()
                    mic.clearTurn()
                    if (userTurn) noteTurn(gotText = false, speech, wasStream = true)
                    dispatch(LiveTurns.Event.HeardNothing)
                }
            }
        }
    }

    private fun via(): String = when {
        !hearing -> "none"
        handoff -> "mic"
        viaStream -> "stream"
        else -> "none"
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
        val heldMs = clock() - holdStarted
        val lastQuestion = !chat.busy && voice.sayingLast && HoldDecision.isQuestion(voice.nowSaying)
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
                resumeReason = "backchannel"
                log("hold decision=Resume heldMs=$heldMs partialChars=${partial.length}")
                dispatch(LiveTurns.Event.Resume)
            }
            HoldDecision.Decision.Commit -> {
                log("hold decision=Commit heldMs=$heldMs partialChars=${partial.length}")
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
                if (speechEndAt > 0) log("latency speechEnd→firstWord=${clock() - speechEndAt}ms")
                dispatch(LiveTurns.Event.ReplyStarted)
            }
            snapshotFlow { chat.busy }.first { !it }
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
            snapshotFlow { chat.busy }.first { !it }
            delay(SETTLE_MS)
            val drained = withTimeoutOrNull(MAX_SPEAK_MS) {
                snapshotFlow { voice.queued || voice.paused || presence.speaking }.first { !it }
            } != null
            if (!drained) voice.stop()
            dispatch(LiveTurns.Event.ReplyFinished)
        }
    }

    companion object {
        private const val TAG = "LucyLive"
        const val SETTLE_MS = 450L
        const val MAX_SPEAK_MS = 60_000L
        const val NO_VOICE_MS = 8_000L
        const val HOLD_TICK_MS = 40L
        const val MONITOR_MS = 100L

        /** Lo que se espera a que el chat quede libre para mandar lo dicho; luego se manda igual. */
        const val GATE_MS = 500L

        /** Tras el fin de turno, lo que se espera al resultado final antes de mandar el último parcial. */
        const val FINAL_WAIT_MS = 700L

        /** Sin parciales nuevos (y con texto), el turno se da por terminado. */
        const val PARTIAL_TIMEOUT_MS = 1_200L

        /** Tras el fin de turno sin ningún texto, se deja de esperar y se vuelve a escuchar. */
        const val NO_TEXT_MS = 5_000L

        /** Pausas sin texto en una misma respuesta suya tras las que la voz ya no la pausa (solo el botón). */
        const val NO_TEXT_RESUMES_LIMIT = 2

        /** Una pausa no dura más: si a los 2,5 s no se sabe, va en serio. */
        const val MAX_HOLD_MS = 2_500L

        /** La conversación en vivo de la app, con sus piezas de Android. */
        fun create(
            ears: MashaEars,
            voice: MashaVoice,
            presence: MashaPresence,
            masha: MashaController,
            mic: LiveMic,
            scope: CoroutineScope,
            language: () -> String,
            voiceBargeIn: () -> Boolean,
            hasPermission: () -> Boolean,
            onEnded: (LiveTurns.EndReason) -> Unit,
        ) = LiveVoice(
            ears = AndroidEars(ears, mic),
            voice = AndroidVoice(voice),
            presence = AndroidPresence(presence),
            chat = AndroidChat(masha),
            mic = mic,
            scope = scope,
            language = language,
            voiceBargeIn = voiceBargeIn,
            hasPermission = hasPermission,
            onEnded = onEnded,
        )
    }
}

/** [MashaEars] con el pipe de [LiveMic] (Android 13+). */
private class AndroidEars(private val ears: MashaEars, private val mic: LiveMic) : LiveVoice.Ears {
    override fun listenToStream(language: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val source = mic.openStream() ?: return false
        ears.startStream(language, source)
        return true
    }

    override fun listenToMic(language: String) = ears.start(language)
    override fun finish() = ears.finish()
    override fun stop() = ears.stop()
}

private class AndroidVoice(private val voice: MashaVoice) : LiveVoice.Voice {
    override val queued: Boolean get() = voice.queued
    override val paused: Boolean get() = voice.paused
    override val sayingLast: Boolean get() = voice.sayingLast
    override val nowSaying: String? get() = voice.nowSaying
    override fun pause() = voice.pause()
    override fun resume() = voice.resume()
    override fun stop() = voice.stop()
    override fun spokenSoFar(): String = voice.spokenSoFar()
}

private class AndroidPresence(private val presence: MashaPresence) : LiveVoice.Presence {
    override val speaking: Boolean get() = presence.speaking
    override var listening: Boolean
        get() = presence.listening
        set(value) {
            presence.listening = value
        }
}

private class AndroidChat(private val masha: MashaController) : LiveVoice.Chat {
    override val busy: Boolean get() = masha.busy
    override fun send(text: String, language: String, force: Boolean) = masha.say(text, language, force)
    override fun stop() = masha.stop()
    override fun interrupt(spoken: String) = masha.interrupt(spoken)
}
