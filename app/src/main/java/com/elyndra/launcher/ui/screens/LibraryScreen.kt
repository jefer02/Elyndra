package com.elyndra.launcher.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.LibraryFilter
import com.elyndra.launcher.ui.LibraryItem
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.components.AppIconImage
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.Hero
import com.elyndra.launcher.ui.components.LogoImage
import com.elyndra.launcher.ui.components.Metrics
import com.elyndra.launcher.ui.components.PlayGlyph
import com.elyndra.launcher.ui.components.SearchGlyph
import com.elyndra.launcher.ui.components.SettingsGlyph
import com.elyndra.launcher.ui.components.inputStyle
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.HeroTitleShadow
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Swift
import com.elyndra.launcher.ui.theme.WordmarkShadow
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.animFadeIn
import com.elyndra.launcher.ui.theme.animPopIn
import com.elyndra.launcher.ui.theme.animTitleIn
import com.elyndra.launcher.ui.theme.bobOffset
import com.elyndra.launcher.ui.theme.consoleFaceBrush
import com.elyndra.launcher.ui.theme.curtainAlpha
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.pulseHintAlpha
import com.elyndra.launcher.ui.theme.ringProgress
import com.elyndra.launcher.ui.theme.selectionLift
import com.elyndra.launcher.ui.theme.sheenBrush
import com.elyndra.launcher.ui.theme.sheenProgress
import com.elyndra.launcher.ui.theme.tileGlossBrush

