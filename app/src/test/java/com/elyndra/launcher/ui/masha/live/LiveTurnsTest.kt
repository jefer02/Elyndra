package com.elyndra.launcher.ui.masha.live

import com.elyndra.launcher.ui.masha.live.LiveTurns.Effect
import com.elyndra.launcher.ui.masha.live.LiveTurns.EndReason
import com.elyndra.launcher.ui.masha.live.LiveTurns.Event
import com.elyndra.launcher.ui.masha.live.LiveTurns.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(listOf(Effect.OpenMic), t.on(Event.Start))
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
    fun startingDoesNotCutWhatSheIsSaying() {
        assertEquals(listOf(Effect.OpenMic), LiveTurns().on(Event.Start))
    }

    @Test
    fun onlyLeavingBackgroundTheLockOrAMissingPermissionEndTheSession() {
        val reasons = mapOf(
            Event.Leave to EndReason.Left,
            Event.Background to EndReason.Background,
            Event.Lock to EndReason.Locked,
            Event.PermissionMissing to EndReason.Permission,
        )
        reasons.forEach { (ev, reason) ->
            assertEquals(listOf(Effect.StopVoice, Effect.CloseMic, Effect.End(reason)), waiting().on(ev))
            assertEquals(
                listOf(Effect.StopReply, Effect.StopVoice, Effect.CloseMic, Effect.End(reason)),
                speaking().on(ev),
            )
            val held = speaking().apply { on(Event.SpeechStart) }
            assertEquals(
                listOf(Effect.CancelRecognizer, Effect.StopReply, Effect.StopVoice, Effect.CloseMic, Effect.End(reason)),
                held.on(ev),
            )
            assertEquals(State.Idle, held.state)
            val hearing = waiting().apply { on(Event.SpeechStart) }
            assertEquals(listOf(Effect.CancelRecognizer, Effect.StopVoice, Effect.CloseMic, Effect.End(reason)), hearing.on(ev))
        }
        assertEquals(emptyList<Effect>(), LiveTurns().on(Event.Leave))
    }

    @Test
    fun everyRecognizerErrorCodeKeepsTheSessionAlive() {
        (1..15).forEach { code ->
            // Mientras el usuario habla: vuelve a esperar y se recupera el reconocedor.
            val hearing = waiting().apply { on(Event.SpeechStart) }
            assertEquals(listOf(Effect.CancelRecognizer, Effect.RecoverRecognizer(code)), hearing.on(Event.RecognizerError(code)))
            assertEquals(State.Waiting, hearing.state)
            assertTrue(hearing.active)
            // En una pausa sobre su voz: ella sigue.
            val held = speaking().apply { on(Event.SpeechStart) }
            assertEquals(listOf(Effect.CancelRecognizer, Effect.ResumeVoice), held.on(Event.RecognizerError(code)))
            assertEquals(State.Speaking, held.state)
            // En cualquier otro estado, un error tardío no hace nada.
            listOf(waiting(), thinking(), speaking()).forEach { t ->
                val before = t.state
                assertEquals(emptyList<Effect>(), t.on(Event.RecognizerError(code)))
                assertEquals(before, t.state)
            }
            // Y se puede seguir hablando.
            assertEquals(listOf(Effect.OpenRecognizer), hearing.on(Event.SpeechStart))
        }
    }

    @Test
    fun aPauseWithNoTextResumesHerSpeechAndKeepsTheSession() {
        val t = speaking().apply { on(Event.SpeechStart) }
        assertEquals(State.Held, t.state)
        assertEquals(listOf(Effect.CancelRecognizer, Effect.ResumeVoice), t.on(Event.HeardNothing))
        assertEquals(State.Speaking, t.state)
        val again = speaking().apply { on(Event.SpeechStart) }
        assertEquals(listOf(Effect.CancelRecognizer, Effect.ResumeVoice), again.on(Event.Heard("  ")))
        assertEquals(State.Speaking, again.state)
        assertTrue(again.active)
    }

    @Test
    fun tenEmptyResultsInARowKeepTheSession() {
        val t = waiting()
        repeat(10) {
            t.on(Event.SpeechStart)
            t.on(Event.TurnEnded)
            if (it % 2 == 0) t.on(Event.HeardNothing) else t.on(Event.Heard(""))
            assertEquals(State.Waiting, t.state)
        }
        assertTrue(t.active)
        assertEquals(listOf(Effect.OpenRecognizer), t.on(Event.SpeechStart))
    }

    @Test
    fun herVoiceStartingWhileWaitingCountsAsSpeaking() {
        // Respuesta a algo escrito o tocado: hablarle encima la pausa igual.
        val t = waiting()
        assertEquals(listOf(Effect.WatchVoice), t.on(Event.ReplyStarted))
        assertEquals(State.Speaking, t.state)
        assertEquals(listOf(Effect.PauseVoice, Effect.OpenRecognizer), t.on(Event.SpeechStart))
        t.on(Event.Resume)
        assertEquals(emptyList<Effect>(), t.on(Event.ReplyFinished))
        assertEquals(State.Waiting, t.state)
    }

    @Test
    fun theBackoffGrowsAndResets() {
        val b = RecognizerBackoff()
        assertEquals(listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 4_000L), List(6) { b.next() })
        b.reset()
        assertEquals(250L, b.next())
    }

    @Test
    fun transitionsAreLoggedWithoutWhatTheUserSaid() {
        val line = LiveTurns.describe(Event.Heard("my secret"), State.UserSpeaking, State.Thinking, listOf(Effect.Send("my secret")))
        assertEquals("event=Heard(9 chars) from=UserSpeaking to=Thinking effects=[Send]", line)
        assertEquals("NO_MATCH", RecognizerErrors.name(7))
    }

    @Test
    fun eventsOutOfTurnAreIgnored() {
        val idle = LiveTurns()
        listOf(Event.SpeechStart, Event.TurnEnded, Event.Heard("hello"), Event.ReplyFinished, Event.Resume, Event.Commit, Event.RecognizerError(7), Event.ReplyStarted)
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
