package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.BackPriority.State
import com.elyndra.launcher.ui.BackPriority.Target
import org.junit.Assert.assertEquals
import org.junit.Test

class BackPriorityTest {

    private fun next(s: State) = BackPriority.next(s)

    @Test
    fun `el teclado se cierra antes que cualquier capa`() {
        val all = State(keyboard = true, intro = true, dialog = true, sheet = true, identify = true, details = true, screen = Screen.Settings)
        assertEquals(Target.Keyboard, next(all))
    }

    @Test
    fun `orden de las capas de arriba abajo`() {
        var s = State(intro = true, dialog = true, sheet = true, artPicker = true, identify = true, details = true, screen = Screen.Add, searchOpen = true)
        assertEquals(Target.Intro, next(s)); s = s.copy(intro = false)
        assertEquals(Target.Dialog, next(s)); s = s.copy(dialog = false)
        assertEquals(Target.Sheet, next(s)); s = s.copy(sheet = false)
        assertEquals(Target.ArtPicker, next(s)); s = s.copy(artPicker = false)
        assertEquals(Target.Identify, next(s)); s = s.copy(identify = false)
        assertEquals(Target.Details, next(s)); s = s.copy(details = false)
        assertEquals(Target.Screen, next(s)); s = s.copy(screen = Screen.Library)
        assertEquals(Target.Search, next(s)); s = s.copy(searchOpen = false)
        assertEquals(Target.None, next(s))
    }

    @Test
    fun `editar nombre encima de la ficha se cierra primero`() {
        assertEquals(Target.Identify, next(State(identify = true, details = true)))
    }

    @Test
    fun `en Ajustes suelta la fuente cogida y luego vuelve a la lista`() {
        assertEquals(Target.PriorityGrab, next(State(screen = Screen.Settings, priorityGrab = true, settingsPageOpen = true)))
        assertEquals(Target.SettingsCategory, next(State(screen = Screen.Settings, settingsPageOpen = true)))
        assertEquals(Target.Screen, next(State(screen = Screen.Settings)))
        // Fuera de Ajustes, una fuente cogida no cuenta.
        assertEquals(Target.Screen, next(State(screen = Screen.Add, priorityGrab = true)))
    }

    @Test
    fun `licencias y calibración vuelven a Ajustes`() {
        assertEquals(Target.SettingsPage, next(State(screen = Screen.Licenses)))
        assertEquals(Target.SettingsPage, next(State(screen = Screen.VoiceSync)))
    }
}
