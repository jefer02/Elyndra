package com.elyndra.launcher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.domain.library.DeviceGlyph
import com.elyndra.launcher.domain.library.SystemArtSpec

/* ─────────────────────────────────────────────────────────────
   El arte de serie de las carpetas de sistema (ver FolderArtResolver).

   Todo se dibuja aquí con Canvas: un degradado en el color del sistema
   y la silueta genérica de su tipo de aparato (DeviceGlyph), en blanco,
   con el nombre corto del sistema debajo. Son dibujos propios de
   Elyndra, sin logos ni fotos: no hay ningún archivo de terceros
   (THIRD_PARTY_NOTICES.md lo dice).

   Los aparatos se dibujan en coordenadas 0…1 sobre una caja cuadrada.
   ───────────────────────────────────────────────────────────── */

/** La card de una carpeta con el arte de serie de su sistema: el degradado, el aparato y [label] debajo. */
@Composable
fun SystemArtTile(spec: SystemArtSpec, label: String, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    BoxWithConstraints(
        modifier.drawBehind {
            drawRect(Brush.verticalGradient(listOf(Color(spec.top), Color(spec.bottom))))
            // Un brillo suave arriba a la izquierda, como el cristal del resto de cards.
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.2f, size.height * 0.1f),
                    radius = size.maxDimension * 0.7f,
                ),
                radius = size.maxDimension * 0.7f,
                center = Offset(size.width * 0.2f, size.height * 0.1f),
            )
        },
    ) {
        val side = minOf(maxWidth, maxHeight)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(side * 0.16f)) { drawDevice(spec.glyph) }
            }
            if (showLabel) {
                ElyText(
                    label,
                    size = (side.value * 0.13f).coerceIn(8f, 22f),
                    weight = FontWeight.Bold,
                    color = Color.White,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = side * 0.1f),
                )
            }
        }
    }
}

/**
 * Sin arte de serie: las carátulas de la carpeta. Una llena la card; dos van
 * lado a lado; tres o cuatro, en una rejilla de 2 × 2 (con tres, el último
 * hueco queda en el color de la carpeta).
 */
