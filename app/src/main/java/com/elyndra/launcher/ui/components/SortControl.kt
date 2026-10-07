package com.elyndra.launcher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.indication
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.ui.DockPalettes
import com.elyndra.launcher.ui.DotTabsLayout
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Radii
import com.elyndra.launcher.ui.theme.Space
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.TypeScale
import com.elyndra.launcher.ui.theme.consoleFocus
import com.elyndra.launcher.ui.theme.DARK_GLASS_MIN
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.focusRing
import com.elyndra.launcher.ui.theme.pressFeedback
import androidx.compose.foundation.clickable

/** Ordenar: tres renglones de más a menos y la flecha, en el acento. */
@Composable
fun SortGlyph(color: Color, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(18.dp).drawBehind {
            val w = size.width
            val h = size.height
            val t = 1.7.dp.toPx()
            fun line(x0: Float, y: Float, x1: Float) =
                drawLine(color, Offset(w * x0, h * y), Offset(w * x1, h * y), t, cap = StrokeCap.Round)
            line(0.12f, 0.28f, 0.6f)
            line(0.12f, 0.5f, 0.48f)
            line(0.12f, 0.72f, 0.36f)
            drawLine(accent, Offset(w * 0.8f, h * 0.22f), Offset(w * 0.8f, h * 0.78f), t, cap = StrokeCap.Round)
            drawLine(accent, Offset(w * 0.68f, h * 0.64f), Offset(w * 0.8f, h * 0.8f), t, cap = StrokeCap.Round)
            drawLine(accent, Offset(w * 0.92f, h * 0.64f), Offset(w * 0.8f, h * 0.8f), t, cap = StrokeCap.Round)
        },
    )
}

/**
 * El botón de "Ordenar por": una píldora de cristal del alto del dock con el
 * glifo. Abierto el menú, se queda encendido con un velo del acento.
 */
@Composable
fun SortButton(
    description: String,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    /** Suelo de la tinta del cristal (ver [darkGlass]); más alto en la costura. */
    glassMinAlpha: Float = DARK_GLASS_MIN,
) {
    val skin = LocalSkin.current
    val interaction = remember { MutableInteractionSource() }
    val accent = remember(skin.accent) { Color(DockPalettes.glassAccent(skin.accent.content(true).argb())) }
    val toggle by rememberUpdatedState(onClick)
    // En la barra cuenta lo que se ve (44 × 34); la zona táctil sobresale hasta 48 × 48.
    Box(
        modifier
            .layout { measurable, _ ->
                val touch = DotTabsLayout.TOUCH.dp.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(touch, touch))
                val w = SORT_BUTTON_W.dp.roundToPx()
                val h = DotTabsLayout.HEIGHT.dp.roundToPx()
                layout(w, h) { placeable.place((w - touch) / 2, (h - touch) / 2) }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.DropdownList
                if (open) collapse { toggle(); true } else expand { toggle(); true }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = SORT_BUTTON_W.dp, height = DotTabsLayout.HEIGHT.dp)
                .pressFeedback(interaction)
                .darkGlass(CapsuleShape, minAlpha = glassMinAlpha)
                .then(if (open) Modifier.drawBehind { drawRoundRect(skin.a1.copy(alpha = DockPalettes.SORT_OPEN_VEIL), cornerRadius = CornerRadius(size.height / 2f)) } else Modifier)
                .consoleFocus(focused, CapsuleShape, color = accent)
                .indication(interaction, focusRing(CapsuleShape)),
            contentAlignment = Alignment.Center,
        ) {
            SortGlyph(Color.White, accent)
        }
    }
}

private const val SORT_BUTTON_W = 44f

/**
 * El menú de orden: una lámina pequeña anclada bajo el botón ([anchor], en
 * coordenadas de ventana) con las opciones; la elegida lleva la píldora del
 * dock y las demás su punto. Arriba, el título y el número de elementos.
 * Entra con un muelle desde el botón y se va más deprisa. Un toque fuera lo
 * cierra. [focusIndex] es la fila que señala el mando (−1 ninguna).
 *
 * Se compone como una capa más de la pantalla (sin ventana aparte): así el
 * mando y "atrás" siguen pasando por el ViewModel.
 */
