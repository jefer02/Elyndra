package com.elyndra.launcher.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * ¿La está moviendo el usuario? Desde que arrastra la lista hasta que se
 * para del todo (también mientras se desliza tras soltarla). Las animaciones
 * del programa (ir a la seleccionada) no cuentan: un segundo toque rápido
 * mientras la lista va a su sitio sí abre. Se lee sin recomponer.
 */
@Composable
fun rememberUserScrolling(list: LazyListState): () -> Boolean {
    val flag = remember(list) { BooleanArray(1) }
    LaunchedEffect(list) {
        launch { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) flag[0] = true } }
        snapshotFlow { list.isScrollInProgress }.collect { if (!it) flag[0] = false }
    }
    return remember(list) { { flag[0] } }
}

/** Dónde está un elemento en la ventana: los toques se comparan ahí (la lista puede moverse entre uno y otro). */
class TapAnchor {
    var coordinates: LayoutCoordinates? = null

    fun toWindow(local: Offset): Offset = coordinates?.takeIf { it.isAttached }?.localToWindow(local) ?: local
}

/** Distancia máxima (px) entre los dos toques de un doble toque. */
@Composable
fun rememberTapSlop(): Float = with(LocalDensity.current) { 24.dp.toPx() }
