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
    fun theTiltedAxisWinsOverTheOnesAtRest() {
        // Cruceta por el "hat" con los dos sticks quietos.
        assertEquals(-1f to 0f, Gamepad.axisDirection(-1f, 0f, 0f, 0f, 0f, 0f))
        // Stick derecho de un mando de PlayStation (Z/RZ), el izquierdo quieto.
        assertEquals(0f to 0.9f, Gamepad.axisDirection(0f, 0f, 0f, 0f, 0f, 0.9f))
        // Un stick gastado que no descansa del todo no tapa al que se mueve.
        assertEquals(0.8f to 0f, Gamepad.axisDirection(0f, 0f, 0.05f, 0f, 0.8f, 0f))
    }

    @Test
    fun theStickFiresOnceAndLuegoRepeats() {
        val stick = StickRepeater(firstDelayMs = 300, repeatMs = 100)
        // Al inclinar, una pulsación.
        assertEquals(Pad.Right, stick.update(1f, 0f, 0))
        // Mientras dura la espera inicial, ninguna más.
        assertNull(stick.update(1f, 0f, 100))
        assertNull(stick.update(1f, 0f, 299))
        // Pasada la espera, repite a su ritmo.
        assertEquals(Pad.Right, stick.update(1f, 0f, 300))
        assertNull(stick.update(1f, 0f, 350))
        assertEquals(Pad.Right, stick.update(1f, 0f, 400))
    }

    @Test
    fun aTremblingStickIsNotAPress() {
        val stick = StickRepeater()
        assertEquals(Pad.Right, stick.update(1f, 0f, 0))
        // Vuelve a medias: sigue contando como inclinado, no dispara de nuevo.
        assertNull(stick.update(StickRepeater.RELEASE + 0.05f, 0f, 10))
        assertNull(stick.update(1f, 0f, 20))
        // Soltado del todo, la siguiente inclinación sí es una pulsación nueva.
        assertNull(stick.update(0f, 0f, 30))
        assertEquals(Pad.Right, stick.update(1f, 0f, 40))
    }

    @Test
    fun aDiagonalOnlyMovesOneWay() {
        val stick = StickRepeater()
        // Empujado en diagonal manda el eje dominante: no se mueve en cruz.
        assertEquals(Pad.Down, stick.update(0.6f, 0.9f, 0))
        stick.reset()
        assertEquals(Pad.Left, stick.update(-0.9f, 0.6f, 100))
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
