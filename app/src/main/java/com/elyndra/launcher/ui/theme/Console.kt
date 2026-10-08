package com.elyndra.launcher.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.ui.ShelfLayout
import kotlinx.coroutines.delay

/* ─────────────────────────────────────────────────────────────
   Elyndra Console: tokens, superficies y movimiento comunes.
   Las reglas están en docs/UI_DESIGN.md.
   ───────────────────────────────────────────────────────────── */

/** Rejilla de 8 dp (con medio paso de 4 para ajustes finos). */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/** Escala de radios: chips, filas, tarjetas, paneles y capas flotantes. */
object Radii {
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 20.dp
    val xl = 28.dp
}

/** Escala tipográfica (sp) de Poppins. */
object TypeScale {
    const val Headline = 19f
    const val Title = 15f
    const val Body = 12.5f
    const val Label = 11f
    const val Caption = 10f
    const val Overline = 9.5f
}

/** Zona táctil mínima de cualquier control. */
val MinTouch = 48.dp

/** Entrada escalonada de listas: retardo entre elementos y a partir de cuál dejan de esperar. */
const val STAGGER_STEP_MS = 25
const val STAGGER_MAX = 8

/**
 * Lámina de la consola: el cristal de siempre (respeta tinte, desenfoque y
 * transparencia de Ajustes) con el brillo champán de la intro en su canto.
 */
@Composable
fun Modifier.consoleSurface(
    shape: Shape = RoundedCornerShape(Radii.l),
    elevation: Dp = 10.dp,
    solid: Boolean = false,
): Modifier = glass(shape, shadow = elevation, solid = solid, sheen = 0.6f).topGlint()

/**
 * El canto de luz: una línea de 1 dp arriba, champán en el centro y que se
 * apaga hacia los lados, como la luz que barre el rótulo de la intro.
 * Va después del recorte de la pieza, así sigue sus esquinas.
 */
fun Modifier.topGlint(strength: Float = 1f): Modifier = drawWithCache {
    val c = P.champagne
    val a = (if (P.isDark) 0.55f else 0.7f) * strength
    val glint = Brush.horizontalGradient(
        0f to Color.Transparent,
        0.5f to c.copy(alpha = a),
        1f to Color.Transparent,
        startX = size.width * 0.12f,
        endX = size.width * 0.88f,
    )
    val y = 0.5.dp.toPx()
    val stroke = 1.dp.toPx()
    onDrawBehind {
        if (size.width > 0f) drawLine(glint, Offset(size.width * 0.12f, y), Offset(size.width * 0.88f, y), strokeWidth = stroke)
    }
}

/** El color liso del estante: el papel con un velo del acento (perla en claro, tinta honda en oscuro). */
@Composable
fun shelfColor(): Color {
    val skin = LocalSkin.current
    val dark = P.isDark
    val paper = P.paper
    return remember(paper, skin.a1, dark) { Color(ShelfLayout.shelfColor(paper.argb(), skin.a1.argb(), dark)) }
}

/**
 * El estante de Biblioteca y Carpeta: la mitad baja, donde viven las cards.
 *
 * No es un panel suelto sino la continuación del hero: perla en claro (el
 * papel con un velo del acento, nunca gris) y tinta honda en oscuro; arriba,
 * la sombra que el hero proyecta sobre él y un filo de luz champán donde se
 * tocan. El fondo vivo de la selección se sigue adivinando a través.
 *
 * Con [extension] (el arte del hero sigue por detrás, ver `Hero`) el tramo de
 * arriba se queda sin fondo —lo pinta el velo del hero, que acaba en este
 * mismo color— y desde ahí el estante es liso y se aclara apenas hacia el pie.
 * Sin costura: ni sombra ni filo, es la misma escena.
 */
@Composable
fun Modifier.shelfSurface(extension: Dp = 0.dp): Modifier {
    val skin = LocalSkin.current
    val dark = P.isDark
    if (extension > 0.dp) {
        val solid = shelfColor()
        val bottom = ShelfLayout.shelfBottomAlpha(dark)
        return drawWithCache {
            val top = extension.roundToPx().toFloat().coerceAtMost(size.height)
            val brush = Brush.verticalGradient(listOf(solid, solid.copy(alpha = bottom)), startY = top, endY = size.height)
            onDrawBehind {
                if (top < size.height) drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, size.height - top))
            }
        }
    }
    // Los degradados se crean una vez por tamaño: el estante se repinta al
    // desplazar el carrusel y no puede crear objetos en cada fotograma.
    return drawWithCache {
        val base = Brush.verticalGradient(
            listOf(
                P.paper.copy(alpha = if (dark) 0.55f else 0.66f),
                P.paper.copy(alpha = if (dark) 0.9f else 0.94f),
            ),
        )
        val tint = skin.a1.copy(alpha = ShelfLayout.shelfTintAlpha(dark))
        val cast = Brush.verticalGradient(
            listOf(P.shade.copy(alpha = if (dark) 0.32f else 0.10f), Color.Transparent),
            endY = 22.dp.toPx(),
        )
        val castSize = Size(size.width, 22.dp.toPx())
        val rim = Color.White.copy(alpha = if (dark) 0.10f else 0.75f)
        val y = 0.5.dp.toPx()
        val stroke = 1.dp.toPx()
        onDrawBehind {
            drawRect(base)
            drawRect(tint)
            drawRect(cast, size = castSize)
            drawLine(rim, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        }
    }.topGlint(0.8f)
}

/**
 * El bloque de título del hero al cambiar de selección: el nuevo entra con
 * un fundido y una subida corta y el viejo se apaga deprisa. El alto salta
 * (logo y titular miden distinto) para no remedir el hero en cada paso.
 */
fun AnimatedContentTransitionScope<*>.heroInfoTransition(reduced: Boolean): ContentTransform =
    if (reduced) {
        fadeIn(snap()) togetherWith fadeOut(snap())
    } else {
        (fadeIn(Springs.fade()) + slideInVertically(Springs.enter()) { h -> (h * 0.14f).toInt() })
            .togetherWith(fadeOut(Springs.exit()))
            .using(SizeTransform(clip = false) { _, _ -> snap() })
    }

/**
 * Entrada escalonada de una lista: fundido y 8 dp de subida, [STAGGER_STEP_MS]
 * entre elementos. Solo entran así los [STAGGER_MAX] primeros; los demás ya
 * están (en una lista perezosa, los que aparecen al desplazar no se animan).
 * [enabled] = false lo salta (p. ej. al volver a una fila ya vista). Se lee
 * en la fase de capa, así que no remide nada.
 */
@Composable
fun Modifier.staggerIn(index: Int, key: Any? = Unit, enabled: Boolean = true): Modifier {
    val reduced = LocalReducedMotion.current
    val animate = enabled && !reduced && index < STAGGER_MAX
    val p = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key) {
        if (p.value >= 1f) return@LaunchedEffect
        delay(index.toLong() * STAGGER_STEP_MS)
        p.animateTo(1f, Springs.enter())
    }
    return graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 8.dp.toPx()
    }
}

/** Hundirse a 0,97 mientras se pulsa y volver con muelle. */
@Composable
fun Modifier.pressFeedback(interaction: InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, motion(Springs.snappy()), label = "press")
    return graphicsLayer {
        scaleX = s
        scaleY = s
    }
}
