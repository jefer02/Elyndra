package com.elyndra.launcher.ui.meridian

import androidx.compose.runtime.Composable

/** En release no se informa del cristal (el informe de depuración está en src/debug). */
object GlassDebug {

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun Report(spec: GlassPanelLayout.Spec, lite: Boolean) = Unit
}
