package com.elyndra.launcher.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.ui.ClassicLayout
import com.elyndra.launcher.ui.ShelfLayout
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.ArtVariant
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.FallbackArt
import com.elyndra.launcher.ui.components.PreviewTheme
import com.elyndra.launcher.ui.components.heroScrims
import com.elyndra.launcher.ui.components.shelfRise
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.selection.rememberSelectionLook
import com.elyndra.launcher.ui.selection.selectionFrame
import com.elyndra.launcher.ui.theme.HeroText
import com.elyndra.launcher.ui.theme.LocalHeroText
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.shelfColor
import com.elyndra.launcher.ui.theme.shelfSurface
import androidx.compose.runtime.CompositionLocalProvider

/*
 * El Clásico de una carpeta con el reparto de verdad ([ClassicLayout]): la
 * barra, el bloque del hero ya compactado (fichas, logo, pista y ficha de la
 * carpeta, o esa ficha en la barra), la fila con la seleccionada a tamaño
 * completo y las vecinas encogidas desde el pie, y los nombres en una sola
 * línea. Móvil apaisado (el de la captura, 1600×720 a 2,0 = 800×360 dp, y
 * 2340×1080 = 891×411 dp), con y sin mando; vertical; tableta y TV. El arte
 * es el de reserva (la vista previa no carga archivos); el logo es su caja.
 */

@Preview(name = "Clásico · móvil 800×360 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 800, heightDp = 360)
@Preview(name = "Clásico · móvil 800×360 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 800, heightDp = 360)
@Composable
private fun ClassicPhonePreview() = PreviewTheme { ClassicSample(hints = false) }

@Preview(name = "Clásico · móvil 800×360 · mando · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 800, heightDp = 360)
@Composable
private fun ClassicPhonePadPreview() = PreviewTheme { ClassicSample(hints = true) }

@Preview(name = "Clásico · móvil 891×411 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 891, heightDp = 411)
@Composable
private fun ClassicPhoneTallPreview() = PreviewTheme { ClassicSample(hints = false) }

@Preview(name = "Clásico · vertical 360×800 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 800)
@Preview(name = "Clásico · vertical 360×800 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360, heightDp = 800)
@Composable
private fun ClassicPortraitPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true) }

/*
 * Vertical: el aire a la vista (~9 dp en móvil, ~12 en tableta, de filo
 * dibujado a filo dibujado), carátulas de ~136 dp e iconos de ~149 en un
 * móvil de 360 dp (~2,2 a la vista), el halo recortado al aire y la
 * seleccionada siempre entera (primera, en medio, última). Iconos = las cards
 * cuadradas de la biblioteca; carátulas = las de la carpeta.
 */

@Preview(name = "Vertical · móvil 360×800 · carátulas · primera", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 800)
@Composable
private fun PortraitCoversFirstPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, selected = 0) }

@Preview(name = "Vertical · móvil 360×800 · carátulas · en medio", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360, heightDp = 800)
@Composable
private fun PortraitCoversMiddlePreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, selected = 3) }

@Preview(name = "Vertical · móvil 360×800 · carátulas · última", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 800)
@Composable
private fun PortraitCoversLastPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, selected = TITLES.lastIndex) }

@Preview(name = "Vertical · móvil 360×800 · iconos · primera", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360, heightDp = 800)
@Composable
private fun PortraitIconsFirstPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, square = true, selected = 0) }

@Preview(name = "Vertical · móvil 360×800 · iconos · en medio", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 800)
@Composable
private fun PortraitIconsPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, square = true, selected = 3) }

@Preview(name = "Vertical · móvil 360×720 · iconos · en medio", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 720)
@Composable
private fun PortraitIconsMiddlePreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, square = true, selected = 3) }

@Preview(name = "Vertical · móvil 360×720 · iconos · última", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360, heightDp = 720)
@Composable
private fun PortraitIconsLastPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, square = true, selected = TITLES.lastIndex) }

@Preview(name = "Vertical · móvil 411×914 · carátulas · en medio", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 411, heightDp = 914)
@Composable
private fun PortraitPhoneHdCoversPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, selected = 3) }

@Preview(name = "Vertical · tableta 800×1280 · iconos · en medio", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 800, heightDp = 1280)
@Composable
private fun PortraitTabletIconsPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, square = true, selected = 3) }

@Preview(name = "Vertical · tableta 800×1280 · carátulas · última", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 800, heightDp = 1280)
@Composable
private fun PortraitTabletCoversPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, selected = TITLES.lastIndex) }

