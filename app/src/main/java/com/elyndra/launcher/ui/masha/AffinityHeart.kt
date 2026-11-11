package com.elyndra.launcher.ui.masha

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.elyndra.launcher.domain.relationship.AffinityLevel
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.LocalReducedMotion

/** El nombre de cada nivel en la interfaz. */
fun AffinityLevel.labelRes(): Int = when (this) {
    AffinityLevel.Distant -> R.string.lucy_level_distant
    AffinityLevel.Curious -> R.string.lucy_level_curious
    AffinityLevel.Friendly -> R.string.lucy_level_friendly
    AffinityLevel.Close -> R.string.lucy_level_close
    AffinityLevel.Devoted -> R.string.lucy_level_devoted
}

/**
 * El nivel de la relación con Lucy, discreto: un corazón que se llena por
 * tramos (uno por nivel) y el nombre del nivel. Cuando sube ([levelUps]
 * cambia), late una vez; al bajar no hace nada.
 */
@Composable
fun AffinityHeart(level: AffinityLevel, levelUps: Int, glow: Color, dim: Color, modifier: Modifier = Modifier) {
    val label = stringResource(level.labelRes())
    val description = stringResource(R.string.lucy_level_cd, label)
    val reduced = LocalReducedMotion.current
    val beat = remember { Animatable(1f) }
    LaunchedEffect(levelUps) {
        if (levelUps == 0 || reduced) return@LaunchedEffect
        beat.animateTo(1.45f, tween(180))
        beat.animateTo(0.92f, tween(160))
        beat.animateTo(1f, tween(220))
    }
    val fill = (level.ordinal + 1) / AffinityLevel.entries.size.toFloat()
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer {
                    scaleX = beat.value
                    scaleY = beat.value
                }
                .drawBehind {
                    val heart = heartPath(size.width, size.height)
                    drawPath(heart, glow, style = Stroke(1.dp.toPx()))
                    // Lleno de abajo arriba, un tramo por nivel.
                    clipRect(top = size.height * (1f - fill)) { drawPath(heart, glow) }
                },
        )
        Spacer(Modifier.width(4.dp))
        ElyText(label, size = 9.5f, weight = FontWeight.Medium, color = dim, maxLines = 1, letterSpacing = tracking(0.06f))
    }
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
