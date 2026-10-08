package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.ColorPair
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.SignaturePalettes
import com.elyndra.launcher.data.SignaturePreset
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.components.ConsolePreviews
import com.elyndra.launcher.ui.components.CssGrid
import com.elyndra.launcher.ui.components.PreviewTheme
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.SettingsGroup
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.meridian.LayoutStyle
import com.elyndra.launcher.ui.meridian.WheelArc
import com.elyndra.launcher.ui.meridian.WheelTransform
import com.elyndra.launcher.ui.components.SwitchRow
import kotlin.math.asin
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Radii
import com.elyndra.launcher.ui.theme.TypeScale

/**
 * Ajustes → Apariencia → estilo de la lista: Meridian (rueda vertical, el de
 * partida) o Clásico (carrusel). Cada opción con su miniatura dibujada.
 * Solo cambia la ventana apaisada y ancha: en vertical o en una ventana
 * estrecha siempre se ve el carrusel, y eso se explica debajo.
 */
@Composable
internal fun LayoutStyleGroup(vm: ElyndraViewModel) {
    val s = vm.settings
    LayoutStyleChooser(s.layoutStyle, s::updateLayoutStyle)
    SettingsGroup {
        SwitchRow(
            stringResource(R.string.meridian_adaptive_color_title),
            stringResource(R.string.meridian_adaptive_color_desc),
            s.meridianAdaptiveColor,
            s::toggleMeridianAdaptiveColor,
        )
    }
}

