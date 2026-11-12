package com.elyndra.launcher.ui.masha.live

/**
 * Los turnos de la conversación en vivo con Lucy, sin Android: quién tiene la
 * palabra y qué hay que hacer al pasar de uno a otro. El micrófono está
 * abierto toda la sesión; lo que cambia es qué se hace con lo que oye.
 *
 *     Idle ─Start→ Waiting ─SpeechStart→ UserSpeaking ─Heard→ Thinking ─ReplyStarted→ Speaking
 *                    ↑  ↑ │                   │  ↑                │                    │  ↑
 *                    │  │ └─ReplyStarted──────┼──┼────────────────┼──────────────────→ │  │
 *                    │  └── Heard("") / error ┘  └─ SpeechStart ──┘ (siguió hablando)  │  │
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
 * - **Ella habla sin turno:** una respuesta a algo escrito o tocado, o el
 *   saludo, empieza a sonar con la sesión esperando (ReplyStarted en Waiting):
 *   pasa a Speaking, así hablarle encima la pausa igual.
 * - **Nada de lo que oye termina la sesión:** un error del reconocedor
 *   ([Event.RecognizerError], cualquier código), un resultado vacío, el
 *   silencio o una pausa vuelven a escuchar (o a que ella siga). No hay
 *   temporizador de inactividad.
 * - Solo terminan: salir de la pantalla ([Event.Leave]), la app al fondo o la
 *   pantalla apagada ([Event.Background]), el bloqueo del micrófono
 *   ([Event.Lock]) y la falta de permiso ([Event.PermissionMissing]). Sueltan
 *   el micrófono desde cualquier estado.
 *
 * [on] devuelve los efectos en el orden en que hay que ejecutarlos.
 */
class LiveTurns {

    enum class State { Idle, Waiting, UserSpeaking, Thinking, Speaking, Held }

    sealed interface Event {
        data object Start : Event

        /** Se sale de la pantalla de Lucy. */
        data object Leave : Event

        /** La app al fondo o la pantalla apagada. */
        data object Background : Event

        /** El usuario bloquea el micrófono. */
        data object Lock : Event

        /** Sin permiso de micrófono. */
        data object PermissionMissing : Event

        /** El usuario empezó a hablar (~250 ms de voz que no es la de ella). */
        data object SpeechStart : Event

        /** El usuario terminó de hablar (silencio de fin de turno). */
        data object TurnEnded : Event

        /** El reconocedor entendió algo (vacío = nada). */
        data class Heard(val text: String) : Event

        /** El reconocedor terminó sin nada. */
        data object HeardNothing : Event

        /** El reconocedor falló con este código (los de SpeechRecognizer.ERROR_*): nunca termina la sesión. */
        data class RecognizerError(val code: Int) : Event

        /** La voz de Lucy empezó a sonar. */
        data object ReplyStarted : Event

        /** La respuesta terminó de llegar y la voz no tiene nada más que decir. */
        data object ReplyFinished : Event

        /** Lo dicho en la pausa era una muletilla: que siga. */
        data object Resume : Event

        /** Lo dicho en la pausa va en serio (o el botón mantenido): se corta. */
        data object Commit : Event
    }

    enum class EndReason { Left, Background, Locked, Permission }

    sealed interface Effect {
        data object OpenMic : Effect
        data object CloseMic : Effect
        data object OpenRecognizer : Effect
        data object FinishRecognizer : Effect
        data object CancelRecognizer : Effect

        /**
         * El reconocedor falló a mitad del turno del usuario: si sigue hablando,
         * se vuelve a abrir tras una espera creciente (ver [RecognizerBackoff]).
         */
        data class RecoverRecognizer(val code: Int) : Effect
        data object PauseVoice : Effect
        data object ResumeVoice : Effect
        data object StopVoice : Effect

        /** Sigue la voz que ya suena hasta que se calle (ReplyFinished). */
        data object WatchVoice : Effect

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
            listOf(Effect.OpenMic)
        } else {
            emptyList()
        }

        Event.Leave -> end(EndReason.Left)
        Event.Background -> end(EndReason.Background)
        Event.Lock -> end(EndReason.Locked)
        Event.PermissionMissing -> end(EndReason.Permission)

        Event.SpeechStart -> when (state) {
            State.Waiting -> go(State.UserSpeaking, Effect.OpenRecognizer)
            // Siguió hablando tras la pausa: lo que se mandó estaba a medias.
            State.Thinking -> go(State.UserSpeaking, Effect.StopReply, Effect.StopVoice, Effect.OpenRecognizer)
            State.Speaking -> go(State.Held, Effect.PauseVoice, Effect.OpenRecognizer)
            else -> emptyList()
        }

