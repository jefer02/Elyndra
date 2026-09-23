package com.elyndra.launcher.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Swift
import com.elyndra.launcher.ui.theme.accentGradient

/* ─────────────────────────────────────────────────────────────
   GlassKit: las piezas de cristal de las pantallas secundarias.

   Añadir y Ajustes eran paneles blancos planos. Aquí están los
   tres materiales que los convierten en superficies: la lámina
   ([GlassCard]), el interruptor ([GlowingSwitch]) y el campo de
   texto ([GlassTextField]), más la cápsula de pestañas
   ([GlassTabBar]).

   SOBRE EL DESENFOQUE. Compose no tiene `backdrop-filter`: no hay
   forma de desenfocar *lo que hay detrás* de una vista dentro de
   la misma ventana (`Modifier.blur` desenfoca el propio
   contenido, no el fondo). Así que el cristal se construye como
   ya hace el resto de Elyndra: lechosidad, degradado de luz,
   filo fino y sombra. `frost` gradúa esa lechosidad, que es lo
   que el radio de desenfoque haría si existiese; a más frost,
   menos se transparenta el fondo.
   ───────────────────────────────────────────────────────────── */

/**
 * Lámina de cristal. Es el contenedor de todo lo demás.
 *
 * @param frost cuánto vela el fondo (0 = casi transparente, 1 = casi opaco).
 * @param glowColor color del filo y del halo; null = el acento del tema.
 * @param glow intensidad del halo, 0…1. A 0 solo queda el filo fino.
 * @param elevation sombra proyectada: es lo que separa la lámina del fondo.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    frost: Float = 0.72f,
    glowColor: Color? = null,
    glow: Float = 0f,
    elevation: Dp = 10.dp,
    padding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val skin = LocalSkin.current
    val accent = glowColor ?: skin.a1
    val shape = RoundedCornerShape(cornerRadius)

    Column(
        modifier
            .fillMaxWidth()
            // El halo va en la sombra y no en un borde ancho: una sombra de
            // color se difumina hacia fuera, que es justo lo que hace un
            // resplandor. Un borde grueso solo engorda el contorno.
            .shadow(
                elevation = elevation + (14.dp * glow),
                shape = shape,
                clip = false,
                ambientColor = if (glow > 0f) accent else P.shade,
                spotColor = if (glow > 0f) accent else P.shade,
            )
            .clip(shape)
            .background(
                // Degradado de arriba abajo: la luz entra por arriba, como en
                // una lámina real puesta de pie.
                Brush.verticalGradient(
                    listOf(
                        P.paper.copy(alpha = (frost + 0.14f).coerceAtMost(1f)),
                        P.paper.copy(alpha = (frost - 0.1f).coerceAtLeast(0f)),
                    ),
                ),
            )
            .drawBehind {
                // Realce de acento pegado al filo superior: el "glow border".
                if (glow > 0f) {
                    drawRect(
                        Brush.verticalGradient(
                            colors = listOf(accent.copy(alpha = 0.22f * glow), Color.Transparent),
                            endY = size.height * 0.4f,
                        ),
                    )
                }
            }
            .border(
                BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.6f),
                            accent.copy(alpha = 0.12f + 0.4f * glow),
                        ),
                    ),
                ),
                shape,
            )
            .padding(padding),
        content = content,
    )
}

/**
 * Interruptor del acento.
 *
 * Al encenderse, la pista se llena con el degradado del tema y el pomo cruza
 * con un muelle —no con una curva lineal— para que el gesto acabe con un
 * pequeño asentamiento en vez de frenar en seco. Encendido, además, la pista
 * proyecta su propio halo.
 */
@Composable
fun GlowingSwitch(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(15.dp)
    // Un muelle medio: llega rápido y se asienta sin rebotar de más.
    val knob by animateDpAsState(
        targetValue = if (checked) 21.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "knob",
    )
    val trackGlow by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(260, easing = Swift),
        label = "trackGlow",
    )

    Box(
        modifier
            .size(50.dp, 29.dp)
            .shadow(
                elevation = 10.dp * trackGlow,
                shape = shape,
                clip = false,
                ambientColor = skin.a1,
                spotColor = skin.a1,
            )
            .clip(shape)
            .drawBehind {
                // Apagado: pista neutra. Encendido: el degradado del acento
                // por encima, con la opacidad animada para que el color entre
                // a la vez que se mueve el pomo.
                drawRect(P.ink.copy(alpha = 0.13f))
                if (trackGlow > 0f) {
                    drawRect(accentGradient(skin, 145f, size), alpha = trackGlow)
                }
            }
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(3.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .size(23.dp)
                .shadow(3.dp, CircleShape, clip = false, ambientColor = P.shade, spotColor = P.shade)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * Campo de texto de cristal que se enciende al recibir el foco.
 *
 * El foco no se pinta con un borde más grueso —que descuadra el alto— sino
 * con color y halo: el filo pasa a acento y la caja proyecta un resplandor
 * corto. Así se ve cuál de los seis campos de credenciales está activo sin
 * que la lista dé un salto.
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    textSize: Float = 11f,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    val skin = LocalSkin.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    val border by animateColorAsState(
        targetValue = if (focused) skin.a1 else P.ink.copy(alpha = 0.12f),
        animationSpec = tween(200, easing = Swift),
        label = "fieldBorder",
    )
    val halo by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(220, easing = Swift),
        label = "fieldHalo",
    )

    Column(modifier.fillMaxWidth()) {
        label?.let {
            ElyText(
                it,
                size = 8.5f,
                weight = FontWeight.SemiBold,
                color = if (focused) skin.a2 else P.ink2,
                letterSpacing = tracking(0.08f),
            )
            Spacer(Modifier.height(4.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp * halo,
                    shape = shape,
                    clip = false,
                    ambientColor = skin.a1,
                    spotColor = skin.a1,
                )
                .clip(shape)
                .background(P.paper.copy(alpha = if (P.isDark) 0.42f else 0.68f))
                .border(1.dp, border, shape)
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    textStyle = inputStyle(textSize),
                    cursorBrush = SolidColor(skin.a2),
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    interactionSource = interaction,
                    modifier = Modifier.fillMaxWidth(),
                )
                // El marcador va debajo del campo y solo cuando está vacío:
                // `BasicTextField` no trae ninguno.
                if (value.isEmpty() && placeholder != null) {
                    ElyText(placeholder, size = textSize, color = P.ink2.copy(alpha = 0.6f), maxLines = 1)
                }
            }
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
        }
    }
}

