package com.elyndra.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.elyndra.launcher.core.device.JankMonitor

/**
 * Le dice a [JankMonitor] si lo que se ve ahora cuenta para medir tirones:
 * Biblioteca o Carpeta, sin la intro encima y sin una descarga de metadatos en
 * marcha (la biblioteca se recompone con cada juego que llega). Masha, Ajustes
 * y el resto no cuentan.
 *
 * Es una función aparte a propósito: lee la pantalla, la intro y el progreso
 * de metadatos (que cambia a menudo durante una descarga) y solo ella se
 * recompone con ellos.
 */
@Composable
fun JankEligibility(vm: ElyndraViewModel) {
    val eligible = (vm.screen == Screen.Library || vm.screen == Screen.Folder) &&
        !vm.intro.visible &&
        !vm.metaProgress.running
    SideEffect { JankMonitor.eligible = eligible }
}
