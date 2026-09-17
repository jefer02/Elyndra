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
import com.elyndra.launcher.ui.SheetIcon

/* ─────────────────────────────────────────────────────────────
   Glifos del menú de pulsación larga.

   Dibujados con Canvas, como el resto de la iconografía de Elyndra
   (la lupa de la barra, el galón de atrás). Así no entra una
   librería de iconos entera por trece símbolos, cada uno se tiñe
   con el acento del tema y todos comparten grosor de trazo: el menú
   se lee como una familia y no como trece iconos de sitios
   distintos.

   Se dibujan en coordenadas 0…1 sobre una caja cuadrada, así que el
   tamaño lo pone quien los coloca.
   ───────────────────────────────────────────────────────────── */

@Composable
fun SheetGlyph(icon: SheetIcon, color: Color, size: Dp = 16.dp) {
    Box(Modifier.size(size).drawBehind { drawSheetGlyph(icon, color) })
}

/** Galón de "abre otra hoja", al final de la fila. */
@Composable
fun SheetChevron(color: Color, size: Dp = 12.dp) {
    Box(
        Modifier.size(size).drawBehind {
            val w = this.size.width
            val h = this.size.height
            val t = 1.5.dp.toPx()
            drawLine(color, Offset(w * 0.38f, h * 0.24f), Offset(w * 0.64f, h * 0.5f), t, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.64f, h * 0.5f), Offset(w * 0.38f, h * 0.76f), t, cap = StrokeCap.Round)
        },
    )
}

private fun DrawScope.drawSheetGlyph(icon: SheetIcon, color: Color) {
    val w = size.width
    val h = size.height
    val t = 1.6.dp.toPx()
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

    fun shape(style: Stroke?, build: Path.() -> Unit) {
        val path = Path().apply(build)
        if (style == null) drawPath(path, color) else drawPath(path, color, style = style)
    }

    when (icon) {
        // Triángulo de "reproducir", relleno: es la acción principal.
        SheetIcon.Play -> shape(null) {
            moveTo(w * 0.30f, h * 0.20f)
            lineTo(w * 0.80f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.80f)
            close()
        }

        // Ficha: una "i" dentro de un círculo.
        SheetIcon.Details -> {
            drawCircle(color, radius = w * 0.38f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
            drawCircle(color, radius = t * 0.6f, center = Offset(w * 0.5f, h * 0.32f))
            line(0.5f, 0.45f, 0.5f, 0.70f)
        }

        // Mando: cuerpo redondeado con cruceta y dos botones.
        SheetIcon.Emulator -> {
            box(0.08f, 0.28f, 0.84f, 0.44f, radius = 0.22f)
            line(0.26f, 0.50f, 0.38f, 0.50f)
            line(0.32f, 0.42f, 0.32f, 0.58f)
            drawCircle(color, radius = t * 0.75f, center = Offset(w * 0.66f, h * 0.44f))
            drawCircle(color, radius = t * 0.75f, center = Offset(w * 0.74f, h * 0.58f))
        }

        // Carpeta con una línea dentro: volver a analizarla.
        SheetIcon.Rescan -> {
            shape(stroke) {
                moveTo(w * 0.10f, h * 0.78f)
                lineTo(w * 0.10f, h * 0.26f)
                lineTo(w * 0.40f, h * 0.26f)
                lineTo(w * 0.48f, h * 0.38f)
                lineTo(w * 0.90f, h * 0.38f)
                lineTo(w * 0.90f, h * 0.78f)
                close()
            }
            line(0.28f, 0.58f, 0.72f, 0.58f)
        }

        // Flecha circular: actualizar metadatos.
        SheetIcon.Refresh -> {
            drawArc(
                color = color,
                startAngle = 40f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(w * 0.14f, h * 0.14f),
                size = Size(w * 0.72f, h * 0.72f),
                style = stroke,
            )
            shape(null) {
                moveTo(w * 0.90f, h * 0.34f)
                lineTo(w * 0.70f, h * 0.42f)
                lineTo(w * 0.88f, h * 0.58f)
                close()
            }
        }

        // Carátula: rectángulo vertical con el lomo marcado.
        SheetIcon.Cover -> {
            box(0.22f, 0.12f, 0.56f, 0.76f, radius = 0.08f)
            line(0.34f, 0.12f, 0.34f, 0.88f)
        }

        // Fondo: paisaje — marco apaisado con sol y monte.
        SheetIcon.Background -> {
            box(0.08f, 0.22f, 0.84f, 0.56f, radius = 0.10f)
            drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.32f, h * 0.40f))
            shape(stroke) {
                moveTo(w * 0.16f, h * 0.72f)
                lineTo(w * 0.44f, h * 0.48f)
                lineTo(w * 0.62f, h * 0.62f)
                lineTo(w * 0.76f, h * 0.52f)
                lineTo(w * 0.88f, h * 0.66f)
            }
        }

        // Logo: rótulo ancho y bajo, como los wheels.
        SheetIcon.Logo -> {
            box(0.06f, 0.30f, 0.88f, 0.40f, radius = 0.14f)
            line(0.20f, 0.50f, 0.46f, 0.50f)
            line(0.56f, 0.50f, 0.80f, 0.50f)
        }

        // Icono: cuadrado redondeado, como el de una app.
        SheetIcon.Icon -> {
            box(0.16f, 0.16f, 0.68f, 0.68f, radius = 0.20f)
            drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.5f))
        }

        // Galería: una foto con su montaña, y otra detrás.
        SheetIcon.Gallery -> {
            box(0.06f, 0.20f, 0.60f, 0.52f, radius = 0.10f)
            shape(stroke) {
                moveTo(w * 0.12f, h * 0.66f)
                lineTo(w * 0.28f, h * 0.46f)
                lineTo(w * 0.42f, h * 0.60f)
                lineTo(w * 0.52f, h * 0.52f)
            }
            line(0.78f, 0.34f, 0.78f, 0.82f)
            line(0.30f, 0.82f, 0.78f, 0.82f)
        }

        // Servicio: nube — de ahí bajan las imágenes.
        SheetIcon.Service -> shape(stroke) {
            moveTo(w * 0.26f, h * 0.70f)
            cubicTo(w * 0.02f, h * 0.70f, w * 0.06f, h * 0.38f, w * 0.28f, h * 0.40f)
            cubicTo(w * 0.34f, h * 0.16f, w * 0.72f, h * 0.18f, w * 0.72f, h * 0.44f)
            cubicTo(w * 0.96f, h * 0.44f, w * 0.96f, h * 0.70f, w * 0.74f, h * 0.70f)
            close()
        }

        // App instalada.
        SheetIcon.App -> {
            box(0.14f, 0.14f, 0.72f, 0.72f, radius = 0.18f)
            line(0.36f, 0.50f, 0.64f, 0.50f)
            line(0.50f, 0.36f, 0.50f, 0.64f)
        }

        // Quitar: papelera.
        SheetIcon.Remove -> {
            line(0.14f, 0.28f, 0.86f, 0.28f)
            line(0.40f, 0.20f, 0.60f, 0.20f)
            shape(stroke) {
                moveTo(w * 0.24f, h * 0.30f)
                lineTo(w * 0.30f, h * 0.84f)
                lineTo(w * 0.70f, h * 0.84f)
                lineTo(w * 0.76f, h * 0.30f)
            }
            line(0.44f, 0.44f, 0.46f, 0.70f)
            line(0.56f, 0.44f, 0.54f, 0.70f)
        }
    }
}
