package com.elyndra.launcher.ui.screens
import com.elyndra.launcher.ui.UiTags
import androidx.compose.ui.platform.testTag
import com.elyndra.launcher.ui.components.NeedsNameBadge
import com.elyndra.launcher.ui.theme.focusRing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import com.elyndra.launcher.ui.components.touchTarget
import com.elyndra.launcher.ui.ShelfLayout
import com.elyndra.launcher.ui.ClassicLayout
import androidx.compose.ui.platform.LocalDensity
import com.elyndra.launcher.ui.theme.pulseHint
import com.elyndra.launcher.ui.DockPalettes
import com.elyndra.launcher.ui.components.COVER_RATIO
import com.elyndra.launcher.ui.components.CapsuleShape
import com.elyndra.launcher.ui.components.dockSurface
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.motion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.layout
import androidx.compose.animation.AnimatedContent
import com.elyndra.launcher.ui.LibraryItem
import com.elyndra.launcher.ui.components.LocalScreenSize
import com.elyndra.launcher.ui.meridian.MeridianFolder
import com.elyndra.launcher.ui.meridian.MeridianMode
import com.elyndra.launcher.ui.meridian.WheelMath
import androidx.compose.runtime.SideEffect
import com.elyndra.launcher.ui.components.ConsoleGlyph
import com.elyndra.launcher.ui.components.ConsoleGlyphIcon
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
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.elyndra.launcher.ui.components.SheetGlyph
import com.elyndra.launcher.ui.SheetIcon
import com.elyndra.launcher.ui.FolderBarLayout
import com.elyndra.launcher.ui.meridian.FolderHeader
import com.elyndra.launcher.ui.TapAction
import com.elyndra.launcher.ui.TapAnchor
import com.elyndra.launcher.ui.TapGate
import com.elyndra.launcher.ui.rememberTapSlop
import com.elyndra.launcher.ui.rememberUserScrolling
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
import com.elyndra.launcher.ui.theme.HeroText
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
    val window = LocalScreenSize.current
    val meridian = MeridianMode.active(window.width.value, window.height.value, vm.settings.layoutStyle)
    SideEffect { vm.input.meridian = meridian }
    if (meridian) {
        MeridianFolder(vm, item)
        return
    }
    val roms = vm.folderRoms(item.folder.id)
    val rom = vm.selectedRom()
    val emulatorLabel = item.emulatorName ?: stringResource(R.string.choose_emulator)
    // Con mando la selección se mueve sin arrastrar el carrusel: va detrás.
    // Nace ya en la selección (al girar desde la rueda no hay que recorrerlo otra vez).
    // La fila se desplaza dejando una card delante de la seleccionada (ClassicLayout.anchorIndex):
    // se ve de dónde se viene y la seleccionada no queda pegada al borde.
    val carousel = rememberLazyListState(
        initialFirstVisibleItemIndex = remember { ClassicLayout.anchorIndex(WheelMath.restoreIndex(roms.map { it.key }, rom?.key, addFocused = false, hasAdd = false)) },
    )

    // Tocar: lo no seleccionado se selecciona; lo seleccionado se abre (ver TapGate).
    val gate = remember { TapGate() }
    val slop = rememberTapSlop()
    val userScrolling = rememberUserScrolling(carousel)
    val openRom: (RomEntry) -> Unit = {
        if (vm.tryOpen()) {
            vm.selectRom(it.key)
            vm.openRom(it)
        }
    }
    val onRomTap: (RomEntry, Offset, Boolean) -> Unit = tap@{ r, at, moving ->
        if (vm.userFolders.dropAt(r.key)) return@tap
        val d = gate.tap(r.key, rom?.key, moving, vm.settings.tapOpensSelected, SystemClock.uptimeMillis(), at.x, at.y, slop)
        when (d.action) {
            TapAction.Select -> vm.selectRom(r.key)
            TapAction.Open -> roms.firstOrNull { it.key == d.key }?.let(openRom)
            TapAction.Ignore -> Unit
        }
    }
    // Un solo reloj para la flotación de todos los logos de juego de la pantalla.
    val floatClock = rememberFloatClock()
    // El marco de la selección (Ajustes → Apariencia): halo, partículas y su color.
    val look = rememberSelectionLook(vm.settings.selectionParticleColor, vm.settings.selectionGlow, vm.settings.selectionParticles)
    val labelBlock = labelBlock(FOLDER_LABEL_SP)
    val fontScale = LocalDensity.current.fontScale
    // La pista táctil: nunca con mando (ni mientras se maneja con él).
    val showHint = ClassicLayout.showTouchHint(vm.input.gamepadPresent, vm.input.active)
    // La sinopsis se mide aquí para repartir el hero; el bloque la vuelve a pedir para lo que enseña.
    val description = rememberDescription(vm, rom?.key, rom?.meta)

    BoxWithConstraints(Modifier.fillMaxSize().animFadeIn(key = Screen.Folder)) {
        // El reparto del Clásico (ClassicLayout): la carátula manda, el estante
        // mide lo justo y el hero se queda el resto, con su bloque bajo la barra.
        val landscape = m.landscape
        val barBottom = ClassicLayout.barBottom(landscape)
        val bottomPad = ClassicLayout.heroBottomPad(landscape)
        val chips = ClassicLayout.chipsHeight(fontScale, landscape)
        val merged = ClassicLayout.mergedChipsHeight(fontScale, landscape)
        val frame = ClassicLayout.frame(
            width = maxWidth.value,
            height = maxHeight.value,
            aspect = COVER_RATIO,
            hintsVisible = vm.input.gamepadPresent,
            labelBlock = labelBlock,
            topInset = 0f,
            heroMin = ClassicLayout.heroMin(barBottom, bottomPad, ClassicLayout.Block(chips, merged, logoMax = ClassicLayout.LOGO_MIN)),
        )
        val secondary = when {
            description != null -> ClassicLayout.synopsisHeight(fontScale, landscape)
            showHint -> ClassicLayout.hintHeight(fontScale, landscape)
            else -> 0f
        }
        // Los logos de las carpetas, algo mayores en apaisado (ver FOLDER_LOGO_HEIGHT_LANDSCAPE).
        val logoMax = ClassicLayout.logoHeight(frame.heroHeight, landscape) * (if (landscape) FOLDER_LOGO_HEIGHT_LANDSCAPE else 1f)
        val plan = ClassicLayout.heroPlan(
            ClassicLayout.heroRoom(frame.heroHeight, barBottom, bottomPad),
            ClassicLayout.Block(
                chips, merged, logoMax,
                secondary = secondary,
                stats = ClassicLayout.STATS_BLOCK,
                // La sinopsis pierde líneas antes de irse; la pista es una sola.
                secondaryLines = if (description != null) ClassicLayout.synopsisLines(landscape) else 1,
                secondaryLine = if (description != null) ClassicLayout.synopsisLineHeight(fontScale) else 0f,
            ),
        )
        // Sin ROMs no hay escena que continuar: el estado vacío va sobre el estante liso.
        val extension = if (roms.isEmpty()) 0.dp else ShelfLayout.artExtension(frame.shelfHeight, maxHeight.value, frame.labelTop).dp
        val rowY = animateFloatAsState(frame.heroHeight + frame.tileTop, motion(Springs.enter()), label = "folderRow")
        val rowLandscape = maxWidth >= maxHeight
        // Aire, escala de las vecinas, halo y nombres de la fila (en vertical, con el aire a la vista).
        val rowStyle = ClassicLayout.rowStyle((maxWidth - m.pad * 2).value, frame.tileWidth, rowLandscape)
        val gap = rowStyle.gap
        // El desplazamiento de la fila hacia la selección (ver ClassicLayout.anchor): en
        // apaisado, una card entera delante; en vertical, la seleccionada en el primer
        // hueco con un poco de la anterior asomando. La primera vez se coloca sin
        // animar (al nacer o al girar); después, con la animación de siempre.
        val density = LocalDensity.current
        val placed = remember { booleanArrayOf(false) }
        // Con un juego cogido cambia su puesto y no su clave: la fila también lo sigue.
        val grabbedAt = if (vm.userFolders.grabKey != null) roms.indexOfFirst { it.key == rom?.key } else -1
        LaunchedEffect(rom?.key, grabbedAt, roms.size, frame.tileWidth, gap, rowLandscape) {
            val index = roms.indexOfFirst { it.key == rom?.key }
            if (index < 0) return@LaunchedEffect
            val a = ClassicLayout.anchor(index, frame.tileWidth, gap, rowLandscape)
            val offset = with(density) { a.offset.dp.roundToPx() }
            runCatching {
                if (placed[0]) carousel.animateScrollToItem(a.index, offset) else carousel.scrollToItem(a.index, offset)
            }
            placed[0] = true
        }
        val hero = FolderHero(frame, plan, barBottom, bottomPad, ClassicLayout.titleSp(frame.heroHeight, landscape), showHint)
        FolderContent(vm, item, roms, rom, m, hero, rowStyle, extension, { rowY.value }, emulatorLabel, carousel, floatClock, look, reduced, userScrolling, onRomTap, openRom)
    }
}