/**
 * Cápsula de pestañas con pastilla deslizante.
 *
 * La pastilla no es un fondo por pestaña que se enciende y se apaga: es una
 * sola, que se mueve. Por eso el cambio se lee como un objeto que viaja y no
 * como dos luces parpadeando. Su sitio sale de medir la cápsula
 * ([BoxWithConstraints]) y repartirla entre las pestañas.
 */
@Composable
fun GlassTabBar(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(height / 2)
    if (tabs.isEmpty()) return

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .shadow(8.dp, shape, clip = false, ambientColor = P.shade, spotColor = P.shade)
            .clip(shape)
            .background(P.paper.copy(alpha = if (P.isDark) 0.4f else 0.66f))
            .border(1.dp, Color.White.copy(alpha = 0.5f), shape),
    ) {
        val inset = 4.dp
        val slot = (maxWidth - inset * 2) / tabs.size
        // Un solo valor animado gobierna la pastilla: su posición en "slots".
        val position by animateFloatAsState(
            targetValue = selected.toFloat(),
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "tabPill",
        )

        Box(
            Modifier
                .padding(inset)
                .offset(x = slot * position)
                .width(slot)
                .fillMaxSize()
                .shadow(10.dp, shape, clip = false, ambientColor = skin.a1, spotColor = skin.a1)
                .clip(shape)
                .drawBehind { drawRect(accentGradient(skin, 145f, size)) },
        )

        Row(Modifier.fillMaxSize().padding(inset)) {
            tabs.forEachIndexed { index, label ->
                val active = index == selected
                Box(
                    Modifier
                        .width(slot)
                        .fillMaxSize()
                        .clip(shape)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    ElyText(
                        label,
                        size = 12f,
                        weight = if (active) FontWeight.Bold else FontWeight.Medium,
                        // Sobre la pastilla el rótulo va en blanco; fuera, en tinta.
                        color = if (active) Color.White else P.ink2,
                        letterSpacing = tracking(0.04f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Casilla de selección de una tarjeta: marco, marca y el latido al encenderse.
 *
 * El pulso es un `Animatable` que se dispara al cambiar [checked] y vuelve
 * solo: sube a 1 y baja, sin dejar estado que limpiar después.
 */
@Composable
fun GlowCheck(checked: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(size / 3)
    val fill by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "check",
    )

    Box(
        modifier
            .size(size)
            .shadow(9.dp * fill, shape, clip = false, ambientColor = skin.a1, spotColor = skin.a1)
            .clip(shape)
            .drawBehind {
                drawRect(P.ink.copy(alpha = 0.07f))
                if (fill > 0f) drawRect(accentGradient(skin, 145f, this.size), alpha = fill)
            }
            .border(1.dp, if (checked) Color.White.copy(alpha = 0.55f) else P.ink.copy(alpha = 0.12f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (fill > 0.05f) {
            // La marca entra escalando con el relleno: aparece "dentro" de él.
            ElyText(
                "✓",
                size = 12f,
                weight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.graphicsLayer {
                    scaleX = fill
                    scaleY = fill
                    alpha = fill
                },
            )
        }
    }
}

/**
 * Botón de una acción destructiva.
 *
 * Mismo cuerpo que el de acento, pero en rojo y con halo propio: el color no
 * es decoración, es la señal de que lo que hay detrás no se deshace. Se usa
 * en el diálogo de confirmación de borrado.
 */
@Composable
fun DangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Float = 12.5f,
    cornerRadius: Dp = 15.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dangerPress",
    )
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .height(42.dp)
            // El halo es rojo y no gris: el botón se anuncia antes de leerse.
            .shadow(14.dp, shape, clip = false, ambientColor = P.red, spotColor = P.red)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(P.red.copy(alpha = 0.94f), P.red)))
            .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ElyText(label, size = fontSize, weight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}
