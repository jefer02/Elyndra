package com.elyndra.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.animPopIn
import com.elyndra.launcher.ui.theme.glass
import kotlin.math.min

/**
 * La línea ambiental de Masha: un bocadillo junto a su botón flotante.
 *
 * Se coloca al lado del botón que tenga más sitio (el botón se puede arrastrar
 * a cualquier punto), centrado en su altura y sin salirse de la pantalla. Solo
 * el bocadillo recibe toques: el resto del carrusel sigue a mano.
 *
 * [anchorX]/[anchorY] son la esquina superior izquierda del botón y
 * [anchorSize] su lado, en el mismo sistema que [screen].
 */
@Composable
fun MashaInsightBubble(
    text: String,
    anchorX: Dp,
    anchorY: Dp,
    anchorSize: Dp,
    screen: DpSize,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    key: Any,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .layout { measurable, constraints ->
                val margin = 12.dp.roundToPx()
                val gap = 10.dp.roundToPx()
                val screenW = screen.width.roundToPx()
                val screenH = screen.height.roundToPx()
                val left = anchorX.roundToPx()
                val top = anchorY.roundToPx()
                val size = anchorSize.roundToPx()
                val onRightHalf = left + size / 2 > screenW / 2
                // Hacia el lado con sitio: el ancho disponible es lo que queda entre el botón y el borde.
                val room = if (onRightHalf) left - gap - margin else screenW - (left + size + gap) - margin
                val maxW = min(MAX_WIDTH.roundToPx(), room.coerceAtLeast(MIN_WIDTH.roundToPx()))
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxW, minHeight = 0))
                val x = if (onRightHalf) left - gap - placeable.width else left + size + gap
                val y = top + size / 2 - placeable.height / 2
                layout(placeable.width, placeable.height) {
                    placeable.place(
                        x.coerceIn(margin, (screenW - placeable.width - margin).coerceAtLeast(margin)),
                        y.coerceIn(margin, (screenH - placeable.height - margin).coerceAtLeast(margin)),
                    )
                }
            }
            .animPopIn(420, key = key)
            .glass(shape, borderColor = skin.a1.copy(alpha = 0.35f))
            .drawBehind { drawRect(skin.a1, size = Size(2.dp.toPx(), size.height)) }
            .clip(shape)
            .clickable(onClick = onTap)
            .padding(start = 13.dp, end = 11.dp, top = 10.dp, bottom = 9.dp),
    ) {
        ElyText(text, size = 11.5f, color = P.ink, lineHeightRatio = 1.45f, maxLines = 4)
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ElyText(
                stringResource(R.string.masha),
                size = 8.5f,
                weight = FontWeight.SemiBold,
                color = skin.a2,
                uppercase = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            ElyText(
                stringResource(R.string.masha_insight_dismiss),
                size = 9f,
                weight = FontWeight.SemiBold,
                color = P.ink2,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(P.ink.copy(alpha = 0.06f))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private val MAX_WIDTH = 250.dp
private val MIN_WIDTH = 150.dp
