package com.elyndra.launcher.ui.intro

import com.elyndra.launcher.ui.IntroController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroGateTest {

    @Test
    fun playsOnlyOnTheFirstClaimOfTheProcess() {
        val gate = IntroGate()
        assertTrue(gate.claim(enabled = true, restored = false))
        // Giro, recreación de la Activity, volver del segundo plano.
        assertFalse(gate.claim(enabled = true, restored = true))
        assertFalse(gate.claim(enabled = true, restored = false))
    }

    @Test
    fun disabledIntroIsNeverShownAndEnablingLaterDoesNotReplayIt() {
        val gate = IntroGate()
        assertFalse(gate.claim(enabled = false, restored = false))
        assertFalse(gate.claim(enabled = true, restored = false))
    }

    @Test
    fun aRestoredActivityAfterProcessDeathDoesNotPlayIt() {
        val gate = IntroGate()
        assertFalse(gate.claim(enabled = true, restored = true))
        assertFalse(gate.claim(enabled = true, restored = false))
    }

    @Test
    fun skipIsIgnoredDuringTheFirstPointEightSeconds() {
        val intro = IntroController()
        intro.start()
        intro.startNanos = 1_000_000_000L
        intro.skip(nowNanos = intro.startNanos + 500_000_000L)
        assertEquals(IntroTimeline.NO_SKIP, intro.skipAt, 0f)
        intro.skip(nowNanos = intro.startNanos + 900_000_000L)
        assertEquals(900f, intro.skipAt, 0.01f)
        // El primero manda.
        intro.skip(nowNanos = intro.startNanos + 1_500_000_000L)
        assertEquals(900f, intro.skipAt, 0.01f)
    }

    @Test
    fun previewReplaysFromTheStart() {
        val intro = IntroController()
        intro.start()
        intro.startNanos = 5L
        intro.finish()
        assertFalse(intro.visible)
        val run = intro.run
        intro.preview()
        assertTrue(intro.visible)
        assertEquals(0L, intro.startNanos)
        assertEquals(run + 1, intro.run)
        assertEquals(IntroTimeline.NO_SKIP, intro.skipAt, 0f)
    }
}
