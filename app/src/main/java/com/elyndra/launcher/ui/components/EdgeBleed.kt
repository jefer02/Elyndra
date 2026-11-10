package com.elyndra.launcher.ui.components

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/* ─────────────────────────────────────────────────────────────
   Fondos a sangre bajo la muesca.

   La raíz (ElyndraApp) deja las pantallas dentro del área segura: con
   las barras ocultas, eso es el recorte de la cámara, que en un móvil
   apaisado es una franja de 25–50 dp en un solo lado. Los controles y
   el texto se quedan dentro; los fondos no, o la franja queda lisa
   junto al arte. Dos formas de llegar al filo de la pantalla:

     · [edgeBleed]: la capa se mide más grande, hasta el filo. Para el
       arte (hero, fondo vivo, arte de Meridian): se reencuadra solo.
     · [edgeMirror]: la capa no se mueve; su canto se pinta reflejado en
       la franja. Para velos y cristales que dibujan con coordenadas
       propias (la rueda de Meridian): reflejado, un difuminado o un
       degradado vertical siguen sin costura.

   Las dos dan por hecho que la capa toca ese canto del área segura, y
   que ningún padre entre ella y la raíz recorta (por eso el
   `AnimatedContent` de la raíz va con `SizeTransform(clip = false)`).
   ───────────────────────────────────────────────────────────── */

/** Lo que la raíz deja fuera del área segura: barras (ocultas, 0) y recorte de pantalla. */
val SafeAreaInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * La capa crece hasta el filo de la ventana: a los lados ([sides]) y arriba
 * y abajo, lo que se pida. En la fila ocupa lo mismo que antes; solo se pinta
 * más allá. Se lee en la medida, así que girar el móvil no recompone.
 */
@Composable
fun Modifier.edgeBleed(top: Boolean = true, bottom: Boolean = true, sides: Boolean = true): Modifier {
    val insets = SafeAreaInsets
    return layout { measurable, constraints ->
        val l = if (sides) insets.getLeft(this, layoutDirection) else 0
        val r = if (sides) insets.getRight(this, layoutDirection) else 0
        val t = if (top) insets.getTop(this) else 0
        val b = if (bottom) insets.getBottom(this) else 0
        val placeable = measurable.measure(constraints.grow(l + r, t + b))
        layout(constraints.constrainWidth(placeable.width - l - r), constraints.constrainHeight(placeable.height - t - b)) {
            placeable.place(-l, -t)
        }
    }
}

/**
 * Pinta el canto izquierdo y/o derecho de la capa reflejado en la franja
 * de la muesca y, con [top], también el de arriba (la barra de estado o la
 * muesca de arriba): sin él, un cristal que empieza en el área segura deja
 * una franja de arte nítido encima. No cambia su medida ni su sitio: lo que
 * dibuje con sus propias coordenadas sigue donde estaba.
 */
@Composable
fun Modifier.edgeMirror(left: Boolean = true, right: Boolean = true, top: Boolean = false): Modifier {
    val insets = SafeAreaInsets
    return drawWithContent {
        drawContent()
        val l = if (left) insets.getLeft(this, layoutDirection).toFloat() else 0f
        val r = if (right) insets.getRight(this, layoutDirection).toFloat() else 0f
        val t = if (top) insets.getTop(this).toFloat() else 0f
        if (t > 0f) clipRect(left = -l, top = -t, right = size.width + r, bottom = 0f) {
            scale(scaleX = 1f, scaleY = -1f, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
        }
        if (l > 0f) clipRect(left = -l, top = 0f, right = 0f, bottom = size.height) {
            scale(scaleX = -1f, scaleY = 1f, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
        }
        if (r > 0f) clipRect(left = size.width, top = 0f, right = size.width + r, bottom = size.height) {
            scale(scaleX = -1f, scaleY = 1f, pivot = Offset(size.width, 0f)) { this@drawWithContent.drawContent() }
        }
    }
}

/**
 * Dentro de una capa que ya llega al filo ([edgeBleed] sobre la pantalla
 * entera), devuelve el contenido al área segura: el mismo margen que la raíz,
 * leído en crudo (la raíz ya lo consumió, así que `windowInsetsPadding` aquí
 * daría 0). Una vez por pantalla, no por pieza.
 */
@Composable
fun Modifier.safeAreaPadding(): Modifier = padding(SafeAreaInsets.asPaddingValues())

private fun Constraints.grow(dx: Int, dy: Int): Constraints = Constraints(
    minWidth = minWidth + dx,
    maxWidth = if (hasBoundedWidth) maxWidth + dx else Constraints.Infinity,
    minHeight = minHeight + dy,
    maxHeight = if (hasBoundedHeight) maxHeight + dy else Constraints.Infinity,
)
