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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.ui.theme.Space
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.components.StatusStrip
import com.elyndra.launcher.ui.components.SettingsGlyph
import com.elyndra.launcher.ui.components.SearchGlyph
import com.elyndra.launcher.ui.components.OpenButton
import com.elyndra.launcher.ui.components.ConsoleIconButton
import com.elyndra.launcher.ui.TopBarLayout
import com.elyndra.launcher.ui.DotTabsLayout
import com.elyndra.launcher.core.device.StatusMode
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.DotTabs
import com.elyndra.launcher.ui.components.Hero
import com.elyndra.launcher.ui.components.LocalScreenSize
import com.elyndra.launcher.ui.components.SortButton
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.theme.DefaultSkin
import com.elyndra.launcher.ui.theme.ElyndraTheme
import com.elyndra.launcher.ui.theme.shelfSurface

/*
 * La pantalla principal en vertical (el hero del carrusel): móvil en claro y
 * en oscuro, móvil estrecho y tableta, con un juego sin logo y nombre largo
 * (el titular se ajusta a dos líneas) y uno corto. El arte es el de reserva
 * del juego (la vista previa no carga archivos). La barra de arriba y el dock
 * con el orden son los de verdad ([PreviewHeroBar], [DockWithSort]): en el
 * móvil "Abrir" se queda en su icono y en el estrecho la píldora pierde el
 * tanto por ciento, pero Ajustes cabe siempre.
 */

@Preview(name = "Principal · móvil vertical · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 412, heightDp = 892)
@Preview(name = "Principal · móvil vertical · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 412, heightDp = 892)
@Preview(name = "Principal · móvil vertical estrecho · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360, heightDp = 780)
@Preview(name = "Principal · móvil vertical estrecho · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 780)
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
                            PreviewHeroBar(
                                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = m.pad, end = m.pad, top = Space.m),
                                landscape = false,
                                masha = true,
                            )
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
                DockWithSort(
                    Modifier.align(Alignment.TopCenter).offset(y = m.iconHeroH - (DotTabsLayout.HEIGHT / 2f).dp),
                    tabs = { DotTabs(labels = listOf("Todo", "Android", "Consolas"), selected = 0, onSelect = {}, light = !P.isDark) },
                    sort = { SortButton(description = "Ordenar", open = false, onClick = {}, light = !P.isDark) },
                )
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

/**
 * La barra del hero de las vistas previas: las piezas de verdad ("Abrir",
 * píldora, buscar y Ajustes) compactadas con la misma decisión que la
 * biblioteca ([rememberTopBarFit]). El emblema de Masha ([masha]) es un
 * círculo del acento: el de verdad necesita el ViewModel. Con [sort], el
 * botón de orden delante del buscador (la barra de Meridian). La píldora
 * de hora y batería ([status]) sale como en una instalación nueva: apagada.
 */
@Composable
internal fun PreviewHeroBar(modifier: Modifier, landscape: Boolean, masha: Boolean, sort: Boolean = false, grouped: Boolean = false, status: Boolean = SettingsStore.DEFAULT_STATUS_VISIBLE) = BoxWithConstraints(modifier) {
    val fit = rememberTopBarFit(maxWidth.value, landscape, StatusMode.DEFAULT, statusVisible = status, searchOpen = false, masha = masha, sort = sort)
    // [grouped] (Meridian): Masha abre el grupo de la derecha, pegada a la píldora; si no, sola a la izquierda.
    Row(if (grouped) Modifier else Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (masha) Box(Modifier.size(TopBarLayout.MASHA.dp).background(LocalSkin.current.a1, CircleShape))
        if (grouped) Spacer(Modifier.width(Space.s)) else Spacer(Modifier.weight(1f))
        if (fit.status) {
            StatusStrip(StatusMode.DEFAULT, showPercent = fit.statusPercent)
            Spacer(Modifier.width(Space.s))
        }
        OpenButton(enabled = true, showLabel = fit.openLabel, onClick = {})
        Spacer(Modifier.width(Space.s))
        if (sort) {
            SortButton(description = "Ordenar", open = false, onClick = {})
            Spacer(Modifier.width(Space.s))
        }
        ConsoleIconButton(onClick = {}) { glyph -> SearchGlyph(glyph) }
        Spacer(Modifier.width(Space.s))
        ConsoleIconButton(onClick = {}) { glyph -> SettingsGlyph(glyph) }
    }
}
