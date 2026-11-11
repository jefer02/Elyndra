package com.elyndra.launcher.ui.masha.live

/**
 * Los turnos de la conversación en vivo con Lucy, sin Android: quién tiene la
 * palabra y qué hay que hacer al pasar de uno a otro. El micrófono está
 * abierto toda la sesión; lo que cambia es qué se hace con lo que oye.
 *
 *     Idle ─Start→ Waiting ─SpeechStart→ UserSpeaking ─Heard→ Thinking ─ReplyStarted→ Speaking
 *                    ↑  ↑                     │  ↑                │                    │  ↑
 *                    │  └── Heard("") ────────┘  └─ SpeechStart ──┘ (siguió hablando)  │  │
 *                    │                           └──── Commit ──── Held ←─SpeechStart──┘  │
 *                    └──────────── ReplyFinished ─────────────────  Held ──Resume─────────┘
 *
 * - **Escuchar siempre:** con Lucy hablando, si el usuario empieza a hablar
 *   (SpeechStart) ella se pausa (Held) y el reconocedor ya escucha; si era
 *   una muletilla (Resume) sigue donde iba; si va en serio (Commit) se corta
 *   la respuesta, lo dicho queda marcado como interrumpido y el turno es suyo.
 * - **Fin de turno:** TurnEnded (silencio) cierra el reconocedor; su texto
 *   (Heard) se manda. Si mientras piensa el usuario sigue hablando, se
 *   cancela la petición y se espera a que termine.
 * - **Sin uso:** [IDLE_TIMEOUT_MS] sin voz del usuario terminan la sesión
 *   (lo cuenta LiveVoice: IdleTimeout).
 * - Stop (botón), Background (fondo, pantalla apagada, salir) y Failed
 *   terminan desde cualquier estado y sueltan el micrófono.
 *
 * [on] devuelve los efectos en el orden en que hay que ejecutarlos.
 */
class LiveTurns {

    enum class State { Idle, Waiting, UserSpeaking, Thinking, Speaking, Held }

    sealed interface Event {
        data object Start : Event
        data object Stop : Event
        data object Background : Event

        /** El usuario empezó a hablar (~250 ms de voz que no es la de ella). */
        data object SpeechStart : Event

        /** El usuario terminó de hablar (silencio de fin de turno). */
        data object TurnEnded : Event

        /** El reconocedor entendió algo (vacío = nada). */
        data class Heard(val text: String) : Event

        /** El reconocedor terminó sin nada. */
        data object HeardNothing : Event

        /** La voz de Lucy empezó a sonar. */
        data object ReplyStarted : Event

        /** La respuesta terminó de llegar y la voz no tiene nada más que decir. */
        data object ReplyFinished : Event

        /** Lo dicho en la pausa era una muletilla: que siga. */
        data object Resume : Event

        /** Lo dicho en la pausa va en serio (o el botón mantenido): se corta. */
        data object Commit : Event

        data object IdleTimeout : Event

        /** Fallo que impide seguir (sin micrófono, sin reconocedor, sin permiso…). */
        data object Failed : Event
    }

    enum class EndReason { Stopped, Silence, Failed }

    sealed interface Effect {
        data object OpenMic : Effect
        data object CloseMic : Effect
        data object OpenRecognizer : Effect
        data object FinishRecognizer : Effect
        data object CancelRecognizer : Effect
        data object PauseVoice : Effect
        data object ResumeVoice : Effect
        data object StopVoice : Effect

        /** Corta la respuesta en curso (la petición a la IA). */
        data object StopReply : Effect

        /** Corta la respuesta y deja en el hilo solo lo que llegó a decir, marcado como interrumpido. */
        data object Interrupt : Effect
        data class Send(val text: String) : Effect
        data class End(val reason: EndReason) : Effect
    }

    var state: State = State.Idle
        private set

    val active: Boolean get() = state != State.Idle

    fun on(event: Event): List<Effect> = when (event) {
        Event.Start -> if (state == State.Idle) {
            state = State.Waiting
            listOf(Effect.StopVoice, Effect.OpenMic)
        } else {
            emptyList()
        }

        Event.Stop, Event.Background -> end(EndReason.Stopped)
        Event.Failed -> end(EndReason.Failed)
        Event.IdleTimeout -> if (state == State.Waiting) end(EndReason.Silence) else emptyList()

        Event.SpeechStart -> when (state) {
            State.Waiting -> go(State.UserSpeaking, Effect.OpenRecognizer)
            // Siguió hablando tras la pausa: lo que se mandó estaba a medias.
            State.Thinking -> go(State.UserSpeaking, Effect.StopReply, Effect.StopVoice, Effect.OpenRecognizer)
            State.Speaking -> go(State.Held, Effect.PauseVoice, Effect.OpenRecognizer)
            else -> emptyList()
        }

        Event.TurnEnded -> if (state == State.UserSpeaking) listOf(Effect.FinishRecognizer) else emptyList()

        is Event.Heard -> when {
            state != State.UserSpeaking -> emptyList()
            event.text.isBlank() -> go(State.Waiting)
            else -> go(State.Thinking, Effect.Send(event.text.trim()))
        }

        Event.HeardNothing -> if (state == State.UserSpeaking) go(State.Waiting) else emptyList()

        Event.ReplyStarted -> if (state == State.Thinking) go(State.Speaking) else emptyList()

        Event.ReplyFinished -> if (state == State.Thinking || state == State.Speaking) go(State.Waiting) else emptyList()

        Event.Resume -> if (state == State.Held) go(State.Speaking, Effect.CancelRecognizer, Effect.ResumeVoice) else emptyList()

        Event.Commit -> when (state) {
            State.Held -> go(State.UserSpeaking, Effect.Interrupt, Effect.StopVoice, Effect.OpenRecognizer)
            // El botón mantenido mientras habla o piensa: igual, sin pasar por la pausa.
            State.Speaking, State.Thinking -> go(State.UserSpeaking, Effect.Interrupt, Effect.StopVoice, Effect.OpenRecognizer)
            else -> emptyList()
        }
    }

    private fun go(next: State, vararg effects: Effect): List<Effect> {
        state = next
        return effects.toList()
    }

    private fun end(reason: EndReason): List<Effect> {
        if (state == State.Idle) return emptyList()
        val talking = state == State.Thinking || state == State.Speaking || state == State.Held
        val hearing = state == State.UserSpeaking || state == State.Held
        state = State.Idle
        return buildList {
            if (hearing) add(Effect.CancelRecognizer)
            if (talking) add(Effect.StopReply)
            add(Effect.StopVoice)
            add(Effect.CloseMic)
            add(Effect.End(reason))
        }
    }

    companion object {
        /** Tres minutos sin que el usuario diga nada terminan la conversación en vivo. */
        const val IDLE_TIMEOUT_MS = 180_000L
    }
}
