package com.elyndra.launcher.ui.masha.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** El detector de voz del barge-in: el fondo no dispara, una voz sostenida sí (una vez). */
class VoiceActivityTest {

    private fun frames(vad: VoiceActivity, db: Float, n: Int): List<Boolean> = (1..n).map { vad.accept(db) }

    @Test
    fun steadyBackgroundNeverTriggers() {
        val vad = VoiceActivity()
        assertTrue(frames(vad, -55f, 200).none { it })
    }

    @Test
    fun lucysOwnEchoBecomesTheFloor() {
        // El detector se abre cuando ella ya suena: lo que se cuela tras la cancelación de eco es el fondo,
        // con sus subidas y bajadas de unos pocos dB.
        val vad = VoiceActivity()
        val echo = (0 until 400).map { -40f + if (it % 7 < 3) 4f else -3f }
        assertTrue(echo.none { vad.accept(it) })
        // El usuario hablando claramente por encima, sí.
        assertTrue(frames(vad, -22f, 20).any { it })
    }

    @Test
    fun sustainedSpeechAboveTheFloorTriggersOnce() {
        val vad = VoiceActivity(frameMs = 20, minSpeechMs = 260)
        frames(vad, -55f, 50)
        val speech = frames(vad, -28f, 40)
        assertEquals(1, speech.count { it })
        assertEquals(12, speech.indexOf(true)) // 13 tramos de 20 ms = 260 ms
    }

    @Test
    fun aShortClickDoesNotTrigger() {
        val vad = VoiceActivity()
        frames(vad, -55f, 50)
        assertTrue(frames(vad, -20f, 5).none { it })
        assertTrue(frames(vad, -55f, 10).none { it })
    }

    @Test
    fun itCanTriggerAgainAfterSilence() {
        val vad = VoiceActivity()
        frames(vad, -55f, 50)
        assertTrue(frames(vad, -28f, 20).any { it })
        frames(vad, -55f, 20)
        assertTrue(frames(vad, -28f, 20).any { it })
    }

    @Test
    fun veryQuietSoundsNeverCountAsSpeech() {
        val vad = VoiceActivity()
        frames(vad, -90f, 50)
        assertFalse(frames(vad, -60f, 50).any { it }) // muy por encima del fondo, pero bajo el mínimo
    }

    @Test
    fun levelIsMeasuredInDbfs() {
        assertEquals(-100f, VoiceActivity.levelDb(ShortArray(320)), 0.01f)
        val fullScaleSine = ShortArray(320) { (sin(2 * PI * it / 32) * 32767).toInt().toShort() }
        assertEquals(-3f, VoiceActivity.levelDb(fullScaleSine), 0.2f)
        assertEquals(-100f, VoiceActivity.levelDb(ShortArray(0)), 0.01f)
    }
}