@Preview(name = "Vertical · tableta 601×962 · iconos · en medio", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 601, heightDp = 962)
@Composable
private fun PortraitTabletSmallIconsPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, square = true, selected = 3) }

@Preview(name = "Vertical · tableta 601×962 · carátulas · en medio", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 601, heightDp = 962)
@Composable
private fun PortraitTabletSmallCoversPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true, selected = 3) }

@Preview(name = "Clásico · tableta 1280×800 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 1280, heightDp = 800)
@Composable
private fun ClassicTabletPreview() = PreviewTheme { ClassicSample(hints = false, synopsis = true) }

@Preview(name = "Clásico · TV 960×540 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 960, heightDp = 540)
@Composable
private fun ClassicTvPreview() = PreviewTheme { ClassicSample(hints = true) }

private val TITLES = listOf("Tekken 6", "God of War: Chains of Olympus", "Daxter", "Lumines", "Patapon", "Wipeout Pure", "Ridge Racer")

@Composable
private fun ClassicSample(hints: Boolean, synopsis: Boolean = false, square: Boolean = false, selected: Int = 1) {
    val skin = LocalSkin.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize().background(P.paper)) {
        val landscape = maxWidth > maxHeight
        val pad = if (landscape) 22f else 18f
        val bar = ClassicLayout.barBottom(landscape)
        val bottomPad = ClassicLayout.heroBottomPad(landscape)
        val chips = ClassicLayout.chipsHeight(fontScale, landscape)
        val merged = ClassicLayout.mergedChipsHeight(fontScale, landscape)
        val labelBlock = ShelfLayout.LABEL_GAP + 8.5f * 1.45f * fontScale
        val frame = ClassicLayout.frame(
            maxWidth.value, maxHeight.value, if (square) 1f else 2f / 3f, hints, labelBlock, topInset = 0f,
            heroMin = ClassicLayout.heroMin(bar, bottomPad, ClassicLayout.Block(chips, merged, logoMax = ClassicLayout.LOGO_MIN)),
        )
        val secondary = when {
            synopsis -> ClassicLayout.synopsisHeight(fontScale, landscape)
            !hints -> ClassicLayout.hintHeight(fontScale, landscape)
            else -> 0f
        }
        val logoMax = ClassicLayout.logoHeight(frame.heroHeight, landscape) * (if (landscape) FOLDER_LOGO_HEIGHT_LANDSCAPE else 1f)
        val plan = ClassicLayout.heroPlan(
            ClassicLayout.heroRoom(frame.heroHeight, bar, bottomPad),
            ClassicLayout.Block(
                chips, merged, logoMax,
                secondary = secondary,
                stats = ClassicLayout.STATS_BLOCK,
                secondaryLines = if (synopsis) ClassicLayout.synopsisLines(landscape) else 1,
                secondaryLine = if (synopsis) ClassicLayout.synopsisLineHeight(fontScale) else 0f,
            ),
        )
        val heroH = frame.heroHeight.dp
        val ext = ShelfLayout.artExtension(frame.shelfHeight, maxHeight.value, frame.labelTop).dp
        // Aire, escala de las vecinas, halo y nombres, como en la pantalla de verdad.
        val rowStyle = ClassicLayout.rowStyle(maxWidth.value - 2 * pad, frame.tileWidth, landscape)
        val gap = rowStyle.gap
        // Dónde quedaría la fila desplazada hasta [selected] (como la LazyRow con ClassicLayout.anchor).
        val (selX, _) = ClassicLayout.selectedSpan(selected, TITLES.size, frame.tileWidth, gap, maxWidth.value, landscape)
        val rowX = selX - selected * (frame.tileWidth + gap)

        // El fondo del hero con su extensión y sus velos, como en `Hero`.
        Box(Modifier.fillMaxWidth().height(heroH + ext).clipToBounds()) {
            FallbackArt(ArtFallback(key = "classic-preview", title = TITLES[0]), ArtVariant.Banner, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().heroScrims(heroH, art = false, skin.scrim))
            Box(Modifier.fillMaxSize().shelfRise(heroH, shelfColor()))
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(heroH)) {
                // La barra: volver, "Abrir", emulador y, a la derecha, el sistema (y la ficha si sube).
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = pad.dp, end = pad.dp, top = (bar - ClassicLayout.BAR_HEIGHT).dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(3) { Box(Modifier.width(if (it == 0) 34.dp else 72.dp).height(34.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f))) }
                    Spacer(Modifier.weight(1f))
                    if (plan.statsInBar) PreviewStats(short = true)
                    Column(horizontalAlignment = Alignment.End) {
                        ElyText("PlayStation Portable", size = 13f, weight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                        ElyText("ROMs/psp", size = 9f, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                    }
                }
                CompositionLocalProvider(LocalHeroText provides HeroText.CLEAN) {
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(top = (bar + ClassicLayout.BAR_GAP).dp)
                            .padding(start = pad.dp, end = pad.dp, bottom = bottomPad.dp),
                    ) {
                        if (plan.mergedChips) {
                            MergedChipLine(listOf("PSP", "Not played yet", "ZIP"), landscape)
                        } else {
                            HeroChipRow("ROM · PSP", "Not played yet · ZIP", landscape)
                        }
                        // La caja del logo, del alto que da el plan.
                        Box(
                            Modifier
                                .width((plan.logoHeight * 2.4f).dp)
                                .height(plan.logoHeight.dp)
                                .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.35f))), RoundedCornerShape(6.dp)),
                        )
                        if (plan.showSecondary) {
                            if (synopsis) {
                                HeroSynopsis("A fighting game with a long story mode, dozens of characters and a synopsis that needs two lines at least.", landscape, maxLines = plan.secondaryLines)
                            } else {
                                ElyText(
                                    "TAP TO SELECT · TAP AGAIN TO OPEN · HOLD FOR OPTIONS",
                                    modifier = Modifier.padding(top = if (landscape) 4.dp else 7.dp),
                                    size = ClassicLayout.HINT_SP,
                                    weight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.8f),
                                    letterSpacing = tracking(0.14f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (!plan.statsInBar) {
                            Spacer(Modifier.height(ClassicLayout.STATS_GAP.dp))
                            PreviewStats(short = false)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).shelfSurface(ext)) {
                Row(
                    Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .offset(x = rowX.dp, y = frame.tileTop.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap.dp),
                ) {
                    TITLES.forEachIndexed { i, title -> PreviewCover(frame, rowStyle, title, selected = i == selected, square = square) }
                }
            }
        }
    }
}

