package com.elyndra.launcher.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadTest {

    @Test
    fun everyControllerSpeaksTheSameLanguage() {
        // El botón de abajo del rombo: A en Xbox, ✕ en PlayStation.
        assertEquals(Pad.Confirm, Gamepad.actionFor(KeyEvent.KEYCODE_BUTTON_A))
        // El de la derecha: B en Xbox, ◯ en PlayStation.
        assertEquals(Pad.Back, Gamepad.actionFor(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(Pad.Details, Gamepad.actionFor(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(Pad.Options, Gamepad.actionFor(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(Pad.Menu, Gamepad.actionFor(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(Pad.Search, Gamepad.actionFor(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(Pad.AppMenu, Gamepad.actionFor(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals(Pad.PagePrev, Gamepad.actionFor(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(Pad.PageNext, Gamepad.actionFor(KeyEvent.KEYCODE_BUTTON_R1))
        // Mando clónico sin perfil: Android le numera los botones.
        assertEquals(Pad.Confirm, Gamepad.actionFor(KeyEvent.KEYCODE_BUTTON_1))
        assertEquals(Pad.Back, Gamepad.actionFor(KeyEvent.KEYCODE_BUTTON_2))
        // Cruceta, teclado y mando a distancia de una tele.
        assertEquals(Pad.Up, Gamepad.actionFor(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(Pad.Confirm, Gamepad.actionFor(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(Pad.Confirm, Gamepad.actionFor(KeyEvent.KEYCODE_ENTER))
        assertEquals(Pad.Back, Gamepad.actionFor(KeyEvent.KEYCODE_BACK))
        // Lo que no es de mando se deja pasar tal cual.
        assertNull(Gamepad.actionFor(KeyEvent.KEYCODE_A))
        assertNull(Gamepad.actionFor(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun buttonsAreTranslatedForTheFocusOfTheFormScreens() {
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, Gamepad.systemKey(KeyEvent.KEYCODE_BUTTON_A))
        // La cruceta ya es la tecla que entiende el foco: no hay que traducirla.
        assertNull(Gamepad.systemKey(KeyEvent.KEYCODE_DPAD_UP))
        // Y lo que no mueve el foco (Start, Select, L1…) tampoco se reenvía.
        assertNull(Gamepad.systemKey(KeyEvent.KEYCODE_BUTTON_START))
        assertNull(Gamepad.systemKey(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun theStickNeedsHalfItsTravelAndHasHysteresis() {
        val gate = AxisGate()
        // Por debajo de la zona muerta (~0.5) no hay dirección.
        assertNull(gate.update(0.45f, 0f))
        assertEquals(Pad.Right, gate.update(0.6f, 0f))
        // Vuelve a medias: sigue contando como la misma inclinación.
        assertEquals(Pad.Right, gate.update(AxisGate.RELEASE + 0.05f, 0f))
        // Soltado del todo.
        assertNull(gate.update(0.1f, 0f))
    }

    @Test
    fun aDiagonalOnlyMovesOneWay() {
        val gate = AxisGate()
        // Empujado en diagonal manda el eje dominante: no se mueve en cruz.
        assertEquals(Pad.Down, gate.update(0.6f, 0.9f))
        gate.reset()
        assertEquals(Pad.Left, gate.update(-0.9f, 0.6f))
    }

    /** Temporizador de mentira: el tiempo avanza cuando la prueba lo dice. */
    private class FakeTicker : DirectionalRepeater.Ticker {
        var now = 0L
        private val pending = mutableListOf<Pair<Long, Runnable>>()
        override fun postDelayed(block: Runnable, delayMs: Long) {
            pending += (now + delayMs) to block
        }
        override fun removeCallbacks(block: Runnable) {
            pending.removeAll { it.second === block }
        }
        fun advanceTo(t: Long) {
            while (true) {
                val next = pending.filter { it.first <= t }.minByOrNull { it.first } ?: break
                pending.remove(next)
                now = next.first
                next.second.run()
            }
            now = t
        }
    }

    @Test
    fun oneStepPerPushThenControlledRepeat() {
        val ticker = FakeTicker()
        val fired = mutableListOf<Long>()
        val repeater = DirectionalRepeater({ fired += ticker.now }, firstDelayMs = 350, repeatMs = 150, handler = ticker)
        repeater.press(DirectionalRepeater.Channel.Stick, Pad.Right)
        // Un paso al instante…
        assertEquals(listOf(0L), fired)
        // …ninguno más durante la espera inicial…
        ticker.advanceTo(349)
        assertEquals(1, fired.size)
        // …y a partir de ahí, uno cada 150 ms.
        ticker.advanceTo(350 + 150 * 2)
        assertEquals(listOf(0L, 350L, 500L, 650L), fired)
        repeater.release(DirectionalRepeater.Channel.Stick)
        ticker.advanceTo(2_000)
        assertEquals(4, fired.size)
    }

    @Test
    fun theSamePushFromTwoSourcesCountsOnce() {
        val ticker = FakeTicker()
        var count = 0
        val repeater = DirectionalRepeater({ count++ }, handler = ticker)
        // Hay mandos que mandan la cruceta como tecla y como "hat" a la vez.
        repeater.press(DirectionalRepeater.Channel.Keys, Pad.Down)
        repeater.press(DirectionalRepeater.Channel.Hat, Pad.Down)
        assertEquals(1, count)
        repeater.release(DirectionalRepeater.Channel.Keys, Pad.Down)
        repeater.release(DirectionalRepeater.Channel.Hat)
        repeater.press(DirectionalRepeater.Channel.Keys, Pad.Down)
        assertEquals(2, count)
    }

    @Test
    fun onlyRealControllersGetAxisTreatment() {
        val gamepad = 0x00000401
        val joystick = 0x01000010
        val touchscreen = 0x00001002
        assertTrue(Gamepad.isGamepadSource(gamepad))
        assertTrue(Gamepad.isGamepadSource(joystick))
        assertTrue(Gamepad.isGamepadSource(gamepad or joystick))
        assertTrue(!Gamepad.isGamepadSource(touchscreen))
    }
}
