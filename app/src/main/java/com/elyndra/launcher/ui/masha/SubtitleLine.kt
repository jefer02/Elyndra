package com.elyndra.launcher.ui.masha

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.ui.components.ElyText
import kotlinx.coroutines.delay

/**
 * El subtítulo de Lucy con el chat oculto: lo que está diciendo ([text], la
 * frase que suena) en [maxLines] línea(s), centrado sobre el holograma. Al
 * callarse ([text] = null) se queda [LINGER_MS] y se desvanece.
 */
@Composable
internal fun SubtitleLine(text: String?, modifier: Modifier = Modifier, maxLines: Int = 1) {
    var shown by remember { mutableStateOf(text) }
    var visible by remember { mutableStateOf(text != null) }
    LaunchedEffect(text) {
        if (text != null) {
            shown = text
            visible = true
        } else {
            delay(LINGER_MS)
            visible = false
        }
    }
    AnimatedVisibility(visible && shown != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(300)), modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Crossfade(shown, animationSpec = tween(140), label = "subtitle") { line ->
                ElyText(
                    line.orEmpty(),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Holo.bg.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    size = 13f,
                    color = Holo.text,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    align = TextAlign.Center,
                    lineHeightRatio = 1.35f,
                )
            }
        }
    }
}

private const val LINGER_MS = 600L

/** Cuánto se queda en pantalla una respuesta leída sin voz: 3 s o 60 ms por carácter, lo que sea más. */
internal fun captionMs(text: String): Long = maxOf(3_000L, text.length * 60L)
