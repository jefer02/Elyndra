package com.elyndra.launcher.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.elyndra.launcher.ui.components.ConsoleGlyph
import com.elyndra.launcher.ui.components.ConsoleGlyphIcon
import com.elyndra.launcher.ui.components.Expandable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.elyndra.launcher.ui.theme.shapeClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.SettingsDivider
import com.elyndra.launcher.ui.components.SettingsGroup
import com.elyndra.launcher.ui.components.SwitchRow
import com.elyndra.launcher.ui.selection.SelectionFx
import com.elyndra.launcher.ui.selection.SelectionInks
import com.elyndra.launcher.ui.selection.drawStar
import com.elyndra.launcher.ui.selection.rememberSelectionLook
import com.elyndra.launcher.ui.selection.selectionFrame
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.consoleFocus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Colores de fósforo de partida: los típicos de monitor y de LED de consola.
 * Con el deslizador de tono se puede elegir cualquier otro.
 */
private val PRESETS = listOf(
    0xFF5CF2FF, // cian
    0xFF6CFF8E, // verde fósforo
    0xFFFFC857, // ámbar
    0xFFFF5CD6, // magenta
    0xFFA78BFF, // violeta
    0xFFFF5C6C, // rojo
    0xFF4D9BFF, // azul
    0xFFF4F7FF, // blanco
).map { it.toInt() }

/** Saturación y brillo de los colores elegidos con el deslizador: vivos, que brillen. */
private const val PICK_SATURATION = 0.68f
private const val PICK_VALUE = 1f

/**
 * Ajustes → Masha → color de las partículas: vista previa animada, colores de
 * partida y un deslizador de tono. Se guarda al momento (SettingsStore) y el
 * botón de Masha lo usa en el siguiente arrastre.
 */
@Composable
internal fun ParticleColorGroup(vm: ElyndraViewModel) {
    val s = vm.settings
    ParticleColorPicker(
        title = stringResource(R.string.masha_particles_title),
        desc = stringResource(R.string.masha_particles_desc),
        argb = s.mashaParticleColor,
        onPick = s::updateMashaParticleColor,
    ) { color -> ParticlePreview(color) }
}

/**
 * Ajustes → Apariencia → selección: cómo se enciende la card seleccionada
 * (icono o carátula). Dos interruptores —el halo del marco y el polvo
 * estelar— que comparten el color de abajo; la vista previa enseña los dos
 * sobre una card de icono y otra de carátula.
 */
@Composable
internal fun SelectionFxGroup(vm: ElyndraViewModel) {
    val s = vm.settings
    SettingsGroup(padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ElyText(
                stringResource(R.string.selection_fx_desc),
                size = 10f,
                color = P.ink2,
                lineHeightRatio = 1.45f,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            SelectionPreview(s.selectionParticleColor, s.selectionGlow, s.selectionParticles)
        }
        SwitchRow(
            stringResource(R.string.selection_glow_title),
            stringResource(R.string.selection_glow_desc),
            s.selectionGlow,
            s::toggleSelectionGlow,
        )
        SettingsDivider()
        SwitchRow(
            stringResource(R.string.selection_particles_title),
            stringResource(R.string.selection_particles_desc),
            s.selectionParticles,
            s::toggleSelectionParticles,
        )
        Spacer(Modifier.height(4.dp))
        ColorChoices(s.selectionParticleColor, s::updateSelectionParticleColor)
    }
}

/** El bloque de color de la estela de Masha: título con su vista previa y los colores. */
@Composable
private fun ParticleColorPicker(
    title: String,
    desc: String,
    argb: Int,
    onPick: (Int) -> Unit,
    preview: @Composable (Color) -> Unit,
) {
    SettingsGroup(padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ElyText(title, size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
                Spacer(Modifier.height(4.dp))
                ElyText(desc, size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
            }
            Spacer(Modifier.width(12.dp))
            preview(Color(argb))
        }
        ColorChoices(argb, onPick)
    }
}

/**
 * La fila de colores de partida. La última muestra es el color personalizado:
 * despliega el deslizador de tono, que también sale solo si el color elegido
 * no es uno de partida.
 */
@Composable
private fun ColorChoices(argb: Int, onPick: (Int) -> Unit) {
    val custom = argb !in PRESETS
    var customOpen by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PRESETS.forEach { preset ->
                ColorDot(Color(preset), selected = preset == argb, modifier = Modifier.weight(1f)) { onPick(preset) }
            }
            CustomHueDot(
                selected = custom,
                expanded = customOpen || custom,
                modifier = Modifier.weight(1f),
            ) { customOpen = !customOpen }
        }
        Expandable(customOpen || custom) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ElyText(stringResource(R.string.particle_custom_hue), size = 11.5f, weight = FontWeight.Medium, color = P.ink)
                Spacer(Modifier.weight(1f))
                ElyText("${hueOf(argb).roundToInt()}°", size = 11.5f, weight = FontWeight.Medium, color = P.ink2)
            }
            Spacer(Modifier.height(7.dp))
            HueSlider(hueOf(argb)) { hue ->
                onPick(Color.hsv(hue, PICK_SATURATION, PICK_VALUE).toArgb())
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * La muestra del color personalizado: un aro con el arcoíris de tonos. Toca
 * para desplegar (o recoger) el deslizador; seleccionada si el color actual
 * no es uno de partida.
 */
@Composable
private fun CustomHueDot(selected: Boolean, expanded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val label = stringResource(R.string.particle_custom_hue)
    val action = stringResource(if (expanded) R.string.a11y_collapse else R.string.a11y_expand)
    val sweep = remember { Brush.sweepGradient((0..6).map { Color.hsv(it * 60f % 360f, PICK_SATURATION, PICK_VALUE) }) }
    Box(modifier.height(44.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .drawBehind { drawCircle(sweep) }
                .border(if (selected) 2.5.dp else 1.dp, if (selected) P.ink else P.ink.copy(alpha = 0.15f), CircleShape)
                .semantics { contentDescription = label }
                .shapeClickable(CircleShape, onClickLabel = action, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ConsoleGlyphIcon(ConsoleGlyph.Plus, Color.White, size = 12.dp)
        }
    }
}

private fun hueOf(argb: Int): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    return hsv[0]
}

/** Una muestra redonda de color; seleccionada, con aro de tinta. */
@Composable
private fun ColorDot(color: Color, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(44.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(Brush.radialGradient(listOf(Color.White, color, color.copy(alpha = 0.7f)), radius = size.minDimension * 0.7f))
                }
                .border(if (selected) 2.5.dp else 1.dp, if (selected) P.ink else P.ink.copy(alpha = 0.15f), CircleShape)
                .shapeClickable(CircleShape, onClick = onClick),
        )
    }
}

