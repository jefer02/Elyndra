package com.elyndra.launcher.ui.masha.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** El VAD de repuesto por energía (sin Silero): el fondo no es voz, algo claro por encima sí. */
class VoiceActivityTest {

    @Test
    fun steadyBackgroundIsNotSpeech() {
        val vad = VoiceActivity()
        assertTrue((1..200).map { vad.prob(-55f) }.all { it < 0.1f })
    }

    @Test
    fun aClearVoiceAboveTheFloorIs() {
        val vad = VoiceActivity()
        repeat(100) { vad.prob(-58f) }
        assertTrue(vad.prob(-28f) > 0.9f)
    }

    @Test
    fun theFloorFollowsTheRoomDownQuicklyAndUpSlowly() {
        val vad = VoiceActivity()
        repeat(100) { vad.prob(-40f) }
        repeat(20) { vad.prob(-70f) }
        // Bajó deprisa: −35 ya destaca (con el suelo aún en −40 no lo haría).
        assertTrue(vad.prob(-35f) > 0.5f)
    }

    @Test
    fun veryQuietSoundsNeverCountAsSpeech() {
        val vad = VoiceActivity()
        repeat(50) { vad.prob(-95f) }
        assertEquals(0f, vad.prob(-60f))
    }

    @Test
    fun levelIsMeasuredInDbfs() {
        assertEquals(-100f, VoiceActivity.levelDb(ShortArray(320)), 0.01f)
        val fullScaleSine = ShortArray(320) { (sin(2 * PI * it / 32) * 32767).toInt().toShort() }
        assertEquals(-3f, VoiceActivity.levelDb(fullScaleSine), 0.2f)
        assertEquals(-100f, VoiceActivity.levelDb(ShortArray(0)), 0.01f)
    }
}
