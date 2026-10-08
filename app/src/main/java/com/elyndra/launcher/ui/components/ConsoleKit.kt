package com.elyndra.launcher.ui.components

import com.elyndra.launcher.sound.UiSound
import com.elyndra.launcher.ui.LocalUiSounds
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.MinTouch
import com.elyndra.launcher.ui.theme.Radii
import com.elyndra.launcher.ui.theme.Space
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.TypeScale
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.consoleFocus
import com.elyndra.launcher.ui.theme.consoleSurface
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.motion
import com.elyndra.launcher.ui.theme.outerShadow
import com.elyndra.launcher.ui.theme.pressFeedback
import com.elyndra.launcher.ui.theme.shapeClickable

/* ─────────────────────────────────────────────────────────────
   Piezas de la interfaz de consola (ver docs/UI_DESIGN.md): cabeceras
   de sección, filas de ajustes, control segmentado, acordeón, botón de
   icono, pestañas deslizantes, estado vacío, barra de acción fija y
   pistas del mando.
   ───────────────────────────────────────────────────────────── */

/**
 * Ajustes en ventana ancha y apaisada: filas y cabeceras con algo menos de
 * aire (ver `SettingsFrame.dense`). En vertical y en el móvil, el de siempre.
 */
val LocalDenseSettings = staticCompositionLocalOf { false }

/** Cabecera de sección: versalitas pequeñas y espaciadas. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = if (LocalDenseSettings.current) 13.dp else 18.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ElyText(
            text,
            modifier = Modifier.weight(1f),
            size = TypeScale.Overline,
            weight = FontWeight.SemiBold,
            color = P.ink2,
            letterSpacing = tracking(0.26f),
            uppercase = true,
        )
        trailing?.invoke(this)
    }
}

/**
 * La fila de Ajustes: rótulo y descripción a la izquierda, el control a la
 * derecha. Plana, sin tarjeta; la separan las filas vecinas o un filo.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(vertical = if (LocalDenseSettings.current) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ElyText(title, size = TypeScale.Body, weight = FontWeight.SemiBold, color = P.ink)
            if (description != null) {
                Spacer(Modifier.height(3.dp))
                ElyText(description, size = TypeScale.Caption, color = P.ink2, lineHeightRatio = 1.45f)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.m))
            trailing()
        }
    }
}

/** Fila con interruptor. */
@Composable
fun SwitchRow(title: String, description: String?, checked: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    SettingRow(title, description = description) { GlowingSwitch(checked, onToggle, enabled = enabled) }
}

/** Fila que lleva a otra página: rótulo, valor y galón. Toda la fila se pulsa. */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    glyph: ConsoleGlyph? = null,
) {
    val shape = RoundedCornerShape(Radii.s)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .shapeClickable(shape, onClick = onClick)
            .padding(horizontal = Space.s, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            GlyphBadge(glyph)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            ElyText(title, size = TypeScale.Body, weight = FontWeight.SemiBold, color = P.ink)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                ElyText(description, size = TypeScale.Caption, color = P.ink2, lineHeightRatio = 1.4f)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(Space.s))
            ElyText(value, size = TypeScale.Label, weight = FontWeight.Medium, color = P.ink2, maxLines = 1)
        }
        Spacer(Modifier.width(Space.s))
        ConsoleGlyphIcon(ConsoleGlyph.Chevron, P.ink2.copy(alpha = 0.7f), Modifier.graphicsLayer { rotationZ = -90f }, size = 14.dp)
    }
}