/** El reparto del Clásico de la carpeta, ya decidido (ver [ClassicLayout]). */
private class FolderHero(
    val frame: ClassicLayout.Frame,
    val plan: ClassicLayout.HeroPlan,
    val barBottom: Float,
    val bottomPad: Float,
    val titleSp: Float,
    val showHint: Boolean,
)

@Composable
private fun FolderContent(
    vm: ElyndraViewModel,
    item: LibraryItem.Folder,
    roms: List<RomEntry>,
    rom: RomEntry?,
    m: Metrics,
    hero: FolderHero,
    /** Aire, escala de las vecinas, halo y nombres de la fila ([ClassicLayout.rowStyle]). */
    rowStyle: ClassicLayout.RowStyle,
    extension: Dp,
    rowY: () -> Float,
    emulatorLabel: String,
    carousel: androidx.compose.foundation.lazy.LazyListState,
    floatClock: FloatClock,
    look: SelectionLook,
    reduced: Boolean,
    userScrolling: () -> Boolean,
    onRomTap: (RomEntry, Offset, Boolean) -> Unit,
    openRom: (RomEntry) -> Unit,
) {
    // Como en la biblioteca, en los dos temas: el arte limpio (sin franjas oscuras) y el texto blanco con su sombra sutil.
    val heroText = HeroText.CLEAN
    Column(Modifier.fillMaxSize()) {

        Hero(
            clean = heroText.clean,
            fallback = rom?.let { vm.romFallback(it) } ?: vm.fallbackOf(item),
            heroKey = rom?.key ?: item.key,
            // Quitar el fondo lo deshace en polvo antes de borrarlo.
            backgroundVanishing = rom?.let { vm.isVanishingArt(it.key, ArtKind.Background) } == true,
            onBackgroundVanished = { vm.finishVanish() },
            height = hero.frame.heroHeight.dp,
            extension = extension,
            imagePath = rom?.let { it.meta.hero ?: it.meta.screenshot } ?: item.heroPath.takeIf { item.userFolder != null },
            topBar = {
                BoxWithConstraints(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(start = m.pad, end = m.pad, top = if (m.landscape) 8.dp else 16.dp),
                ) {
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
                        AddGameButton(vm, item)
                        Spacer(Modifier.width(8.dp))
                        // La misma cabecera que en Meridian: el emulador al principio del hueco,
                        // el sistema con su ruta (recortada por el centro) y la hora al final.
                        // En vertical, si no cabe, el emulador se recorta y luego se queda en su
                        // icono (FolderBarLayout); antes era un 32 % fijo del ancho.
                        FolderHeader(
                            title = item.system.name,
                            path = item.folder.displayPath,
                            modifier = Modifier.weight(1f),
                            emulatorLabel = emulatorLabel,
                            // Una carpeta virtual (ports) no tiene emulador.
                            emulator = if (item.folder.isVirtual) null else ({ iconOnly -> EmulatorSelector(vm, item, emulatorLabel, iconOnly = iconOnly) }),
                            spread = true,
                            shadow = heroText.shadow(null),
                            // Último paso de compactación: la ficha de la carpeta sube aquí, junto al sistema (como en Meridian).
                            pill = if (hero.plan.statsInBar) ({ short -> FolderChips(vm, item, roms.size, short = short) }) else null,
                        ) {
                            if (vm.settings.statusVisible) StatusStrip(vm.settings.statusMode)
                        }
                    }
                }
            },
            info = {
                AnimatedContent(
                    targetState = rom,
                    contentKey = { it?.key ?: item.key },
                    // Nunca por encima de la barra: el bloque empieza, como muy arriba, bajo ella.
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(top = (hero.barBottom + ClassicLayout.BAR_GAP).dp),
                    contentAlignment = Alignment.BottomStart,
                    transitionSpec = { heroInfoTransition(reduced) },
                    label = "folderInfo",
                ) { shown ->
                    FolderHeroInfo(vm, item, shown, roms.size, m, hero, floatClock)
                }
            },
        )

        // ── ROMS ──
        Box(Modifier.fillMaxWidth().weight(1f).shelfSurface(extension)) {
            if (roms.isEmpty()) {
                EmptyState(
                    title = null,
                    message = stringResource(if (item.userFolder != null) R.string.user_folder_empty else R.string.folder_empty),
                    actionLabel = stringResource(R.string.folder_add_title),
                    onAction = { vm.folderAdd.open(item.folder) },
                    modifier = Modifier.fillMaxSize().padding(bottom = if (vm.input.gamepadPresent) PAD_HINTS_HEIGHT else 0.dp),
                    glyph = ConsoleGlyph.Folder,
                )
            } else {
                LazyRow(
                    Modifier
                        .fillMaxSize()
                        .testTag(UiTags.SHELF)
                        .offset { IntOffset(0, (rowY() - hero.frame.heroHeight - hero.frame.tileTop).dp.roundToPx()) },
                    state = carousel,
                    contentPadding = PaddingValues(
                        start = m.pad,
                        end = m.pad,
                        // Por encima queda el hueco para lo que la seleccionada sube y alumbra.
                        top = hero.frame.tileTop.dp,
                    ),
                    // El aire que hace que la fila llene el ancho (ClassicLayout.spacing).
                    horizontalArrangement = Arrangement.spacedBy(rowStyle.gap.dp),
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
                                grabbed = r.key == vm.userFolders.grabKey,
                                look = look,
                                fallback = vm.romFallback(r),
                                width = hero.frame.tileWidth.dp,
                                height = hero.frame.tileHeight.dp,
                                rowStyle = rowStyle,
                                coverVanishing = vm.isVanishingArt(r.key, ArtKind.Cover),
                                onCoverVanished = { vm.finishVanish() },
                                coverMaterializing = vm.isMaterializingArt(r.key, ArtKind.Cover),
                                onCoverMaterialized = { vm.finishMaterializeArt() },
                                scrolling = userScrolling,
                                onTap = { at, moving -> onRomTap(r, at, moving) },
                                onOpen = { openRom(r) },
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
                hints = if (vm.userFolders.grabKey != null) FOLDER_GRAB_HINTS else FOLDER_HINTS,
                visible = vm.input.gamepadPresent,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = m.pad - 10.dp, bottom = 2.dp),
            )
        }

    }
}

