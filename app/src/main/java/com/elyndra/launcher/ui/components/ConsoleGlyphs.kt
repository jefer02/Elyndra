package com.elyndra.launcher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glifos de la interfaz de consola (categorías de Ajustes, acciones de fila,
 * estado vacío). Misma familia que [SheetGlyph]: trazo de 1,6 dp con remates
 * redondos, dibujados en coordenadas 0…1 sobre una caja cuadrada.
 */
enum class ConsoleGlyph {
    Display, Sound, Music, Appearance, Library, Metadata, Masha, About,
    Play, Folder, Reset, Handle, Chevron, Check, Plus, Close, Search, Gamepad,
}

@Composable
fun ConsoleGlyphIcon(glyph: ConsoleGlyph, color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Box(modifier.size(size).drawBehind { drawConsoleGlyph(glyph, color) })
}

fun DrawScope.drawConsoleGlyph(glyph: ConsoleGlyph, color: Color) {
    val w = size.width
    val h = size.height
    val t = (1.6.dp.toPx() * (w / 18.dp.toPx()).coerceIn(0.8f, 2f))
    val stroke = Stroke(width = t, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(w * x1, h * y1), Offset(w * x2, h * y2), t, cap = StrokeCap.Round)

    fun box(x: Float, y: Float, rw: Float, rh: Float, radius: Float = 0.10f) = drawRoundRect(
        color = color,
        topLeft = Offset(w * x, h * y),
        size = Size(w * rw, h * rh),
        cornerRadius = CornerRadius(w * radius),
        style = stroke,
    )

    fun path(fill: Boolean = false, build: Path.() -> Unit) {
        val p = Path().apply(build)
        if (fill) drawPath(p, color) else drawPath(p, color, style = stroke)
    }

    when (glyph) {
        // Pantalla con su peana.
        ConsoleGlyph.Display -> {
            box(0.08f, 0.16f, 0.84f, 0.54f, radius = 0.08f)
            line(0.50f, 0.70f, 0.50f, 0.84f)
            line(0.32f, 0.86f, 0.68f, 0.86f)
        }
        // Altavoz con dos ondas.
        ConsoleGlyph.Sound -> {
            path {
                moveTo(w * 0.12f, h * 0.40f)
                lineTo(w * 0.28f, h * 0.40f)
                lineTo(w * 0.48f, h * 0.22f)
                lineTo(w * 0.48f, h * 0.78f)
                lineTo(w * 0.28f, h * 0.60f)
                lineTo(w * 0.12f, h * 0.60f)
                close()
            }
            drawArc(color, -45f, 90f, false, Offset(w * 0.46f, h * 0.34f), Size(w * 0.24f, h * 0.32f), style = stroke)
            drawArc(color, -50f, 100f, false, Offset(w * 0.46f, h * 0.20f), Size(w * 0.42f, h * 0.60f), style = stroke)
        }
        // Corchea doble.
        ConsoleGlyph.Music -> {
            line(0.38f, 0.20f, 0.38f, 0.70f)
            line(0.80f, 0.14f, 0.80f, 0.62f)
            line(0.38f, 0.20f, 0.80f, 0.14f)
            drawCircle(color, w * 0.11f, Offset(w * 0.28f, h * 0.72f))
            drawCircle(color, w * 0.11f, Offset(w * 0.70f, h * 0.64f))
        }
        // Gota con su brillo: color.
        ConsoleGlyph.Appearance -> {
            path {
                moveTo(w * 0.50f, h * 0.10f)
                cubicTo(w * 0.70f, h * 0.36f, w * 0.80f, h * 0.48f, w * 0.80f, h * 0.60f)
                cubicTo(w * 0.80f, h * 0.78f, w * 0.66f, h * 0.90f, w * 0.50f, h * 0.90f)
                cubicTo(w * 0.34f, h * 0.90f, w * 0.20f, h * 0.78f, w * 0.20f, h * 0.60f)
                cubicTo(w * 0.20f, h * 0.48f, w * 0.30f, h * 0.36f, w * 0.50f, h * 0.10f)
                close()
            }
            line(0.36f, 0.62f, 0.40f, 0.72f)
        }
        // Tres cards en su estante.
        ConsoleGlyph.Library -> {
            box(0.10f, 0.18f, 0.22f, 0.54f, radius = 0.05f)
            box(0.39f, 0.18f, 0.22f, 0.54f, radius = 0.05f)
            box(0.68f, 0.18f, 0.22f, 0.54f, radius = 0.05f)
            line(0.08f, 0.86f, 0.92f, 0.86f)
        }
        // Nube con la flecha de bajada: de ahí llegan los datos.
        ConsoleGlyph.Metadata -> {
            path {
                moveTo(w * 0.26f, h * 0.66f)
                cubicTo(w * 0.02f, h * 0.66f, w * 0.06f, h * 0.34f, w * 0.28f, h * 0.36f)
                cubicTo(w * 0.34f, h * 0.12f, w * 0.72f, h * 0.14f, w * 0.72f, h * 0.40f)
                cubicTo(w * 0.96f, h * 0.40f, w * 0.96f, h * 0.66f, w * 0.74f, h * 0.66f)
            }
            line(0.50f, 0.46f, 0.50f, 0.86f)
            line(0.40f, 0.76f, 0.50f, 0.86f)
            line(0.60f, 0.76f, 0.50f, 0.86f)
        }
        // Destello de cuatro puntas: la presencia de Masha.
        ConsoleGlyph.Masha -> {
            path {
                moveTo(w * 0.50f, h * 0.08f)
                quadraticTo(w * 0.56f, h * 0.44f, w * 0.92f, h * 0.50f)
                quadraticTo(w * 0.56f, h * 0.56f, w * 0.50f, h * 0.92f)
                quadraticTo(w * 0.44f, h * 0.56f, w * 0.08f, h * 0.50f)
                quadraticTo(w * 0.44f, h * 0.44f, w * 0.50f, h * 0.08f)
                close()
            }
        }
        // "i" en su círculo.
        ConsoleGlyph.About -> {
            drawCircle(color, w * 0.38f, Offset(w * 0.5f, h * 0.5f), style = stroke)
            drawCircle(color, t * 0.65f, Offset(w * 0.5f, h * 0.32f))
            line(0.50f, 0.45f, 0.50f, 0.70f)
        }
        ConsoleGlyph.Play -> path(fill = true) {
            moveTo(w * 0.30f, h * 0.20f)
            lineTo(w * 0.80f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.80f)
            close()
        }
        ConsoleGlyph.Folder -> path {
            moveTo(w * 0.10f, h * 0.78f)
            lineTo(w * 0.10f, h * 0.24f)
            lineTo(w * 0.40f, h * 0.24f)
            lineTo(w * 0.48f, h * 0.36f)
            lineTo(w * 0.90f, h * 0.36f)
            lineTo(w * 0.90f, h * 0.78f)
            close()
        }
        // Flecha que vuelve: restablecer.
        ConsoleGlyph.Reset -> {
            drawArc(color, 200f, 260f, false, Offset(w * 0.18f, h * 0.18f), Size(w * 0.64f, h * 0.64f), style = stroke)
            path(fill = true) {
                moveTo(w * 0.10f, h * 0.30f)
                lineTo(w * 0.34f, h * 0.30f)
                lineTo(w * 0.20f, h * 0.52f)
                close()
            }
        }
        // Asa de arrastre: dos columnas de tres puntos.
        ConsoleGlyph.Handle -> {
            for (x in listOf(0.38f, 0.62f)) for (y in listOf(0.28f, 0.50f, 0.72f)) {
                drawCircle(color, t * 0.85f, Offset(w * x, h * y))
            }
        }
        // Galón hacia abajo (se gira para otras direcciones).
        ConsoleGlyph.Chevron -> path {
            moveTo(w * 0.24f, h * 0.38f)
            lineTo(w * 0.50f, h * 0.64f)
            lineTo(w * 0.76f, h * 0.38f)
        }
        ConsoleGlyph.Check -> path {
            moveTo(w * 0.20f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.72f)
            lineTo(w * 0.80f, h * 0.30f)
        }
        ConsoleGlyph.Plus -> {
            line(0.50f, 0.20f, 0.50f, 0.80f)
            line(0.20f, 0.50f, 0.80f, 0.50f)
        }
        ConsoleGlyph.Close -> {
            line(0.26f, 0.26f, 0.74f, 0.74f)
            line(0.74f, 0.26f, 0.26f, 0.74f)
        }
        ConsoleGlyph.Search -> {
            drawCircle(color, w * 0.26f, Offset(w * 0.44f, h * 0.44f), style = stroke)
            line(0.63f, 0.63f, 0.84f, 0.84f)
        }
        // Mando: cuerpo con asas, cruceta y dos botones.
        ConsoleGlyph.Gamepad -> {
            path {
                moveTo(w * 0.24f, h * 0.30f)
                lineTo(w * 0.76f, h * 0.30f)
                cubicTo(w * 0.92f, h * 0.30f, w * 0.98f, h * 0.72f, w * 0.86f, h * 0.76f)
                cubicTo(w * 0.76f, h * 0.80f, w * 0.70f, h * 0.62f, w * 0.62f, h * 0.62f)
                lineTo(w * 0.38f, h * 0.62f)
                cubicTo(w * 0.30f, h * 0.62f, w * 0.24f, h * 0.80f, w * 0.14f, h * 0.76f)
                cubicTo(w * 0.02f, h * 0.72f, w * 0.08f, h * 0.30f, w * 0.24f, h * 0.30f)
                close()
            }
            line(0.24f, 0.46f, 0.36f, 0.46f)
            line(0.30f, 0.40f, 0.30f, 0.52f)
            drawCircle(color, t * 0.75f, Offset(w * 0.66f, h * 0.42f))
            drawCircle(color, t * 0.75f, Offset(w * 0.74f, h * 0.50f))
        }
    }
}
