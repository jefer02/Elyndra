package com.elyndra.launcher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Swift
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.drawArcSpinner
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.spinAngle
import kotlin.math.roundToInt

/**
 * `pill(active)` — la píldora de filtros, sistemas, emuladores e idiomas.
 * Activa: degradado de acento, texto blanco y sombra. Inactiva: cristal claro con borde tenue.
 */
@Composable
fun Pill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp? = null,
    fontSize: Float = 11f,
    horizontalPadding: Dp = 13.dp,
    verticalPadding: Dp = 7.dp,
    cornerRadius: Dp = 12.dp,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(cornerRadius)
    var m = modifier
        .then(if (height != null) Modifier.height(height) else Modifier)
    if (active) {
        m = m
            .shadow(8.dp, shape, clip = false, ambientColor = P.shade.copy(alpha = 0.22f), spotColor = P.shade.copy(alpha = 0.22f))
            .clip(shape)
            .drawBehind { drawRect(accentGradient(skin, 145f, size)) }
    } else {
        m = m
            .clip(shape)
            .background(P.chip)
            .border(1.dp, P.ink.copy(alpha = 0.14f), shape)
    }
    Box(
        m.clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = if (height != null) 0.dp else verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        ElyText(
            label,
            size = fontSize,
            weight = FontWeight.Medium,
            color = if (active) Color.White else P.ink2,
            maxLines = 1,
        )
    }
}

/** `panel` — la tarjeta de cristal con 14dp de aire y 18dp de radio. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    cornerRadius: Dp = 18.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(cornerRadius)).padding(padding),
        content = content,
    )
}

/** `cta()` — el botón grande de 50dp con el degradado de acento. */
@Composable
fun CtaButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(17.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .then(
                if (enabled) {
                    Modifier.shadow(14.dp, shape, clip = false, ambientColor = P.shade.copy(alpha = 0.24f), spotColor = P.shade.copy(alpha = 0.24f))
                } else Modifier,
            )
            .clip(shape)
            .drawBehind { drawRect(accentGradient(skin, 145f, size)) }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ElyText(label, size = 13.5f, weight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
    }
}

/** Botón de acción principal del dock (`playBtn`): 42dp de alto, degradado y sombra. */
@Composable
fun AccentButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Float = 12.5f,
    cornerRadius: Dp = 15.dp,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier
            .height(42.dp)
            .shadow(12.dp, shape, clip = false, ambientColor = P.shade.copy(alpha = 0.24f), spotColor = P.shade.copy(alpha = 0.24f))
            .clip(shape)
            .drawBehind { drawRect(accentGradient(skin, 145f, size)) }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        ElyText(label, size = fontSize, weight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
    }
}

/** `iconBtn` — botón cuadrado de cristal (38dp) usado en las cabeceras de las hojas. */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    cornerRadius: Dp = 13.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .glass(RoundedCornerShape(cornerRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/** `ghostBtn` — botón secundario de la pantalla Añadir. */
@Composable
fun GhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .clip(shape)
            .background(P.chip)
            .border(1.dp, P.ink.copy(alpha = 0.14f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ElyText(label, size = 11f, weight = FontWeight.Medium, color = P.ink, maxLines = 1)
    }
}

/** `spinner` — cuadrado redondeado de 24dp girando, con el borde superior abierto. */
@Composable
fun ArcSpinner(size: Dp = 24.dp, stroke: Dp = 2.dp, color: Color? = null, periodMs: Int = 1100) {
    val skin = LocalSkin.current
    val c = color ?: skin.a2
    val angle = spinAngle(periodMs)
    Box(
        Modifier
            .size(size)
            .rotate(angle)
            .drawBehind { drawArcSpinner(c, stroke, 45f) },
    )
}

/** El interruptor de "aplicar metadatos automáticamente" (48×28, pomo de 22). */
@Composable
fun AccentSwitch(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(14.dp)
    val knobX by animateFloatAsState(
        targetValue = if (checked) 20f else 0f,
        animationSpec = tween(300, easing = Swift),
        label = "knob",
    )
    Box(
        modifier
            .size(48.dp, 28.dp)
            .clip(shape)
            .then(
                if (checked) Modifier.drawBehind { drawRect(accentGradient(skin, 145f, size)) }
                else Modifier.background(P.ink.copy(alpha = 0.15f)),
            )
            .clickable(onClick = onToggle)
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobX.dp)
                .size(22.dp)
                .shadow(2.dp, CircleShape, clip = false, ambientColor = P.shade.copy(alpha = 0.3f), spotColor = P.shade.copy(alpha = 0.3f))
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * Deslizador de los ajustes. Compose trae `Slider` de Material 3, pero arrastra
 * su propio pomo, sus ripples y su altura; aquí hace falta la pista fina y el
 * pomo pequeño del `input[type=range]` con `accent-color`, así que va a mano.
 */
@Composable
fun AccentSlider(
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val knob = with(density) { 16.dp.toPx() }
        val travel = (widthPx - knob).coerceAtLeast(1f)
        val span = (range.last - range.first).coerceAtLeast(1)
        val fraction = ((value - range.first).toFloat() / span).coerceIn(0f, 1f)

        fun report(x: Float) {
            val f = ((x - knob / 2f) / travel).coerceIn(0f, 1f)
            onChange(range.first + (f * span).roundToInt())
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(range, widthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { report(it.x) },
                        onHorizontalDrag = { change, _ -> report(change.position.x) },
                    )
                }
                .clickable(interactionSource = interaction, indication = null) {},
        )

        // Pista
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(P.ink.copy(alpha = 0.15f)),
        )
        // Relleno
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(skin.a2),
        )
        // Pomo
        Box(
            Modifier
                .offset(x = with(density) { (fraction * travel).toDp() })
                .size(16.dp)
                .shadow(3.dp, CircleShape, clip = false, ambientColor = P.shade.copy(alpha = 0.35f), spotColor = P.shade.copy(alpha = 0.35f))
                .clip(CircleShape)
                .background(skin.a2)
                .border(BorderStroke(2.dp, Color.White), CircleShape),
        )
    }
}

