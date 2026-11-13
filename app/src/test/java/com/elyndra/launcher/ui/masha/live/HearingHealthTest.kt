package com.elyndra.launcher.ui.masha.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Si Lucy oye de verdad: turnos sin texto, el pipe que nadie lee y el resumen por segundo del micrófono. */
class HearingHealthTest {

    @Test
    fun turnsWithoutTextAddUpAndOneWithTextClearsThem() {
        val h = HearingHealth()
        assertEquals(HearingHealth.Advice.None, h.onTurn(gotText = false, speechMs = 800, viaStream = true))
        assertEquals(HearingHealth.Advice.UseHandoff, h.onTurn(gotText = false, speechMs = 800, viaStream = true))
        assertFalse(h.struggling)
        h.onTurn(gotText = false, speechMs = 800, viaStream = false)
        assertTrue(h.struggling)
        h.onTurn(gotText = true, speechMs = 800, viaStream = false)
        assertEquals(0, h.misses)
        assertFalse(h.struggling)
    }

    @Test
    fun aShortNoiseDoesNotCount() {
        val h = HearingHealth()
        repeat(5) { h.onTurn(gotText = false, speechMs = 300, viaStream = true) }
        assertEquals(0, h.misses)
        assertFalse(h.struggling)
    }

    @Test
    fun handoffIsOnlyAdvisedWhileListeningThroughTheStream() {
        val h = HearingHealth()
        repeat(4) { assertEquals(HearingHealth.Advice.None, h.onTurn(gotText = false, speechMs = 900, viaStream = false)) }
    }

    @Test
    fun aMicGivingOnlySilenceIsAProblem() {
        val h = HearingHealth()
        h.onMicSilenced(HearingHealth.SILENCED_LIMIT_MS - 1)
        assertFalse(h.struggling)
        h.onMicSilenced(HearingHealth.SILENCED_LIMIT_MS)
        assertTrue(h.struggling)
        h.reset()
        assertFalse(h.struggling)
    }

    @Test
    fun theStreamCheckSpotsARecognizerThatIgnoresOurAudio() {
        assertEquals(StreamCheck.Verdict.Ok, StreamCheck.verdict(silencedMs = 0, backlogMs = 0))
        assertEquals(StreamCheck.Verdict.RecognizerTookMic, StreamCheck.verdict(silencedMs = 320, backlogMs = 0))
        assertEquals(StreamCheck.Verdict.NotReading, StreamCheck.verdict(silencedMs = 0, backlogMs = 700))
    }

    @Test
    fun silenceThatWasThereBeforeOrAfterTheTurnIsNotTheRecognizer() {
        // Un supresor de ruido que deja ceros en una habitación callada.
        assertEquals(StreamCheck.Verdict.Ok, StreamCheck.verdict(silencedMs = 900, backlogMs = 0, silencedAtOpen = 200))
        // El usuario ya terminó: el silencio es suyo.
        assertEquals(StreamCheck.Verdict.Ok, StreamCheck.verdict(silencedMs = 900, backlogMs = 0, inTurn = false))
    }

    @Test
    fun theMeterSummarisesASecondOfFrames() {
        val m = MicMeter(frameMs = 16, periodMs = 1_000)
        repeat(62) { assertNull(m.add(-40f, 0.1f, EchoGate.SILENT, EchoGate.SILENT, voiced = false, echoRejected = false)) }
        val s = m.add(-20f, 0.9f, -30f, -42f, voiced = true, echoRejected = false)!!
        assertEquals(63, s.frames)
        assertEquals(-20f, s.micMaxDb)
        assertEquals(0.9f, s.probMax)
        assertEquals(1, s.voiced)
        assertEquals(0, s.silenced)
        val line = s.line("silero")
        assertTrue(line.contains("silero p avg="))
        assertTrue(line.contains("lucy=-30 dBFS"))
        assertTrue(line.contains("expectedEcho=-42 dBFS"))
        // Y empieza otro segundo.
        assertNull(m.add(MicMeter.SILENT_DB, 0f, EchoGate.SILENT, EchoGate.SILENT, voiced = false, echoRejected = false))
    }

    @Test
    fun theMeterCountsDigitalSilenceAndSaysWhenLucyIsQuiet() {
        val m = MicMeter(frameMs = 500, periodMs = 1_000)
        assertNull(m.add(MicMeter.SILENT_DB, 0f, EchoGate.SILENT, EchoGate.SILENT, voiced = false, echoRejected = false))
        val s = m.add(MicMeter.SILENT_DB, 0f, EchoGate.SILENT, EchoGate.SILENT, voiced = false, echoRejected = false)!!
        assertEquals(2, s.silenced)
        assertTrue(s.line("energy").contains("lucy=silent"))
        assertTrue(s.line("energy").contains("expectedEcho=none"))
    }
}