@Composable
internal fun LayoutStyleChooser(selected: LayoutStyle, onSelect: (LayoutStyle) -> Unit) {
    SettingsGroup {
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LayoutStyle.entries.forEach { style ->
                LayoutOption(
                    style = style,
                    selected = selected == style,
                    onClick = { onSelect(style) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ElyText(stringResource(R.string.layout_style_desc), size = TypeScale.Caption, color = P.ink2, lineHeightRatio = 1.45f)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun LayoutOption(style: LayoutStyle, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(Radii.m)
    val title = stringResource(if (style == LayoutStyle.Meridian) R.string.layout_meridian else R.string.layout_classic)
    val desc = stringResource(if (style == LayoutStyle.Meridian) R.string.layout_meridian_desc else R.string.layout_classic_desc)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) skin.a2.copy(alpha = if (P.isDark) 0.16f else 0.08f) else P.ink.copy(alpha = if (P.isDark) 0.06f else 0.035f))
            .border(if (selected) 2.dp else 1.dp, if (selected) skin.a2 else P.ink.copy(alpha = 0.1f), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
    ) {
        LayoutThumbnail(style, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Spacer(Modifier.height(8.dp))
        ElyText(title, size = TypeScale.Body, weight = FontWeight.SemiBold, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ElyText(desc, size = TypeScale.Caption, color = P.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * La miniatura de un estilo: una pantalla apaisada en pequeño. Meridian: la
 * rueda a la izquierda (filas que se curvan y encogen, la enfocada con su
 * filo), el dial curvo con su nodo y el arte con el titular a la derecha. Clásico:
 * el hero arriba y la fila de cards abajo. Se pinta en oscuro en los dos
 * temas, como las demás vistas previas, con los colores del acento.
 */
@Composable
internal fun LayoutThumbnail(style: LayoutStyle, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val pair = remember(skin.accent) {
        val raw = SignaturePalettes.accentPair(skin.accent.id, skin.a1.argb(), skin.secondary.argb())
        if (raw.primary == raw.secondary) SignaturePalettes.derive(raw.primary) else raw
    }
    Canvas(modifier.clip(RoundedCornerShape(Radii.s)).background(P.mediaBack)) {
        if (style == LayoutStyle.Meridian) drawMeridian(pair) else drawClassic(pair)
    }
}

/** Un arte de juego de mentira: el mismo degradado de las vistas previas de la selección. */
private val ThumbArt = listOf(Color(0xFF2B3550), Color(0xFF6A4C7E), Color(0xFFE3A15C))

private fun DrawScope.drawMeridian(pair: ColorPair) {
    val w = size.width
    val h = size.height
    val axis = w * 0.4f
    drawRect(Brush.linearGradient(ThumbArt, Offset(axis, 0f), Offset(w, h)), topLeft = Offset(axis, 0f), size = Size(w - axis, h))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)), startY = h * 0.3f, endY = h), topLeft = Offset(axis, 0f), size = Size(w - axis, h))
    // La rueda: la misma profundidad que en pantalla (WheelTransform), la del centro la más grande.
    val focus = h * WheelTransform.FOCUS_FRACTION
    val tileFull = h * WheelTransform.ICON_TILE
    val inset = axis * 0.08f - WheelTransform.arc(1f) * tileFull
    for (d in listOf(-3, 3, -2, 2, -1, 1, 0)) {
        val f = d.toFloat()
        val k = WheelTransform.scale(f)
        val tile = tileFull * k
        val cy = focus + WheelTransform.center(f) * tileFull
        val x = inset + WheelTransform.arc(f) * tileFull
        val y = cy - tile / 2f
        val a = WheelTransform.alpha(f)
        drawRoundRect(Color.White.copy(alpha = 0.55f * a), Offset(x, y), Size(tile, tile), CornerRadius(tile * 0.2f))
        drawRoundRect(P.mediaBack.copy(alpha = WheelTransform.fog(f)), Offset(x, y), Size(tile, tile), CornerRadius(tile * 0.2f))
        drawRoundRect(Color.White.copy(alpha = 0.4f * a * WheelTransform.detail(f)), Offset(x + tile + tileFull * 0.15f, cy - tile * 0.09f), Size(axis * 0.3f * k, tile * 0.16f), CornerRadius(tile * 0.08f))
        if (d == 0) {
            drawRoundRect(
                Brush.linearGradient(listOf(Color(pair.primary), Color(pair.secondary))),
                Offset(x - 1.5f, y - 1.5f),
                Size(tile + 3f, tile + 3f),
                CornerRadius(tile * 0.22f),
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
    // El dial: un tramo del mismo círculo que siguen las filas, con su nodo.
    val radius = WheelArc.radius(h)
    val reach = Math.toDegrees(asin((h * 0.42f / radius).toDouble())).toFloat()
    drawArc(
        Brush.verticalGradient(listOf(Color(pair.primary), Color(pair.secondary))),
        -reach,
        reach * 2f,
        false,
        Offset(axis - radius * 2f, focus - radius),
        Size(radius * 2f, radius * 2f),
        style = Stroke(1.5.dp.toPx()),
    )
    drawCircle(Color(pair.primary).copy(alpha = 0.45f), 4.5.dp.toPx(), Offset(axis, focus))
    drawCircle(Color(pair.spark), 1.8.dp.toPx(), Offset(axis, focus))
    // Titular y botón.
    drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(axis + w * 0.1f, h * 0.62f), Size(w * 0.32f, h * 0.08f), CornerRadius(2f))
    drawRoundRect(Color.White.copy(alpha = 0.5f), Offset(axis + w * 0.1f, h * 0.74f), Size(w * 0.22f, h * 0.05f), CornerRadius(2f))
    drawRoundRect(Color(pair.primary), Offset(axis + w * 0.1f, h * 0.84f), Size(w * 0.12f, h * 0.08f), CornerRadius(h * 0.04f))
}

private fun DrawScope.drawClassic(pair: ColorPair) {
    val w = size.width
    val h = size.height
    val hero = h * 0.56f
    drawRect(Brush.linearGradient(ThumbArt, Offset.Zero, Offset(w, hero)), size = Size(w, hero))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), startY = hero * 0.4f, endY = hero), size = Size(w, hero))
    drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(w * 0.07f, hero * 0.62f), Size(w * 0.38f, hero * 0.12f), CornerRadius(2f))
    drawRoundRect(Color.White.copy(alpha = 0.5f), Offset(w * 0.07f, hero * 0.8f), Size(w * 0.26f, hero * 0.07f), CornerRadius(2f))
    val tile = (h - hero) * 0.62f
    val gap = tile * 0.28f
    val y = hero + (h - hero - tile) / 2f
    for (i in 0 until 5) {
        val x = w * 0.07f + i * (tile + gap)
        if (x + tile > w) break
        drawRoundRect(Color.White.copy(alpha = if (i == 0) 0.7f else 0.35f), Offset(x, y), Size(tile, tile), CornerRadius(tile * 0.2f))
        if (i == 0) {
            drawRoundRect(
                Brush.linearGradient(listOf(Color(pair.primary), Color(pair.secondary))),
                Offset(x - 1.5f, y - 1.5f),
                Size(tile + 3f, tile + 3f),
                CornerRadius(tile * 0.22f),
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
}

/**
 * Ajustes → Apariencia → paleta de firma: Plasma, Ember, Aurora, Neon Rose y
 * Solar. Elegir una pone de una vez el acento, el halo y las partículas de la
 * selección y el color de la intro; cada uno sigue pudiéndose cambiar abajo
 * por separado (entonces ninguna sale marcada).
 */
@Composable
internal fun SignatureGroup(vm: ElyndraViewModel) {
    val s = vm.settings
    SignatureChooser(s.signature, s::applySignature)
}

@Composable
internal fun SignatureChooser(current: SignaturePreset?, onPick: (SignaturePreset) -> Unit) {
    SettingsGroup {
        ElyText(stringResource(R.string.signature_desc), size = TypeScale.Caption, color = P.ink2, lineHeightRatio = 1.45f)
        Spacer(Modifier.height(10.dp))
        CssGrid(
            columns = SignaturePreset.entries.size,
            horizontalGap = 8.dp,
            verticalGap = 8.dp,
            modifier = Modifier.selectableGroup(),
            items = SignaturePreset.entries.map { preset ->
                { SignatureChip(preset, selected = preset == current) { onPick(preset) } }
            },
        )
        Spacer(Modifier.height(6.dp))
    }
}

/** Una paleta de firma: su degradado (primario → secundario) con el destello, y el nombre debajo. */
@Composable
internal fun SignatureChip(preset: SignaturePreset, selected: Boolean, onClick: () -> Unit) {
    val name = stringResource(preset.nameRes)
    val shape = RoundedCornerShape(12.dp)
    val pair = preset.pair
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = name }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(shape)
                .drawBehind {
                    drawRect(Brush.linearGradient(listOf(Color(pair.primary), Color(pair.secondary)), Offset.Zero, Offset(size.width, size.height)))
                    drawCircle(
                        Brush.radialGradient(listOf(Color(pair.spark), Color(pair.spark).copy(alpha = 0f)), center = Offset(size.width * 0.72f, size.height * 0.38f), radius = size.height * 0.5f),
                        radius = size.height * 0.5f,
                        center = Offset(size.width * 0.72f, size.height * 0.38f),
                    )
                }
                .border(if (selected) 2.5.dp else 1.dp, if (selected) P.ink else P.ink.copy(alpha = 0.12f), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
            }
        }
        Spacer(Modifier.height(5.dp))
        ElyText(name, size = 9.5f, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) P.ink else P.ink2, align = TextAlign.Center, letterSpacing = tracking(0.02f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@ConsolePreviews
@Composable
private fun LayoutStylePreview() = PreviewTheme {
    var style by remember { mutableStateOf(LayoutStyle.Meridian) }
    Column(Modifier.width(360.dp)) { LayoutStyleChooser(style) { style = it } }
}

@ConsolePreviews
@Composable
private fun SignaturePreview() = PreviewTheme {
    var current by remember { mutableStateOf<SignaturePreset?>(SignaturePreset.Plasma) }
    Column(Modifier.width(360.dp)) { SignatureChooser(current) { current = it } }
}