@Composable
fun LibraryScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val skin = LocalSkin.current
    val items = vm.items()
    val sel = vm.selected()
    val libraryEmpty = vm.loaded && vm.library.folders.isEmpty() && vm.library.apps.isEmpty()

    Column(Modifier.fillMaxSize().animFadeIn(key = Screen.Library)) {

        Hero(
            pairIndex = sel?.let { vm.pairIndexOf(it) } ?: 0,
            heroKey = sel?.key ?: "none",
            height = m.heroH,
            imagePath = when (sel) {
                is LibraryItem.App -> sel.app.meta.hero ?: sel.app.meta.screenshot
                is LibraryItem.Folder -> sel.heroPath
                null -> null
            },
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
                            .offset(y = bobOffset().dp)
                            .size(9.dp)
                            .shadow(6.dp, RoundedCornerShape(3.dp), clip = false, ambientColor = skin.a1, spotColor = skin.a1)
                            .clip(RoundedCornerShape(3.dp))
                            .background(skin.a1),
                    )
                    Spacer(Modifier.width(8.dp))
                    ElyText(
                        "ELYNDRA",
                        size = if (m.landscape) 17f else 19f,
                        weight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = tracking(0.16f),
                        shadow = WordmarkShadow,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    SearchField(vm, m)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(34.dp)
                            .darkGlass(RoundedCornerShape(12.dp))
                            .clickable { vm.go(Screen.Settings) },
                        contentAlignment = Alignment.Center,
                    ) { SettingsGlyph() }
                }
            },
            info = {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = m.pad, end = m.pad, bottom = if (m.landscape) 8.dp else 18.dp),
                ) {
                    if (sel != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = if (m.landscape) 3.dp else 6.dp),
                        ) {
                            HeroChip(
                                stringResource(
                                    if (sel is LibraryItem.Folder) R.string.chip_emulator_folder else R.string.chip_android_app,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            ElyText(
                                heroSubline(sel),
                                size = 9.5f,
                                weight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.85f),
                                letterSpacing = tracking(0.1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val logo = (sel as? LibraryItem.App)?.app?.meta?.logo
                    if (logo != null) {
                        LogoImage(
                            logo,
                            Modifier
                                .animTitleIn(key = sel.key)
                                .fillMaxWidth(0.72f)
                                .height(if (m.landscape) 42.dp else 64.dp),
                        )
                    } else {
                        ElyText(
                            when {
                                sel != null -> sel.name
                                vm.loaded -> stringResource(R.string.empty_library_title)
                                else -> ""
                            },
                            modifier = Modifier.animTitleIn(key = sel?.key ?: "none"),
                            size = if (m.landscape) 34f else 40f,
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
                        stringResource(if (sel == null) R.string.empty_library_hint else R.string.hint_gestures),
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

        // ── CARRUSEL ──
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = m.pad, end = m.pad, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryFilter.entries.forEach { f ->
                    FilterTab(stringResource(f.label), vm.filter == f) { vm.updateFilter(f) }
                }
                Spacer(Modifier.weight(1f))
                ElyText(
                    pluralStringResource(R.plurals.items_count, items.size, items.size),
                    size = 9f,
                    weight = FontWeight.Medium,
                    color = P.ink2.copy(alpha = 0.8f),
                    letterSpacing = tracking(0.18f),
                    uppercase = true,
                )
            }

            if (items.isEmpty() && !libraryEmpty && vm.loaded) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = m.pad), contentAlignment = Alignment.Center) {
                    ElyText(stringResource(R.string.no_results), size = 11f, color = P.ink2, align = TextAlign.Center)
                }
            } else {
                LazyRow(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(
                        start = m.pad,
                        end = m.pad,
                        top = if (m.landscape) 6.dp else 8.dp,
                        bottom = if (m.landscape) 6.dp else 10.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (libraryEmpty) {
                        item(key = "add") { AddTile(m) { vm.go(Screen.Add) } }
                    }
                    itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                        LibraryTile(
                            item = item,
                            index = i,
                            selected = item.key == sel?.key,
                            metrics = m,
                            pairIndex = vm.pairIndexOf(item),
                            onTap = { vm.select(item.key) },
                            onOpen = { vm.open(item) },
                            onLongPress = {
                                vm.select(item.key)
                                vm.itemOptions(item)
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
                .padding(start = m.pad, end = m.pad, bottom = if (m.landscape) 10.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .then(if (m.landscape) Modifier else Modifier.weight(1f))
                    .height(42.dp)
                    .glass(RoundedCornerShape(15.dp))
                    .clickable { vm.go(Screen.Add) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(20.dp).clip(RoundedCornerShape(7.dp)).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    ElyText("+", size = 14f, weight = FontWeight.SemiBold, color = skin.a2, lineHeightRatio = 1f)
                }
                Spacer(Modifier.width(8.dp))
                ElyText(
                    stringResource(if (m.landscape) R.string.add_long else R.string.add_short),
                    size = 12f,
                    weight = FontWeight.SemiBold,
                    color = P.ink,
                    maxLines = 1,
                )
            }

            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .alpha(if (sel != null) 1f else 0.45f)
                    .shadow(12.dp, RoundedCornerShape(15.dp), clip = false, ambientColor = P.ink.copy(alpha = 0.24f), spotColor = P.ink.copy(alpha = 0.24f))
                    .clip(RoundedCornerShape(15.dp))
                    .drawBehind { drawRect(accentGradient(skin, 145f, size)) }
                    .clickable(enabled = sel != null) { sel?.let { vm.open(it) } },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayGlyph()
                Spacer(Modifier.width(7.dp))
                ElyText(stringResource(R.string.open), size = 12.5f, weight = FontWeight.SemiBold, color = Color.White)
            }

            LucyFab(onClick = { vm.go(Screen.Lucy) })
        }
    }
}

@Composable
private fun heroSubline(sel: LibraryItem): String = when (sel) {
    is LibraryItem.Folder -> pluralStringResource(
        R.plurals.roms_with_emulator,
        sel.romCount,
        sel.romCount,
        sel.emulatorName ?: "—",
    )
    is LibraryItem.App -> when {
        !sel.installed -> stringResource(R.string.app_not_installed_badge)
        sel.app.stats.minutes > 0 -> stringResource(R.string.played_time, fmtMinutes(sel.app.stats.minutes))
        else -> stringResource(R.string.never_played)
    }
}

/* ─────────────────────────────────────────────────────────────
   Piezas de la biblioteca
   ───────────────────────────────────────────────────────────── */

@Composable
internal fun HeroChip(label: String) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .shadow(6.dp, RoundedCornerShape(8.dp), clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(RoundedCornerShape(8.dp))
            .drawBehind { drawRect(accentGradient(skin, 120f, size)) }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        ElyText(
            label,
            size = 8.5f,
            weight = FontWeight.Bold,
            color = Color.White,
            letterSpacing = tracking(0.16f),
            maxLines = 1,
            uppercase = true,
        )
    }
}

/** Pestaña de filtro: texto + subrayado de acento de 2dp. */
@Composable
private fun FilterTab(label: String, active: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    // El ancho lo fija el propio rótulo (IntrinsicSize.Max): sin esto la
    // primera pestaña se llevaría todo el ancho de la fila.
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .clickable(onClick = onClick)
            .padding(start = 9.dp, end = 9.dp, top = 4.dp, bottom = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ElyText(
            label,
            size = 11f,
            weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) P.ink else P.ink2.copy(alpha = 0.6f),
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .then(
                    if (active) Modifier.drawBehind { drawRect(accentGradient(skin, 90f, size)) }
                    else Modifier,
                ),
        )
    }
}

/** Buscador plegable de la barra superior. */
@Composable
private fun SearchField(vm: ElyndraViewModel, m: Metrics) {
    val open = vm.searchOpen
    // Al cerrarse, el campo suelta el foco para que el teclado no siga escribiendo en un buscador invisible.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(open) { if (!open) focusManager.clearFocus() }
    val fieldWidth by animateDpAsState(
        targetValue = if (open) (if (m.landscape) 190.dp else 96.dp) else 0.dp,
        animationSpec = tween(300, easing = Swift),
        label = "searchW",
    )
    val fieldAlpha by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(250),
        label = "searchA",
    )

    Row(
        Modifier
            .height(34.dp)
            .then(if (open) Modifier.darkGlass(RoundedCornerShape(12.dp)) else Modifier)
            .padding(start = if (open) 11.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(fieldWidth).alpha(fieldAlpha).clipToBounds()) {
            BasicTextField(
                value = vm.query,
                onValueChange = vm::updateQuery,
                enabled = open,
                singleLine = true,
                textStyle = inputStyle(12f, Color.White),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (vm.query.isEmpty()) {
                        ElyText(stringResource(R.string.search_hint), size = 12f, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
                    }
                    inner()
                },
            )
        }
        Box(
            Modifier
                .size(34.dp)
                .then(if (open) Modifier else Modifier.darkGlass(RoundedCornerShape(12.dp)))
                .clickable { vm.toggleSearch() },
            contentAlignment = Alignment.Center,
        ) { SearchGlyph() }
    }
}