/**
 * "Añadir juego" en la barra de la carpeta (también SELECT y sus opciones):
 * el más, en un botón cuadrado como volver.
 */
@Composable
internal fun AddGameButton(vm: ElyndraViewModel, item: LibraryItem.Folder) {
    val label = stringResource(R.string.folder_add_title)
    ConsoleIconButton(
        onClick = { vm.folderAdd.open(item.folder) },
        modifier = Modifier.semantics { contentDescription = label },
        focused = vm.input.isBarFocused(BarItem.AddGame),
    ) { glyph -> Box(glyph) { SheetGlyph(SheetIcon.Add, Color.White, 18.dp) } }
}

/** Cuerpo (sp) del rótulo del emulador: [FolderHeader] lo mide con este mismo para compactar. */
internal const val EMULATOR_LABEL_SP = 10f

/**
 * El selector de emulador de la carpeta (en la cabecera, con el sistema): su
 * nombre en cristal oscuro; apagado si no está instalado. Con [iconOnly]
 * (la cabecera no da para más, ver [com.elyndra.launcher.ui.FolderBarLayout])
 * solo el icono, y el nombre en la descripción. Es el emulador de la
 * carpeta; el de cada juego sigue en sus opciones.
 */
@Composable
internal fun EmulatorSelector(vm: ElyndraViewModel, item: LibraryItem.Folder, label: String, modifier: Modifier = Modifier, iconOnly: Boolean = false) {
    EmulatorChip(
        label = label,
        installed = item.emulatorInstalled,
        focused = vm.input.isBarFocused(BarItem.Emulator),
        iconOnly = iconOnly,
        modifier = modifier,
    ) { vm.pickFolderEmulator(item.folder) }
}

