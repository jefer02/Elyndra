package com.elyndra.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
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
 * Con [region] el bocadillo no tapa su botón ni [obstacles] (el dock, el
 * bloque del hero): ver [BubbleSlot]. Con [group] (Meridian: el grupo de
 * arriba a la derecha, con Masha la primera) va debajo del grupo, dentro de
 * [region] (el arte), con la línea entera en una fila ([BubbleSlot.underGroup]);
 * si no hay sitio que no tape nada, no se enseña.
 * Con [oneLine] el texto va en una fila con la X al lado; si no le cabe, se
 * parte en líneas ([ONE_LINE_WRAP]) con "Ahora no" debajo en vez de cortarse.
 * [onPlaced] recibe su rectángulo en la ventana (null al irse).
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
    group: DpRect? = null,
    oneLine: Boolean = false,
    onPlaced: ((Rect?) -> Unit)? = null,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(if (oneLine) 14.dp else 16.dp)
    // En una línea cabe más texto a lo ancho sin crecer en alto; bajo el grupo de arriba, más aún.
    val maxWidth = when {
        group != null -> MAX_WIDTH_GROUP
        oneLine -> MAX_WIDTH_ONE_LINE
        else -> MAX_WIDTH
    }
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
            val anchor = BubbleSlot.Area(anchorX.toPx(), anchorY.toPx(), (anchorX + anchorSize).toPx(), (anchorY + anchorSize).toPx())
            // Su ancho natural (el texto en una fila) y su alto para cada ancho, si se parte.
            val natural = measurable.maxIntrinsicWidth(Constraints.Infinity).toFloat()
            val heightFor = { w: Float -> measurable.minIntrinsicHeight(w.toInt().coerceAtLeast(1)).toFloat() }
            val s = if (group != null) {
                BubbleSlot.underGroup(
                    anchor = anchor,
                    group = area(group),
                    natural = natural,
                    maxWidth = maxWidth.toPx(),
                    heightFor = heightFor,
                    bounds = box,
                    obstacles = obstacles.map(::area),
                    gap = gap,
                    margin = margin,
                    minWidth = MIN_WIDTH.toPx(),
                )
            } else {
                BubbleSlot.choose(anchor, natural, BubbleSlot.maxWidth(box.width, margin, maxWidth.toPx()), heightFor, box, obstacles.map(::area), gap, margin)
            }
            // Sin sitio que no tape nada (ver BubbleSlot.Slot.clear): no se enseña.
            if (!s.clear) return@layout layout(0, 0) {}
            val placeable = measurable.measure(Constraints(maxWidth = s.width.toInt().coerceAtLeast(1), maxHeight = constraints.maxHeight))
            layout(placeable.width, placeable.height) { placeable.place(s.x.roundToInt(), s.y.roundToInt()) }
        }
    }
    val outer = (slot ?: Modifier
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
                val maxW = min(maxWidth.roundToPx(), room.coerceAtLeast(MIN_WIDTH.roundToPx()))
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
    val row = remember { InsightRow() }
    // La X va encima del cuerpo, fuera de su recorte: así se toca en 48 × 48 aunque el bocadillo mida ~30 de alto.
    BubbleWithClose(outer, row, close = if (oneLine) ({ CloseButton(onDismiss) }) else null) {
    Column(
        Modifier
            // Base opaca: el bocadillo flota sobre el titular del hero y, transparente, se mezclaban las letras.
            .glass(shape, borderColor = skin.a1.copy(alpha = 0.35f), solid = true)
            .drawBehind { drawRect(skin.a1, size = Size(2.dp.toPx(), size.height)) }
            .shapeClickable(shape, onClick = onTap)
            .padding(start = 13.dp, end = if (oneLine) ONE_LINE_END else 11.dp, top = if (oneLine) 6.dp else 10.dp, bottom = if (oneLine) 6.dp else 9.dp),
    ) {
        if (oneLine) {
            InsightLine(
                row = row,
                text = { ElyText(text, size = 11.5f, color = P.ink, maxLines = ONE_LINE_WRAP, overflow = TextOverflow.Ellipsis) },
                chip = { DismissChip(onDismiss) },
            )
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
}

/** Lo que [InsightLine] decide al medirse y lee [BubbleWithClose] justo después: si la línea va en una fila (con la X). */
private class InsightRow {
    var inRow = false
}

/**
 * La línea de Masha: en una fila con la X al lado si cabe (en Meridian,
 * bajo el grupo de arriba, sobre el arte); si no (ventana estrecha, letra
 * grande), el texto usa todo el ancho y se parte, y "Ahora no", con su
 * texto, baja debajo, a la derecha.
 *
 * En la fila, la X ocupa 22 dp en vez de los ~58 de "Ahora no": la línea
 * entera necesita ~36 dp menos para ir en una fila. Aquí solo se le guarda el
 * sitio ([CLOSE_SIZE]): la X de verdad la pone [BubbleWithClose] encima,
 * fuera del recorte del bocadillo.
 *
 * El texto se mide una sola vez, con todo el ancho: si así no deja sitio a la
 * X, es que no caben en una fila.
 */
@Composable
private fun InsightLine(row: InsightRow, text: @Composable () -> Unit, chip: @Composable () -> Unit) {
    Layout(
        content = {
            text()
            chip()
            Spacer(Modifier.size(CLOSE_SIZE))
        },
        measurePolicy = remember(row) { InsightLinePolicy(row) },
    )
}

private class InsightLinePolicy(private val row: InsightRow) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = INSIGHT_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val textP = measurables[0].measure(loose)
        val chipP = measurables[1].measure(loose)
        val closeP = measurables[2].measure(loose)
        val inRow = textP.width + gap + closeP.width <= constraints.maxWidth
        row.inRow = inRow
        val width = if (inRow) textP.width + gap + closeP.width else maxOf(textP.width, chipP.width)
        val height = if (inRow) maxOf(textP.height, closeP.height) else textP.height + gap / 2 + chipP.height
        return layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            if (inRow) {
                // El sitio de la X (la pinta BubbleWithClose); "Ahora no" no se coloca.
                textP.place(0, (height - textP.height) / 2)
                closeP.place(textP.width + gap, (height - closeP.height) / 2)
            } else {
                textP.place(0, 0)
                chipP.place(width - chipP.width, textP.height + gap / 2)
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables[0].maxIntrinsicWidth(height) + INSIGHT_GAP.roundToPx() + CLOSE_SIZE.roundToPx()

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        maxOf(measurables[0].minIntrinsicWidth(height), measurables[1].maxIntrinsicWidth(height))

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val gap = INSIGHT_GAP.roundToPx()
        val close = CLOSE_SIZE.roundToPx()
        val textW = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        if (textW + gap + close <= width) return maxOf(measurables[0].minIntrinsicHeight(width), close)
        val chipW = measurables[1].maxIntrinsicWidth(Constraints.Infinity)
        return measurables[0].minIntrinsicHeight(width) + gap / 2 + measurables[1].minIntrinsicHeight(chipW)
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        minIntrinsicHeight(measurables, width)
}

/**
 * El bocadillo ([body], que recorta) con la X ([close]) encima, en el sitio
 * que le guarda [InsightLine], solo si la línea fue en una fila. Fuera del
 * recorte, la X se toca en 48 × 48 aunque se vea de [CLOSE_SIZE]. Mide lo
 * que el cuerpo (también para el sitio del bocadillo, que pregunta su ancho
 * natural y su alto).
 */
@Composable
private fun BubbleWithClose(modifier: Modifier, row: InsightRow, close: (@Composable () -> Unit)?, body: @Composable () -> Unit) {
    Layout(
        content = {
            body()
            close?.invoke()
        },
        modifier = modifier,
        measurePolicy = remember(row) { BubbleWithClosePolicy(row) },
    )
}

private class BubbleWithClosePolicy(private val row: InsightRow) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val bodyP = measurables[0].measure(constraints)
        val closeP = measurables.getOrNull(1)?.measure(Constraints())
        return layout(bodyP.width, bodyP.height) {
            bodyP.place(0, 0)
            if (closeP != null && row.inRow) {
                closeP.place(bodyP.width - ONE_LINE_END.roundToPx() - closeP.width, (bodyP.height - closeP.height) / 2)
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = measurables[0].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = measurables[0].minIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = measurables[0].minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = measurables[0].maxIntrinsicHeight(width)
}

/** La X del bocadillo de una línea: lo mismo que "Ahora no", en [CLOSE_SIZE] y tocable en 48 × 48. */
@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    val description = stringResource(R.string.masha_insight_dismiss)
    val ink = P.ink2
    Box(
        Modifier
            .touchTarget(
                Modifier
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = description },
            )
            .size(CLOSE_SIZE)
            .clip(CircleShape)
            .background(P.ink.copy(alpha = 0.06f))
            .drawBehind {
                val r = size.minDimension * 0.2f
                val c = center
                val stroke = 1.5.dp.toPx()
                drawLine(ink, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), stroke, StrokeCap.Round)
                drawLine(ink, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), stroke, StrokeCap.Round)
            },
    )
}

/** La X: algo menor que el alto de la línea, para que el bocadillo no crezca. */
private val CLOSE_SIZE = 22.dp

/** Aire a la derecha del bocadillo de una línea (también el de la X). */
private val ONE_LINE_END = 6.dp

private val INSIGHT_GAP = 8.dp

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
private val MAX_WIDTH_ONE_LINE = 380.dp

/** Bajo el grupo de arriba de Meridian, sobre el arte: la línea entera en una fila (en español mide ~430 dp). */
private val MAX_WIDTH_GROUP = 560.dp

/** Las líneas que puede ocupar el bocadillo "de una línea" cuando no le cabe en una fila (lo normal, dos). */
private const val ONE_LINE_WRAP = 3
private val MIN_WIDTH = 150.dp
