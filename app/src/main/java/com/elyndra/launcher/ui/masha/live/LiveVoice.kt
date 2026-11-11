package com.elyndra.launcher.ui.masha.live

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
 * La conversación en vivo con Lucy: el usuario habla, ella contesta en voz
 * alta (con los labios) y vuelve a escuchar, sin tocar nada.
 *
 * Une las piezas que ya existían —[MashaEars] (oír), [MashaController.send]
 * (la misma conversación del chat, con herramientas y sin conexión), la voz
 * frase a frase de [MashaVoice]— con los turnos de [LiveTurns] y el barge-in
 * de [BargeInListener]. Todo en el hilo principal.
 *
 * Quién avisa de qué:
 * - La pantalla pasa a [onHeard] / [onEarsFailure] lo que oyen los oídos
 *   mientras [active].
 * - Tras mandar una frase, un vigilante espera a que Lucy empiece a sonar
 *   (ReplyStarted, y entonces se abre el barge-in) y a que termine: respuesta
 *   completa y voz sin nada en cola (ReplyFinished). Si la voz no llega a
 *   sonar (sin motor), se da por terminada a los [MAX_SPEAK_MS].
 */
class LiveVoice(
    private val ears: MashaEars,
    private val voice: MashaVoice,
    private val presence: MashaPresence,
    private val masha: MashaController,
    private val barge: BargeInListener,
    private val scope: CoroutineScope,
    private val language: () -> String,
    /** Con auriculares el barge-in no se oye a sí mismo aunque no haya cancelación de eco. */
    private val headphones: () -> Boolean,
    private val onEnded: (LiveTurns.EndReason) -> Unit,
) {
    private val turns = LiveTurns()
    private var watcher: Job? = null

    var state by mutableStateOf(LiveTurns.State.Idle)
        private set

    val active: Boolean get() = state != LiveTurns.State.Idle

    fun toggle() = dispatch(if (active) LiveTurns.Event.Stop else LiveTurns.Event.Start)

    fun stop() = dispatch(LiveTurns.Event.Stop)

    /** La app se va al fondo o se sale de la pantalla: se suelta el micrófono. */
    fun background() = dispatch(LiveTurns.Event.Background)

    /** El usuario quiere hablar ya (botón mantenido mientras ella piensa o habla). */
    fun bargeIn() = dispatch(LiveTurns.Event.BargeIn)

    fun onHeard(text: String) = dispatch(LiveTurns.Event.Heard(text))

    fun onEarsFailure(failure: MashaEars.Failure) = dispatch(
        when (failure) {
            MashaEars.Failure.NoMatch, MashaEars.Failure.Busy -> LiveTurns.Event.HeardNothing
            MashaEars.Failure.Unavailable, MashaEars.Failure.Permission, MashaEars.Failure.Network -> LiveTurns.Event.Failed
        },
    )

    fun release() {
        stop()
        barge.stop()
    }

    private fun dispatch(event: LiveTurns.Event) {
        val effects = turns.on(event)
        state = turns.state
        effects.forEach(::run)
    }

    private fun run(effect: LiveTurns.Effect) {
        when (effect) {
            LiveTurns.Effect.Listen -> {
                watcher?.cancel()
                barge.stop()
                ears.start(language())
            }
            LiveTurns.Effect.StopListening -> ears.stop()
            LiveTurns.Effect.StopVoice -> {
                watcher?.cancel()
                barge.stop()
                voice.stop()
            }
            LiveTurns.Effect.StopReply -> masha.stop()
            is LiveTurns.Effect.Send -> {
                masha.updateDraft(effect.text)
                masha.send(language())
                watch()
            }
            is LiveTurns.Effect.End -> {
                watcher?.cancel()
                barge.stop()
                onEnded(effect.reason)
            }
        }
    }

    /** Sigue la respuesta: cuándo empieza a sonar y cuándo ha terminado del todo. */
    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            launch {
                snapshotFlow { presence.speaking }.first { it }
                dispatch(LiveTurns.Event.ReplyStarted)
                if (barge.usable(headphones())) barge.start()
            }
            snapshotFlow { masha.busy }.first { !it }
            // La última frase se pone en cola un instante después de terminar la respuesta.
            delay(SETTLE_MS)
            // Con frases en cola que no llegan a sonar (sin motor de voz), no se espera un minuto.
            val sounding = presence.speaking || !voice.queued ||
                withTimeoutOrNull(NO_VOICE_MS) { snapshotFlow { presence.speaking }.first { it } } != null
            val drained = sounding && withTimeoutOrNull(MAX_SPEAK_MS) { snapshotFlow { voice.queued }.first { !it } } != null
            if (!drained) voice.stop()
            barge.stop()
            dispatch(LiveTurns.Event.ReplyFinished)
        }
    }

    private companion object {
        const val SETTLE_MS = 450L
        const val MAX_SPEAK_MS = 60_000L
        const val NO_VOICE_MS = 8_000L
    }
}
