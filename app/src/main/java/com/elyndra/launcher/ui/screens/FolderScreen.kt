package com.elyndra.launcher.ui.screens

import com.elyndra.launcher.ui.components.NeedsNameBadge
import androidx.compose.animation.AnimatedContent
import com.elyndra.launcher.ui.LibraryItem
import com.elyndra.launcher.ui.components.ConsoleGlyph
import com.elyndra.launcher.ui.components.EmptyState
import com.elyndra.launcher.ui.components.Metrics
import com.elyndra.launcher.ui.components.PAD_HINTS_HEIGHT
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.heroInfoTransition
import com.elyndra.launcher.ui.theme.shelfSurface
import com.elyndra.launcher.library.NameCheck
import com.elyndra.launcher.ui.heroDescription
import com.elyndra.launcher.ui.rememberDescription
import com.elyndra.launcher.ui.components.StatusStrip
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.theme.rememberPress
import com.elyndra.launcher.ui.theme.pressScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.elyndra.launcher.ui.theme.shapeClickable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import com.elyndra.launcher.ui.BarItem
import com.elyndra.launcher.ui.components.ConsoleIconButton
import com.elyndra.launcher.ui.components.HeroBarHeight
import com.elyndra.launcher.ui.theme.FloatClock
import com.elyndra.launcher.ui.theme.consoleFocus
import com.elyndra.launcher.ui.theme.floatPhaseOf
import com.elyndra.launcher.ui.theme.floating
import com.elyndra.launcher.ui.theme.rememberFloatClock
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.ui.components.DisintegratingContainer
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.Hero
import com.elyndra.launcher.ui.components.LogoImage
import com.elyndra.launcher.ui.components.MaterializingContainer
import com.elyndra.launcher.ui.components.OpenButton
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.HeroTitleShadow
import com.elyndra.launcher.ui.theme.animFadeIn
import com.elyndra.launcher.ui.theme.animFadeUp
import com.elyndra.launcher.ui.theme.animPopIn
import com.elyndra.launcher.ui.theme.animTitleIn
import com.elyndra.launcher.ui.theme.curtainAlpha
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.pulseHintAlpha
import com.elyndra.launcher.ui.theme.selectionLift
import com.elyndra.launcher.ui.theme.selectionScale
import com.elyndra.launcher.ui.selection.SelectionLook
import com.elyndra.launcher.ui.selection.rememberSelectionLook
import com.elyndra.launcher.ui.selection.selectionFrame
import androidx.compose.ui.zIndex