/** Botón flotante de Lucy, con el aro que late. */
@Composable
private fun LucyFab(onClick: () -> Unit) {
    val skin = LocalSkin.current
    val ring = ringProgress()
    Box(
        Modifier.size(42.dp).glass(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val k = 1f + 0.85f * ring
                    scaleX = k
                    scaleY = k
                    alpha = 0.5f * (1f - ring)
                }
                .border(1.5.dp, skin.a1, RoundedCornerShape(16.dp)),
        )
        ElyText("L", size = 16f, weight = FontWeight.Bold, color = skin.a2)
    }
}

/** Primera vez: la biblioteca está vacía y la única card invita a añadir. */
@Composable
private fun AddTile(metrics: Metrics, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Column(
        Modifier.width(metrics.consoleW).animPopIn(key = "add").clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(metrics.tileH)
                .glass(RoundedCornerShape(16.dp), borderColor = skin.a1.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            ElyText("+", size = 28f, weight = FontWeight.SemiBold, color = skin.a2)
        }
        Spacer(Modifier.height(6.dp))
        ElyText(
            stringResource(R.string.add_long),
            size = 9.5f,
            weight = FontWeight.SemiBold,
            color = P.ink,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Una card del carrusel: carpeta de consola o app Android. */
@Composable
private fun LibraryTile(
    item: LibraryItem,
    index: Int,
    selected: Boolean,
    metrics: Metrics,
    pairIndex: Int,
    onTap: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val skin = LocalSkin.current
    val width: Dp = if (item is LibraryItem.Folder) metrics.consoleW else metrics.appW
    val lift = selectionLift(selected)
    val shape = RoundedCornerShape(16.dp)
    val curtain = curtainAlpha(index * 40, key = item.key)
    val sheen = sheenProgress()
    val dimmed = item is LibraryItem.App && !item.installed

    Column(
        Modifier
            .width(width)
            .offset(y = lift)
            .animPopIn(delayMs = index * 35, key = item.key)
            .alpha(if (dimmed) 0.5f else 1f)
            .pointerInput(item.key) {
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
                .height(metrics.tileH)
                .shadow(
                    if (selected) 16.dp else 8.dp,
                    shape,
                    clip = false,
                    ambientColor = P.ink.copy(alpha = if (selected) 0.32f else 0.2f),
                    spotColor = P.ink.copy(alpha = if (selected) 0.32f else 0.2f),
                )
                .clip(shape)
                .border(
                    if (selected) 2.5.dp else 1.dp,
                    if (selected) skin.a1 else Color.White.copy(alpha = 0.6f),
                    shape,
                ),
        ) {
            val cover = (item as? LibraryItem.App)?.app?.meta?.cover
            ArtImage(cover, pairIndex, Modifier.fillMaxSize())
            // brillo diagonal
            Box(Modifier.fillMaxSize().drawBehind { drawRect(tileGlossBrush(size)) })

            when (item) {
                is LibraryItem.Folder -> Column(
                    Modifier
                        .fillMaxSize()
                        .drawBehind { drawRect(consoleFaceBrush(size)) }
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ElyText(
                        item.system.abbr,
                        size = 9f,
                        weight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.75f),
                        letterSpacing = tracking(0.2f),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    ElyText(
                        item.system.short,
                        size = if (metrics.landscape) 16f else 18f,
                        weight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = tracking(-0.01f),
                        lineHeightRatio = 1f,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    ElyText(
                        pluralStringResource(R.plurals.roms_with_emulator, item.romCount, item.romCount, item.emulatorName ?: "—"),
                        size = 8f,
                        weight = FontWeight.Medium,
                        color = Color.White.copy(alpha = if (item.emulatorInstalled) 0.8f else 0.55f),
                        letterSpacing = tracking(0.08f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        uppercase = true,
                    )
                }

                is LibraryItem.App -> if (cover == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AppIconImage(item.app.packageName, Modifier.size(metrics.tileH * 0.52f))
                    }
                }
            }

            if (selected) {
                // `sheen`: banda del 36 % del ancho que cruza la card.
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
            item.name,
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
