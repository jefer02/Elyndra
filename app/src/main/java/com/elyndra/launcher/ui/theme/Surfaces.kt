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
import kotlin.math.min

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

/** Lado a partir del cual una pieza recibe el reflejo entero (ver [liquidSheen]). */
private val SHEEN_REFERENCE = 140.dp

/* ─────────────────────────────────────────────────────────────
   El acabado "liquid glass".

   Lo que separa un panel translúcido de una lámina de cristal es
   cómo se comporta la luz en su canto. Son tres capas, y las comparte
   toda la app porque van dentro de los modificadores de siempre
   ([glass], [liquidGlass], [darkGlass]): cualquier panel, hoja, menú
   o píldora que ya los usara queda con el mismo material, sin tocar
   un solo sitio de llamada.

     · Barrido especular en diagonal — el reflejo que cruza la pieza
       de la esquina superior izquierda a la inferior derecha, con un
       valle transparente en medio. Es lo que da sensación de grosor.
     · Lente superior — un halo radial pegado al borde de arriba, la
       luz que entra por el canto y se difunde hacia dentro.
       (Las dos, en [liquidSheen].)
     · Canto con degradado — el borde no es de un solo blanco: brilla
       arriba, se apaga en los lados y vuelve a encenderse abajo, como
       haría un bisel real. (En [rimBrush].)

   Todo se apoya sobre el tinte y el velo que ya tenía el diseño, así
   que un panel sigue siendo el mismo color: solo cambia su canto.
   ───────────────────────────────────────────────────────────── */

/**
 * Las dos capas de reflejo.
 *
 * El reflejo se atenúa solo en las piezas pequeñas. Un barrido diagonal que
 * queda precioso cruzando una hoja de 500 dp, metido en una píldora de 30,
 * pasa a ser un degradado que se come el rótulo: ahí la diagonal no llega a
 * leerse como reflejo, solo aclara el fondo. Se mide por el lado corto contra
 * [SHEEN_REFERENCE], así que ningún sitio de llamada tiene que acordarse.
 *
 * [strength] es el ajuste manual encima de eso, para el cristal oscuro.
 */
private fun Modifier.liquidSheen(strength: Float = 1f): Modifier = drawBehind {
    // Una pieza sin área todavía no tiene canto que iluminar, y los degradados
    // que vienen abajo se dividen por su tamaño.
    if (size.width <= 0f || size.height <= 0f) return@drawBehind
    val dark = P.isDark
    val fit = (min(size.width, size.height) / SHEEN_REFERENCE.toPx()).coerceIn(0.35f, 1f)
    val amount = strength * fit
    // En oscuro el reflejo tiene que ser mucho más tenue: el mismo blanco que
    // en claro apenas se nota sobre papel, pero sobre un panel oscuro se lee
    // como una mancha gris.
    val peak = (if (dark) 0.10f else 0.22f) * amount
    val tail = (if (dark) 0.04f else 0.09f) * amount

    drawRect(
        Brush.linearGradient(
            0.00f to Color.White.copy(alpha = peak),
            0.18f to Color.White.copy(alpha = peak * 0.35f),
            0.48f to Color.Transparent,
            0.82f to Color.Transparent,
            1.00f to Color.White.copy(alpha = tail),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
    )

    // Halo del canto de arriba: elipse ancha y baja, centrada sobre el borde,
    // que se desvanece hacia dentro. El radio se estira en horizontal para que
    // cubra toda la anchura sin subir el foco.
    val radius = size.width * 0.75f
    val center = Offset(size.width * 0.5f, 0f)
    withTransform({ scale(1f, (size.height * 0.9f) / radius, center) }) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = (if (dark) 0.07f else 0.16f) * amount),
                    Color.Transparent,
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

/** El canto: blanco vivo arriba, apagado en medio, medio encendido abajo. */
@Composable
private fun rimBrush(base: Color): Brush {
    val dark = P.isDark
    val top = if (dark) 0.26f else 0.92f
    val mid = if (dark) 0.08f else 0.42f
    val bottom = if (dark) 0.16f else 0.66f
    return Brush.linearGradient(
        0f to base.copy(alpha = base.alpha * top + if (dark) 0.10f else 0f),
        0.45f to base.copy(alpha = base.alpha * mid),
        1f to base.copy(alpha = base.alpha * bottom),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
}

/**
 * `glass()` del diseño, ya con el acabado liquid glass.
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
    /** Cuánto reflejo. Se baja en las piezas pequeñas (píldoras, botones). */
    sheen: Float = 1f,
): Modifier {
    val skin = LocalSkin.current
    val haze = hazeFor(skin.blur)
    val dark = P.isDark
    val rim = rimBrush(borderColor)
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
        .liquidSheen(sheen)
        .insetHighlight()
        .border(1.dp, rim, shape)
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
        // Sobre tinta oscura el reflejo sí puede subir: no hay texto oscuro que
        // perder y es lo que separa la barra del fondo del hero.
        .liquidSheen(0.7f)
        .border(1.dp, rimBrush(Color.White.copy(alpha = 0.28f)), shape)
}

/**
 * `liquidGlass()` — cristal semitransparente sin base opaca.
 *
 * Igual que [glass] pero sin el relleno que tapa lo de detrás: se usa donde el
 * fondo de la pantalla (la aurora, el fondo del hero) tiene que verse a través,
 * y en los bloques del menú de pulsación larga, que flotan sobre la hoja.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    borderColor: Color = P.hairline,
    sheen: Float = 1f,
): Modifier {
    val skin = LocalSkin.current
    val haze = hazeFor(skin.blur)
    val dark = P.isDark
    val rim = rimBrush(borderColor)
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
        .liquidSheen(sheen)
        .insetHighlight()
        .border(1.dp, rim, shape)
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
