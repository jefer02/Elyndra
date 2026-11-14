package com.elyndra.launcher.session

import com.elyndra.launcher.session.ForegroundTime.Event
import com.elyndra.launcher.session.ForegroundTime.PAUSED
import com.elyndra.launcher.session.ForegroundTime.RESUMED
import com.elyndra.launcher.session.ForegroundTime.STOPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTimeTest {

    private val menu = "emu.MainActivity"
    private val game = "emu.EmulationActivity"

    @Test fun `no events of the package means unknown`() {
        assertNull(ForegroundTime.total(emptyList(), to = 10_000))
    }

    @Test fun `a single activity counts from resumed to paused`() {
        val events = listOf(Event(RESUMED, game, 1_000), Event(PAUSED, game, 61_000), Event(STOPPED, game, 61_500))
        assertEquals(60_000L, ForegroundTime.total(events, to = 100_000))
    }

    @Test fun `the stop of the previous activity does not close the one on screen`() {
        // El emulador abre su menú y este lanza la actividad del juego: A se pausa, B se reanuda, A se detiene.
        val events = listOf(
            Event(RESUMED, menu, 0),
            Event(PAUSED, menu, 1_000),
            Event(RESUMED, game, 1_100),
            Event(STOPPED, menu, 1_600),
            Event(PAUSED, game, 7_201_100),
            Event(STOPPED, game, 7_201_500),
        )
        assertEquals(1_000L + 7_200_000L, ForegroundTime.total(events, to = 8_000_000))
    }

    @Test fun `time away from the emulator is not counted`() {
        val events = listOf(
            Event(RESUMED, game, 0),
            Event(PAUSED, game, 10_000),
            Event(RESUMED, game, 50_000),
            Event(PAUSED, game, 70_000),
        )
        assertEquals(30_000L, ForegroundTime.total(events, to = 90_000))
    }

    @Test fun `an activity still resumed counts until the end`() {
        assertEquals(40_000L, ForegroundTime.total(listOf(Event(RESUMED, game, 5_000)), to = 45_000))
    }

    @Test fun `events without a resume add nothing`() {
        assertEquals(0L, ForegroundTime.total(listOf(Event(PAUSED, game, 5_000), Event(STOPPED, game, 5_500)), to = 45_000))
    }
}
