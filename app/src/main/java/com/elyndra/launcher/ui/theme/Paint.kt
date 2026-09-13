package com.elyndra.launcher.ui.theme

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

/** El degradado de acento del diseño: `grad(deg)` → de accent.a a accent.b. */
fun accentGradient(skin: ElyndraSkin, angleDeg: Float, size: Size): Brush =
    cssLinearGradient(angleDeg, listOf(skin.a1, skin.a2), size)

/* ─────────────────────────────────────────────────────────────
   Carátulas procedurales — `art()` en el diseño:

     repeating-linear-gradient(115deg, rgba(255,255,255,.14) 0 2px,
                                       transparent 2px 9px),
     linear-gradient(150deg, a 0%, b 100%)

   Es decir: base diagonal de dos colores + trama fina de rayas.
   ───────────────────────────────────────────────────────────── */

/** Base de la carátula: el degradado a 150deg del par [pair]. */
fun artBaseBrush(pair: Pair<Color, Color>, size: Size): Brush =
    cssLinearGradient(150f, listOf(pair.first, pair.second), size)

/**
 * Posiciones de las rayas de la trama, en coordenadas de la caja.
 * Devuelve, para cada raya, el segmento (inicio, fin) a trazar.
 * Periodo 9 px, grosor 2 px, ángulo 115deg — igual que el CSS.
 */
fun artStripeSegments(size: Size, periodPx: Float, angleDeg: Float = 115f): List<Pair<Offset, Offset>> {
    if (size.width <= 0f || size.height <= 0f) return emptyList()
    val d = cssDirection(angleDeg)
    val p = Offset(-d.y, d.x) // perpendicular: la dirección en la que corre cada raya
    val center = Offset(size.width / 2f, size.height / 2f)

    // Proyección de las cuatro esquinas sobre la línea de degradado.
    val corners = listOf(
        Offset(0f, 0f), Offset(size.width, 0f),
        Offset(0f, size.height), Offset(size.width, size.height),
    )
    val ts = corners.map { (it - center).let { v -> v.x * d.x + v.y * d.y } }
    val tMin = ts.min()
    val tMax = ts.max()

    val half = hypot(size.width, size.height) // sobra para cruzar la caja de lado a lado
    val out = ArrayList<Pair<Offset, Offset>>()
    var t = floor(tMin / periodPx) * periodPx
    while (t <= tMax) {
        val anchor = center + Offset(d.x * t, d.y * t)
        out += (anchor - Offset(p.x * half, p.y * half)) to (anchor + Offset(p.x * half, p.y * half))
        t += periodPx
    }
    return out
}

val ArtStripeColor = Color.White.copy(alpha = 0.14f)

/* ─────────────────────────────────────────────────────────────
   Velos y realces reutilizados
   ───────────────────────────────────────────────────────────── */

/** Velo del hero: tres paradas cuya opacidad depende del ajuste "intensidad del hero". */
fun heroScrimBrush(scrim: Float, size: Size): Brush = cssLinearGradient(
    180f,
    listOf(
        Color(0xFF333333).copy(alpha = scrim * 0.85f),
        Color(0xFF333333).copy(alpha = scrim * 0.35f),
        Color(0xFF333333).copy(alpha = minOf(0.96f, scrim + 0.30f)),
    ),
    size,
    stops = listOf(0f, 0.42f, 1f),
)

/** Velo de las carátulas de ROM: claro arriba, tinta abajo para que se lea el título. */
fun romScrimBrush(size: Size): Brush = cssLinearGradient(
    180f,
    listOf(
        Color.White.copy(alpha = 0.26f),
        Color.Transparent,
        Color(0xFF333333).copy(alpha = 0.8f),
    ),
    size,
    stops = listOf(0f, 0.40f, 1f),
)

/** Velo del rótulo de consola: `linear-gradient(90deg, rgba(51,51,51,.55), rgba(51,51,51,.12))`. */
fun consoleFaceBrush(size: Size): Brush = cssLinearGradient(
    90f,
    listOf(Color(0xFF333333).copy(alpha = 0.55f), Color(0xFF333333).copy(alpha = 0.12f)),
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

/** `text-shadow: 0 2px 12px rgba(0,0,0,.5)` del wordmark. */
val WordmarkShadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 2f), blurRadius = 12f)