@Composable
fun FolderScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val reduced = LocalReducedMotion.current
    val item = vm.currentFolder()
    if (item == null) {
        LaunchedEffect(Unit) { vm.go(Screen.Library) }
        return
    }
    val roms = vm.folderRoms(item.folder.id)
    val rom = vm.selectedRom()
    val emulatorLabel = item.emulatorName ?: stringResource(R.string.choose_emulator)
    // Con mando la selección se mueve sin arrastrar el carrusel: va detrás.
    val carousel = rememberLazyListState()
    LaunchedEffect(rom?.key, roms.size) {
        val index = roms.indexOfFirst { it.key == rom?.key }
        if (index >= 0) runCatching { carousel.animateScrollToItem(index) }
    }

    // Un solo reloj para la flotación de todos los logos de juego de la pantalla.
    val floatClock = rememberFloatClock()
    // El marco de la selección (Ajustes → Apariencia): halo, partículas y su color.
    val look = rememberSelectionLook(vm.settings.selectionParticleColor, vm.settings.selectionGlow, vm.settings.selectionParticles)

    Column(Modifier.fillMaxSize().animFadeIn(key = Screen.Folder)) {

        Hero(
            fallback = rom?.let { vm.romFallback(it) } ?: vm.fallbackOf(item),
            heroKey = rom?.key ?: item.key,
            // Quitar el fondo lo deshace en polvo antes de borrarlo.
            backgroundVanishing = rom?.let { vm.isVanishingArt(it.key, ArtKind.Background) } == true,
            onBackgroundVanished = { vm.finishVanish() },
            height = m.heroH,
            imagePath = rom?.let { it.meta.hero ?: it.meta.screenshot },
            topBar = {
                BoxWithConstraints(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(start = m.pad, end = m.pad, top = if (m.landscape) 8.dp else 16.dp),
                ) {
                    // Tope del selector de emulador: su nombre puede ser larguísimo.
                    val emulatorMax = maxWidth * 0.32f
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Arriba a la izquierda, a la misma altura que la barra de la
                        // biblioteca (donde está Ajustes): volver, "Abrir" y el
                        // selector de emulador, juntos. El nombre de la carpeta pasa
                        // a la derecha, pegado al borde, y cede el ancho que haga falta.
                        ConsoleIconButton(
                            onClick = { vm.go(Screen.Library) },
                            focused = vm.input.isBarFocused(BarItem.Back),
                        ) { glyph -> Box(glyph) { BackChevron(color = Color.White) } }
                        Spacer(Modifier.width(8.dp))
                        OpenButton(enabled = rom != null, focused = vm.input.isBarFocused(BarItem.Open)) { rom?.let { vm.openRom(it) } }
                        Spacer(Modifier.width(8.dp))
                        // El selector lleva un tope fijo y no un peso: con
                        // `weight(fill = false)` la parte que no usaba se quedaba sin
                        // repartir y el bloque de la derecha no llegaba al borde.
                        Box(
                            Modifier
                                .widthIn(max = emulatorMax)
                                .height(HeroBarHeight)
                                .alpha(if (item.emulatorInstalled) 1f else 0.6f)
                                .darkGlass(RoundedCornerShape(11.dp))
                                .consoleFocus(vm.input.isBarFocused(BarItem.Emulator), cornerRadius = 11.dp)
                                .shapeClickable(RoundedCornerShape(11.dp)) { vm.pickFolderEmulator(item.folder) }
                                .padding(horizontal = 11.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            ElyText(
                                emulatorLabel,
                                size = 10f,
                                weight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            ElyText(
                                item.system.name,
                                modifier = Modifier.fillMaxWidth(),
                                size = 12.5f,
                                weight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                align = TextAlign.End,
                            )
                            Spacer(Modifier.height(2.dp))
                            ElyText(
                                item.folder.displayPath,
                                modifier = Modifier.fillMaxWidth(),
                                size = 8.5f,
                                color = Color.White.copy(alpha = 0.72f),
                                letterSpacing = tracking(0.1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                align = TextAlign.End,
                            )
                        }
                        // Hora y batería, pegadas al borde derecho, tras el nombre de la carpeta.
                        if (vm.settings.statusVisible) {
                            Spacer(Modifier.width(10.dp))
                            StatusStrip(vm.settings.statusMode)
                        }
                    }
                }
            },
            info = {
                AnimatedContent(
                    targetState = rom,
                    contentKey = { it?.key ?: item.key },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    contentAlignment = Alignment.BottomStart,
                    transitionSpec = { heroInfoTransition(reduced) },
                    label = "folderInfo",
                ) { shown ->
                    FolderHeroInfo(vm, item, shown, m, floatClock)
                }
            },
        )

        // ── ROMS ──
        Column(Modifier.weight(1f).shelfSurface().padding(top = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = m.pad, end = m.pad, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ElyText(stringResource(R.string.roms_header), size = 9.5f, weight = FontWeight.SemiBold, color = P.ink2, letterSpacing = tracking(0.24f))
                // PS4: los .pkg que aún no se han extraído no son juegos; se
                // cuentan aquí, discretos, y llevan a Bachata para instalarlos.
                val notInstalled = vm.ps4NotInstalled[item.folder.id] ?: 0
                if (notInstalled > 0) {
                    Spacer(Modifier.width(8.dp))
                    val pillShape = RoundedCornerShape(9.dp)
                    Box(
                        Modifier
                            .weight(1f, fill = false)
                            .clip(pillShape)
                            .background(P.chip)
                            .border(1.dp, P.ink.copy(alpha = 0.12f), pillShape)
                            .shapeClickable(pillShape) { vm.openBachata() }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        ElyText(
                            pluralStringResource(R.plurals.ps4_pkgs_not_installed, notInstalled, notInstalled),
                            size = 8.5f,
                            weight = FontWeight.Medium,
                            color = P.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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

            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (roms.isEmpty()) {
                    EmptyState(
                        title = null,
                        message = stringResource(R.string.folder_empty),
                        actionLabel = stringResource(R.string.rescan_folder),
                        onAction = { vm.rescanFolder(item.folder) },
                        modifier = Modifier.fillMaxSize().padding(bottom = if (vm.input.gamepadPresent) PAD_HINTS_HEIGHT else 0.dp),
                        glyph = ConsoleGlyph.Folder,
                    )
                } else {
                    LazyRow(
                        Modifier.fillMaxSize(),
                        state = carousel,
                        contentPadding = PaddingValues(
                            start = m.pad,
                            end = m.pad,
                            // Hueco para la card seleccionada, que sube y se amplía: sin él
                            // se metía sobre el rótulo de ROMS.
                            top = m.carouselTop,
                            bottom = m.carouselBottom,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        itemsIndexed(roms, key = { _, r -> r.id }) { i, r ->
                            // Quitar el juego deshace la card entera; quitar solo
                            // su carátula deshace únicamente la imagen, dentro.
                            val selected = r.key == rom?.key
                            DisintegratingContainer(
                                isDisintegrating = vm.vanishing == r.key,
                                onAnimationEnd = { vm.finishVanish() },
                                // La seleccionada, por encima de sus vecinas: su halo no queda debajo.
                                modifier = Modifier.zIndex(if (selected) 1f else 0f),
                            ) {
                                RomTile(
                                    rom = r,
                                    floatClock = floatClock,
                                    time = playedLabel(r),
                                    index = i,
                                    selected = selected,
                                    look = look,
                                    fallback = vm.romFallback(r),
                                    width = m.romW,
                                    height = m.romTileH,
                                    coverVanishing = vm.isVanishingArt(r.key, ArtKind.Cover),
                                    onCoverVanished = { vm.finishVanish() },
                                    coverMaterializing = vm.isMaterializingArt(r.key, ArtKind.Cover),
                                    onCoverMaterialized = { vm.finishMaterializeArt() },
                                    onTap = { vm.selectRom(r.key) },
                                    onOpen = { vm.openRom(r) },
                                    onBounds = vm::noteSelectedCard,
                                    onLongPress = { bounds ->
                                        vm.selectRom(r.key)
                                        vm.markSheetOrigin(bounds)
                                        vm.romOptions(r)
                                    },
                                )
                            }
                        }
                    }
                }
                PadHints(
                    hints = FOLDER_HINTS,
                    visible = vm.input.gamepadPresent,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = m.pad - 10.dp, bottom = 2.dp),
                )
            }
        }

    }
}

/** Las pistas del mando al pie de la carpeta. */
private val FOLDER_HINTS = listOf(
    PadHint("A", R.string.sheet_play),
    PadHint("X", R.string.details),
    PadHint("Y", R.string.hint_options),
    PadHint("B", R.string.hint_back),
)

/** El bloque de título del hero de la carpeta: el de la ROM elegida o, sin ella, el del sistema. */
@Composable
private fun FolderHeroInfo(vm: ElyndraViewModel, item: LibraryItem.Folder, rom: RomEntry?, m: Metrics, floatClock: FloatClock) {
    Column(
        Modifier
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
            MaterializingContainer(
                isMaterializing = vm.isMaterializingArt(rom.key, ArtKind.Logo),
                onAnimationEnd = { vm.finishMaterializeArt() },
            ) {
                DisintegratingContainer(
                    isDisintegrating = vm.isVanishingArt(rom.key, ArtKind.Logo),
                    onAnimationEnd = { vm.finishVanish() },
                ) {
                    LogoImage(
                        logo,
                        Modifier
                            .floating(floatClock, floatPhaseOf(rom.key), amplitude = 3.dp, periodSeconds = 4.2f)
                            .fillMaxWidth(0.72f)
                            .height(m.logoH),
                    )
                }
            }
        } else {
            ElyText(
                rom?.displayTitle ?: item.system.name,
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
        val description = rememberDescription(vm, rom?.key, rom?.meta)
        if (description != null) {
            ElyText(
                heroDescription(description, vm.settings.lang),
                modifier = Modifier
                    .padding(top = if (m.landscape) 4.dp else 6.dp)
                    .fillMaxWidth(0.86f),
                size = 10f,
                weight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.72f),
                letterSpacing = tracking(0.02f),
                lineHeightRatio = 1.28f,
                maxLines = if (m.landscape) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (!vm.input.gamepadPresent) {
            // Con mando, las pistas de sus botones ya van al pie del estante.
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
    /** Reloj compartido de la flotación del logo del juego (cuando no hay carátula). */
    floatClock: FloatClock,
    time: String,
    index: Int,
    selected: Boolean,
    look: SelectionLook,
    fallback: ArtFallback,
    width: Dp,
    height: Dp,
    coverVanishing: Boolean,
    onCoverVanished: () -> Unit,
    coverMaterializing: Boolean,
    onCoverMaterialized: () -> Unit,
    onTap: () -> Unit,
    onOpen: () -> Unit,
    /** Recibe el rectángulo de la card en la ventana: el menú sale de ahí. */
    onLongPress: (Rect) -> Unit,
    /** Rectángulo de la card mientras está seleccionada (el menú sale de ahí con el mando). */
    onBounds: (Rect) -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    val bounds = remember { arrayOf(Rect.Zero) }
    val lift = selectionLift(selected)
    val scale = selectionScale(selected)
    val press = rememberPress()
    val pressed = pressScale(press)
    val curtain = curtainAlpha(minOf(index, 12) * 40, key = rom.id)
    val cover = rom.meta.cover
    // La luz de la selección la pone el marco; la sombra es siempre neutra.
    val shadowColor = P.shade.copy(alpha = 0.2f)
    // El detector de gestos sobrevive a las recomposiciones (llave = id): tiene
    // que llamar a las lambdas actuales, que llevan la ROM con sus datos al día.
    val tap by rememberUpdatedState(onTap)
    val open by rememberUpdatedState(onOpen)
    val longPress by rememberUpdatedState(onLongPress)

    Column(
        Modifier
            .width(width)
            .offset(y = lift)
            .animPopIn(delayMs = minOf(index, 12) * 35, key = rom.id)
            .onGloballyPositioned {
                bounds[0] = it.boundsInWindow()
                if (selected) onBounds(bounds[0])
            }
            .pointerInput(rom.id) {
                detectTapGestures(
                    onPress = { press.track(this) },
                    onTap = { tap() },
                    onDoubleTap = { open() },
                    onLongPress = { longPress(bounds[0]) },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .graphicsLayer {
                    scaleX = scale * pressed.value
                    scaleY = scale * pressed.value
                }
                .shadow(
                    if (press.pressed) 4.dp else if (selected) 12.dp else 8.dp,
                    shape,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
                // Tras la escala (la acompaña) y antes del recorte: halo, luz en
                // el estante y partículas asoman por fuera de la carátula.
                .selectionFrame(selected, look, shape)
                .clip(shape)
                .border(1.dp, P.hairline, shape),
        ) {
            MaterializingContainer(
                isMaterializing = coverMaterializing,
                onAnimationEnd = onCoverMaterialized,
                modifier = Modifier.fillMaxSize(),
            ) {
                DisintegratingContainer(
                    isDisintegrating = coverVanishing,
                    onAnimationEnd = onCoverVanished,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Sin carátula, el arte de reserva: el icono del juego (flotando,
                    // como siempre) y su título.
                    ArtImage(
                        cover,
                        fallback,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        iconModifier = Modifier.floating(floatClock, floatPhaseOf(rom.id)),
                    )
                }
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
            // Sin un nombre que sirva para buscarlo: se marca, sin más avisos.
            val unnamed = remember(rom.displayTitle) { !NameCheck.isNameUsable(rom.displayTitle) }
            if (unnamed) NeedsNameBadge(Modifier.align(Alignment.TopStart).padding(5.dp), onDark = true)

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
