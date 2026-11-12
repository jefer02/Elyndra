package com.elyndra.launcher.ui.masha

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.domain.relationship.AffinityBadge
import com.elyndra.launcher.domain.relationship.AffinityLevel
import com.elyndra.launcher.domain.relationship.AffinityProgress
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.shapeClickable

/** El nombre de cada nivel en la interfaz. */
fun AffinityLevel.labelRes(): Int = when (this) {
    AffinityLevel.Distant -> R.string.lucy_level_distant
    AffinityLevel.Curious -> R.string.lucy_level_curious
    AffinityLevel.Friendly -> R.string.lucy_level_friendly
    AffinityLevel.Close -> R.string.lucy_level_close
    AffinityLevel.Devoted -> R.string.lucy_level_devoted
}

/** El corazón, con el contorno y relleno de abajo arriba hasta [fill] (0..1). */
@Composable
fun HeartGlyph(fill: Float, glow: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.drawBehind {
            val heart = heartPath(size.width, size.height)
            drawPath(heart, glow, style = Stroke(1.dp.toPx()))
            clipRect(top = size.height * (1f - fill.coerceIn(0f, 1f))) { drawPath(heart, glow) }
        },
    )
}

/** Corazón en la caja [w]×[h]: dos lóbulos y la punta abajo. */
private fun heartPath(w: Float, h: Float): Path = Path().apply {
    moveTo(w * 0.5f, h * 0.92f)
    cubicTo(w * 0.1f, h * 0.62f, -w * 0.02f, h * 0.32f, w * 0.18f, h * 0.14f)
    cubicTo(w * 0.32f, h * 0.02f, w * 0.46f, h * 0.1f, w * 0.5f, h * 0.24f)
    cubicTo(w * 0.54f, h * 0.1f, w * 0.68f, h * 0.02f, w * 0.82f, h * 0.14f)
    cubicTo(w * 1.02f, h * 0.32f, w * 0.9f, h * 0.62f, w * 0.5f, h * 0.92f)
    close()
}

/**
 * La insignia de confianza de la cabecera: el corazón con un aro que se llena
 * hacia el siguiente nivel, el nombre del nivel con "Nivel 2 de 5" y una barra
 * fina con lo que falta ("12 pts para Cercana"). Es claramente pulsable (caja
 * con borde y ›) y abre "Lucy y tú". Cuando cambia la puntuación el aro se
 * llena animado (al subir de nivel, hasta arriba y vuelve a empezar); el
 * corazón late al subir.
 */
@Composable
fun TrustBadge(progress: AffinityProgress, levelUps: Int, glow: Color, dim: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val badge = AffinityBadge.of(progress)
    val reduced = LocalReducedMotion.current
    val ring = remember { Animatable(badge.ring) }
    val shown = remember { arrayOf(badge) }
    LaunchedEffect(badge) {
        val steps = AffinityBadge.ringSteps(shown[0], badge)
        shown[0] = badge
        if (reduced) {
            ring.snapTo(badge.ring)
            return@LaunchedEffect
        }
        steps.forEachIndexed { i, target ->
            // El salto de lleno a vacío al subir de nivel es instantáneo.
            if (i == 1 && steps.size == 3) ring.snapTo(target) else ring.animateTo(target, tween(700))
        }
    }
    val beat = remember { Animatable(1f) }
    LaunchedEffect(levelUps) {
        if (levelUps == 0 || reduced) return@LaunchedEffect
        beat.animateTo(1.35f, tween(180))
        beat.animateTo(0.94f, tween(160))
        beat.animateTo(1f, tween(220))
    }
    val name = stringResource(badge.level.labelRes())
    val levelLine = stringResource(R.string.lucy_level_of, badge.number, badge.total)
    val toNext = badge.next?.let { stringResource(R.string.lucy_pts_to, badge.pointsToNext, stringResource(it.labelRes())) }
        ?: stringResource(R.string.lucy_bond_max)
    val description = stringResource(R.string.lucy_trust_badge_cd, name, badge.number, badge.total, toNext)
    val open = stringResource(R.string.lucy_bond_open)
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .heightIn(min = 44.dp)
            .widthIn(max = 230.dp)
            .clip(shape)
            .background(glow.copy(alpha = 0.10f))
            .border(1.dp, glow.copy(alpha = 0.45f), shape)
            .shapeClickable(shape, onClickLabel = open, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .graphicsLayer {
                    scaleX = beat.value
                    scaleY = beat.value
                }
                .drawBehind {
                    val stroke = 2.5.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(glow.copy(alpha = 0.22f), -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                    drawArc(glow, -90f, 360f * ring.value, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    // El corazón dentro, lleno por tramos (uno por nivel).
                    val h = size.width * 0.46f
                    val o = (size.width - h) / 2
                    val heart = heartPath(h, h).apply { translate(androidx.compose.ui.geometry.Offset(o, o + h * 0.04f)) }
                    drawPath(heart, glow, style = Stroke(1.dp.toPx()))
                    val fill = badge.number / badge.total.toFloat()
                    clipRect(top = o + h * (1f - fill)) { drawPath(heart, glow) }
                },
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ElyText(name, size = 11f, weight = FontWeight.Bold, color = Color.White, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                ElyText(levelLine, size = 9f, weight = FontWeight.Medium, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(glow.copy(alpha = 0.2f))
                    .drawBehind { drawRect(glow, size = size.copy(width = size.width * ring.value.coerceIn(0f, 1f))) },
            )
            Spacer(Modifier.height(2.dp))
            ElyText(toNext, size = 8.5f, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        ElyText("›", size = 15f, weight = FontWeight.Bold, color = glow)
    }
}
