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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
 *
 * Todo se dibuja con la forma [shape] y recortado a ella: velo, marco y filo
 * siguen el contorno exacto de la pieza. Un indicador no sabe qué forma tiene
 * lo que realza, así que las piezas que no son de 12 dp lo piden con la suya
 * ([shapeClickable]); si no, un marco de otro radio asoma como una segunda
 * caja dentro de la tarjeta.
 */
class FocusRing(
    private val accent: Color,
    private val shape: Shape = DefaultShape,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, accent, shape)

    override fun equals(other: Any?): Boolean = other is FocusRing && other.accent == accent && other.shape == shape

    override fun hashCode(): Int = 31 * accent.hashCode() + shape.hashCode()

    private class Node(
        private val interactions: InteractionSource,
        private val accent: Color,
        shape: Shape,
    ) : Modifier.Node(), DrawModifierNode {

        private var pressed = 0
        private val amount = Animatable(0f)
        private var pulse = 0f
        private var pulseJob: Job? = null
        private val outline = OutlineCache(shape)

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
            val a = amount.value
            if (pressed == 0 && a <= 0f) return
            outline.update(size, layoutDirection, this)
            if (pressed > 0) drawOutline(outline.outline, accent.copy(alpha = 0.12f))
            if (a > 0f) drawConsoleFocus(accent, a, pulse, outline)
        }
    }

    private companion object {
        /** Un radio intermedio que le sienta bien a filas y botones. */
        val DefaultShape = RoundedCornerShape(12.dp)
        const val PULSE_MS = 1_400f
    }
}

/**
 * El contorno de una forma a un tamaño, con su trazado para recortar.
 * Se rehace solo si cambia el tamaño: la respiración del foco repinta en cada
 * fotograma y no puede crear objetos.
 */
class OutlineCache(private val shape: Shape) {
    private var size = Size.Unspecified
    private var direction: LayoutDirection? = null
    lateinit var outline: Outline
        private set
    val path = Path()

    fun update(size: Size, layoutDirection: LayoutDirection, density: androidx.compose.ui.unit.Density) {
        if (size == this.size && layoutDirection == direction) return
        this.size = size
        direction = layoutDirection
        outline = shape.createOutline(size, layoutDirection, density)
        path.reset()
        path.addOutline(outline)
    }
}

/**
 * El dibujo del realce de consola, compartido por [FocusRing] (foco de
 * Compose) y [consoleFocus] (foco que lleva el propio Elyndra, como la barra
 * del hero). [amount] 0…1 es cuánto ha entrado; [pulse] 0…1, la respiración.
 *
 * Marco y filo son trazos centrados en el borde y recortados a la forma: solo
 * queda su mitad de dentro, así que siguen el contorno de la pieza en
 * cualquier forma (redondeada, píldora, círculo) sin calcular radios interiores.
 */
fun DrawScope.drawConsoleFocus(accent: Color, amount: Float, pulse: Float, shape: OutlineCache) {
    val stroke = 2.5.dp.toPx()
    // Velo de acento.
    drawOutline(shape.outline, accent.copy(alpha = (0.14f + 0.06f * pulse) * amount))
    clipPath(shape.path) {
        // Filo interior claro: lo que hace que se lea "seleccionado" como en una
        // consola. Va primero y más ancho; el marco lo tapa salvo 1 dp por dentro.
        drawOutline(shape.outline, Color.White.copy(alpha = (0.35f + 0.25f * pulse) * amount), style = Stroke((stroke + 1.dp.toPx()) * 2f))
        // Marco de acento.
        drawOutline(shape.outline, accent.copy(alpha = (0.85f + 0.15f * pulse) * amount), style = Stroke(stroke * 2f))
    }
}

/**
 * El mismo realce para lo que no usa el foco de Compose: la barra superior
 * del hero, que se recorre con el mando a través del [InputController].
 * Con [focused] en false no dibuja ni anima nada.
 */
@Composable
fun Modifier.consoleFocus(focused: Boolean, cornerRadius: Dp = 12.dp): Modifier =
    consoleFocus(focused, RoundedCornerShape(cornerRadius))

/** [consoleFocus] con una forma cualquiera (la de la pieza que se señala); [color] = acento propio. */
@Composable
fun Modifier.consoleFocus(focused: Boolean, shape: Shape, color: Color? = null): Modifier {
    val accent = color ?: LocalSkin.current.a2
    val amount by animateFloatAsState(if (focused) 1f else 0f, tween(if (focused) 140 else 110), label = "padFocus")
    if (amount <= 0f) return this
    // La respiración se lee al dibujar, no al componer: solo se repinta el botón.
    val transition = rememberInfiniteTransition(label = "padFocusPulse")
    val pulse = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val cache = remember(shape) { OutlineCache(shape) }
    return this.drawWithCache {
        cache.update(size, layoutDirection, this)
        onDrawWithContent {
            drawContent()
            drawConsoleFocus(accent, amount, if (focused) pulse.value else 0f, cache)
        }
    }
}

/** El realce con el acento del tema; se cachea para no recrearlo en cada recomposición. */
fun focusRingFor(skin: ElyndraSkin): Indication = FocusRing(skin.a2)

/** El realce con la forma de una pieza concreta; [color] = acento propio (si no, el del tema). */
@Composable
fun focusRing(shape: Shape, color: Color? = null): Indication {
    val accent = color ?: LocalSkin.current.a2
    return remember(accent, shape) { FocusRing(accent, shape) }
}

/**
 * `clickable` para una pieza con forma propia: recorta a [shape] y el realce
 * (foco y pulsación) sigue exactamente esa forma. Va **después** del fondo y
 * del borde de la pieza en la cadena de modificadores, y antes del padding.
 */
@Composable
fun Modifier.shapeClickable(
    shape: Shape,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    color: Color? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = this
    .clip(shape)
    .clickable(
        interactionSource = interactionSource,
        indication = focusRing(shape, color),
        enabled = enabled,
        onClickLabel = onClickLabel,
        onClick = onClick,
    )
