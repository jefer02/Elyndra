package com.elyndra.launcher.ui.masha.live

/**
 * Los turnos de la conversación en vivo con Lucy, sin Android: quién tiene la
 * palabra y qué hay que hacer al pasar de uno a otro.
 *
 *     Idle ─Start→ Listening ─Heard→ Thinking ─ReplyStarted→ Speaking
 *                     ↑  │                │                      │
 *                     │  └HeardNothing×2→ Idle (silencio)         │
 *                     └──────── ReplyFinished / BargeIn ──────────┘
 *
 * - Tras hablar Lucy, vuelve a escuchar sola.
 * - Barge-in: si el usuario habla (o pulsa) mientras ella piensa o habla, se
 *   corta la respuesta y la voz y se escucha.
 * - Dos escuchas seguidas sin nada que entender ([MAX_SILENT_LISTENS]) terminan
 *   la sesión: el silencio también es una respuesta.
 * - Stop (botón) o Background (la app se va al fondo) terminan desde cualquier estado.
 *
 * [on] devuelve los efectos en el orden en que hay que ejecutarlos.
 */
class LiveTurns {

    enum class State { Idle, Listening, Thinking, Speaking }

    sealed interface Event {
        data object Start : Event
        data object Stop : Event
        data object Background : Event

        /** El reconocedor entendió algo. */
        data class Heard(val text: String) : Event

        /** Terminó una escucha sin nada (silencio, nada reconocible). */
        data object HeardNothing : Event

        /** La voz de Lucy empezó a sonar. */
        data object ReplyStarted : Event

        /** La respuesta terminó de llegar y la voz no tiene nada más que decir. */
        data object ReplyFinished : Event

        /** El usuario habló o pulsó mientras Lucy pensaba o hablaba. */
        data object BargeIn : Event

        /** Fallo que impide seguir (sin reconocedor, sin permiso…). */
        data object Failed : Event
    }

    enum class EndReason { Stopped, Silence, Failed }

    sealed interface Effect {
        data object Listen : Effect
        data object StopListening : Effect
        data object StopVoice : Effect

        /** Corta la respuesta en curso (la petición a la IA). */
        data object StopReply : Effect
        data class Send(val text: String) : Effect
        data class End(val reason: EndReason) : Effect
    }

    var state: State = State.Idle
        private set

    val active: Boolean get() = state != State.Idle

    private var silentListens = 0

    fun on(event: Event): List<Effect> = when (event) {
        Event.Start -> if (state == State.Idle) {
            silentListens = 0
            state = State.Listening
            listOf(Effect.StopVoice, Effect.Listen)
        } else {
            emptyList()
        }

        Event.Stop, Event.Background -> end(EndReason.Stopped)

        Event.Failed -> end(EndReason.Failed)

        is Event.Heard -> when {
            state != State.Listening -> emptyList()
            event.text.isBlank() -> heardNothing()
            else -> {
                silentListens = 0
                state = State.Thinking
                listOf(Effect.Send(event.text.trim()))
            }
        }

        Event.HeardNothing -> if (state == State.Listening) heardNothing() else emptyList()

        Event.ReplyStarted -> if (state == State.Thinking) {
            state = State.Speaking
            emptyList()
        } else {
            emptyList()
        }

        Event.ReplyFinished -> if (state == State.Thinking || state == State.Speaking) {
            state = State.Listening
            listOf(Effect.Listen)
        } else {
            emptyList()
        }

        Event.BargeIn -> if (state == State.Thinking || state == State.Speaking) {
            silentListens = 0
            state = State.Listening
            listOf(Effect.StopReply, Effect.StopVoice, Effect.Listen)
        } else {
            emptyList()
        }
    }

    private fun heardNothing(): List<Effect> {
        silentListens++
        return if (silentListens >= MAX_SILENT_LISTENS) end(EndReason.Silence) else listOf(Effect.Listen)
    }

    private fun end(reason: EndReason): List<Effect> {
        if (state == State.Idle) return emptyList()
        val wasTalking = state == State.Thinking || state == State.Speaking
        state = State.Idle
        silentListens = 0
        return buildList {
            add(Effect.StopListening)
            if (wasTalking && reason != EndReason.Silence) add(Effect.StopReply)
            add(Effect.StopVoice)
            add(Effect.End(reason))
        }
    }

    companion object {
        const val MAX_SILENT_LISTENS = 2
    }
}