@Composable
fun SortPopover(
    visible: Boolean,
    anchor: () -> Rect,
    title: String,
    caption: String,
    options: List<String>,
    selected: Int,
    focusIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val dismiss by rememberUpdatedState(onDismiss)
    var host by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { host = it.positionInWindow() }) {
        if (visible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            waitForUpOrCancellation()?.consume()
                            dismiss()
                        }
                    },
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.layout { measurable, constraints ->
                val margin = 12.dp.roundToPx()
                val gap = 8.dp.roundToPx()
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val a = anchor()
                val right = (a.right - host.x).toInt()
                val below = (a.bottom - host.y).toInt() + gap
                val above = (a.top - host.y).toInt() - gap - placeable.height
                val fitsBelow = below + placeable.height <= constraints.maxHeight - margin
                val x = (right - placeable.width).coerceIn(margin, (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin))
                val y = if (fitsBelow || above < margin) below else above
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x, y) }
            },
            enter = if (reduced) fadeIn(snap()) else fadeIn(Springs.fade()) + scaleIn(Springs.enter(), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = if (reduced) fadeOut(snap()) else fadeOut(Springs.exit()) + scaleOut(Springs.exit(), targetScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            SortMenu(title, caption, options, selected, focusIndex, onPick, onDismiss = { dismiss() })
        }
    }
}

@Composable
internal fun SortMenu(
    title: String,
    caption: String,
    options: List<String>,
    selected: Int,
    focusIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit = {},
) {
    val dark = P.isDark
    val shape = RoundedCornerShape(Radii.l)
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = 208.dp, max = 300.dp)
            .dockSurface(shape, alpha = DockPalettes.popoverAlpha(dark), elevation = 18.dp)
            // Un toque en la lámina (fuera de las filas) no llega al velo que cierra.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
            .semantics {
                paneTitle = title
                dismiss { onDismiss(); true }
            }
            .padding(Space.s - 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ElyText(
                title,
                modifier = Modifier.weight(1f),
                size = TypeScale.Overline,
                weight = FontWeight.SemiBold,
                color = Color(DockPalettes.ink2(dark)),
                letterSpacing = tracking(0.22f),
                uppercase = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            ElyText(
                caption,
                size = 9f,
                weight = FontWeight.Medium,
                color = Color(DockPalettes.ink2(dark)),
                letterSpacing = tracking(0.12f),
                uppercase = true,
                maxLines = 1,
            )
        }
        Column(Modifier.selectableGroup()) {
            options.forEachIndexed { i, label ->
                SortRow(label, i == selected, i == focusIndex) { onPick(i) }
            }
        }
    }
}

@Composable
private fun SortRow(label: String, current: Boolean, focused: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val dark = P.isDark
    val shape = RoundedCornerShape(Radii.s)
    val interaction = remember { MutableInteractionSource() }
    val pillStart = remember(skin.a1) { Color(DockPalettes.pillStart(skin.a1.argb())) }
    val pillEnd = remember(skin.fillEnd) { Color(DockPalettes.pillEnd(skin.fillEnd.argb())) }
    val dot = Color(DockPalettes.dot(dark))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .consoleFocus(focused, shape)
            .selectable(selected = current, interactionSource = interaction, indication = focusRing(shape), role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // El mismo lenguaje que el dock: píldora la elegida, punto las demás.
        Box(
            Modifier.width(18.dp).height(10.dp).drawBehind {
                if (current) {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(Brush.linearGradient(listOf(pillStart, pillEnd)), cornerRadius = r)
                    drawRoundRect(
                        Color.White.copy(alpha = if (dark) 0.35f else 0.6f),
                        cornerRadius = r,
                        style = Stroke(1.dp.toPx()),
                    )
                } else {
                    drawCircle(dot, radius = 3.dp.toPx(), center = Offset(4.dp.toPx(), size.height / 2f))
                }
            },
        )
        Spacer(Modifier.width(10.dp))
        ElyText(
            label,
            size = TypeScale.Body,
            weight = if (current) FontWeight.SemiBold else FontWeight.Medium,
            color = Color(if (current) DockPalettes.ink(dark) else DockPalettes.ink2(dark)),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeightRatio = 1.3f,
        )
    }
}
