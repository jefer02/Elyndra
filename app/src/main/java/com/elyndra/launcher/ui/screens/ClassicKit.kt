package com.elyndra.launcher.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.ui.ClassicLayout
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.LocalHeroText
import com.elyndra.launcher.ui.theme.SelectionLift
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.motion

/**
 * La seleccionada sube y las vecinas encogen (ver [ClassicLayout]). Son
 * estados animados que se leen en la capa ([classicCard]): cambiar de
 * selección no recompone las cards, solo vuelve a colocar sus capas.
 *
 * Antes la subida era un `offset(y = lift)` de la columna entera (carátula y
 * nombre) leído en la composición: el nombre de la seleccionada quedaba más
 * arriba que el de las demás y cada fotograma del muelle recomponía la card.
 */
@Stable
internal class ClassicCardMotion(val scale: State<Float>, val lift: State<Dp>)

/** [neighborScale]: la de [ClassicLayout.RowStyle] (en vertical, más cerca de 1 para no abrir el aire). */
@Composable
internal fun rememberClassicCardMotion(selected: Boolean, neighborScale: Float = ClassicLayout.NEIGHBOR_SCALE): ClassicCardMotion {
    val scale = animateFloatAsState(if (selected) 1f else neighborScale, motion(Springs.snappy()), label = "classicScale")
    val lift = animateDpAsState(if (selected) -SelectionLift else 0.dp, motion(Springs.snappy()), label = "classicLift")
    return remember(scale, lift) { ClassicCardMotion(scale, lift) }
}

/** El recorte del halo de la fila para `selectionFrame`: sin él (apaisado), el de siempre. */
internal fun ClassicLayout.RowStyle.haloSpreadDp(): Dp = haloSpread?.dp ?: Dp.Unspecified

/**
 * Escala desde el pie de la carátula (su nombre, debajo, no se mueve) y la
 * subida de la seleccionada, en la capa. [press] multiplica la escala (el
 * hundimiento al pulsar).
 */
internal fun Modifier.classicCard(motion: ClassicCardMotion, press: State<Float>? = null): Modifier = graphicsLayer {
    val s = motion.scale.value * (press?.value ?: 1f)
    scaleX = s
    scaleY = s
    transformOrigin = TransformOrigin(0.5f, 1f)
    translationY = motion.lift.value.toPx()
}

/**
 * Las fichas del hero fundidas en una sola línea de texto ("PSP · SIN JUGAR
 * TODAVÍA · ZIP"), sin píldora: el tercer paso de compactación del Clásico
 * ([ClassicLayout.Step.MergedChips]). Es más baja que la fila de píldoras y
 * se sigue leyendo como la misma información.
 */
@Composable
internal fun MergedChipLine(parts: List<String>, landscape: Boolean) {
    val text = LocalHeroText.current
    ElyText(
        parts.filter { it.isNotBlank() }.joinToString(" · "),
        modifier = Modifier.padding(bottom = if (landscape) 3.dp else 6.dp),
        size = ClassicLayout.SUBLINE_SP,
        weight = FontWeight.SemiBold,
        color = text.secondary(0.9f),
        letterSpacing = tracking(0.12f),
        shadow = text.shadow(null),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        uppercase = true,
    )
}
