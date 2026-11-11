package com.elyndra.launcher.ui.masha.live

import com.elyndra.launcher.ui.masha.live.LiveTurns.Effect
import com.elyndra.launcher.ui.masha.live.LiveTurns.EndReason
import com.elyndra.launcher.ui.masha.live.LiveTurns.Event
import com.elyndra.launcher.ui.masha.live.LiveTurns.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los turnos de la conversación en vivo: escuchar → pensar → hablar → escuchar, barge-in y silencio. */
class LiveTurnsTest {

    private fun listening() = LiveTurns().apply { on(Event.Start) }

    private fun speaking() = listening().apply {
        on(Event.Heard("what should I play?"))
        on(Event.ReplyStarted)
    }

    @Test
    fun aFullTurnGoesBackToListening() {
        val t = LiveTurns()
        assertEquals(listOf(Effect.StopVoice, Effect.Listen), t.on(Event.Start))
        assertEquals(State.Listening, t.state)
        assertEquals(listOf(Effect.Send("what should I play?")), t.on(Event.Heard("  what should I play? ")))
        assertEquals(State.Thinking, t.state)
        assertEquals(emptyList<Effect>(), t.on(Event.ReplyStarted))
        assertEquals(State.Speaking, t.state)
        assertEquals(listOf(Effect.Listen), t.on(Event.ReplyFinished))
        assertEquals(State.Listening, t.state)
    }

    @Test
    fun aReplyThatNeverSoundsStillHandsTheTurnBack() {
        val t = listening()
        t.on(Event.Heard("hi"))
        assertEquals(listOf(Effect.Listen), t.on(Event.ReplyFinished))
        assertEquals(State.Listening, t.state)
    }

    @Test
    fun bargeInStopsLucyAndListens() {
        val t = speaking()
        assertEquals(listOf(Effect.StopReply, Effect.StopVoice, Effect.Listen), t.on(Event.BargeIn))
        assertEquals(State.Listening, t.state)
        // También mientras piensa.
        val thinking = listening().apply { on(Event.Heard("hey")) }
        assertEquals(listOf(Effect.StopReply, Effect.StopVoice, Effect.Listen), thinking.on(Event.BargeIn))
    }

    @Test
    fun bargeInWhileListeningOrIdleDoesNothing() {
        assertEquals(emptyList<Effect>(), listening().on(Event.BargeIn))
        assertEquals(emptyList<Effect>(), LiveTurns().on(Event.BargeIn))
    }

    @Test
    fun twoSilentListensInARowEndTheSession() {
        val t = listening()
        assertEquals(listOf(Effect.Listen), t.on(Event.HeardNothing))
        assertEquals(State.Listening, t.state)
        assertEquals(listOf(Effect.StopListening, Effect.StopVoice, Effect.End(EndReason.Silence)), t.on(Event.HeardNothing))
        assertEquals(State.Idle, t.state)
        assertFalse(t.active)
    }

    @Test
    fun hearingSomethingResetsTheSilenceCount() {
        val t = listening()
        t.on(Event.HeardNothing)
        t.on(Event.Heard("okay"))
        t.on(Event.ReplyFinished)
        assertEquals(listOf(Effect.Listen), t.on(Event.HeardNothing))
        assertTrue(t.active)
    }

    @Test
    fun blankRecognitionCountsAsSilence() {
        val t = listening()
        assertEquals(listOf(Effect.Listen), t.on(Event.Heard("   ")))
        assertEquals(State.Listening, t.state)
    }

    @Test
    fun stopOrBackgroundEndFromAnyState() {
        listOf(Event.Stop, Event.Background).forEach { ev ->
            val fromListening = listening()
            assertEquals(listOf(Effect.StopListening, Effect.StopVoice, Effect.End(EndReason.Stopped)), fromListening.on(ev))
            val fromSpeaking = speaking()
            assertEquals(listOf(Effect.StopListening, Effect.StopReply, Effect.StopVoice, Effect.End(EndReason.Stopped)), fromSpeaking.on(ev))
            assertEquals(State.Idle, fromSpeaking.state)
        }
        assertEquals(emptyList<Effect>(), LiveTurns().on(Event.Stop))
    }

    @Test
    fun aFailureEndsTheSession() {
        val t = listening()
        assertEquals(listOf(Effect.StopListening, Effect.StopVoice, Effect.End(EndReason.Failed)), t.on(Event.Failed))
        assertEquals(State.Idle, t.state)
    }

    @Test
    fun eventsOutOfTurnAreIgnored() {
        val idle = LiveTurns()
        assertEquals(emptyList<Effect>(), idle.on(Event.Heard("hello")))
        assertEquals(emptyList<Effect>(), idle.on(Event.ReplyFinished))
        assertEquals(State.Idle, idle.state)
        val thinking = listening().apply { on(Event.Heard("hey")) }
        assertEquals(emptyList<Effect>(), thinking.on(Event.Heard("again")))
        assertEquals(emptyList<Effect>(), thinking.on(Event.HeardNothing))
        assertEquals(State.Thinking, thinking.state)
        assertEquals(emptyList<Effect>(), listening().on(Event.Start))
    }

    @Test
    fun aNewSessionStartsWithACleanSilenceCount() {
        val t = listening()
        t.on(Event.HeardNothing)
        t.on(Event.Stop)
        t.on(Event.Start)
        assertEquals(listOf(Effect.Listen), t.on(Event.HeardNothing))
    }
}
