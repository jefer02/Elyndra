package com.elyndra.launcher.ui.masha.live

import com.elyndra.launcher.ui.masha.live.LiveTurns.Effect
import com.elyndra.launcher.ui.masha.live.LiveTurns.EndReason
import com.elyndra.launcher.ui.masha.live.LiveTurns.Event
import com.elyndra.launcher.ui.masha.live.LiveTurns.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Los turnos de la conversación en vivo con el micrófono siempre abierto: pausa, muletilla, corte y fin. */
class LiveTurnsTest {

    private fun waiting() = LiveTurns().apply { on(Event.Start) }

    private fun thinking() = waiting().apply {
        on(Event.SpeechStart)
        on(Event.Heard("what should I play?"))
    }

    private fun speaking() = thinking().apply { on(Event.ReplyStarted) }

    @Test
    fun aFullTurnGoesBackToWaitingWithTheMicStillOpen() {
        val t = LiveTurns()
        assertEquals(listOf(Effect.StopVoice, Effect.OpenMic), t.on(Event.Start))
        assertEquals(State.Waiting, t.state)
        assertEquals(listOf(Effect.OpenRecognizer), t.on(Event.SpeechStart))
        assertEquals(State.UserSpeaking, t.state)
        assertEquals(listOf(Effect.FinishRecognizer), t.on(Event.TurnEnded))
        assertEquals(listOf(Effect.Send("what should I play?")), t.on(Event.Heard("  what should I play? ")))
        assertEquals(State.Thinking, t.state)
        assertEquals(emptyList<Effect>(), t.on(Event.ReplyStarted))
        assertEquals(State.Speaking, t.state)
        // Ni CloseMic ni OpenMic: el micrófono no se ha cerrado en ningún momento.
        assertEquals(emptyList<Effect>(), t.on(Event.ReplyFinished))
        assertEquals(State.Waiting, t.state)
    }

    @Test
    fun talkingOverHerPausesHerAndListensAtOnce() {
        val t = speaking()
        assertEquals(listOf(Effect.PauseVoice, Effect.OpenRecognizer), t.on(Event.SpeechStart))
        assertEquals(State.Held, t.state)
    }

    @Test
    fun aBackchannelLetsHerCarryOn() {
        val t = speaking().apply { on(Event.SpeechStart) }
        assertEquals(listOf(Effect.CancelRecognizer, Effect.ResumeVoice), t.on(Event.Resume))
        assertEquals(State.Speaking, t.state)
        // Y puede volver a pausarse luego.
        assertEquals(listOf(Effect.PauseVoice, Effect.OpenRecognizer), t.on(Event.SpeechStart))
    }

    @Test
    fun aRealInterruptionCutsHerAndTheTurnIsTheUsers() {
        val t = speaking().apply { on(Event.SpeechStart) }
        assertEquals(listOf(Effect.Interrupt, Effect.StopVoice, Effect.OpenRecognizer), t.on(Event.Commit))
        assertEquals(State.UserSpeaking, t.state)
        assertEquals(listOf(Effect.FinishRecognizer), t.on(Event.TurnEnded))
        assertEquals(listOf(Effect.Send("wait, what about Okami?")), t.on(Event.Heard("wait, what about Okami?")))
    }

    @Test
    fun holdingTheButtonCutsHerWithoutThePause() {
        assertEquals(listOf(Effect.Interrupt, Effect.StopVoice, Effect.OpenRecognizer), speaking().on(Event.Commit))
        assertEquals(listOf(Effect.Interrupt, Effect.StopVoice, Effect.OpenRecognizer), thinking().on(Event.Commit))
        assertEquals(emptyList<Effect>(), waiting().on(Event.Commit))
    }

    @Test
    fun speakingAgainWhileSheThinksCancelsTheHalfSentQuestion() {
        val t = thinking()
        assertEquals(listOf(Effect.StopReply, Effect.StopVoice, Effect.OpenRecognizer), t.on(Event.SpeechStart))
        assertEquals(State.UserSpeaking, t.state)
    }

    @Test
    fun nothingRecognisedGoesBackToWaiting() {
        val t = waiting().apply { on(Event.SpeechStart) }
        assertEquals(emptyList<Effect>(), t.on(Event.Heard("   ")))
        assertEquals(State.Waiting, t.state)
        t.on(Event.SpeechStart)
        t.on(Event.HeardNothing)
        assertEquals(State.Waiting, t.state)
    }

    @Test
    fun threeMinutesWithoutTheUserEndTheSessionOnlyWhileWaiting() {
        assertEquals(180_000L, LiveTurns.IDLE_TIMEOUT_MS)
        assertEquals(emptyList<Effect>(), speaking().on(Event.IdleTimeout))
        val t = waiting()
        assertEquals(listOf(Effect.StopVoice, Effect.CloseMic, Effect.End(EndReason.Silence)), t.on(Event.IdleTimeout))
        assertFalse(t.active)
    }

    @Test
    fun stopOrBackgroundReleaseEverythingFromAnyState() {
        listOf(Event.Stop, Event.Background).forEach { ev ->
            assertEquals(listOf(Effect.StopVoice, Effect.CloseMic, Effect.End(EndReason.Stopped)), waiting().on(ev))
            assertEquals(
                listOf(Effect.StopReply, Effect.StopVoice, Effect.CloseMic, Effect.End(EndReason.Stopped)),
                speaking().on(ev),
            )
            val held = speaking().apply { on(Event.SpeechStart) }
            assertEquals(
                listOf(Effect.CancelRecognizer, Effect.StopReply, Effect.StopVoice, Effect.CloseMic, Effect.End(EndReason.Stopped)),
                held.on(ev),
            )
            assertEquals(State.Idle, held.state)
            val hearing = waiting().apply { on(Event.SpeechStart) }
            assertEquals(listOf(Effect.CancelRecognizer, Effect.StopVoice, Effect.CloseMic, Effect.End(EndReason.Stopped)), hearing.on(ev))
        }
        assertEquals(emptyList<Effect>(), LiveTurns().on(Event.Stop))
    }

    @Test
    fun aFailureEndsTheSession() {
        val t = waiting()
        assertEquals(listOf(Effect.StopVoice, Effect.CloseMic, Effect.End(EndReason.Failed)), t.on(Event.Failed))
        assertEquals(State.Idle, t.state)
    }

    @Test
    fun eventsOutOfTurnAreIgnored() {
        val idle = LiveTurns()
        listOf(Event.SpeechStart, Event.TurnEnded, Event.Heard("hello"), Event.ReplyFinished, Event.Resume, Event.Commit, Event.IdleTimeout)
            .forEach { assertEquals(emptyList<Effect>(), idle.on(it)) }
        assertEquals(State.Idle, idle.state)
        val t = thinking()
        assertEquals(emptyList<Effect>(), t.on(Event.Heard("again")))
        assertEquals(emptyList<Effect>(), t.on(Event.TurnEnded))
        assertEquals(emptyList<Effect>(), t.on(Event.Resume))
        assertEquals(State.Thinking, t.state)
        assertEquals(emptyList<Effect>(), waiting().on(Event.Start))
        // En la pausa, otra vez "empieza a hablar" no hace nada: ya escucha.
        assertEquals(emptyList<Effect>(), speaking().apply { on(Event.SpeechStart) }.on(Event.SpeechStart))
    }
}
