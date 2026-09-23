package com.elyndra.launcher.ui.components

import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.motion
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Swift
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.consoleFocus
import com.elyndra.launcher.ui.theme.darkGlass
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
    enabled: Boolean = true,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(cornerRadius)
    var m = modifier
        .then(if (height != null) Modifier.height(height) else Modifier)
        .alpha(if (enabled) 1f else 0.4f)
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
        m.clickable(enabled = enabled, onClick = onClick)
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
    // Cada módulo de ajustes es una lámina de [GlassCard]: mismo material que
    // el resto de la interfaz nueva, y así los tres bloques —Tema, Fondo,
    // Metadatos— se leen como piezas separadas y no como una lista larga.
    GlassCard(
        modifier = modifier,
        cornerRadius = cornerRadius,
        padding = androidx.compose.foundation.layout.PaddingValues(padding),
        content = content,
    )
}

/**
 * Bloque de Ajustes **sin contenedor**: sin tarjeta, sin fondo y sin borde.
 * Las opciones van directamente sobre el fondo de la pantalla y se separan
 * solo por aire y un filo muy tenue debajo. Acepta los mismos parámetros que
 * [GlassPanel] para poder sustituirlo sin tocar cada llamada; [cornerRadius]
 * ya no se usa.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    @Suppress("UNUSED_PARAMETER") cornerRadius: Dp = 0.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth().padding(vertical = (padding * 0.72f).coerceAtLeast(0.dp)),
            content = content,
        )
        SettingsDivider()
    }
}

/** El filo que separa las opciones de Ajustes: una línea de pelo, casi invisible. */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(P.ink.copy(alpha = 0.07f)),
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

/** Alto de la barra del hero, que "Abrir" comparte con los botones de al lado. */
val HeroBarHeight = 34.dp

/**
 * "Abrir", en la barra de arriba del hero y sobre el fondo del juego.
 *
 * El mismo botón en Biblioteca y en Carpeta, y discreto a propósito: el
 * cristal oscuro de sus vecinos, el alto de la barra y ni rastro del degradado
 * de acento que llevaba en el dock. Ahí arriba lo que se tiene que ver es el
 * fondo del juego, y abrir se hace además con doble toque sobre la card o con
 * el mando. [focused] es el foco del mando (ver `InputController.barFocus`).
 */
