package com.elyndra.launcher.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.theme.focusRing
import androidx.compose.animation.core.animateFloatAsState
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.motion

/* ─────────────────────────────────────────────────────────────
   La muestra de color de Ajustes, una sola para todos los selectores
   (acento, tinte del cristal, color de la intro, partículas de la
   selección y de Masha).

   Una ficha "squircle" de 40 dp dentro de una zona táctil de 50 dp: la
   elegida lleva un aro de tinta separado por un hilo de aire y un punto
   blanco en el centro (se ve en los dos temas y sobre cualquier color);
   con mando, el aro de foco de siempre rodea la zona táctil.
   ───────────────────────────────────────────────────────────── */

/** Forma de las muestras: un cuadrado de esquinas muy redondas. */
val SwatchShape = RoundedCornerShape(percent = 32)

/**
 * Una muestra de color.
 *
 * [label] es lo que lee el lector de pantalla (el nombre del color);
 * [paint] pinta el relleno dentro de la forma (degradados, brillos…);
 * [content] va encima, centrado (el "+" del color personalizado).
 */
@Composable
fun ColorSwatch(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable BoxScope.() -> Unit)? = null,
    paint: DrawScope.() -> Unit,
) {
    val slotShape = RoundedCornerShape(percent = 30)
    val interaction = remember { MutableInteractionSource() }
    val ring by animateFloatAsState(if (selected) 1f else 0f, motion(Springs.snappy()), label = "swatchRing")
    Box(
        modifier
            .size(SwatchLayout.SLOT_DP.dp)
            .semantics { contentDescription = label }
            .clip(slotShape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = focusRing(slotShape),
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // El aro de la elegida: tinta, separado de la ficha por un hilo de aire.
        // Entra con un muelle corto (`motion` lo deja en seco sin animaciones).
        if (ring > 0.01f) {
            Box(
                Modifier
                    .size((SwatchLayout.VISUAL_DP + 8f).dp)
                    .border(2.dp, P.ink.copy(alpha = ring * 0.9f), RoundedCornerShape(percent = 34)),
            )
        }
        Box(
            Modifier
                .size(SwatchLayout.VISUAL_DP.dp)
                .shadow(if (selected) 5.dp else 2.dp, SwatchShape, clip = false, ambientColor = P.shade.copy(alpha = 0.18f), spotColor = P.shade.copy(alpha = 0.18f))
                .clip(SwatchShape)
                .drawBehind(paint)
                .border(1.dp, P.ink.copy(alpha = 0.12f), SwatchShape),
            contentAlignment = Alignment.Center,
        ) {
            content?.invoke(this)
            if (selected && content == null) {
                Box(
                    Modifier
                        .size(9.dp)
                        .shadow(2.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                        .drawBehind { drawCircle(Color.White) },
                )
            }
        }
    }
}

/** Atajo: una muestra con un degradado. */
@Composable
fun ColorSwatch(
    label: String,
    brush: Brush,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = ColorSwatch(label, selected, onClick, modifier) { drawRect(brush) }

/**
 * Las muestras de un selector, en una fila que se parte en filas parejas
 * ([SwatchLayout]): las diez de la intro caben en una línea en el panel
 * apaisado; en un móvil van 5 + 5.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SwatchRow(
    count: Int,
    modifier: Modifier = Modifier,
    item: @Composable (index: Int) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val perRow = SwatchLayout.perRow(maxWidth.value, count).coerceAtLeast(1)
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(SwatchLayout.GAP_DP.dp),
            verticalArrangement = Arrangement.spacedBy(SwatchLayout.GAP_DP.dp),
            maxItemsInEachRow = perRow,
        ) {
            repeat(count) { item(it) }
        }
    }
}