/** El glifo de una categoría en su placa redondeada, teñida de acento. */
@Composable
fun GlyphBadge(glyph: ConsoleGlyph, active: Boolean = false, size: androidx.compose.ui.unit.Dp = 34.dp) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(Radii.s - 2.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .then(
                if (active) Modifier.drawBehind { drawRect(accentGradient(skin, 145f, this.size)) }
                else Modifier.background(skin.a2.copy(alpha = if (P.isDark) 0.18f else 0.10f)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        ConsoleGlyphIcon(glyph, if (active) Color.White else skin.a2, size = size * 0.52f)
    }
}

/**
 * Control segmentado: una sola pastilla de acento que se desliza entre las
 * opciones (la misma que la cápsula de pestañas de Añadir, en compacto).
 * Para pocas opciones y rótulos cortos; si no caben, mejor píldoras.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: (Int) -> Boolean = { true },
) {
    if (options.isEmpty()) return
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(Radii.s)
    val inner = RoundedCornerShape(Radii.s - 3.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(P.ink.copy(alpha = if (P.isDark) 0.10f else 0.06f))
            .border(1.dp, P.ink.copy(alpha = 0.08f), shape)
            .padding(3.dp),
    ) {
        val slot = maxWidth / options.size
        val position by animateFloatAsState(selected.toFloat(), motion(Springs.snappy()), label = "segment")
        if (selected in options.indices) {
            Box(
                Modifier
                    .offset { IntOffset((slot * position).roundToPx(), 0) }
                    .width(slot)
                    .fillMaxSize()
                    .outerShadow(6.dp, inner, ambientColor = skin.a1, spotColor = skin.a1)
                    .clip(inner)
                    .drawBehind { drawRect(accentGradient(skin, 145f, size)) },
            )
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val active = i == selected
                val enabled = isEnabled(i)
                Box(
                    Modifier
                        .width(slot)
                        .fillMaxSize()
                        .alpha(if (enabled) 1f else 0.4f)
                        .semantics { this.selected = active }
                        .shapeClickable(inner, enabled = enabled, color = if (active) Color.White else null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    ElyText(
                        label,
                        size = TypeScale.Label,
                        weight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) Color.White else P.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Contenido que se despliega con muelle y se recoge más deprisa; con
 * "reducir movimiento", aparece y desaparece sin animar.
 */
@Composable
fun Expandable(expanded: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = if (reduced) fadeIn(snap()) else expandVertically(Springs.expand()) + fadeIn(Springs.fade()),
        exit = if (reduced) fadeOut(snap()) else shrinkVertically(Springs.exit()) + fadeOut(Springs.exit()),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * Cabecera de acordeón: rótulo, resumen de una línea cuando está cerrado y un
 * galón que gira. Toda la fila se pulsa y se alcanza con el mando.
 */
@Composable
fun AccordionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val turn by animateFloatAsState(if (expanded) 1f else 0f, motion(Springs.snappy()), label = "chevron")
    val expandLabel = stringResource(if (expanded) R.string.a11y_collapse else R.string.a11y_expand)
    val sounds = LocalUiSounds.current
    val shape = RoundedCornerShape(Radii.s)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { stateDescription = expandLabel }
            .shapeClickable(shape, onClickLabel = expandLabel) {
                sounds?.play(if (expanded) UiSound.ToggleOff else UiSound.ToggleOn)
                onToggle()
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            ElyText(title, size = TypeScale.Body, weight = FontWeight.SemiBold, color = P.ink)
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                ElyText(summary, size = TypeScale.Caption, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.s))
            trailing()
        }
        Spacer(Modifier.width(Space.s))
        ConsoleGlyphIcon(
            ConsoleGlyph.Chevron,
            P.ink2,
            Modifier.graphicsLayer { rotationZ = 180f * turn },
            size = 16.dp,
        )
    }
}

/**
 * Botón de icono con su descripción para lectores de pantalla y 48 dp de zona
 * táctil (el glifo se ve más pequeño).
 */
@Composable
fun IconAction(
    glyph: ConsoleGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = P.ink,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val skin = LocalSkin.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(MinTouch)
            .alpha(if (enabled) 1f else 0.35f)
            .pressFeedback(interaction)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .shapeClickable(CircleShape, enabled = enabled, interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (filled) skin.a2.copy(alpha = if (P.isDark) 0.22f else 0.12f) else P.ink.copy(alpha = if (P.isDark) 0.08f else 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            ConsoleGlyphIcon(glyph, if (filled) skin.a2 else tint, size = 16.dp)
        }
    }
}

/**
 * Pestañas con un solo indicador que se desliza bajo el rótulo elegido. Las
 * posiciones se toman relativas a la fila, así que no cambian mientras la
 * pantalla entera se mueve en una transición.
 */
