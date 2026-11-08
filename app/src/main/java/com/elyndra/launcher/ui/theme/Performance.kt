package com.elyndra.launcher.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.elyndra.launcher.core.device.PerformanceProfile

/**
 * El perfil de coste en vigor (ver [com.elyndra.launcher.core.device.DevicePerformance]).
 * Lo provee MainActivity junto a "reducir movimiento"; cambia muy rara vez
 * (Ajustes o el ahorro de batería), por eso es estático. De partida, Calidad:
 * las vistas previas se ven como siempre.
 */
val LocalPerformance = staticCompositionLocalOf { PerformanceProfile.QUALITY }

/** Alfa de la pista del hero cuando no respira (Rendimiento o "reducir movimiento"): el centro de su vaivén. */
const val HINT_STILL_ALPHA = 0.8f

/**
 * La pista del hero respirando entre 0,45 y 0,95 (`@keyframes pulseHint`).
 *
 * Antes era `Modifier.alpha(pulseHintAlpha())`: el valor se leía en la
 * composición y el bloque de título entero (logo, fichas, sinopsis) se
 * recomponía en cada fotograma mientras se veía la pista, es decir, siempre
 * en táctil. Ahora se lee en la capa: solo se vuelve a pintar la pista. En
 * Rendimiento (o con "reducir movimiento") se queda quieta y no pide fotogramas.
 */
@Composable
fun Modifier.pulseHint(): Modifier {
    val still = LocalReducedMotion.current || !LocalPerformance.current.ambientMotion
    if (still) return graphicsLayer { alpha = HINT_STILL_ALPHA }
    val t = rememberInfiniteTransition(label = "hint")
    val v = t.animateFloat(
        0.45f, 0.95f,
        infiniteRepeatable(tween(1300, easing = EaseInOut), RepeatMode.Reverse),
        label = "hint",
    )
    return graphicsLayer { alpha = v.value }
}
