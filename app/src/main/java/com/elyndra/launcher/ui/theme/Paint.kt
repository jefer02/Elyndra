package com.elyndra.launcher.ui.theme

import com.elyndra.launcher.data.P

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/* ─────────────────────────────────────────────────────────────
   Degradados con la semántica de CSS.

   En CSS, `linear-gradient(θ, …)` mide θ en sentido horario desde
   "hacia arriba": 0deg sube, 90deg va a la derecha. Compose quiere
   dos puntos, así que hay que convertir — sin esto, cada degradado
   del diseño saldría girado.
   ───────────────────────────────────────────────────────────── */

/** Vector unitario de la línea de degradado para un ángulo CSS (eje Y hacia abajo). */
private fun cssDirection(angleDeg: Float): Offset {
    val r = Math.toRadians(angleDeg.toDouble())
    return Offset(sin(r).toFloat(), -cos(r).toFloat())
}

/** Longitud de la línea de degradado que cubre la caja entera, como en la especificación CSS. */
private fun cssLineLength(angleDeg: Float, size: Size): Float {
    val r = Math.toRadians(angleDeg.toDouble())
    return abs(size.width * sin(r)).toFloat() + abs(size.height * cos(r)).toFloat()
}

/** `linear-gradient(angleDeg, colors…)` sobre una caja de tamaño [size]. */
fun cssLinearGradient(
    angleDeg: Float,
    colors: List<Color>,
    size: Size,
    stops: List<Float>? = null,
): Brush {
    val d = cssDirection(angleDeg)
    val len = cssLineLength(angleDeg, size)
    val center = Offset(size.width / 2f, size.height / 2f)
    val start = center - Offset(d.x * len / 2f, d.y * len / 2f)
    val end = center + Offset(d.x * len / 2f, d.y * len / 2f)
    return if (stops == null) {
        Brush.linearGradient(colors = colors, start = start, end = end)
    } else {
        Brush.linearGradient(
            colorStops = stops.zip(colors).map { (s, c) -> s to c }.toTypedArray(),
            start = start,
            end = end,
        )
    }
}

/** El degradado de relleno del acento: `grad(deg)` → de accent.a a accent.b (blanco encima). */
fun accentGradient(skin: ElyndraSkin, angleDeg: Float, size: Size): Brush =
    cssLinearGradient(angleDeg, listOf(skin.a1, skin.fillEnd), size)

/* ─────────────────────────────────────────────────────────────
   Velos y realces reutilizados
   ───────────────────────────────────────────────────────────── */

/** Velo del hero: tres paradas cuya opacidad depende del ajuste "intensidad del hero". */
fun heroScrimBrush(scrim: Float, size: Size): Brush = cssLinearGradient(
    180f,
    listOf(
        P.shade.copy(alpha = scrim * 0.85f),
        P.shade.copy(alpha = scrim * 0.35f),
        P.shade.copy(alpha = minOf(0.96f, scrim + 0.30f)),
    ),
    size,
    stops = listOf(0f, 0.42f, 1f),
)

/**
 * Velo del hero cuando el juego sí tiene fondo: solo sus dos cantos.
 *
 * El fondo de un juego es la pieza que más se mira de la pantalla, así que
 * en el centro el velo vale cero — la imagen se ve tal cual, sin lavar— y el
 * degradado se guarda para la franja de la barra de arriba y para la del
 * titular, que es donde el texto blanco necesita algo debajo. La tinta es
 * negra y no el gris del diseño: da el mismo contraste con menos opacidad y,
 * sobre todo, oscurece la imagen en vez de desteñirla.
 * "Intensidad del hero" sigue mandando sobre las dos franjas.
 */
fun heroEdgeScrimBrush(scrim: Float, size: Size): Brush = cssLinearGradient(
    180f,
    listOf(
        Color.Black.copy(alpha = scrim * 0.55f),
        Color.Transparent,
        Color.Transparent,
        Color.Black.copy(alpha = scrim * 0.85f),
    ),
    size,
    stops = listOf(0f, 0.30f, 0.58f, 1f),
)

/**
 * El color con el que acaba cada velo del hero en su canto inferior: la
 * extensión del arte arranca de él para que no se vea la costura.
 */
fun heroScrimBottom(scrim: Float, art: Boolean): Color =
    if (art) Color.Black.copy(alpha = scrim * 0.85f) else P.shade.copy(alpha = minOf(0.96f, scrim + 0.30f))

/** Velo del rótulo de consola: `linear-gradient(90deg, rgba(51,51,51,.55), rgba(51,51,51,.12))`. */
fun consoleFaceBrush(size: Size): Brush = cssLinearGradient(
    90f,
    listOf(P.shade.copy(alpha = 0.55f), P.shade.copy(alpha = 0.12f)),
    size,
)

/** Banda de brillo que recorre la card seleccionada (`@keyframes sheen`). */
fun sheenBrush(size: Size): Brush = cssLinearGradient(
    90f,
    listOf(Color.Transparent, Color.White.copy(alpha = 0.4f), Color.Transparent),
    size,
)

/** Sombra de texto del hero, equivalente a `text-shadow: 0 6px 30px rgba(0,0,0,.55)`. */
val HeroTitleShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 6f), blurRadius = 30f)

