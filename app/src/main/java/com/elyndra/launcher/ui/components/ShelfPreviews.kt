package com.elyndra.launcher.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.ShelfLayout
import com.elyndra.launcher.ui.theme.HeroTitleShadow
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.liquidGlass
import com.elyndra.launcher.ui.theme.shelfColor
import com.elyndra.launcher.ui.theme.shelfSurface

/*
 * El estante con la nueva geometría y la extensión del arte del hero: una
 * escena mínima (fondo, velos, titular, cards y nombres) con arte muy claro,
 * muy oscuro y sin arte, en claro y en oscuro, y en una ventana alta y una
 * baja. Las imágenes son degradados: la vista previa no carga archivos.
 */

@Preview(name = "Alta · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 380, heightDp = 640)
@Preview(name = "Alta · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 380, heightDp = 640)
annotation class TallPreviews

@Preview(name = "Baja · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 720, heightDp = 360)
@Preview(name = "Baja · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 720, heightDp = 360)
annotation class ShortPreviews

private val BrightArt = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFFFF1B8), Color(0xFFBFE6FF), Color(0xFFFFFFFF)))
private val DarkArt = Brush.verticalGradient(listOf(Color(0xFF020204), Color(0xFF1C0F2C), Color(0xFF05070C)))

/**
 * Hero + estante. [art] null: el arte de reserva de un juego sin fondo.
 * [heroFraction] del alto para el hero (el resto es el estante).
 */
@Composable
private fun Scene(art: Brush?, heroFraction: Float, label: String) {
    val skin = LocalSkin.current
    val shelfTint = shelfColor()
    BoxWithConstraints(Modifier.fillMaxSize().background(P.paper)) {
        val heroH = maxHeight * heroFraction
        val shelfH = (maxHeight - heroH).value
        val r = ShelfLayout.compute(
            available = shelfH,
            hintsVisible = false,
            aspect = 1f,
            base = (shelfH * 0.5f).coerceAtMost(150f),
            topInset = 0f,
            labelBlock = ShelfLayout.LABEL_GAP + 9.5f * 1.45f,
            minTile = 72f,
            maxTile = 185f,
        )
        val ext: Dp = ShelfLayout.artExtension(shelfH, maxHeight.value, r.labelTop).dp
        // El fondo del hero con su extensión y sus velos, como en `Hero`.
        Box(Modifier.fillMaxWidth().height(heroH + ext).clipToBounds()) {
            if (art != null) {
                Box(Modifier.fillMaxSize().background(art))
            } else {
                FallbackArt(ArtFallback(key = "preview-$label", title = label), ArtVariant.Banner, Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().heroScrims(heroH, art = art != null, skin.scrim))
            Box(Modifier.fillMaxSize().shelfRise(heroH, shelfTint))
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(heroH).padding(18.dp), contentAlignment = Alignment.BottomStart) {
                Column {
                    ElyText(label, size = 30f, weight = FontWeight.ExtraBold, color = Color.White, shadow = HeroTitleShadow, uppercase = true, maxLines = 1)
                    ElyText("Una sinopsis corta del juego en dos líneas como mucho.", size = 10f, color = Color.White.copy(alpha = 0.72f), maxLines = 2)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).shelfSurface(ext)) {
                Row(
                    Modifier.padding(start = 18.dp).offset(y = r.tileTop.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(4) { i -> PreviewTile(r.tileHeight.dp, i == 0, listOf("Celeste", "Hades", "Gris", "Inside")[i]) }
                }
            }
        }
    }
}

@Composable
private fun PreviewTile(side: Dp, selected: Boolean, name: String) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.width(side).offset(y = if (selected) (-10).dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(side)
                .liquidGlass(shape, P.hairline)
                .background(Brush.linearGradient(listOf(skin.a1.copy(alpha = 0.55f), skin.secondary.copy(alpha = 0.35f))), shape),
        )
        Spacer(Modifier.height(6.dp))
        ElyText(
            name,
            size = 9.5f,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) P.ink else P.ink2,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SceneTheme(content: @Composable () -> Unit) = PreviewTheme { Box(Modifier.fillMaxSize()) { content() } }

@TallPreviews
@Composable
private fun ExtensionBrightTallPreview() = SceneTheme { Scene(BrightArt, heroFraction = 0.62f, label = "Arte claro") }

@TallPreviews
@Composable
private fun ExtensionDarkTallPreview() = SceneTheme { Scene(DarkArt, heroFraction = 0.62f, label = "Arte oscuro") }

@TallPreviews
@Composable
private fun ExtensionNoArtTallPreview() = SceneTheme { Scene(null, heroFraction = 0.62f, label = "Sin arte") }

@ShortPreviews
@Composable
private fun ExtensionBrightShortPreview() = SceneTheme { Scene(BrightArt, heroFraction = 0.54f, label = "Arte claro") }

@ShortPreviews
@Composable
private fun ExtensionDarkShortPreview() = SceneTheme { Scene(DarkArt, heroFraction = 0.54f, label = "Arte oscuro") }

@ShortPreviews
@Composable
private fun ExtensionNoArtShortPreview() = SceneTheme { Scene(null, heroFraction = 0.54f, label = "Sin arte") }