        Event.TurnEnded -> if (state == State.UserSpeaking) listOf(Effect.FinishRecognizer) else emptyList()

        is Event.Heard -> when {
            state == State.Held && event.text.isBlank() -> resumeAfterPause()
            state != State.UserSpeaking -> emptyList()
            event.text.isBlank() -> go(State.Waiting)
            else -> go(State.Thinking, Effect.Send(event.text.trim()))
        }

        Event.HeardNothing -> when (state) {
            State.UserSpeaking -> go(State.Waiting)
            // Una pausa sin texto: era ruido o una muletilla que no se entendió; ella sigue.
            State.Held -> resumeAfterPause()
            else -> emptyList()
        }

        is Event.RecognizerError -> when (state) {
            State.UserSpeaking -> go(State.Waiting, Effect.CancelRecognizer, Effect.RecoverRecognizer(event.code))
            State.Held -> resumeAfterPause()
            else -> emptyList()
        }

        Event.ReplyStarted -> when (state) {
            State.Thinking -> go(State.Speaking)
            // Habla sin que viniera de un turno de voz (algo escrito o tocado, el saludo).
            State.Waiting -> go(State.Speaking, Effect.WatchVoice)
            else -> emptyList()
        }

        Event.ReplyFinished -> if (state == State.Thinking || state == State.Speaking) go(State.Waiting) else emptyList()

        Event.Resume -> if (state == State.Held) go(State.Speaking, Effect.CancelRecognizer, Effect.ResumeVoice) else emptyList()

        Event.Commit -> when (state) {
            State.Held -> go(State.UserSpeaking, Effect.Interrupt, Effect.StopVoice, Effect.OpenRecognizer)
            // El botón mantenido mientras habla o piensa: igual, sin pasar por la pausa.
            State.Speaking, State.Thinking -> go(State.UserSpeaking, Effect.Interrupt, Effect.StopVoice, Effect.OpenRecognizer)
            else -> emptyList()
        }
    }

    private fun resumeAfterPause(): List<Effect> = go(State.Speaking, Effect.CancelRecognizer, Effect.ResumeVoice)

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
        /** Una línea de log por transición (etiqueta LucyLive): `event=… from=… to=… effects=[…]`. */
        fun describe(event: Event, from: State, to: State, effects: List<Effect>): String =
            "event=${name(event)} from=$from to=$to effects=[${effects.joinToString(",") { name(it) }}]"

        private fun name(event: Event): String = when (event) {
            is Event.Heard -> "Heard(${if (event.text.isBlank()) "empty" else "${event.text.length} chars"})"
            is Event.RecognizerError -> "RecognizerError(${event.code})"
            else -> event.toString()
        }

        private fun name(effect: Effect): String = when (effect) {
            is Effect.Send -> "Send"
            is Effect.End -> "End(${effect.reason})"
            is Effect.RecoverRecognizer -> "RecoverRecognizer(${effect.code})"
            else -> effect.toString()
        }
    }
}

/**
 * Cuánto esperar antes de volver a abrir el reconocedor tras fallos seguidos
 * (pura): 250 ms, 500, 1 s, 2 s y luego 4 s como mucho. Un resultado bueno la
 * reinicia.
 */
class RecognizerBackoff {
    var failures = 0
        private set

    /** Apunta un fallo y devuelve cuánto esperar antes del siguiente intento. */
    fun next(): Long {
        val ms = (BASE_MS shl failures.coerceAtMost(4)).coerceAtMost(MAX_MS)
        failures++
        return ms
    }

    fun reset() {
        failures = 0
    }

    companion object {
        const val BASE_MS = 250L
        const val MAX_MS = 4_000L
    }
}

/** Los códigos de error de SpeechRecognizer, con nombre para el log (puro: no depende de Android). */
object RecognizerErrors {
    val NAMES = mapOf(
        1 to "NETWORK_TIMEOUT",
        2 to "NETWORK",
        3 to "AUDIO",
        4 to "SERVER",
        5 to "CLIENT",
        6 to "SPEECH_TIMEOUT",
        7 to "NO_MATCH",
        8 to "RECOGNIZER_BUSY",
        9 to "INSUFFICIENT_PERMISSIONS",
        10 to "TOO_MANY_REQUESTS",
        11 to "SERVER_DISCONNECTED",
        12 to "LANGUAGE_NOT_SUPPORTED",
        13 to "LANGUAGE_UNAVAILABLE",
        14 to "CANNOT_CHECK_SUPPORT",
        15 to "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS",
    )

    fun name(code: Int): String = NAMES[code] ?: "UNKNOWN"
}
