package com.elyndra.launcher.ui.screens

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
import com.elyndra.launcher.ui.components.SettingsGroup
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
    val color = Color(s.mashaParticleColor)
    SettingsGroup(padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ElyText(stringResource(R.string.masha_particles_title), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
                Spacer(Modifier.height(4.dp))
                ElyText(stringResource(R.string.masha_particles_desc), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
            }
            Spacer(Modifier.width(12.dp))
            ParticlePreview(color)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { argb ->
                ColorDot(Color(argb), selected = argb == s.mashaParticleColor, modifier = Modifier.weight(1f)) {
                    s.updateMashaParticleColor(argb)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ElyText(stringResource(R.string.masha_particles_hue), size = 11.5f, weight = FontWeight.Medium, color = P.ink)
            Spacer(Modifier.weight(1f))
            ElyText("${hueOf(s.mashaParticleColor).roundToInt()}°", size = 11.5f, weight = FontWeight.Medium, color = P.ink2)
        }
        Spacer(Modifier.height(7.dp))
        HueSlider(hueOf(s.mashaParticleColor)) { hue ->
            s.updateMashaParticleColor(Color.hsv(hue, PICK_SATURATION, PICK_VALUE).toArgb())
        }
        Spacer(Modifier.height(12.dp))
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
    Box(modifier.height(30.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(Brush.radialGradient(listOf(Color.White, color, color.copy(alpha = 0.7f)), radius = size.minDimension * 0.7f))
                }
                .border(if (selected) 2.5.dp else 1.dp, if (selected) P.ink else P.ink.copy(alpha = 0.15f), CircleShape)
                .clickable(onClick = onClick),
        )
    }
}

/**
 * Vista previa: Masha con su estela girando alrededor, en el color elegido.
 * La animación se lee en la fase de dibujo (no recompone los Ajustes).
 */
@Composable
private fun ParticlePreview(color: Color) {
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "particlePreview")
    val turn = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B0E13))
            .drawBehind {
                val c = Offset(size.width / 2f, size.height / 2f)
                val orbit = size.minDimension * 0.34f
                val head = if (reduced) 0.15f else turn.value
                // Estela: 14 chispas detrás de la cabeza, cada vez más tenues.
                for (i in 0 until 14) {
                    val t = head - i * 0.022f
                    val a = t * 2f * PI.toFloat()
                    val p = Offset(c.x + cos(a) * orbit, c.y + sin(a) * orbit)
                    val f = 1f - i / 14f
                    val r = (1.2f + 2.4f * f) * density
                    drawCircle(color, radius = r * 3f, center = p, alpha = 0.14f * f)
                    drawCircle(color, radius = r * 1.5f, center = p, alpha = 0.4f * f)
                    drawCircle(Color.White, radius = r * 0.5f, center = p, alpha = 0.9f * f)
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