@Composable
fun FolderCollage(covers: List<String>, fallback: ArtFallback, modifier: Modifier = Modifier) {
    val cells = covers.take(4)
    @Composable
    fun cell(path: String?, m: Modifier) = ArtImage(
        path = path,
        fallback = fallback.colorOnly(),
        modifier = m,
        variant = ArtVariant.Square,
        contentScale = ContentScale.Crop,
        showTitle = false,
    )
    when (cells.size) {
        0 -> FallbackArt(fallback.colorOnly(), ArtVariant.Square, modifier, showIcon = false)
        1 -> cell(cells[0], modifier)
        2 -> Row(modifier) { cells.forEach { cell(it, Modifier.weight(1f).fillMaxSize()) } }
        else -> Column(modifier) {
            for (row in 0 until 2) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (col in 0 until 2) cell(cells.getOrNull(row * 2 + col), Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
}

/** La silueta de [glyph], en blanco, llenando el cuadrado mayor que cabe centrado. */
fun DrawScope.drawDevice(glyph: DeviceGlyph, color: Color = Color.White) {
    val side = minOf(size.width, size.height)
    val ox = (size.width - side) / 2f
    val oy = (size.height - side) / 2f
    val t = side * 0.045f
    val stroke = Stroke(width = t, cap = StrokeCap.Round)
    val soft = color.copy(alpha = 0.28f)

    fun p(x: Float, y: Float) = Offset(ox + x * side, oy + y * side)
    fun sz(w: Float, h: Float) = Size(w * side, h * side)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Boolean = false, c: Color = color) {
        if (fill) {
            drawRoundRect(c, p(x, y), sz(w, h), CornerRadius(r * side))
        } else {
            drawRoundRect(c, p(x, y), sz(w, h), CornerRadius(r * side), style = stroke)
        }
    }
    fun dot(x: Float, y: Float, r: Float, c: Color = color) = drawCircle(c, r * side, p(x, y))
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), t, cap = StrokeCap.Round)
    fun dpad(x: Float, y: Float, s: Float) {
        line(x - s, y, x + s, y)
        line(x, y - s, x, y + s)
    }

    when (glyph) {
        DeviceGlyph.Cartridge -> {
            // El cartucho asomando por arriba y la consola debajo.
            box(0.30f, 0.16f, 0.40f, 0.30f, 0.04f, fill = true, c = soft)
            box(0.30f, 0.16f, 0.40f, 0.30f, 0.04f)
            line(0.38f, 0.26f, 0.62f, 0.26f)
            box(0.10f, 0.40f, 0.80f, 0.40f, 0.08f)
            line(0.18f, 0.70f, 0.40f, 0.70f)
            dot(0.70f, 0.62f, 0.04f)
            dot(0.80f, 0.62f, 0.04f)
        }
        DeviceGlyph.Disc -> {
            box(0.08f, 0.52f, 0.84f, 0.28f, 0.06f)
            line(0.16f, 0.68f, 0.36f, 0.68f)
            dot(0.80f, 0.66f, 0.03f)
            // El disco, por encima.
            dot(0.50f, 0.32f, 0.22f, soft)
            drawCircle(color, 0.22f * side, p(0.50f, 0.32f), style = stroke)
            dot(0.50f, 0.32f, 0.05f)
        }
        DeviceGlyph.Handheld -> {
            box(0.24f, 0.06f, 0.52f, 0.88f, 0.08f)
            box(0.32f, 0.14f, 0.36f, 0.32f, 0.03f, fill = true, c = soft)
            box(0.32f, 0.14f, 0.36f, 0.32f, 0.03f)
            dpad(0.37f, 0.62f, 0.06f)
            dot(0.58f, 0.64f, 0.035f)
            dot(0.66f, 0.58f, 0.035f)
            line(0.44f, 0.80f, 0.56f, 0.80f)
        }
        DeviceGlyph.Widescreen -> {
            box(0.04f, 0.28f, 0.92f, 0.44f, 0.14f)
            box(0.28f, 0.34f, 0.44f, 0.32f, 0.03f, fill = true, c = soft)
            box(0.28f, 0.34f, 0.44f, 0.32f, 0.03f)
            dpad(0.16f, 0.50f, 0.05f)
            dot(0.81f, 0.46f, 0.03f)
            dot(0.86f, 0.54f, 0.03f)
        }
        DeviceGlyph.DualScreen -> {
            box(0.18f, 0.06f, 0.64f, 0.40f, 0.06f)
            box(0.26f, 0.12f, 0.48f, 0.28f, 0.02f, fill = true, c = soft)
            box(0.18f, 0.50f, 0.64f, 0.44f, 0.06f)
            box(0.34f, 0.56f, 0.32f, 0.26f, 0.02f, fill = true, c = soft)
            box(0.34f, 0.56f, 0.32f, 0.26f, 0.02f)
            dpad(0.255f, 0.66f, 0.04f)
            dot(0.75f, 0.64f, 0.025f)
            dot(0.75f, 0.72f, 0.025f)
        }
        DeviceGlyph.Hybrid -> {
            // La tableta y los dos mandos, un poco separados.
            box(0.04f, 0.26f, 0.16f, 0.48f, 0.07f)
            box(0.80f, 0.26f, 0.16f, 0.48f, 0.07f)
            box(0.22f, 0.26f, 0.56f, 0.48f, 0.03f)
            box(0.27f, 0.31f, 0.46f, 0.38f, 0.02f, fill = true, c = soft)
            dot(0.12f, 0.38f, 0.035f)
            dpad(0.12f, 0.58f, 0.035f)
            dot(0.88f, 0.40f, 0.025f)
            dot(0.88f, 0.60f, 0.035f)
        }
        DeviceGlyph.Goggles -> {
            box(0.10f, 0.20f, 0.80f, 0.34f, 0.12f)
            dot(0.34f, 0.37f, 0.09f, soft)
            dot(0.66f, 0.37f, 0.09f, soft)
            line(0.40f, 0.54f, 0.34f, 0.86f)
            line(0.60f, 0.54f, 0.66f, 0.86f)
            line(0.24f, 0.88f, 0.76f, 0.88f)
        }
        DeviceGlyph.Cabinet -> {
            box(0.24f, 0.04f, 0.52f, 0.92f, 0.04f)
            box(0.24f, 0.04f, 0.52f, 0.12f, 0.04f, fill = true, c = soft)
            box(0.32f, 0.22f, 0.36f, 0.28f, 0.02f, fill = true, c = soft)
            box(0.32f, 0.22f, 0.36f, 0.28f, 0.02f)
            line(0.24f, 0.60f, 0.76f, 0.60f)
            line(0.38f, 0.60f, 0.38f, 0.52f)
            dot(0.38f, 0.51f, 0.03f)
            dot(0.56f, 0.56f, 0.025f)
            dot(0.64f, 0.56f, 0.025f)
            line(0.40f, 0.80f, 0.60f, 0.80f)
        }
        DeviceGlyph.Computer -> {
            box(0.06f, 0.36f, 0.88f, 0.34f, 0.05f)
            for (row in 0 until 3) {
                val y = 0.45f + row * 0.08f
                for (k in 0 until 7) dot(0.18f + k * 0.11f, y, 0.022f)
            }
            line(0.30f, 0.63f, 0.70f, 0.63f)
            line(0.18f, 0.20f, 0.82f, 0.20f)
        }
        DeviceGlyph.Monitor -> {
            box(0.10f, 0.12f, 0.80f, 0.54f, 0.05f)
            box(0.16f, 0.18f, 0.68f, 0.42f, 0.02f, fill = true, c = soft)
            // Un indicador de línea de órdenes en la pantalla.
            line(0.24f, 0.32f, 0.32f, 0.38f)
            line(0.32f, 0.38f, 0.24f, 0.44f)
            line(0.38f, 0.46f, 0.50f, 0.46f)
            line(0.50f, 0.66f, 0.50f, 0.80f)
            line(0.30f, 0.82f, 0.70f, 0.82f)
        }
    }
}
