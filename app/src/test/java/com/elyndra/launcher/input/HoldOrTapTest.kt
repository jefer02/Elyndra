package com.elyndra.launcher.input

import com.elyndra.launcher.input.HoldOrTap.Gesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El micrófono con dos gestos: tocar (en vivo) o mantener (hablar). */
class HoldOrTapTest {

    @Test
    fun aQuickPressIsATap() {
        val b = HoldOrTap(holdMs = 350)
        b.press(1_000)
        assertNull(b.tick(1_200))
        assertEquals(Gesture.Tap, b.release())
        assertFalse(b.pressed)
    }

    @Test
    fun holdingStartsOnceAndReleasingEnds() {
        val b = HoldOrTap(holdMs = 350)
        b.press(1_000)
        assertEquals(Gesture.HoldStart, b.tick(1_350))
        assertNull(b.tick(1_500)) // una sola vez
        assertTrue(b.pressed)
        assertEquals(Gesture.HoldEnd, b.release())
    }

    @Test
    fun releasingWithoutALateTickIsStillATap() {
        // El aviso de los 350 ms no llegó (hilo ocupado): soltar decide, y no se queda "manteniendo".
        val b = HoldOrTap(holdMs = 350)
        b.press(0)
        assertEquals(Gesture.Tap, b.release())
        assertNull(b.tick(10_000))
    }

    @Test
    fun releaseOrTickWithoutPressDoNothing() {
        val b = HoldOrTap()
        assertNull(b.release())
        assertNull(b.tick(5_000))
    }

    @Test
    fun cancellingAHoldEndsIt() {
        val b = HoldOrTap(holdMs = 100)
        b.press(0)
        b.tick(100)
        assertEquals(Gesture.HoldEnd, b.cancel())
        assertNull(b.release())
        b.press(0)
        assertNull(b.cancel()) // sin mantener, cancelar no es nada
    }
}