/** La píldora del emulador, sin el ViewModel (también para las vistas previas). */
@Composable
internal fun EmulatorChip(label: String, installed: Boolean, focused: Boolean, iconOnly: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val description = stringResource(R.string.folder_emulator_a11y, label)
    Box(
        modifier
            .touchTarget(Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick))
            .then(if (iconOnly) Modifier.semantics { contentDescription = description }.size(HeroBarHeight) else Modifier.height(HeroBarHeight))
            .alpha(if (installed) 1f else 0.6f)
            .darkGlass(RoundedCornerShape(11.dp))
            .consoleFocus(focused, cornerRadius = 11.dp)
            .indication(interaction, focusRing(RoundedCornerShape(11.dp)))
            .then(if (iconOnly) Modifier else Modifier.padding(horizontal = FolderBarLayout.CHIP_PAD.dp)),
        contentAlignment = if (iconOnly) Alignment.Center else Alignment.CenterStart,
    ) {
        if (iconOnly) {
            SheetGlyph(SheetIcon.Emulator, Color.White, 16.dp)
        } else {
            ElyText(
                label,
                size = EMULATOR_LABEL_SP,
                weight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Cuerpo (sp) del nombre bajo la carátula. */
private const val FOLDER_LABEL_SP = 8.5f

/** Alto de la ficha de la carpeta (el de [ClassicLayout.STATS_H]). */
private const val FOLDER_CHIPS_H = ClassicLayout.STATS_H

/**
 * El resumen de la carpeta (cuántas ROMs y cuánto se ha jugado) y, en PS4,
 * los .pkg sin instalar, que llevan a Bachata. Van en fichas de la misma
 * lámina que el dock: así se leen sobre cualquier arte. En el Clásico van al
 * pie del bloque del hero (antes, arriba del estante, le quitaban 36 dp a las
 * carátulas) o, si no caben, en la barra junto al sistema.
 */
@Composable
internal fun FolderChips(vm: ElyndraViewModel, item: LibraryItem.Folder, count: Int, modifier: Modifier = Modifier, short: Boolean = false) {
    val ink2 = Color(DockPalettes.ink2(P.isDark))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .height(FOLDER_CHIPS_H.dp)
                .dockSurface(CapsuleShape, elevation = 6.dp)
                .padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            ElyText(
                // Corta ("4 ROMs"), cuando en la cabecera de Meridian no cabe con el tiempo jugado (ver FolderBarLayout.Pill).
                when {
                    item.userFolder != null && short -> pluralStringResource(R.plurals.user_folder_games, count, count)
                    item.userFolder != null -> pluralStringResource(R.plurals.user_folder_games_summary, count, count, fmtMinutes(item.minutes))
                    short -> pluralStringResource(R.plurals.roms_count, count, count)
                    else -> pluralStringResource(R.plurals.roms_summary, count, count, fmtMinutes(item.minutes))
                },
                size = 8.5f,
                weight = FontWeight.SemiBold,
                color = ink2,
                letterSpacing = tracking(0.16f),
                uppercase = true,
                maxLines = 1,
            )
        }
        // PS4: los .pkg que aún no se han extraído no son juegos; se
        // cuentan aquí, discretos, y llevan a Bachata para instalarlos.
        val notInstalled = vm.ps4NotInstalled[item.folder.id] ?: 0
        if (notInstalled > 0) {
            // Se ve de 24 dp, pero la zona táctil sobresale hasta 48.
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .layout { measurable, constraints ->
                        val touch = 48.dp.roundToPx()
                        val h = FOLDER_CHIPS_H.dp.roundToPx()
                        val placeable = measurable.measure(constraints.copy(minHeight = touch, maxHeight = touch))
                        layout(placeable.width, h) { placeable.place(0, (h - touch) / 2) }
                    }
                    .shapeClickable(CapsuleShape) { vm.openBachata() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .height(FOLDER_CHIPS_H.dp)
                        .dockSurface(CapsuleShape, elevation = 6.dp)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                ElyText(
                    pluralStringResource(R.plurals.ps4_pkgs_not_installed, notInstalled, notInstalled),
                    size = 8.5f,
                    weight = FontWeight.Medium,
                    color = ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                }
            }
        }
    }
}

/** Con un juego cogido: moverlo y soltarlo. */
private val FOLDER_GRAB_HINTS = listOf(
    PadHint("←→", R.string.hint_move),
    PadHint("A / B", R.string.user_folder_hint_drop),
)

/** Las pistas del mando al pie de la carpeta. */
private val FOLDER_HINTS = listOf(
    PadHint("A", R.string.sheet_play),
    PadHint("X", R.string.details),
    PadHint("Y", R.string.hint_options),
    PadHint("SELECT", R.string.folder_add_title),
    PadHint("B", R.string.hint_back),
)

/**
 * El bloque de título del hero de la carpeta: el de la ROM elegida o, sin
 * ella, el del sistema. Va bajo la barra y se compacta con el plan de
 * [ClassicLayout.heroPlan]: sin pista, logo menor, fichas en una línea y la
 * ficha de la carpeta en la barra.
 */
@Composable
private fun FolderHeroInfo(vm: ElyndraViewModel, item: LibraryItem.Folder, rom: RomEntry?, count: Int, m: Metrics, hero: FolderHero, floatClock: FloatClock) {
    // Blanco, sin franjas oscuras detrás, con la sombra sutil (como en la biblioteca).
    val heroText = HeroText.CLEAN
    val plan = hero.plan
    val fontScale = LocalDensity.current.fontScale
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = m.pad, end = m.pad, bottom = hero.bottomPad.dp),
    ) {
        if (plan.mergedChips) {
            MergedChipLine(
                listOfNotNull(
                    item.platformOf(rom) ?: stringResource(R.string.filter_android),
                    rom?.meta?.ra?.takeIf { it.achievements > 0 }?.let { "🏆 ${it.earned}/${it.achievements}" },
                    rom?.let { playedLabel(it) },
                    rom?.subtitle,
                ),
                m.landscape,
            )
        } else Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = if (m.landscape) 3.dp else 6.dp),
        ) {
            HeroChip(folderChip(item, rom))
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
                (rom?.let { playedLabel(it) } ?: "") + (rom?.let { " · " + it.subtitle } ?: ""),
                size = 9.5f,
                weight = FontWeight.Medium,
                color = heroText.secondary(0.85f),
                letterSpacing = tracking(0.1f),
                shadow = heroText.shadow(null),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val logo = rom?.meta?.logo
        // Por debajo de esto (solo en ventanas diminutas) no hay logo que se lea: fuera.
        val logoBox = plan.logoHeight
        if (logoBox < LOGO_HIDE) {
            Unit
        } else if (logo != null) {
            MaterializingContainer(
                isMaterializing = vm.isMaterializingArt(rom.key, ArtKind.Logo),
                onAnimationEnd = { vm.finishMaterializeArt() },
            ) {
                DisintegratingContainer(
                    isDisintegrating = vm.isVanishingArt(rom.key, ArtKind.Logo),
                    onAnimationEnd = { vm.finishVanish() },
                ) {
                    // Igual que en Meridian: a su tamaño, con filtrado de calidad, su sombra y su filo claro;
                    // con la caja de las carpetas (su hero es más bajo y con la de la biblioteca quedaban diminutos).
                    HeroLogo(
                        logo,
                        maxHeight = logoBox.dp,
                        modifier = Modifier.floating(floatClock, floatPhaseOf(rom.key), amplitude = 3.dp, periodSeconds = 4.2f),
                        widthFraction = FOLDER_LOGO_WIDTH,
                    )
                }
            }
        } else {
            ElyText(
                rom?.displayTitle ?: item.system.name,
                size = ClassicLayout.titleSpFor(logoBox, fontScale, hero.titleSp),
                weight = FontWeight.ExtraBold,
                color = heroText.title,
                letterSpacing = tracking(-0.03f),
                lineHeightRatio = 0.92f,
                shadow = heroText.titleShadow,
                uppercase = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val description = rememberDescription(vm, rom?.key, rom?.meta)
        if (!plan.showSecondary) {
            Unit
        } else if (description != null) {
            ElyText(
                heroDescription(description, vm.settings.lang),
                modifier = Modifier
                    .padding(top = if (m.landscape) 4.dp else 6.dp)
                    .fillMaxWidth(0.86f),
                size = 10f,
                weight = FontWeight.Medium,
                color = heroText.secondary(0.72f),
                letterSpacing = tracking(0.02f),
                lineHeightRatio = 1.28f,
                shadow = heroText.shadow(null),
                maxLines = plan.secondaryLines.coerceAtLeast(1),
                overflow = TextOverflow.Ellipsis,
            )
        } else if (hero.showHint) {
            // Con mando, las pistas de sus botones ya van al pie del estante.
            ElyText(
                stringResource(if (vm.settings.tapOpensSelected) R.string.hint_gestures_tap else R.string.hint_gestures),
                modifier = Modifier
                    .padding(top = if (m.landscape) 4.dp else 7.dp)
                    .pulseHint(),
                size = 9f,
                weight = FontWeight.Medium,
                color = heroText.secondary(0.8f),
                letterSpacing = tracking(0.14f),
                shadow = heroText.shadow(null),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                uppercase = true,
            )
        }
        // La ficha de la carpeta, al pie del bloque mientras quepa (si no, va en la barra).
        if (!plan.statsInBar) {
            Spacer(Modifier.height(ClassicLayout.STATS_GAP.dp))
            FolderChips(vm, item, count)
        }
    }
}

/** Alto (dp) por debajo del cual el logo (o el titular) no se pinta: no se leería. */
private const val LOGO_HIDE = 12f

/** La ficha del hero: "ROM · PS2"; en una carpeta del usuario, también "Android" o, vacía, su nombre. */
@Composable
internal fun folderChip(item: LibraryItem.Folder, rom: RomEntry?): String {
    val platform = item.platformOf(rom) ?: return stringResource(R.string.filter_android)
    return if (item.userFolder != null && rom == null) platform else stringResource(R.string.rom_chip, platform)
}

@Composable
internal fun playedLabel(rom: RomEntry): String =
    if (rom.stats.minutes > 0) stringResource(R.string.played_time, fmtMinutes(rom.stats.minutes))
    else stringResource(R.string.never_played)

/** La marca de un juego cogido para cambiarlo de sitio. */
@Composable
internal fun GrabBadge(modifier: Modifier = Modifier) {
    Box(modifier.darkGlass(RoundedCornerShape(12.dp)).padding(8.dp)) {
        ConsoleGlyphIcon(ConsoleGlyph.Handle, Color.White, size = 22.dp)
    }
}

/** Card de ROM: carátula vertical, etiqueta de extensión y título si no hay carátula. */
@Composable
private fun RomTile(
    rom: RomEntry,
    /** Reloj compartido de la flotación del logo del juego (cuando no hay carátula). */
    floatClock: FloatClock,
    time: String,
    index: Int,
    selected: Boolean,
    /** Cogido para cambiarlo de sitio (carpetas del usuario). */
    grabbed: Boolean,
    look: SelectionLook,
    fallback: ArtFallback,
    width: Dp,
    height: Dp,
    /** Escala de las vecinas, halo y nombres de la fila ([ClassicLayout.rowStyle]). */
    rowStyle: ClassicLayout.RowStyle,
    coverVanishing: Boolean,
    onCoverVanished: () -> Unit,
    coverMaterializing: Boolean,
    onCoverMaterialized: () -> Unit,
    /** ¿Se está moviendo la fila con el dedo? Un toque que la para no selecciona ni abre. */
    scrolling: () -> Boolean,
    /** Un toque, en [Offset] de la ventana; el Boolean dice si la fila se movía al apoyar el dedo. */
    onTap: (Offset, Boolean) -> Unit,
    onOpen: () -> Unit,
    /** Recibe el rectángulo de la card en la ventana: el menú sale de ahí. */
    onLongPress: (Rect) -> Unit,
    /** Rectángulo de la card mientras está seleccionada (el menú sale de ahí con el mando). */
    onBounds: (Rect) -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    val bounds = remember { arrayOf(Rect.Zero) }
    // Subida y escala en la capa (ClassicCardMotion): el nombre no se mueve y la card no se recompone.
    val cardMotion = rememberClassicCardMotion(selected, rowStyle.neighborScale)
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
    val moving by rememberUpdatedState(scrolling)
    val anchor = remember { TapAnchor() }
    val openLabel = stringResource(R.string.open)
    val optionsLabel = stringResource(R.string.hint_options)

    Column(
        Modifier
            .width(width)
            .animPopIn(delayMs = minOf(index, 12) * 35, key = rom.id)
            .onGloballyPositioned {
                bounds[0] = it.boundsInWindow()
                anchor.coordinates = it
                if (selected) onBounds(bounds[0])
            }
            .pointerInput(rom.id) {
                var wasMoving = false
                detectTapGestures(
                    onPress = {
                        wasMoving = moving()
                        press.track(this)
                    },
                    onTap = { tap(anchor.toWindow(it), wasMoving) },
                    onLongPress = { longPress(bounds[0]) },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
                onClick(label = if (selected) openLabel else null) { if (selected) open() else tap(Offset.Unspecified, false); true }
                customActions = listOf(
                    CustomAccessibilityAction(openLabel) { open(); true },
                    CustomAccessibilityAction(optionsLabel) { longPress(bounds[0]); true },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .classicCard(cardMotion, pressed)
                .shadow(
                    if (press.pressed) 4.dp else if (selected) 12.dp else 8.dp,
                    shape,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
                // Tras la escala (la acompaña) y antes del recorte: halo, luz en
                // el estante y partículas asoman por fuera de la carátula.
                .selectionFrame(selected, look, shape, rowStyle.haloSpreadDp())
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
            if (grabbed) GrabBadge(Modifier.align(Alignment.Center))

            if (curtain > 0f) {
                Box(Modifier.fillMaxSize().alpha(curtain).background(P.paper))
            }
        }

        Spacer(Modifier.height(ShelfLayout.LABEL_GAP.dp))
        ElyText(
            if (cover != null) rom.displayTitle else time,
            size = FOLDER_LABEL_SP,
            weight = FontWeight.Medium,
            color = if (selected) P.ink else P.ink2,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Del ancho de lo que se ve de la carátula (en vertical, la vecina encogida).
            modifier = Modifier.fillMaxWidth().padding(horizontal = rowStyle.labelInset.dp),
        )
    }
}
