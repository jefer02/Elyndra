package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.Hero
import com.elyndra.launcher.ui.components.LogoImage
import com.elyndra.launcher.ui.components.PlayGlyph
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.HeroTitleShadow
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.animFadeIn
import com.elyndra.launcher.ui.theme.animPopIn
import com.elyndra.launcher.ui.theme.animTitleIn
import com.elyndra.launcher.ui.theme.curtainAlpha
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.pulseHintAlpha
import com.elyndra.launcher.ui.theme.romScrimBrush
import com.elyndra.launcher.ui.theme.selectionLift
import com.elyndra.launcher.ui.theme.selectionScale
import com.elyndra.launcher.ui.theme.sheenBrush
import com.elyndra.launcher.ui.theme.sheenProgress

@Composable
fun FolderScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val skin = LocalSkin.current
    val item = vm.currentFolder()
    if (item == null) {
        LaunchedEffect(Unit) { vm.go(Screen.Library) }
        return
    }
    val roms = vm.folderRoms(item.folder.id)
    val rom = vm.selectedRom()
    val emulatorLabel = item.emulatorName ?: stringResource(R.string.choose_emulator)

    Column(Modifier.fillMaxSize().animFadeIn(key = Screen.Folder)) {

        Hero(
            pairIndex = rom?.let { vm.romPairIndex(it) } ?: item.system.pair,
            heroKey = rom?.key ?: item.key,
            height = m.heroH,
            imagePath = rom?.let { it.meta.hero ?: it.meta.screenshot },
            topBar = {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(start = m.pad, end = m.pad, top = if (m.landscape) 8.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .darkGlass(RoundedCornerShape(12.dp))
                            .clickable { vm.go(Screen.Library) },
                        contentAlignment = Alignment.Center,
                    ) { BackChevron(color = Color.White) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        ElyText(item.system.name, size = 12.5f, weight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        ElyText(
                            item.folder.displayPath,
                            size = 8.5f,
                            color = Color.White.copy(alpha = 0.72f),
                            letterSpacing = tracking(0.1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .alpha(if (item.emulatorInstalled) 1f else 0.6f)
                            .darkGlass(RoundedCornerShape(11.dp))
                            .clickable { vm.pickFolderEmulator(item.folder) }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        ElyText(emulatorLabel, size = 10f, weight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                    }
                }
            },
            info = {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = m.pad, end = m.pad, bottom = if (m.landscape) 8.dp else 18.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = if (m.landscape) 3.dp else 6.dp),
                    ) {
                        HeroChip(stringResource(R.string.rom_chip, item.system.short))
                        rom?.meta?.ra?.takeIf { it.achievements > 0 }?.let { ra ->
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .darkGlass(RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                ElyText("🏆 ${ra.earned}/${ra.achievements}", size = 8.5f, weight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        ElyText(
                            (rom?.let { playedLabel(it) } ?: "") + (rom?.let { " · " + it.extension } ?: ""),
                            size = 9.5f,
                            weight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.85f),
                            letterSpacing = tracking(0.1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val logo = rom?.meta?.logo
                    if (logo != null) {
                        LogoImage(
                            logo,
                            Modifier
                                .animTitleIn(key = rom.key)
                                .fillMaxWidth(0.72f)
                                .height(m.logoH),
                        )
                    } else {
                        ElyText(
                            rom?.displayTitle ?: item.system.name,
                            modifier = Modifier.animTitleIn(key = rom?.key ?: item.key),
                            size = m.titleSize,
                            weight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = tracking(-0.03f),
                            lineHeightRatio = 0.92f,
                            shadow = HeroTitleShadow,
                            uppercase = true,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ElyText(
                        stringResource(R.string.hint_gestures),
                        modifier = Modifier
                            .padding(top = if (m.landscape) 4.dp else 7.dp)
                            .alpha(pulseHintAlpha()),
                        size = 9f,
                        weight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.8f),
                        letterSpacing = tracking(0.14f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        uppercase = true,
                    )
                }
            },
        )

        // ── ROMS ──
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = m.pad, end = m.pad, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ElyText(stringResource(R.string.roms_header), size = 9.5f, weight = FontWeight.SemiBold, color = P.ink2, letterSpacing = tracking(0.24f))
                Spacer(Modifier.weight(1f))
                ElyText(
                    pluralStringResource(R.plurals.roms_summary, roms.size, roms.size, fmtMinutes(item.minutes)),
                    size = 9f,
                    weight = FontWeight.Medium,
                    color = P.ink2.copy(alpha = 0.8f),
                    letterSpacing = tracking(0.18f),
                    uppercase = true,
                )
            }

            if (roms.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = m.pad),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ElyText(stringResource(R.string.folder_empty), size = 11f, color = P.ink2, align = TextAlign.Center, lineHeightRatio = 1.5f)
                    Spacer(Modifier.height(10.dp))
                    GhostButton(stringResource(R.string.rescan_folder), { vm.rescanFolder(item.folder) })
                }
            } else {
                LazyRow(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(
                        start = m.pad,
                        end = m.pad,
                        // Hueco extra arriba para la card seleccionada, que sube y se amplía.
                        top = if (m.landscape) 12.dp else 14.dp,
                        bottom = if (m.landscape) 6.dp else 10.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    itemsIndexed(roms, key = { _, r -> r.id }) { i, r ->
                        RomTile(
                            rom = r,
                            time = playedLabel(r),
                            index = i,
                            selected = r.key == rom?.key,
                            pairIndex = vm.romPairIndex(r),
                            width = m.romW,
                            height = m.romTileH,
                            onTap = { vm.selectRom(r.key) },
                            onOpen = { vm.openRom(r) },
                            onLongPress = {
                                vm.selectRom(r.key)
                                vm.romOptions(r)
                            },
                        )
                    }
                }
            }
        }

        // ── DOCK ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = m.pad, end = m.pad, bottom = if (m.landscape) 6.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .height(34.dp)
                    .glass(RoundedCornerShape(12.dp))
                    .clickable { vm.go(Screen.Library) }
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron(size = 13.dp)
                Spacer(Modifier.width(5.dp))
                ElyText(stringResource(R.string.library_back), size = 11.5f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 1)
            }

            Row(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .alpha(if (rom != null) 1f else 0.45f)
                    .shadow(10.dp, RoundedCornerShape(12.dp), clip = false, ambientColor = P.shade.copy(alpha = 0.24f), spotColor = P.shade.copy(alpha = 0.24f))
                    .clip(RoundedCornerShape(12.dp))
                    .drawBehind { drawRect(accentGradient(skin, 145f, size)) }
                    .clickable(enabled = rom != null) { rom?.let { vm.openRom(it) } },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayGlyph()
                Spacer(Modifier.width(7.dp))
                ElyText(
                    stringResource(R.string.open_in, rom?.emulatorId?.let { vm.emulatorName(it) } ?: emulatorLabel),
                    size = 12.5f,
                    weight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun playedLabel(rom: RomEntry): String =
    if (rom.stats.minutes > 0) stringResource(R.string.played_time, fmtMinutes(rom.stats.minutes))
    else stringResource(R.string.never_played)

/** Card de ROM: carátula vertical, etiqueta de extensión y título si no hay carátula. */
@Composable
private fun RomTile(
    rom: RomEntry,
    time: String,
    index: Int,
    selected: Boolean,
    pairIndex: Int,
    width: Dp,
    height: Dp,
    onTap: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(12.dp)
    val lift = selectionLift(selected)
    val scale = selectionScale(selected)
    val curtain = curtainAlpha(minOf(index, 12) * 40, key = rom.id)
    val sheen = sheenProgress()
    val cover = rom.meta.cover

    Column(
        Modifier
            .width(width)
            .offset(y = lift)
            .animPopIn(delayMs = minOf(index, 12) * 35, key = rom.id)
            .pointerInput(rom.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onOpen() },
                    onLongPress = { onLongPress() },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    if (selected) 16.dp else 8.dp,
                    shape,
                    clip = false,
                    ambientColor = P.shade.copy(alpha = if (selected) 0.32f else 0.2f),
                    spotColor = P.shade.copy(alpha = if (selected) 0.32f else 0.2f),
                )
                .clip(shape)
                .border(
                    if (selected) 6.dp else 1.dp,
                    if (selected) skin.a1 else P.hairline,
                    shape,
                ),
        ) {
            ArtImage(cover, pairIndex, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)

            if (cover == null) {
                rom.meta.icon?.let { icon ->
                    GameIcon(
                        icon,
                        null,
                        Modifier
                            .align(Alignment.Center)
                            .padding(bottom = height * 0.2f, start = width * 0.1f, end = width * 0.1f)
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                }
                Box(Modifier.fillMaxSize().drawBehind { drawRect(romScrimBrush(size)) })
                ElyText(
                    rom.displayTitle,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 7.dp, vertical = 6.dp),
                    size = 9.5f,
                    weight = FontWeight.SemiBold,
                    color = Color.White,
                    lineHeightRatio = 1.15f,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    // Va encima de la carátula, no de una superficie del tema: se queda
                    // clara con tinta oscura en ambos modos, que es lo que se lee.
                    .background(Color.White.copy(alpha = 0.82f))
                    .padding(horizontal = 5.dp, vertical = 3.dp),
            ) {
                ElyText(rom.extension.ifEmpty { "DIR" }, size = 7f, weight = FontWeight.SemiBold, color = P.shade, letterSpacing = tracking(0.1f))
            }

            if (selected) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.36f)
                        .graphicsLayer { translationX = size.width / 0.36f * sheen }
                        .drawBehind { drawRect(sheenBrush(size)) },
                )
            }

            if (curtain > 0f) {
                Box(Modifier.fillMaxSize().alpha(curtain).background(P.paper))
            }
        }

        Spacer(Modifier.height(6.dp))
        ElyText(
            if (cover != null) rom.displayTitle else time,
            size = 8.5f,
            weight = FontWeight.Medium,
            color = if (selected) P.ink else P.ink2,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