/**
 * Una card del Clásico (carátula o icono cuadrado): la caja de todas, la
 * escala desde el pie, el marco de la selección con el halo de la fila y el
 * nombre en su línea.
 */
@Composable
private fun PreviewCover(frame: ClassicLayout.Frame, rowStyle: ClassicLayout.RowStyle, title: String, selected: Boolean, square: Boolean) {
    val shape = RoundedCornerShape(if (square) 16.dp else 12.dp)
    val look = rememberSelectionLook(SettingsStore.DEFAULT_SELECTION_PARTICLE_COLOR, glow = true, particles = false)
    // Estados fijos (sin muelle): la vista previa enseña el reposo.
    val motion = remember(selected, rowStyle.neighborScale) {
        ClassicCardMotion(
            mutableStateOf(if (selected) 1f else rowStyle.neighborScale),
            mutableStateOf(if (selected) (-ClassicLayout.LIFT).dp else 0.dp),
        )
    }
    Column(Modifier.width(frame.tileWidth.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(frame.tileHeight.dp)
                .classicCard(motion)
                .selectionFrame(selected, look, shape, rowStyle.haloSpreadDp())
                .clip(shape),
        ) {
            FallbackArt(ArtFallback(key = "cover-$title", title = title), if (square) ArtVariant.Square else ArtVariant.Cover, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(ShelfLayout.LABEL_GAP.dp))
        ElyText(
            title,
            size = 8.5f,
            weight = FontWeight.Medium,
            color = if (selected) P.ink else P.ink2,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = rowStyle.labelInset.dp),
        )
    }
}

/** La ficha de la carpeta ("4 ROMs · 0h 0m"), con la lámina del dock. */
@Composable
private fun PreviewStats(short: Boolean) {
    Box(
        Modifier
            .height(ClassicLayout.STATS_H.dp)
            .clip(RoundedCornerShape(50))
            .background(if (P.isDark) Color(0xEE1A1D2A) else Color(0xF2F7F5F0))
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        ElyText(if (short) "4 ROMS" else "4 ROMS · 0H 0M", size = 8.5f, weight = FontWeight.SemiBold, color = P.ink2, letterSpacing = tracking(0.16f), maxLines = 1)
    }
}
