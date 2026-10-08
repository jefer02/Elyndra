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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpRect
import com.elyndra.launcher.ui.meridian.BubbleSlot
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.elyndra.launcher.ui.theme.shapeClickable
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
 *
 * Con [region] (Meridian) el bocadillo no sale de esa zona —la columna de la
 * rueda— ni pisa [obstacles] (el dock, el orden): ver [BubbleSlot]. Con
 * [oneLine] (ventanas bajas) es una sola línea con "Ocultar" al lado.
 * [onPlaced] recibe su rectángulo en la ventana (null al irse): la rueda le
 * deja sitio.
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
    /** Debajo del botón, alineado a su izquierda (el botón está fijo en la barra), en vez de al lado. */
    below: Boolean = false,
    region: DpRect? = null,
    obstacles: List<DpRect> = emptyList(),
    oneLine: Boolean = false,
    onPlaced: ((Rect?) -> Unit)? = null,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(if (oneLine) 14.dp else 16.dp)
    if (onPlaced != null) {
        val placed by rememberUpdatedState(onPlaced)
        DisposableEffect(Unit) { onDispose { placed(null) } }
    }
    val slot = region?.let { bounds ->
        Modifier.layout { measurable, constraints ->
            val margin = 8.dp.roundToPx().toFloat()
            val gap = 8.dp.roundToPx().toFloat()
            fun area(r: DpRect) = BubbleSlot.Area(r.left.toPx(), r.top.toPx(), r.right.toPx(), r.bottom.toPx())
            val box = area(bounds)
            val maxW = BubbleSlot.maxWidth(box.width, margin, MAX_WIDTH.toPx()).toInt().coerceAtLeast(1)
            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxW, minHeight = 0))
            val anchor = BubbleSlot.Area(anchorX.toPx(), anchorY.toPx(), (anchorX + anchorSize).toPx(), (anchorY + anchorSize).toPx())
            val (x, y) = BubbleSlot.place(anchor, placeable.width.toFloat(), placeable.height.toFloat(), box, obstacles.map(::area), gap, margin)
            layout(placeable.width, placeable.height) { placeable.place(x.roundToInt(), y.roundToInt()) }
        }
    }
    Column(
        (slot ?: Modifier
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
                val room = when {
                    below -> screenW - left - margin
                    onRightHalf -> left - gap - margin
                    else -> screenW - (left + size + gap) - margin
                }
                val maxW = min(MAX_WIDTH.roundToPx(), room.coerceAtLeast(MIN_WIDTH.roundToPx()))
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxW, minHeight = 0))
                val x = when {
                    below -> left
                    onRightHalf -> left - gap - placeable.width
                    else -> left + size + gap
                }
                val y = if (below) top + size + gap else top + size / 2 - placeable.height / 2
                layout(placeable.width, placeable.height) {
                    placeable.place(
                        x.coerceIn(margin, (screenW - placeable.width - margin).coerceAtLeast(margin)),
                        y.coerceIn(margin, (screenH - placeable.height - margin).coerceAtLeast(margin)),
                    )
                }
            })
            .then(if (onPlaced != null) Modifier.onGloballyPositioned { onPlaced(it.boundsInWindow()) } else Modifier)
            .animPopIn(420, key = key)
            .glass(shape, borderColor = skin.a1.copy(alpha = 0.35f))
            .drawBehind { drawRect(skin.a1, size = Size(2.dp.toPx(), size.height)) }
            .shapeClickable(shape, onClick = onTap)
            .padding(start = 13.dp, end = if (oneLine) 6.dp else 11.dp, top = if (oneLine) 6.dp else 10.dp, bottom = if (oneLine) 6.dp else 9.dp),
    ) {
        if (oneLine) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ElyText(text, size = 11.5f, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                DismissChip(onDismiss)
            }
            return@Column
        }
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
            DismissChip(onDismiss)
        }
    }
}

@Composable
private fun DismissChip(onDismiss: () -> Unit) {
    ElyText(
        stringResource(R.string.masha_insight_dismiss),
        size = 9f,
        weight = FontWeight.SemiBold,
        color = P.ink2,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(P.ink.copy(alpha = 0.06f))
            .shapeClickable(RoundedCornerShape(8.dp), onClick = onDismiss)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private val MAX_WIDTH = 250.dp
private val MIN_WIDTH = 150.dp