/**
 * Vista previa: Masha con su estela girando alrededor, en el color elegido y
 * con el mismo polvo fino que la estela de verdad. La caja es oscura en los
 * dos temas, así que la luz se suma como en el tema oscuro. La animación se
 * lee en la fase de dibujo (no recompone los Ajustes).
 */
@Composable
private fun ParticlePreview(color: Color) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current.density
    val argb = color.toArgb()
    val ink = remember(argb, density) { SelectionInks.of(argb, dark = true, density = density) }
    val transition = rememberInfiniteTransition(label = "particlePreview")
    val turn = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(P.mediaBack)
            .drawBehind {
                val c = Offset(size.width / 2f, size.height / 2f)
                val orbit = size.minDimension * 0.34f
                val head = if (reduced) 0.15f else turn.value
                // Estela: 18 motas detrás de la cabeza, cada vez más tenues y finas.
                for (i in 0 until 18) {
                    val t = head - i * 0.018f
                    val a = t * 2f * PI.toFloat()
                    val f = 1f - i / 18f
                    val core = (SelectionFx.MIN_SIZE_DP + (SelectionFx.MAX_SIZE_DP - SelectionFx.MIN_SIZE_DP) * f) * density
                    drawStar(ink.atlas, i % SelectionFx.VARIANTS, i == 5, c.x + cos(a) * orbit, c.y + sin(a) * orbit, core, f, ink.blend)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.masha),
            contentDescription = stringResource(R.string.masha_particles_preview),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(26.dp).clip(CircleShape).border(1.5.dp, color, CircleShape),
        )
    }
}

/** Cada cuánto salta la selección entre las dos cards de la vista previa. */
private const val PREVIEW_HOP_MS = 2_600L

/**
 * Vista previa de la selección: una card de icono y otra de carátula con el
 * marco de verdad ([selectionFrame]). La selección salta de una a otra, así
 * se ve el encendido, y solo una anima a la vez. Caja oscura en los dos temas:
 * se pinta con la luz del tema oscuro.
 */
@Composable
private fun SelectionPreview(argb: Int, glow: Boolean, particles: Boolean) {
    val skin = LocalSkin.current
    val reduced = LocalReducedMotion.current
    val look = rememberSelectionLook(argb, glow, particles, dark = true)
    var onCover by remember { mutableStateOf(false) }
    LaunchedEffect(reduced) {
        if (reduced) {
            onCover = false
            return@LaunchedEffect
        }
        while (true) {
            delay(PREVIEW_HOP_MS)
            onCover = !onCover
        }
    }
    val label = stringResource(R.string.masha_particles_preview)
    Box(
        Modifier
            .size(width = 104.dp, height = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(P.mediaBack)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = RoundedCornerShape(7.dp)
            Box(
                Modifier
                    .zIndex(if (onCover) 0f else 1f)
                    .size(28.dp)
                    .selectionFrame(!onCover, look, icon)
                    .clip(icon)
                    .background(Brush.linearGradient(listOf(skin.a1, skin.fillEnd))),
            )
            val cover = RoundedCornerShape(5.dp)
            Box(
                Modifier
                    .zIndex(if (onCover) 1f else 0f)
                    .size(width = 24.dp, height = 36.dp)
                    .selectionFrame(onCover, look, cover)
                    .clip(cover)
                    .background(Brush.verticalGradient(listOf(Color(0xFF2B3550), Color(0xFF6A4C7E), Color(0xFFE3A15C)))),
            )
        }
    }
}

/**
 * Deslizador de tono con la pista del arcoíris. Con el mando se enfoca como
 * cualquier opción y la cruceta izquierda/derecha lo mueve de 10 en 10°.
 */
@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val latest by rememberUpdatedState(onChange)
    val rainbow = remember {
        Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, PICK_SATURATION, PICK_VALUE) })
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .consoleFocus(focused, cornerRadius = 14.dp)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { latest((hue - 10f + 360f) % 360f); true }
                    Key.DirectionRight -> { latest((hue + 10f) % 360f); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.CenterStart,
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val knob = with(density) { 20.dp.toPx() }
        val travel = (widthPx - knob).coerceAtLeast(1f)
        fun report(x: Float) = latest((((x - knob / 2f) / travel).coerceIn(0f, 1f) * 359f))

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { report(it.x) },
                        onHorizontalDrag = { change, _ -> report(change.position.x) },
                    )
                }
                .pointerInput(widthPx) { detectTapGestures { report(it.x) } },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(rainbow),
        )
        Box(
            Modifier
                .offset(x = with(density) { (hue / 359f * travel).toDp() })
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.hsv(hue, PICK_SATURATION, PICK_VALUE))
                .border(2.5.dp, Color.White, CircleShape),
        )
    }
}
