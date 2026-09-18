package com.elyndra.launcher.ui.theme

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * El realce de todo lo que se puede pulsar en Elyndra.
 *
 * Es el indicador que reciben **todos** los `clickable` de la app (se instala
 * en `LocalIndication`, ver `ElyndraApp`), y existe por el mando: con el dedo
 * se ve lo que se toca, pero moviéndose con la cruceta por Ajustes o por
 * Añadir no habría forma de saber sobre qué está el foco. Con esto, cualquier
 * botón de cualquier pantalla se señala solo, sin tocar sus composables.
 *
 * Dibuja un aro de acento al tener el foco y un velo tenue al pulsar. No usa
 * ondulación: el resto de Elyndra tampoco, y una ondulación de Material aquí
 * cantaría.
 */
class FocusRing(private val accent: Color) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, accent)

    override fun equals(other: Any?): Boolean = other is FocusRing && other.accent == accent

    override fun hashCode(): Int = accent.hashCode()

    private class Node(
        private val interactions: InteractionSource,
        private val accent: Color,
    ) : Modifier.Node(), DrawModifierNode {

        private var focused = false
        private var pressed = 0

        override fun onAttach() {
            coroutineScope.launch {
                interactions.interactions.collect { interaction ->
                    when (interaction) {
                        is FocusInteraction.Focus -> focused = true
                        is FocusInteraction.Unfocus -> focused = false
                        is PressInteraction.Press -> pressed++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed--
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed > 0) drawRect(accent.copy(alpha = 0.12f))
            if (!focused) return
            val radius = CornerRadius(RADIUS_PX * density)
            drawRoundRect(accent.copy(alpha = 0.14f), cornerRadius = radius)
            drawRoundRect(accent, cornerRadius = radius, style = Stroke(STROKE_PX * density))
        }

        companion object {
            /** En dp: un radio intermedio que le sienta bien a filas y botones. */
            const val RADIUS_PX = 12f
            const val STROKE_PX = 2f
        }
    }
}

/** El realce con el acento del tema; se cachea para no recrearlo en cada recomposición. */
fun focusRingFor(skin: ElyndraSkin): Indication = FocusRing(skin.a2)
