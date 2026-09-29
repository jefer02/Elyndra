package com.elyndra.launcher.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/* ─────────────────────────────────────────────────────────────
   Flotación de los logos de juego.

   Un vaivén vertical muy discreto (±2,5 dp, ~3,6 s por ciclo) que
   solo llevan los logos e iconos de *juegos* — nunca los iconos de
   la interfaz (buscar, Ajustes, Abrir…).

   Rendimiento, pensado para carruseles con muchos logos:
    - Un único reloj por pantalla ([rememberFloatClock]) en vez de
      una InfiniteTransition por logo.
    - El reloj se lee dentro de `graphicsLayer`, es decir, en la
      fase de capa: moverse no recompone nada, solo recoloca la
      capa ya dibujada.
    - Las listas perezosas solo componen lo visible, así que solo
      flota lo que está en pantalla.
    - Cada logo lleva su propia fase (derivada de su clave), para
      que no suban y bajen todos a la vez.
    - Con "reducir movimiento" el reloj no arranca.
   ───────────────────────────────────────────────────────────── */

@Stable
class FloatClock {
    /** Segundos desde que arrancó el reloj. */
    var seconds by mutableFloatStateOf(0f)
        internal set
}

@Composable
fun rememberFloatClock(): FloatClock {
    val clock = remember { FloatClock() }
    val reduced = LocalReducedMotion.current
    if (!reduced) {
        LaunchedEffect(clock) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { now -> clock.seconds = (now - start) / 1_000_000_000f }
        }
    }
    return clock
}

/** Fase estable 0…1 a partir de una clave: cada logo flota a su aire. */
fun floatPhaseOf(key: Any?): Float = ((key.hashCode() * 0x9E3779B1.toInt()) ushr 16) / 65_535f

/**
 * Vaivén vertical suave. [phase] 0…1 desfasa este elemento respecto al resto.
 */
fun Modifier.floating(
    clock: FloatClock,
    phase: Float,
    amplitude: Dp = 2.5.dp,
    periodSeconds: Float = 3.6f,
): Modifier = graphicsLayer {
    val turns = clock.seconds / periodSeconds + phase
    translationY = sin(turns * 2f * PI.toFloat()) * amplitude.toPx()
}
