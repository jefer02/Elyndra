package com.elyndra.launcher.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.DotTabs
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.Hero
import com.elyndra.launcher.ui.components.LocalScreenSize
import com.elyndra.launcher.ui.components.SortButton
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.theme.DefaultSkin
import com.elyndra.launcher.ui.theme.ElyndraTheme
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.shelfSurface

/*
 * La pantalla principal en vertical (el hero del carrusel): móvil en claro y
 * en oscuro y tableta, con un juego sin logo y nombre largo (el titular se
 * ajusta a dos líneas) y uno corto. El arte es el de reserva del juego (la
 * vista previa no carga archivos).
 */

@Preview(name = "Principal · móvil vertical · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 412, heightDp = 892)
@Preview(name = "Principal · móvil vertical · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 412, heightDp = 892)
@Preview(name = "Principal · tableta vertical · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 800, heightDp = 1280)
@Preview(name = "Principal · tableta vertical · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 800, heightDp = 1280)
annotation class MainPortraitPreviews

@Composable
private fun PortraitSample(name: String, synopsis: String) {
    val configuration = LocalConfiguration.current
    val night = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    remember(night) { P.isDark = night }
    ElyndraTheme(DefaultSkin, landscape = false) {
        CompositionLocalProvider(LocalScreenSize provides DpSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)) {
            Box(Modifier.fillMaxSize().background(P.paper)) {
                val m = metrics()
                Column(Modifier.fillMaxSize()) {
                    Hero(
                        fallback = ArtFallback("preview:$name", name),
                        heroKey = name,
                        height = m.iconHeroH,
                        topBar = {
                            Row(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = m.pad)) {
                                Box(Modifier.height(34.dp).darkGlass(RoundedCornerShape(12.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                    ElyText("21:47 · 84 %", size = 11f, weight = FontWeight.SemiBold, color = Color.White)
                                }
                            }
                        },
                        info = {
                            Column(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(start = m.pad, end = m.pad, bottom = 18.dp),
                            ) {
                                HeroChipRow(stringResource(R.string.chip_android_app), stringResource(R.string.never_played), landscape = false)
                                PortraitTitle(name, m.titleSize)
                                HeroSynopsis(synopsis, landscape = false)
                            }
                        },
                    )
                    Box(Modifier.fillMaxWidth().weight(1f).shelfSurface())
                }
                // El dock en la costura: cristal claro en claro, el oscuro de siempre en oscuro.
                Row(Modifier.align(Alignment.TopCenter).offset(y = m.iconHeroH - 17.dp)) {
                    DotTabs(labels = listOf("Todo", "Android", "Consolas"), selected = 0, onSelect = {}, light = !P.isDark)
                    Spacer(Modifier.width(8.dp))
                    SortButton(description = "Ordenar", open = false, onClick = {}, light = !P.isDark)
                }
            }
        }
    }
}

/** Sin logo y con nombre largo: dos líneas, del mayor cuerpo que cabe. */
@MainPortraitPreviews
@Composable
private fun PortraitLongNameNoLogoPreview() = PortraitSample(
    "Minecraft: Dream it, Build it!",
    "Crea lo que sea que puedas imaginar en tu propio mundo infinito que es único cada vez que lo juegues.",
)

/** Nombre corto: el tope de cuerpo de la vertical (ya no "MINEC…"). */
@MainPortraitPreviews
@Composable
private fun PortraitShortNamePreview() = PortraitSample("Minecraft", "Crea lo que sea que puedas imaginar en tu propio mundo infinito.")