/** Muestra de color de los ajustes (acentos y tintes): 30dp de alto, radio 11. */
@Composable
fun Swatch(
    brush: Brush,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape: Shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .height(30.dp)
            .shadow(
                if (selected) 6.dp else 3.dp,
                shape,
                clip = false,
                ambientColor = P.shade.copy(alpha = if (selected) 0.24f else 0.10f),
                spotColor = P.shade.copy(alpha = if (selected) 0.24f else 0.10f),
            )
            .clip(shape)
            .background(brush)
            .border(
                if (selected) 2.5.dp else 1.dp,
                if (selected) P.ink else P.ink.copy(alpha = 0.12f),
                shape,
            )
            .clickable(onClick = onClick),
    )
}

/** Fila de puntos "escribiendo…" con el retardo escalonado del diseño. */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val (a, dy) = com.elyndra.launcher.ui.theme.typingDotAlpha(i)
            Box(
                Modifier
                    .offset(y = dy.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(P.ink2.copy(alpha = a)),
            )
        }
    }
}

/** Chevron "‹" de las cabeceras, dibujado para que no dependa de la fuente. */
@Composable
fun BackChevron(color: Color = P.ink, size: Dp = 15.dp, thickness: Dp = 1.8.dp) {
    Box(
        Modifier.size(size).drawBehind {
            val w = this.size.width
            val h = this.size.height
            val t = thickness.toPx()
            val x = w * 0.62f
            drawLine(color, Offset(x, h * 0.22f), Offset(w * 0.36f, h * 0.5f), t, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(w * 0.36f, h * 0.5f), Offset(x, h * 0.78f), t, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        },
    )
}

/** Lupa de la barra superior: círculo + mango a 45°. */
@Composable
fun SearchGlyph(color: Color = Color.White) {
    Box(
        Modifier.size(16.dp).drawBehind {
            val t = 1.8.dp.toPx()
            val r = 5.5.dp.toPx()
            val c = Offset(size.width * 0.42f, size.height * 0.42f)
            drawCircle(color, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = t))
            val start = Offset(c.x + r * 0.7f, c.y + r * 0.7f)
            drawLine(color, start, Offset(start.x + 4.dp.toPx(), start.y + 4.dp.toPx()), t, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        },
    )
}

/** Icono de ajustes: dos círculos concéntricos, como en el diseño. */
@Composable
fun SettingsGlyph(color: Color = Color.White) {
    Box(
        Modifier.size(16.dp).drawBehind {
            val t = 1.8.dp.toPx()
            val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            drawCircle(color, radius = 6.5.dp.toPx(), center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = t))
            drawCircle(color, radius = 3.dp.toPx(), center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = t))
        },
    )
}

/** Triángulo "▶" del botón Abrir. */
@Composable
fun PlayGlyph(color: Color = Color.White, size: Dp = 9.dp) {
    Box(
        Modifier.size(size).drawBehind {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(this@drawBehind.size.width, this@drawBehind.size.height / 2f)
                lineTo(0f, this@drawBehind.size.height)
                close()
            }
            drawPath(p, color)
        },
    )
}
