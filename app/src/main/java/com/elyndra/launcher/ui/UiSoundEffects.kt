package com.elyndra.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import com.elyndra.launcher.sound.SoundManager
import com.elyndra.launcher.sound.UiSound
import com.elyndra.launcher.ui.masha.MashaPresence

/** El gestor de sonidos para las piezas que suenan por sí solas (interruptores). */
val LocalUiSounds = staticCompositionLocalOf<SoundManager?> { null }

/**
 * Lo que se ve de la interfaz, en lo que al sonido respecta: cuántas capas
 * hay encima (diálogo, menú, selector de arte, ficha), si el diálogo es un
 * error y lo hondo que está la pantalla.
 */
data class UiSoundState(val overlays: Int, val errorDialog: Boolean, val screen: Screen) {

    companion object {
        fun of(vm: ElyndraViewModel) = UiSoundState(
            overlays = listOfNotNull(vm.dialog, vm.sheet, vm.artPicker, vm.identify.state, vm.detailsKey).size,
            errorDialog = vm.dialog?.error == true,
            screen = vm.screen,
        )

        /** Biblioteca 0; pantallas 1; subpáginas de Ajustes 2. */
        fun depth(screen: Screen): Int = when {
            screen == Screen.Library -> 0
            screen.isSettingsPage -> 2
            else -> 1
        }

        /**
         * El sonido del paso de [prev] a [next]: abrir o cerrar una capa (o el
         * aviso si lo que se abre es un error), y si no, entrar en una
         * pantalla o volver. null = nada que sonar.
         */
        fun soundFor(prev: UiSoundState, next: UiSoundState): UiSound? = when {
            next.errorDialog && !prev.errorDialog -> UiSound.Error
            next.overlays > prev.overlays -> UiSound.Open
            next.overlays < prev.overlays -> UiSound.Close
            next.screen != prev.screen -> if (depth(next.screen) < depth(prev.screen)) UiSound.Back else UiSound.Select
            else -> null
        }
    }
}

/**
 * El enganche de sonido del estado: menús y diálogos que se abren o se cierran
 * y las pantallas que cambian suenan igual con el mando que con el dedo.
 */
@Composable
fun UiSoundEffects(vm: ElyndraViewModel) {
    LaunchedEffect(vm) {
        var last = UiSoundState.of(vm)
        snapshotFlow { UiSoundState.of(vm) }.collect { now ->
            UiSoundState.soundFor(last, now)?.let(vm.sound::play)
            last = now
        }
    }
}

/**
 * Calla los sonidos de la interfaz y baja la música mientras Masha habla o el
 * micrófono graba. Con [hold] (calibración de voz) la música se para del todo.
 */
@Composable
fun MashaQuietsSounds(vm: ElyndraViewModel, presence: MashaPresence, hold: Boolean = false) {
    LaunchedEffect(presence) {
        snapshotFlow { presence.speaking || presence.listening }.collect { busy ->
            vm.sound.setMashaBusy(busy)
            vm.music.setMashaBusy(busy)
        }
    }
    DisposableEffect(presence, hold) {
        if (hold) vm.music.setHeld(true)
        onDispose {
            vm.sound.setMashaBusy(false)
            vm.music.setMashaBusy(false)
            if (hold) vm.music.setHeld(false)
        }
    }
}
