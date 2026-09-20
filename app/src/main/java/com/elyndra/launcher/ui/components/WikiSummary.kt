package com.elyndra.launcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.elyndra.launcher.metadata.Wikipedia

/**
 * Sinopsis corta de Wikipedia para [title], pedida a demanda cuando el hero
 * enseña ese juego. Vuelve a null en cuanto cambia el título, así que el
 * texto anterior no se queda pegado un instante bajo el título nuevo
 * mientras llega la respuesta.
 */
@Composable
fun rememberWikipediaSummary(title: String?): String? {
    var summary by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(title) {
        summary = null
        val t = title?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        summary = Wikipedia.shortSummary(t)
    }
    return summary
}
