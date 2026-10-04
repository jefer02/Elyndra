package com.elyndra.launcher.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.ArtVariant
import com.elyndra.launcher.ui.components.ConsolePreviews
import com.elyndra.launcher.ui.components.FallbackArt
import com.elyndra.launcher.ui.components.PreviewTheme
import com.elyndra.launcher.ui.theme.liquidGlass

/*
 * El marco de selección sobre cada tipo de card del carrusel, en claro y en
 * oscuro: icono (cuadrada, 16 dp), carátula vertical, carátula apaisada y la
 * carátula de reserva sin arte (12 dp). En la vista previa estática el marco
 * sale entero y las partículas ya repartidas por su vida.
 */

private val IconShape = RoundedCornerShape(16.dp)
private val CoverShape = RoundedCornerShape(12.dp)

@Composable
private fun TileRow(glow: Boolean, particles: Boolean) {
    val look = rememberSelectionLook(SettingsStore.DEFAULT_SELECTION_PARTICLE_COLOR, glow, particles)
    Row(
        Modifier.padding(vertical = 20.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewTile(64.dp, 64.dp, IconShape, look) {
            Box(Modifier.fillMaxSize().liquidGlass(IconShape, P.hairline).background(Brush.linearGradient(listOf(Color(0xFF4E56D8), Color(0xFF5BC3DC)))))
        }
        PreviewTile(52.dp, 78.dp, CoverShape, look) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF7F3E8), Color(0xFFE9D9B0), Color(0xFFFFFFFF)))))
        }
        PreviewTile(84.dp, 54.dp, CoverShape, look) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF05060A), Color(0xFF1B1030)))))
        }
        PreviewTile(52.dp, 78.dp, CoverShape, look) {
            FallbackArt(ArtFallback(key = "preview", title = "Chrono Quest"), ArtVariant.Cover, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PreviewTile(width: Dp, height: Dp, shape: Shape, look: SelectionLook, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(width, height)
            .selectionFrame(selected = true, look = look, shape = shape)
            .clip(shape),
    ) { content() }
}

@ConsolePreviews
@Composable
private fun SelectionGlowPreview() = PreviewTheme { TileRow(glow = true, particles = false) }

@ConsolePreviews
@Composable
private fun SelectionGlowAndStardustPreview() = PreviewTheme { TileRow(glow = true, particles = true) }

@ConsolePreviews
@Composable
private fun SelectionStardustOnlyPreview() = PreviewTheme { TileRow(glow = false, particles = true) }

@ConsolePreviews
@Composable
private fun SelectionPlainRingPreview() = PreviewTheme { TileRow(glow = false, particles = false) }
