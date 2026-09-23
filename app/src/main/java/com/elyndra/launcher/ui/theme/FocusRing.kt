package com.elyndra.launcher.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * El realce de todo lo que se puede pulsar en Elyndra.
 *
 * Es el indicador que reciben **todos** los `clickable` de la app (se instala
 * en `LocalIndication`, ver `ElyndraTheme`), y existe por el mando: con el dedo
 * se ve lo que se toca, pero moviéndose con la cruceta por Ajustes, Añadir,
 * los diálogos o el buscador no habría forma de saber sobre qué está el foco.
 * Con esto, cualquier botón de cualquier pantalla se señala solo, sin tocar
 * sus composables.
 *
 * Estilo consola: al recibir el foco el realce entra con un fundido corto,
 * con un velo de acento, un marco de acento y un filo interior claro, y
 * mientras dura "respira" muy despacio. Solo anima el nodo que tiene el
 * foco, así que no cuesta nada en el resto de la pantalla. Al pulsar, un velo.
 */
class FocusRing(private val accent: Color) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, accent)

    override fun equals(other: Any?): Boolean = other is FocusRing && other.accent == accent

    override fun hashCode(): Int = accent.hashCode()

    private class Node(
        private val interactions: InteractionSource,
        private val accent: Color,
    ) : Modifier.Node(), DrawModifierNode {

        private var pressed = 0
        private val amount = Animatable(0f)
        private var pulse = 0f
        private var pulseJob: Job? = null

        override fun onAttach() {
            coroutineScope.launch {
                interactions.interactions.collect { interaction ->
                    when (interaction) {
                        is FocusInteraction.Focus -> focus(true)
                        is FocusInteraction.Unfocus -> focus(false)
                        is PressInteraction.Press -> pressed++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                    }
                    invalidateDraw()
                }
            }
        }

        private fun focus(on: Boolean) {
            coroutineScope.launch {
                amount.animateTo(if (on) 1f else 0f, tween(if (on) 140 else 110)) { invalidateDraw() }
            }
            pulseJob?.cancel()
            pulse = 0f
            if (on) {
                // La respiración del realce: un seno lento, solo mientras hay foco.
                pulseJob = coroutineScope.launch {
                    val start = System.nanoTime()
                    while (true) {
                        androidx.compose.runtime.withFrameNanos { now ->
                            pulse = ((now - start) / 1_000_000f / PULSE_MS * 2f * PI.toFloat()).let { (sin(it) + 1f) / 2f }
                        }
                        invalidateDraw()
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed > 0) drawRect(accent.copy(alpha = 0.12f))
            val a = amount.value
            if (a <= 0f) return
            drawConsoleFocus(accent, a, pulse, RADIUS.toPx())
        }
    }

    private companion object {
        /** Un radio intermedio que le sienta bien a filas y botones. */
        val RADIUS = 12.dp
        const val PULSE_MS = 1_400f
    }
}

/**
 * El dibujo del realce de consola, compartido por [FocusRing] (foco de
 * Compose) y [consoleFocus] (foco que lleva el propio Elyndra, como la barra
 * del hero). [amount] 0…1 es cuánto ha entrado; [pulse] 0…1, la respiración.
 */
fun DrawScope.drawConsoleFocus(accent: Color, amount: Float, pulse: Float, radiusPx: Float) {
    val stroke = 2.5.dp.toPx()
    val inset = stroke / 2f
    val radius = CornerRadius(radiusPx)
    val rectSize = Size(size.width - stroke, size.height - stroke)
    // Velo de acento.
    drawRoundRect(accent.copy(alpha = (0.14f + 0.06f * pulse) * amount), cornerRadius = radius)
    // Marco de acento.
    drawRoundRect(
        accent.copy(alpha = (0.85f + 0.15f * pulse) * amount),
        topLeft = Offset(inset, inset),
        size = rectSize,
        cornerRadius = radius,
        style = Stroke(stroke),
    )
    // Filo interior claro: lo que hace que se lea "seleccionado" como en una consola.
    val inner = stroke + 1.dp.toPx()
    drawRoundRect(
        Color.White.copy(alpha = (0.35f + 0.25f * pulse) * amount),
        topLeft = Offset(inner, inner),
        size = Size(size.width - inner * 2, size.height - inner * 2),
        cornerRadius = CornerRadius((radiusPx - inner).coerceAtLeast(0f)),
        style = Stroke(1.dp.toPx()),
    )
}

/**
 * El mismo realce para lo que no usa el foco de Compose: la barra superior
 * del hero, que se recorre con el mando a través del [InputController].
 * Con [focused] en false no dibuja ni anima nada.
 */
@Composable
fun Modifier.consoleFocus(focused: Boolean, cornerRadius: Dp = 12.dp): Modifier {
    val accent = LocalSkin.current.a2
    val amount by animateFloatAsState(if (focused) 1f else 0f, tween(if (focused) 140 else 110), label = "padFocus")
    if (amount <= 0f) return this
    // La respiración se lee al dibujar, no al componer: solo se repinta el botón.
    val transition = rememberInfiniteTransition(label = "padFocusPulse")
    val pulse = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    return this.drawWithContent {
        drawContent()
        drawConsoleFocus(accent, amount, if (focused) pulse.value else 0f, cornerRadius.toPx())
    }
}

/** El realce con el acento del tema; se cachea para no recrearlo en cada recomposición. */
fun focusRingFor(skin: ElyndraSkin): Indication = FocusRing(skin.a2)
