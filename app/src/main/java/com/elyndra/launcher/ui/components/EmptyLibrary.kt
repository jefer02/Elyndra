package com.elyndra.launcher.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.ui.EmptyStage
import com.elyndra.launcher.ui.StageColors
import com.elyndra.launcher.ui.StageSpot
import com.elyndra.launcher.ui.meridian.LogoFit
import com.elyndra.launcher.ui.meridian.MeridianActionRow
import com.elyndra.launcher.ui.meridian.MeridianActions
import com.elyndra.launcher.ui.meridian.MeridianTitle
import com.elyndra.launcher.ui.meridian.TITLE_MAX_SP
import com.elyndra.launcher.ui.meridian.grainTile
import com.elyndra.launcher.ui.meridian.rememberMeridianInk
import com.elyndra.launcher.ui.meridian.wipeReveal
import com.elyndra.launcher.ui.theme.HeroTitleShadow
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/* ─────────────────────────────────────────────────────────────
   El estado vacío de la biblioteca, compartido por Meridian y el
   diseño clásico (horizontal y vertical): el escenario que ocupa el
   sitio del arte y el bloque de titular, línea y acción. La tarjeta
   "Añadir" es la de cada lista (la fila de la rueda o la card del
   carrusel), que en vacío queda señalada.
   ───────────────────────────────────────────────────────────── */

/** Los colores del escenario para la paleta de firma y el tema actuales (ver [EmptyStage]). */
@Composable
internal fun rememberStageColors(): StageColors {
    val ink = rememberMeridianInk()
    return remember(ink) { EmptyStage.colors(ink.primary.argb(), ink.secondary.argb(), ink.spark.argb(), ink.dark) }
}

/** Opacidad de las tres órbitas del destello, de dentro afuera, y su radio en fracción del lado menor. */
private val RING_ALPHA = floatArrayOf(0.12f, 0.08f, 0.05f)
private val RING_RADIUS = floatArrayOf(0.20f, 0.36f, 0.54f)

private const val STAGE_GRAIN = 0.04f

/**
 * El escenario de una biblioteca sin arte: el fondo hondo teñido por la
 * paleta de firma, el resplandor del primario, el halo del secundario, las
 * órbitas finas del destello con su nodo, una sombra de suelo que asienta el
 * bloque de texto y el grano que quita las bandas. Todo estático: los pinceles
 * se crean una vez por tamaño ([drawWithCache]) y nada se vuelve a pintar
 * por fotograma. [horizon] dibuja un filo de luz abajo (la costura con el
 * estante del diseño clásico).
 */
@Composable
internal fun EmptyStageBackdrop(spot: StageSpot, modifier: Modifier = Modifier, horizon: Boolean = false) {
    val c = rememberStageColors()
    val grain = remember { ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated)) }
    val shade = P.shade
    Box(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val big = max(w, h)
            val small = min(w, h)
            val base = Brush.verticalGradient(0f to Color(c.top), 1f to Color(c.bottom))
            val glowAt = Offset(w * spot.glowX, h * spot.glowY)
            val glowColor = Color(c.glow)
            val glow = Brush.radialGradient(
                0f to glowColor.copy(alpha = c.glowAlpha),
                0.35f to glowColor.copy(alpha = c.glowAlpha * 0.5f),
                0.7f to glowColor.copy(alpha = c.glowAlpha * 0.14f),
                1f to Color.Transparent,
                center = glowAt,
                radius = (big * spot.glowRadius).coerceAtLeast(1f),
            )
            val haloColor = Color(c.halo)
            val halo = Brush.radialGradient(
                0f to haloColor.copy(alpha = c.haloAlpha),
                0.5f to haloColor.copy(alpha = c.haloAlpha * 0.35f),
                1f to Color.Transparent,
                center = Offset(w * spot.haloX, h * spot.haloY),
                radius = (big * spot.haloRadius).coerceAtLeast(1f),
            )
            val floor = Brush.verticalGradient(0f to Color.Transparent, 1f to shade.copy(alpha = 0.32f), startY = h * 0.62f, endY = h)
            val ring = Color(c.ring)
            val stroke = Stroke(1.dp.toPx())
            val nodeRadius = small * RING_RADIUS[1]
            val rad = Math.toRadians(spot.nodeAngleFor(w, h).toDouble())
            val node = Offset(glowAt.x + nodeRadius * cos(rad).toFloat(), glowAt.y + nodeRadius * sin(rad).toFloat())
            val nodeGlow = Brush.radialGradient(
                0f to ring.copy(alpha = 0.55f),
                1f to Color.Transparent,
                center = node,
                radius = 14.dp.toPx(),
            )
            val lineH = 1.5.dp.toPx()
            val edge = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.3f to glowColor.copy(alpha = 0.7f),
                0.7f to haloColor.copy(alpha = 0.7f),
                1f to Color.Transparent,
            )
            onDrawBehind {
                drawRect(base)
                drawRect(halo)
                drawRect(glow)
                for (i in RING_RADIUS.indices) drawCircle(ring, small * RING_RADIUS[i], glowAt, alpha = RING_ALPHA[i], style = stroke)
                drawCircle(nodeGlow, 14.dp.toPx(), node)
                drawCircle(ring, 2.5.dp.toPx(), node, alpha = 0.9f)
                drawRect(floor)
                drawRect(grain, alpha = STAGE_GRAIN)
                if (horizon) drawRect(edge, topLeft = Offset(0f, h - lineH), size = Size(w, lineH))
            }
        },
    )
}

/** El titular más pequeño del estado vacío (un móvil en horizontal, un idioma largo). */
internal const val EMPTY_TITLE_MIN_SP = 18f

/**
 * El bloque del estado vacío, el mismo en los tres diseños: el titular en
 * dos líneas del mayor cuerpo que cabe en [titleBox] (nunca con puntos
 * suspensivos), la línea de qué hacer ([message]) y la acción principal de
 * Meridian ([action], con la A si hay mando). Blanco sobre el escenario
 * ([EmptyStageBackdrop]) o sobre el arte con sus velos. Sin [action], solo
 * el aviso (una búsqueda sin resultados).
 */
@Composable
internal fun EmptyLibraryHero(
    title: String,
    message: String?,
    action: String?,
    onAction: () -> Unit,
    titleBox: LogoFit.Box,
    padGlyphs: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    titleMaxSp: Float = TITLE_MAX_SP,
) {
    val ink = rememberMeridianInk()
    val reduced = LocalReducedMotion.current
    Column(modifier) {
        MeridianTitle(
            title,
            titleBox,
            Modifier.wipeReveal(title, ink.spark, reduced),
            minSp = EMPTY_TITLE_MIN_SP,
            maxSp = titleMaxSp.coerceAtLeast(EMPTY_TITLE_MIN_SP),
        )
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            ElyText(
                message,
                modifier = Modifier.widthIn(max = (titleBox.maxWidth * 1.2f).coerceAtLeast(220f).dp),
                size = 11.5f,
                weight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.88f),
                letterSpacing = tracking(0.02f),
                lineHeightRatio = 1.32f,
                shadow = HeroTitleShadow,
                maxLines = if (compact) 2 else 3,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
            MeridianActionRow(MeridianActions(action, onAction, null, {}, null, {}), ink, padGlyphs, compact = compact)
        }
    }
}
