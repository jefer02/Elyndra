package com.elyndra.launcher.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.PAIRS
import kotlin.math.max

/* ─────────────────────────────────────────────────────────────
   Liquid glass.

   El diseño combina `background: rgba(tinte, α)` con
   `backdrop-filter: blur(N) saturate(150%)`, borde blanco de 1px,
   sombra difusa y un realce interior en el borde superior.

   Compose no tiene backdrop-filter: un composable no puede
   desenfocar lo que hay detrás de él. Lo que sí se puede replicar
   exactamente es todo lo demás — tinte, α, borde, sombra y realce —
   y el desenfoque se traduce en la lechosidad que aporta: a más
   blur, más velo blanco sobre el tinte. Como el fondo de estas
   pantallas son los blobs de aurora, que ya se dibujan como
   degradados radiales suaves, la diferencia en pantalla es mínima.
   ───────────────────────────────────────────────────────────── */

/** Velo blanco con el que se sustituye el `backdrop-filter: blur(N)`. */
private fun hazeFor(blur: Int): Float = (blur / 40f) * 0.20f

/**
 * `glass()` del diseño.
 *
 * @param shadow sombra externa; el diseño usa `0 10px 26px rgba(51,51,51,.10)`.
 * @param borderColor por defecto `rgba(255,255,255,.72)`.
 */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(16.dp),
    shadow: Dp = 10.dp,
    borderColor: Color = P.hairline,
    /** Capas flotantes (diálogos, hojas, ficha): base opaca para que no se transparente lo de detrás. */
    solid: Boolean = false,
): Modifier {
    val skin = LocalSkin.current
    val haze = hazeFor(skin.blur)
    val dark = P.isDark
    return this
        .shadow(shadow, shape, clip = false, ambientColor = P.shade.copy(alpha = 0.10f), spotColor = P.shade.copy(alpha = 0.10f))
        .clip(shape)
        .then(if (solid) Modifier.background(P.surface) else Modifier)
        // En oscuro el cristal NO puede blanquearse. Los tintes del diseño son
        // colores claros (el de serie es blanco puro) y, aplicados al 55 %,
        // dejaban un panel claro con texto casi blanco encima: ilegible. Aquí
        // se apoya primero una base oscura y el tinte queda solo como matiz.
        .then(if (dark && !solid) Modifier.background(P.surface.copy(alpha = 0.86f)) else Modifier)
        .background(skin.tint.color.copy(alpha = if (dark) skin.alpha * 0.16f else skin.alpha))
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (dark) haze * 0.25f + 0.02f else haze + 0.06f),
                    Color.White.copy(alpha = if (dark) 0f else haze * 0.35f),
                ),
            ),
        )
        .insetHighlight()
        .border(1.dp, borderColor, shape)
}

/** `darkGlass()` — la variante oscura del hero y la barra superior. */
@Composable
fun Modifier.darkGlass(
    shape: Shape = RoundedCornerShape(12.dp),
): Modifier {
    val skin = LocalSkin.current
    val a = max(0.25f, skin.alpha * 0.6f)
    return this
        .clip(shape)
        .background(P.shade.copy(alpha = a))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = hazeFor(skin.blur) * 0.5f), Color.Transparent)))
        .border(1.dp, Color.White.copy(alpha = 0.28f), shape)
}

/**
 * `liquidGlass()` — cristal semitransparente sin base opaca.
 *
 * Igual que [glass] pero sin el relleno que tapa lo de detrás: se usa donde el
 * fondo de la pantalla (la aurora, el fondo del hero) tiene que verse a través.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    borderColor: Color = P.hairline,
): Modifier {
    val skin = LocalSkin.current
    val haze = hazeFor(skin.blur)
    val dark = P.isDark
    return this
        .clip(shape)
        .background(skin.tint.color.copy(alpha = if (dark) skin.alpha * 0.22f else skin.alpha * 0.5f))
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (dark) haze * 0.30f + 0.03f else haze + 0.08f),
                    Color.White.copy(alpha = if (dark) 0f else haze * 0.30f),
                ),
            ),
        )
        .insetHighlight()
        .border(1.dp, borderColor, shape)
}

/** `inset 0 1px 0 rgba(255,255,255,.7)` — la línea de luz del borde superior. */
private fun Modifier.insetHighlight(): Modifier = drawBehind {
    val y = 0.5.dp.toPx()
    drawLine(
        // En oscuro el realce se apaga: una línea blanca al 70 % delataría el borde.
        color = Color.White.copy(alpha = if (P.isDark) 0.10f else 0.7f),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.dp.toPx(),
    )
}

