package com.elyndra.launcher.ui

/**
 * Qué cierra "atrás" (B del mando, el gesto o la tecla del sistema), en un
 * solo orden para toda la app: lo de más arriba primero.
 *
 * Teclado → intro → diálogo → menú → selector de arte → editar nombre →
 * ficha → menú de orden → (en Ajustes) fuente cogida → página de Ajustes → pantalla →
 * buscador. Lógica pura: la usan [ElyndraViewModel.back] y el
 * [InputController], y se prueba en la JVM.
 */
object BackPriority {

    enum class Target {
        Keyboard, Intro, Dialog, Sheet, ArtPicker, Identify, Details, SortMenu,
        PriorityGrab, SettingsCategory, SettingsPage, Screen, Search, None,
    }

    data class State(
        val keyboard: Boolean = false,
        val intro: Boolean = false,
        val dialog: Boolean = false,
        val sheet: Boolean = false,
        val artPicker: Boolean = false,
        val identify: Boolean = false,
        val details: Boolean = false,
        /** El menú de orden de la biblioteca. */
        val sortMenu: Boolean = false,
        val screen: Screen = Screen.Library,
        val priorityGrab: Boolean = false,
        /** Ajustes en ventana estrecha con una categoría abierta. */
        val settingsPageOpen: Boolean = false,
        val searchOpen: Boolean = false,
    )

    fun next(s: State): Target = when {
        s.keyboard -> Target.Keyboard
        s.intro -> Target.Intro
        s.dialog -> Target.Dialog
        s.sheet -> Target.Sheet
        s.artPicker -> Target.ArtPicker
        s.identify -> Target.Identify
        s.details -> Target.Details
        s.sortMenu -> Target.SortMenu
        s.screen == Screen.Settings && s.priorityGrab -> Target.PriorityGrab
        s.screen == Screen.Settings && s.settingsPageOpen -> Target.SettingsCategory
        s.screen.isSettingsPage -> Target.SettingsPage
        s.screen != Screen.Library -> Target.Screen
        s.searchOpen -> Target.Search
        else -> Target.None
    }
}