@Composable
fun OpenButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    onClick: () -> Unit,
) {
    val glyph by animateFloatAsState(if (focused) 1.14f else 1f, motion(Springs.snappy()), label = "openGlyph")
    Row(
        modifier
            .height(HeroBarHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .darkGlass(RoundedCornerShape(12.dp))
            .consoleFocus(focused)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenGlyph(Modifier.graphicsLayer { scaleX = glyph; scaleY = glyph })
        Spacer(Modifier.width(6.dp))
        ElyText(
            stringResource(R.string.open),
            size = 11f,
            weight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/**
 * Botón cuadrado de icono de la barra del hero (buscar, Ajustes, volver…),
 * con el realce de consola cuando lo señala el mando y el icono que crece un
 * poco al recibirlo.
 */
@Composable
fun ConsoleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    glass: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    val glyph by animateFloatAsState(if (focused) 1.14f else 1f, motion(Springs.snappy()), label = "iconGlyph")
    Box(
        modifier
            .size(HeroBarHeight)
            .then(if (glass) Modifier.darkGlass(RoundedCornerShape(12.dp)) else Modifier)
            .consoleFocus(focused)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(Modifier.graphicsLayer { scaleX = glyph; scaleY = glyph })
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
        animationSpec = motion(Springs.snappy()),
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

/* ── Iconos de consola ─────────────────────────────────────────
   Buscar, Ajustes y Abrir comparten familia: vectoriales, trazo de
   1,8 dp con remates redondos, geometría limpia y un detalle de luz
   en el color de acento (el "LED" de los menús de consola). Se
   construyen una vez por tamaño con drawWithCache: repintarlos al
   animar el foco no crea objetos nuevos.
   ───────────────────────────────────────────────────────────── */

/** Buscar: lente con reflejo y mango de agarre, con un punto de luz de acento. */
@Composable
fun SearchGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    val accent = LocalSkin.current.a2
    Box(
        modifier.size(18.dp).drawWithCache {
            val t = 1.8.dp.toPx()
            val r = 5.4.dp.toPx()
            val c = Offset(size.width * 0.42f, size.height * 0.42f)
            val handleStart = Offset(c.x + r * 0.72f, c.y + r * 0.72f)
            val handleEnd = Offset(size.width * 0.9f, size.height * 0.9f)
            val glint = Path().apply {
                // Reflejo: un arco corto arriba a la izquierda de la lente.
                arcTo(
                    androidx.compose.ui.geometry.Rect(c, r * 0.58f),
                    startAngleDegrees = 200f,
                    sweepAngleDegrees = 70f,
                    forceMoveTo = true,
                )
            }
            val stroke = Stroke(width = t, cap = StrokeCap.Round)
            val thin = Stroke(width = t * 0.7f, cap = StrokeCap.Round)
            onDrawBehind {
                drawCircle(color, radius = r, center = c, style = stroke)
                drawPath(glint, color.copy(alpha = 0.7f), style = thin)
                drawLine(color, handleStart, handleEnd, t * 1.45f, cap = StrokeCap.Round)
                drawCircle(accent, radius = t * 0.62f, center = c)
            }
        },
    )
}

/** Ajustes: engranaje de ocho dientes con eje; el eje lleva el punto de acento. */
@Composable
fun SettingsGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    val accent = LocalSkin.current.a2
    Box(
        modifier.size(18.dp).drawWithCache {
            val t = 1.7.dp.toPx()
            val c = Offset(size.width / 2f, size.height / 2f)
            val outer = size.minDimension * 0.46f
            val inner = size.minDimension * 0.34f
            val gear = Path()
            val teeth = 8
            val step = 360f / teeth
            for (i in 0 until teeth) {
                val a = i * step
                val points = listOf(inner to a - step * 0.30f, outer to a - step * 0.17f, outer to a + step * 0.17f, inner to a + step * 0.30f)
                points.forEachIndexed { j, (radius, deg) ->
                    val rad = Math.toRadians(deg.toDouble())
                    val x = c.x + radius * kotlin.math.cos(rad).toFloat()
                    val y = c.y + radius * kotlin.math.sin(rad).toFloat()
                    if (i == 0 && j == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                }
            }
            gear.close()
            val stroke = Stroke(width = t, join = StrokeJoin.Round, cap = StrokeCap.Round)
            onDrawBehind {
                drawPath(gear, color, style = stroke)
                drawCircle(color, radius = inner * 0.42f, center = c, style = Stroke(width = t))
                drawCircle(accent, radius = t * 0.6f, center = c)
            }
        },
    )
}

/** Abrir: el botón de "jugar" de una consola — anillo con el triángulo dentro. */
@Composable
fun OpenGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    val accent = LocalSkin.current.a2
    Box(
        modifier.size(18.dp).drawWithCache {
            val t = 1.6.dp.toPx()
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - t
            val tri = size.minDimension * 0.2f
            val play = Path().apply {
                // Centrado óptico: el triángulo se corre un poco a la derecha.
                moveTo(c.x - tri * 0.7f, c.y - tri)
                lineTo(c.x + tri * 1.05f, c.y)
                lineTo(c.x - tri * 0.7f, c.y + tri)
                close()
            }
            val ring = Stroke(width = t, cap = StrokeCap.Round)
            val soften = Stroke(width = t * 0.8f, join = StrokeJoin.Round)
            onDrawBehind {
                // El anillo, abierto arriba a la derecha con el punto de acento.
                drawArc(color, startAngle = -30f, sweepAngle = 320f, useCenter = false, topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = ring)
                val dot = Math.toRadians(-45.0)
                drawCircle(accent, radius = t * 0.7f, center = Offset(c.x + r * kotlin.math.cos(dot).toFloat(), c.y + r * kotlin.math.sin(dot).toFloat()))
                drawPath(play, color)
                drawPath(play, color, style = soften)
            }
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