/* ─────────────────────────────────────────────────────────────
   Carátulas procedurales (`art()`)
   ───────────────────────────────────────────────────────────── */

/** Pinta la carátula del par [pairIndex]: base a 150deg + trama de rayas a 115deg. */
fun DrawScope.drawArt(pairIndex: Int) {
    val pair = PAIRS[((pairIndex % PAIRS.size) + PAIRS.size) % PAIRS.size]
    drawRect(artBaseBrush(pair, size))
    val period = 9.dp.toPx()
    val width = 2.dp.toPx()
    artStripeSegments(size, period).forEach { (a, b) ->
        drawLine(ArtStripeColor, a, b, strokeWidth = width)
    }
}

/** La carátula como modificador de fondo. */
fun Modifier.art(pairIndex: Int): Modifier = drawBehind { drawArt(pairIndex) }

/* ─────────────────────────────────────────────────────────────
   Aurora — los dos blobs difuminados del fondo de las hojas.

   El CSS los define como elipses sólidas dentro de un contenedor
   con `filter: blur(64px)`. Un degradado radial que cae a
   transparente da exactamente esa mancha, sin coste de blur.
   ───────────────────────────────────────────────────────────── */

@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val (dxA, dyA, scaleA) = auroraOffset(17_000, reverse = false)
    val (dxB, dyB, scaleB) = auroraOffset(24_000, reverse = true)

    Box(
        modifier.fillMaxSize().drawBehind {
            // blobA: left 2%, top 4%, 56% × 34%, color a1 al 40% (×.85 del contenedor)
            drawBlob(
                color = skin.a1.copy(alpha = 0.40f * 0.85f),
                left = 0.02f + dxA, top = 0.04f + dyA,
                w = 0.56f, h = 0.34f, scale = scaleA,
            )
            // blobB: right 0%, bottom 6%, 54% × 32%, color a2 al 22%
            drawBlob(
                color = skin.a2.copy(alpha = 0.22f * 0.85f),
                left = 1f - 0.54f + dxB, top = 1f - 0.06f - 0.32f + dyB,
                w = 0.54f, h = 0.32f, scale = scaleB,
            )
        },
    )
}

private fun DrawScope.drawBlob(color: Color, left: Float, top: Float, w: Float, h: Float, scale: Float) {
    val bw = size.width * w * scale
    val bh = size.height * h * scale
    val cx = size.width * left + bw / 2f
    val cy = size.height * top + bh / 2f
    val radius = bw / 2f
    // Se dibuja un degradado radial circular y se estira en vertical: así
    // sale la elipse del diseño con la caída suave que daba el blur(64px).
    withTransform({ scale(1f, bh / bw, Offset(cx, cy)) }) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color, color.copy(alpha = 0f)),
                center = Offset(cx, cy),
                radius = radius,
            ),
            radius = radius,
            center = Offset(cx, cy),
        )
    }
}

/* ─────────────────────────────────────────────────────────────
   Spinner: aro con el borde superior transparente girando
   (`border: 2px solid a2; border-top-color: transparent`).
   ───────────────────────────────────────────────────────────── */

fun DrawScope.drawArcSpinner(color: Color, strokeDp: Dp, angle: Float) {
    val stroke = strokeDp.toPx()
    val inset = stroke / 2f
    drawArc(
        color = color,
        startAngle = angle,
        sweepAngle = 270f,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        style = Stroke(width = stroke),
    )
}