@Composable
fun SlidingTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    val density = LocalDensity.current
    val pad = with(density) { 10.dp.toPx() }
    val lefts = remember(labels.size) { mutableStateListOf<Float>().apply { repeat(labels.size) { add(-1f) } } }
    val widths = remember(labels.size) { mutableStateListOf<Float>().apply { repeat(labels.size) { add(0f) } } }
    val left = remember { Animatable(-1f) }
    val width = remember { Animatable(0f) }
    val reduced = LocalReducedMotion.current
    val targetLeft = lefts.getOrElse(selected) { -1f }
    val targetWidth = widths.getOrElse(selected) { 0f }
    LaunchedEffect(targetLeft, targetWidth) {
        if (targetLeft < 0f) return@LaunchedEffect
        if (left.value < 0f || reduced) {
            left.snapTo(targetLeft)
            width.snapTo(targetWidth)
        } else {
            coroutineScope {
                launch { left.animateTo(targetLeft, Springs.snappy()) }
                width.animateTo(targetWidth, Springs.snappy())
            }
        }
    }
    Row(
        modifier.drawBehind {
            if (left.value < 0f || width.value <= 0f) return@drawBehind
            val h = 2.dp.toPx()
            val bar = Size(width.value, h)
            translate(left.value, size.height - h) {
                drawRoundRect(accentGradient(skin, 90f, bar), size = bar, cornerRadius = CornerRadius(h))
            }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .onPlaced { c ->
                        val x = c.positionInParent().x + pad
                        val w = c.size.width - pad * 2
                        if (lefts[i] != x) lefts[i] = x
                        if (widths[i] != w) widths[i] = w
                    }
                    .semantics { this.selected = active }
                    .shapeClickable(RoundedCornerShape(Radii.xs)) { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                ElyText(
                    label,
                    size = TypeScale.Label,
                    weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) P.ink else P.ink2.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Estado vacío: un icono en su placa, lo que pasa ([title], si no lo dice ya
 * otra pieza), qué hacer y el botón que lo hace. [focused] es el foco del
 * mando. Si el hueco es bajo (un móvil en horizontal), se reparte en fila.
 */
@Composable
fun EmptyState(
    title: String?,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: ConsoleGlyph = ConsoleGlyph.Gamepad,
    focused: Boolean = false,
) {
    val skin = LocalSkin.current
    val icon: @Composable () -> Unit = {
        Box(Modifier.size(64.dp).consoleSurface(CircleShape, elevation = 12.dp), contentAlignment = Alignment.Center) {
            ConsoleGlyphIcon(glyph, skin.a2, size = 30.dp)
        }
    }
    val button: @Composable () -> Unit = {
        AccentButton(actionLabel, onAction, modifier = Modifier.consoleFocus(focused, RoundedCornerShape(15.dp)))
    }
    BoxWithConstraints(modifier.padding(horizontal = Space.m), contentAlignment = Alignment.Center) {
        if (maxHeight < 200.dp) {
            Row(Modifier.widthIn(max = 560.dp), verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f, fill = false)) {
                    if (title != null) {
                        ElyText(title, size = TypeScale.Title, weight = FontWeight.SemiBold, color = P.ink)
                        Spacer(Modifier.height(4.dp))
                    }
                    ElyText(message, size = TypeScale.Caption + 0.5f, color = P.ink2, lineHeightRatio = 1.45f)
                    Spacer(Modifier.height(10.dp))
                    button()
                }
            }
        } else {
            Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                icon()
                Spacer(Modifier.height(Space.m))
                if (title != null) {
                    ElyText(title, size = TypeScale.Title, weight = FontWeight.SemiBold, color = P.ink, align = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                }
                ElyText(message, size = TypeScale.Caption + 0.5f, color = P.ink2, align = TextAlign.Center, lineHeightRatio = 1.45f)
                Spacer(Modifier.height(Space.m))
                button()
            }
        }
    }
}

/**
 * Barra de acción fija al pie: lo que hay elegido a la izquierda y el botón
 * principal a la derecha. Desactivado, el porqué ocupa el sitio del estado.
 */
@Composable
fun ActionBar(
    status: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reason: String? = null,
    busy: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .consoleSurface(RoundedCornerShape(Radii.l), elevation = 16.dp)
            .padding(start = Space.m, end = Space.s, top = Space.s, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            ArcSpinner(size = 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        ElyText(
            if (!enabled && reason != null) reason else status,
            modifier = Modifier.weight(1f),
            size = TypeScale.Label,
            weight = FontWeight.Medium,
            color = P.ink2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeightRatio = 1.35f,
        )
        Spacer(Modifier.width(Space.s))
        Box(Modifier.alpha(if (enabled) 1f else 0.45f)) {
            AccentButton(actionLabel, { if (enabled) onAction() }, enabled = enabled)
        }
    }
}

/** Un botón del mando en una pista: A, B, X, Y o LB / RB. */
data class PadHint(val button: String, @StringRes val label: Int)

/**
 * Pistas del mando ("A Abrir · B Atrás"). Solo se pintan con un mando
 * conectado ([visible]); aparecen y se van con un fundido.
 */
@Composable
fun PadHints(
    hints: List<PadHint>,
    visible: Boolean,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible && hints.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(if (reduced) snap() else Springs.fade()),
        exit = fadeOut(if (reduced) snap() else Springs.exit()),
    ) {
        Row(
            Modifier
                .then(if (onDark) Modifier.darkGlass(RoundedCornerShape(Radii.s)) else Modifier)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            hints.forEach { hint ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PadButtonGlyph(hint.button, onDark)
                    Spacer(Modifier.width(6.dp))
                    ElyText(
                        stringResource(hint.label),
                        size = TypeScale.Caption,
                        weight = FontWeight.Medium,
                        color = if (onDark) Color.White.copy(alpha = 0.9f) else P.ink2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** El botón del mando dibujado: círculo para A/B/X/Y y píldora para los gatillos. */
@Composable
fun PadButtonGlyph(button: String, onDark: Boolean = false) {
    val color = if (onDark) Color.White else P.ink
    val round = button.length == 1
    val shape = if (round) CircleShape else RoundedCornerShape(7.dp)
    Box(
        Modifier
            .then(if (round) Modifier.size(20.dp) else Modifier.height(20.dp))
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f), shape)
            .padding(horizontal = if (round) 0.dp else 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        ElyText(button, size = 9f, weight = FontWeight.Bold, color = color, maxLines = 1)
    }
}
