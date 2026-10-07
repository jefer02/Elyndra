package com.elyndra.launcher.ui.screens

import com.elyndra.launcher.ui.components.NeedsNameBadge
import androidx.compose.animation.AnimatedContent
import com.elyndra.launcher.ui.components.ConsoleGlyph
import com.elyndra.launcher.ui.components.EmptyState
import com.elyndra.launcher.ui.components.PAD_HINTS_HEIGHT
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.DotTabs
import com.elyndra.launcher.ui.components.SortButton
import com.elyndra.launcher.ui.components.SortPopover
import com.elyndra.launcher.ui.DockPlacement
import com.elyndra.launcher.ui.DockPalettes
import com.elyndra.launcher.ui.theme.DARK_GLASS_MIN
import com.elyndra.launcher.ui.DotTabsLayout
import com.elyndra.launcher.ui.ShelfLayout
import com.elyndra.launcher.ui.SortMode
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import com.elyndra.launcher.ui.theme.heroInfoTransition
import com.elyndra.launcher.ui.theme.shelfSurface
import com.elyndra.launcher.library.NameCheck
import com.elyndra.launcher.ui.heroDescription
import com.elyndra.launcher.ui.rememberDescription
import com.elyndra.launcher.ui.components.StatusStrip
import com.elyndra.launcher.ui.components.FallbackArt
import com.elyndra.launcher.ui.components.ArtVariant
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.DynamicBackdrop
import com.elyndra.launcher.ui.theme.rememberPress
import com.elyndra.launcher.ui.theme.pressScale
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.motion
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import com.elyndra.launcher.ui.theme.focusRing
import com.elyndra.launcher.ui.theme.shapeClickable
import com.elyndra.launcher.ui.theme.outerShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.elyndra.launcher.ui.theme.pressFeedback
import com.elyndra.launcher.ui.selection.mashaAura
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.LibraryFilter
import com.elyndra.launcher.ui.LibraryItem
import com.elyndra.launcher.ui.BarItem
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.components.DisintegratingContainer
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.components.MaterializingContainer
import com.elyndra.launcher.ui.components.Hero
import com.elyndra.launcher.ui.components.ConsoleIconButton
import com.elyndra.launcher.ui.components.HeroBarHeight
import com.elyndra.launcher.ui.components.LocalScreenSize
import com.elyndra.launcher.ui.components.MashaInsightBubble
import com.elyndra.launcher.ui.components.LogoImage
import com.elyndra.launcher.ui.components.OpenButton
import com.elyndra.launcher.ui.components.Metrics
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.components.MAGNETIC_PULL_MS
import com.elyndra.launcher.ui.components.rememberMagneticPress
import com.elyndra.launcher.ui.components.SearchGlyph
import com.elyndra.launcher.ui.components.SettingsGlyph
import com.elyndra.launcher.ui.components.inputStyle
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.HeroTitleShadow
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.animAppEntrance
import com.elyndra.launcher.ui.theme.animFadeUp
import com.elyndra.launcher.ui.theme.animTitleIn
import com.elyndra.launcher.ui.theme.consoleFaceBrush
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.liquidGlass
import com.elyndra.launcher.ui.theme.pulseHintAlpha
import com.elyndra.launcher.ui.theme.ringProgress
import com.elyndra.launcher.ui.theme.selectionLift
import com.elyndra.launcher.ui.selection.SelectionLook
import com.elyndra.launcher.ui.selection.rememberSelectionLook
import com.elyndra.launcher.ui.selection.selectionFrame
import androidx.compose.ui.zIndex
import com.elyndra.launcher.ui.theme.selectionScale
import com.elyndra.launcher.ui.theme.FloatClock
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.consoleFocus
import com.elyndra.launcher.ui.theme.floatPhaseOf
import com.elyndra.launcher.ui.theme.floating
import com.elyndra.launcher.ui.theme.rememberFloatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val reduced = LocalReducedMotion.current
    val items = vm.items()
    val sel = vm.selected()
    // Con mando la selección se mueve sin tocar el carrusel, así que el
    // carrusel va detrás de ella: si no, se estaría eligiendo a ciegas.
    val carousel = rememberLazyListState()
    // La card de "Añadir" también se alcanza con el mando: va la última, detrás de los juegos.
    val addFocused = vm.input.isAddFocused()
    LaunchedEffect(sel?.key, items.size, addFocused) {
        val index = if (addFocused) items.size else items.indexOfFirst { it.key == sel?.key }
        if (index >= 0) runCatching { carousel.animateScrollToItem(index) }
    }
    // Un solo reloj para la flotación de todos los logos de juego de la pantalla.
    val floatClock = rememberFloatClock()
    // El marco de la selección (Ajustes → Apariencia): halo, partículas y su color.
    val look = rememberSelectionLook(vm.settings.selectionParticleColor, vm.settings.selectionGlow, vm.settings.selectionParticles)
    val searchEmpty = vm.query.isNotBlank() && items.isEmpty() && vm.loaded
    // Sin nada en la sección (y sin estar buscando): el estado vacío ocupa el estante.
    val showEmpty = vm.loaded && items.isEmpty() && vm.query.isBlank()

    // El dock de secciones va en la barra del hero si la ventana es ancha; si
    // no, en la costura entre el hero y el estante (ver DockPlacement).
    val screen = LocalScreenSize.current
    val dockInBar = DockPlacement.inBar(screen.width.value)
    SideEffect { vm.input.dockInBar = dockInBar }
    val seamRise = DockPlacement.seamRise(heroInfoBottomPad(m).value).dp
    val dockInset = if (dockInBar) 0.dp else DotTabsLayout.HEIGHT.dp - seamRise + 6.dp
    val sortAnchor = remember { mutableStateOf(Rect.Zero) }
    val mashaAnchor = remember { mutableStateOf(Rect.Zero) }
    val mashaHost = remember { mutableStateOf(Offset.Zero) }
    val labelBlock = labelBlock(LIBRARY_LABEL_SP)

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { mashaHost.value = it.positionInWindow() }) {
        val shelfH = (maxHeight - m.iconHeroH).value
        val shelf = ShelfLayout.compute(
            available = shelfH,
            hintsVisible = vm.input.gamepadPresent,
            aspect = 1f,
            base = m.iconTile.value,
            topInset = dockInset.value,
            labelBlock = labelBlock,
            minTile = if (m.landscape) 72f else 96f,
            maxTile = ICON_TILE_LIMIT,
        )
        // Sin cards no hay escena que continuar: el estado vacío va sobre el estante liso.
        val extension = if (showEmpty) 0.dp else ShelfLayout.artExtension(shelfH, maxHeight.value, shelf.labelTop).dp
        val tile = shelf.tileHeight.dp
        // Con la barra de pistas el hero encoge y la fila se recoloca: se anima su
        // altura en pantalla (no en el estante), así que se desliza con muelle.
        val rowY by animateFloatAsState(m.iconHeroH.value + shelf.tileTop, motion(Springs.enter()), label = "shelfRow")

        // Fondo vivo: el arte de la selección, desenfocado y teñido con su color.
        DynamicBackdrop(
            when (sel) {
                is LibraryItem.App -> sel.app.meta.hero ?: sel.app.meta.screenshot ?: sel.app.meta.icon
                is LibraryItem.Folder -> sel.heroPath ?: sel.iconPath
                null -> null
            },
        )
        Column(Modifier.fillMaxSize().animAppEntrance(key = Screen.Library)) {

            Hero(
                fallback = sel?.let { vm.fallbackOf(it) },
                heroKey = sel?.key ?: "none",
                // Quitar el fondo lo deshace en polvo antes de borrarlo.
                backgroundVanishing = sel?.let { vm.isVanishingArt(it.key, ArtKind.Background) } == true,
                onBackgroundVanished = { vm.finishVanish() },
                height = m.iconHeroH,
                extension = extension,
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
                        MashaButton(vm) { mashaAnchor.value = it }
                        Spacer(Modifier.weight(1f))
                        // Ventana ancha: el dock y el orden abren el grupo de la
                        // derecha, a mano junto a la hora y "Abrir", con el mismo cristal.
                        if (dockInBar) {
                            SectionDock(vm) { sortAnchor.value = it }
                            Spacer(Modifier.width(8.dp))
                        }
                        // Hora y batería, arriba a la derecha junto a la barra. Con el
                        // buscador abierto se aparta: el campo necesita el ancho.
                        if (vm.settings.statusVisible && !vm.searchOpen) {
                            StatusStrip(vm.settings.statusMode)
                            Spacer(Modifier.width(8.dp))
                        }
                        // "Abrir" vive aquí, sobre el fondo del juego. Mientras
                        // se busca desaparece: el campo abierto necesita ese
                        // ancho y ahí nadie está lanzando nada.
                        if (!vm.searchOpen) {
                            OpenButton(enabled = sel != null, focused = vm.input.isBarFocused(BarItem.Open)) {
                                sel?.let {
                                    vm.requestOpen(it)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        SearchField(vm, m)
                        Spacer(Modifier.width(8.dp))
                        ConsoleIconButton(
                            onClick = { vm.go(Screen.Settings) },
                            focused = vm.input.isBarFocused(BarItem.Settings),
                        ) { glyph -> SettingsGlyph(glyph) }
                    }
                },
                info = {
                    // El bloque de título cambia con la selección: el nuevo entra
                    // con un fundido y una subida corta (ver heroInfoTransition).
                    AnimatedContent(
                        targetState = sel,
                        contentKey = { it?.key },
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                        contentAlignment = Alignment.BottomStart,
                        transitionSpec = { heroInfoTransition(reduced) },
                        label = "heroInfo",
                    ) { item ->
                        HeroInfo(vm, item, m, floatClock, searching = vm.query.isNotBlank())
                    }
                },
            )

            // ── ESTANTE ──
            // Sin fila de filtros: todo el alto es para el carrusel, que se
            // centra entre el hero (o el dock de la costura) y las pistas.
            Box(Modifier.fillMaxWidth().weight(1f).shelfSurface(extension)) {
                val bottomPad = if (vm.input.gamepadPresent) PAD_HINTS_HEIGHT else 0.dp
                when {
                    searchEmpty -> Box(
                        Modifier.fillMaxSize().padding(start = m.pad, end = m.pad, top = maxOf(dockInset, extension), bottom = bottomPad),
                        contentAlignment = Alignment.Center,
                    ) {
                        ElyText(stringResource(R.string.no_results), size = 11f, color = P.ink2, align = TextAlign.Center)
                    }
                    showEmpty -> Box(Modifier.fillMaxSize().padding(top = dockInset, bottom = bottomPad), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = null,
                            message = stringResource(R.string.empty_library_message),
                            actionLabel = stringResource(R.string.add_long),
                            onAction = { vm.go(Screen.Add) },
                            glyph = ConsoleGlyph.Gamepad,
                            focused = addFocused,
                        )
                    }
                    else -> LazyRow(
                        Modifier
                            .fillMaxSize()
                            .offset { IntOffset(0, (rowY - m.iconHeroH.value - shelf.tileTop).dp.roundToPx()) },
                        state = carousel,
                        contentPadding = PaddingValues(
                            start = m.pad,
                            end = m.pad,
                            // Por encima queda el hueco para lo que la seleccionada sube, crece y alumbra.
                            top = shelf.tileTop.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        itemsIndexed(items, key = { _, it -> it.key }) { _, item ->
                            val materializing = item.key in vm.materializing
                            // Las dos caras de lo mismo: un juego recién
                            // añadido se monta desde el polvo al entrar, y al
                            // quitarlo se deshace en polvo antes de salir de
                            // la lista (`finishVanish` es quien borra).
                            val selected = item.key == sel?.key && !addFocused
                            MaterializingContainer(
                                isMaterializing = materializing,
                                onAnimationEnd = { vm.finishMaterialize(item.key) },
                                // La seleccionada, por encima de sus vecinas: su halo no queda debajo.
                                modifier = Modifier.zIndex(if (selected) 1f else 0f),
                            ) {
                                DisintegratingContainer(
                                    isDisintegrating = vm.vanishing == item.key,
                                    onAnimationEnd = { vm.finishVanish() },
                                ) {
                                    LibraryTile(
                                        item = item,
                                        // Con la card de "Añadir" señalada, la selección es ella.
                                        selected = selected,
                                        look = look,
                                        tile = tile,
                                        landscape = m.landscape,
                                        fallback = vm.fallbackOf(item),
                                        onTap = { vm.select(item.key) },
                                        onOpen = { vm.requestOpen(item) },
                                        onBounds = vm::noteSelectedCard,
                                        onLongPress = { bounds ->
                                            vm.select(item.key)
                                            // De aquí sale el overlay.
                                            vm.markSheetOrigin(bounds)
                                            vm.itemOptions(item)
                                        },
                                    )
                                }
                            }
                        }
                        // "Añadir" es una card más y va al final de la fila: en
                        // cuanto entra un juego se corre detrás de todos sin dejar
                        // de estar a mano. El `loaded` evita que la card asome
                        // mientras se lee la biblioteca del disco.
                        if (vm.loaded) {
                            item(key = "add") { AddTile(tile, addFocused, look) { vm.go(Screen.Add) } }
                        }
                    }
                }
                PadHints(
                    hints = libraryHints(vm, showEmpty),
                    visible = vm.input.gamepadPresent,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = m.pad - 10.dp, bottom = 2.dp),
                )
            }
        }

        // Ventana estrecha: el dock flota en la costura, sin tapar el titular.
        if (!dockInBar) {
            SectionDock(
                vm,
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = m.iconHeroH - seamRise),
                // Media cápsula cae sobre el estante, que en claro es perla: cristal más denso.
                glassMinAlpha = DockPalettes.SEAM_GLASS_MIN,
            ) { sortAnchor.value = it }
        }

        MashaInsight(vm, mashaAnchor.value, mashaHost.value)

        SortPopover(
            visible = vm.sortMenuOpen,
            anchor = { sortAnchor.value },
            title = stringResource(R.string.sort_by),
            caption = pluralStringResource(R.plurals.items_count, items.size, items.size),
            options = SortMode.entries.map { stringResource(it.label) },
            selected = SortMode.entries.indexOf(vm.settings.sortMode),
            focusIndex = if (vm.input.active) vm.input.sortFocus else -1,
            onPick = { vm.pickSort(SortMode.entries[it]) },
            onDismiss = vm::closeSortMenu,
        )
    }
}

/** Tope del lado de la card de icono ya crecida (el tope de `metrics()` más el crecimiento). */
private const val ICON_TILE_LIMIT = 185f

/** Cuerpo (sp) del nombre bajo la card de la biblioteca. */
private const val LIBRARY_LABEL_SP = 9.5f

/** Hueco y línea del nombre bajo la card (dp), con la escala de letra del sistema. */
@Composable
internal fun labelBlock(sp: Float): Float = ShelfLayout.LABEL_GAP + sp * 1.45f * LocalDensity.current.fontScale

/** Margen bajo el bloque de título del hero (el dock de la costura no puede pasar de ahí). */
private fun heroInfoBottomPad(m: Metrics): Dp = if (m.landscape) 8.dp else 18.dp

/** El dock de secciones con el botón de orden al lado. */
@Composable
private fun SectionDock(
    vm: ElyndraViewModel,
    modifier: Modifier = Modifier,
    glassMinAlpha: Float = DARK_GLASS_MIN,
    onSortBounds: (Rect) -> Unit,
) {
    val filters = vm.availableFilters()
    val dockFocused = vm.input.isBarFocused(BarItem.Sections)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DotTabs(
            labels = filters.map { stringResource(it.label) },
            selected = filters.indexOf(vm.filter).coerceAtLeast(0),
            onSelect = { i -> filters.getOrNull(i)?.let(vm::updateFilter) },
            focused = dockFocused,
            focusIndex = if (dockFocused) vm.input.dockFocus else -1,
            glassMinAlpha = glassMinAlpha,
        )
        Spacer(Modifier.width(8.dp))
        SortButton(
            description = stringResource(R.string.sort_button_a11y, stringResource(vm.settings.sortMode.label)),
            open = vm.sortMenuOpen,
            onClick = { if (vm.sortMenuOpen) vm.closeSortMenu() else vm.openSortMenu() },
            focused = vm.input.isBarFocused(BarItem.Sort),
            glassMinAlpha = glassMinAlpha,
            modifier = Modifier.onGloballyPositioned { onSortBounds(it.boundsInWindow()) },
        )
    }
}

/** Las pistas del pie según lo que señale el mando: el carrusel, el dock, el botón de orden o su menú. */
private fun libraryHints(vm: ElyndraViewModel, empty: Boolean): List<PadHint> = when {
    vm.sortMenuOpen -> SORT_MENU_HINTS
    vm.input.isBarFocused(BarItem.Sections) -> DOCK_HINTS
    vm.input.isBarFocused(BarItem.Sort) -> SORT_HINTS
    empty -> EMPTY_HINTS
    else -> LIBRARY_HINTS
}

/** Las pistas del mando al pie de la biblioteca. */
private val LIBRARY_HINTS = listOf(
    PadHint("A", R.string.open),
    PadHint("X", R.string.details),
    PadHint("Y", R.string.hint_options),
    PadHint("LB / RB", R.string.hint_section),
)

/** Con la sección vacía no hay juego que abrir: A pulsa "Añadir" y LB/RB cambian de sección. */
private val EMPTY_HINTS = listOf(
    PadHint("A", R.string.hint_select),
    PadHint("LB / RB", R.string.hint_section),
)

/** En el dock: A elige la sección señalada. */
private val DOCK_HINTS = listOf(
    PadHint("A", R.string.hint_select),
    PadHint("LB / RB", R.string.hint_section),
    PadHint("B", R.string.hint_back),
)

private val SORT_HINTS = listOf(
    PadHint("A", R.string.sort_by),
    PadHint("LB / RB", R.string.hint_section),
    PadHint("B", R.string.hint_back),
)

private val SORT_MENU_HINTS = listOf(
    PadHint("A", R.string.hint_select),
    PadHint("B", R.string.close),
)

/**
 * El bloque de título del hero de una selección: chip y línea de datos, logo
 * (o titular) y la sinopsis o la pista de gestos. Sin selección, el titular
 * dice qué pasa: biblioteca vacía, sección vacía o búsqueda sin resultados.
 */
@Composable
private fun HeroInfo(vm: ElyndraViewModel, sel: LibraryItem?, m: Metrics, floatClock: FloatClock, searching: Boolean) {
    Column(
        Modifier
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
        val logo = when (sel) {
            is LibraryItem.App -> sel.app.meta.logo
            is LibraryItem.Folder -> sel.logoPath
            null -> null
        }
        // El `sel != null` es para el compilador: el `when` de arriba no le
        // basta para deducir que si hay logo entonces hay elemento.
        if (sel != null && logo != null) {
            // Al quitar el logo se deshace en el sitio donde se está viendo,
            // en vez de desaparecer de golpe.
            MaterializingContainer(
                isMaterializing = vm.isMaterializingArt(sel.key, ArtKind.Logo),
                onAnimationEnd = { vm.finishMaterializeArt() },
            ) {
                DisintegratingContainer(
                    isDisintegrating = vm.isVanishingArt(sel.key, ArtKind.Logo),
                    onAnimationEnd = { vm.finishVanish() },
                ) {
                    LogoImage(
                        logo,
                        Modifier
                            .floating(floatClock, floatPhaseOf(sel.key), amplitude = 3.dp, periodSeconds = 4.2f)
                            .fillMaxWidth(0.72f)
                            .height(m.logoH),
                    )
                }
            }
        } else {
            val libraryEmpty = vm.library.apps.isEmpty() && vm.library.folders.isEmpty()
            ElyText(
                when {
                    sel != null -> sel.name
                    !vm.loaded -> ""
                    searching -> stringResource(R.string.no_results)
                    libraryEmpty -> stringResource(R.string.empty_library_title)
                    else -> stringResource(R.string.empty_filter_title)
                },
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
        // Solo las apps tienen sinopsis en el hero; las carpetas no.
        val app = sel as? LibraryItem.App
        val description = rememberDescription(vm, app?.key, app?.app?.meta)
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
        } else if (sel != null && !vm.input.gamepadPresent) {
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

/** Buscador plegable de la barra superior. */
@Composable
private fun SearchField(vm: ElyndraViewModel, m: Metrics) {
    val open = vm.searchOpen
    // Al cerrarse, el campo suelta el foco para que el teclado no siga escribiendo en un buscador invisible.
    // Al abrirse lo pide, con su teclado: el buscador también se abre con el
    // mando (Select), y ahí no hay dedo que vaya a tocar el campo después.
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(open) {
        if (open) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            focusManager.clearFocus()
        }
    }
    val fieldWidth by animateDpAsState(
        targetValue = if (open) (if (m.landscape) 190.dp else 96.dp) else 0.dp,
        animationSpec = motion(Springs.enter()),
        label = "searchW",
    )
    val fieldAlpha by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = motion(Springs.fade()),
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
                // Se filtra al escribir: "Buscar" en el teclado solo lo cierra para ver los resultados.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    if (vm.query.isEmpty()) {
                        ElyText(stringResource(R.string.search_hint), size = 12f, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
                    }
                    inner()
                },
            )
        }
        ConsoleIconButton(
            onClick = { vm.toggleSearch() },
            focused = vm.input.isBarFocused(BarItem.Search),
            glass = !open,
        ) { glyph -> SearchGlyph(glyph) }
    }
}

/** Lado del emblema de Masha: algo mayor que las píldoras de la barra (34 dp), como un avatar. */
private val MASHA_BUTTON = 40.dp

/**
 * El botón de Masha: su emblema, fijo en la barra del hero, arriba a la
 * izquierda —la primera pieza de la barra, también para el mando—. Va dentro
 * del layout, así que no tapa nada ni se puede arrastrar encima del carrusel.
 *
 * Mide 40 dp pero en la barra cuenta como una píldora de 34 (no la desalinea)
 * y se toca en 48 × 48. Alrededor flotan sus partículas, del color de Masha
 * (Ajustes → Masha; ver [mashaAura]). [onBounds] recibe su rectángulo en la
 * ventana: de ahí sale el bocadillo.
 */
@Composable
private fun MashaButton(vm: ElyndraViewModel, onBounds: (Rect) -> Unit) {
    val shape = CircleShape
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .layout { measurable, _ ->
                val touch = 48.dp.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(touch, touch))
                val w = MASHA_BUTTON.roundToPx()
                val h = HeroBarHeight.roundToPx()
                layout(w, h) { placeable.place((w - touch) / 2, (h - touch) / 2) }
            }
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(interactionSource = interaction, indication = null) { vm.go(Screen.Masha) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(MASHA_BUTTON)
                .onGloballyPositioned { onBounds(it.boundsInWindow()) }
                .pressFeedback(interaction)
                .mashaAura(vm.settings.mashaParticleColor)
                .outerShadow(10.dp, shape, ambientColor = P.shade.copy(alpha = 0.45f), spotColor = P.shade.copy(alpha = 0.45f))
                .consoleFocus(vm.input.isBarFocused(BarItem.Masha), shape)
                .indication(interaction, focusRing(shape)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.masha),
                contentDescription = stringResource(R.string.masha),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * La línea ambiental de Masha, bajo su emblema. Se recoge sola al rato: está
 * para enterarse de un vistazo, no para quedarse tapando el hero.
 */
@Composable
private fun MashaInsight(vm: ElyndraViewModel, anchor: Rect, host: Offset) {
    val screen = LocalScreenSize.current
    val density = LocalDensity.current
    val insight = vm.masha.insight
    val revision = vm.masha.insightRevision
    var bubble by remember(insight?.id, revision) { mutableStateOf(insight != null) }
    LaunchedEffect(insight?.id, revision) {
        if (insight != null) {
            delay(MASHA_BUBBLE_MS)
            bubble = false
        }
    }
    if (insight == null || !bubble || anchor == Rect.Zero) return
    with(density) {
        MashaInsightBubble(
            text = vm.masha.insightText(insight).resolve(),
            anchorX = (anchor.left - host.x).toDp(),
            anchorY = (anchor.top - host.y).toDp(),
            anchorSize = anchor.width.toDp(),
            screen = screen,
            onTap = { vm.masha.actOnInsight(vm.settings.lang) },
            onDismiss = { vm.masha.dismissInsight() },
            key = insight.id,
            below = true,
        )
    }
}

/** Cuánto se queda a la vista la línea de Masha antes de recogerse. */
private const val MASHA_BUBBLE_MS = 14_000L

/**
 * La card de "Añadir": la única del carrusel cuando la biblioteca está vacía
 * y, en cuanto hay juegos, la última de la fila. Con el mando se llega a ella
 * pasando del último juego ([focused]) y se abre con A, como cualquier otra.
 */
@Composable
private fun AddTile(tile: Dp, focused: Boolean, look: SelectionLook, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val lift = selectionLift(focused)
    val scale = selectionScale(focused)
    val shape = RoundedCornerShape(16.dp)
    // Se pulsa toda la columna (tile y rótulo), pero el realce se dibuja solo
    // en el tile, con su forma: comparten la fuente de interacciones.
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .width(tile)
            .offset(y = lift)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(tile)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .selectionFrame(focused, look, shape)
                .glass(shape, borderColor = skin.a1.copy(alpha = 0.6f))
                .indication(interaction, focusRing(shape)),
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
    selected: Boolean,
    look: SelectionLook,
    /** Lado de la card cuadrada (lo decide [ShelfLayout]). */
    tile: Dp,
    landscape: Boolean,
    fallback: ArtFallback,
    onTap: () -> Unit,
    onOpen: () -> Unit,
    /** Recibe el rectángulo de la card en la ventana: el menú de acciones sale de ahí. */
    onLongPress: (Rect) -> Unit,
    /** Rectángulo de la card mientras está seleccionada (el menú sale de ahí con el mando). */
    onBounds: (Rect) -> Unit = {},
) {
    // Juegos Android y carpetas de emulador se representan con icono, no con
    // carátula: su contenedor es cuadrado.
    val width: Dp = tile
    val lift = selectionLift(selected)
    val scale = selectionScale(selected)
    val press = rememberPress()
    val pressed = pressScale(press)
    val shape = RoundedCornerShape(16.dp)
    // Tirón magnético: al mantener pulsado, la card se hunde y rebota antes
    // de que salga el menú (ver `rememberMagneticPress`).
    val magnetic = rememberMagneticPress()
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    val scope = rememberCoroutineScope()
    val dimmed = item is LibraryItem.App && !item.installed
    // La luz de la selección la pone el marco; la sombra es siempre neutra.
    val shadowColor = P.shade.copy(alpha = 0.2f)
    // Mismo motivo que en RomTile: el detector vive más que una composición.
    val tap by rememberUpdatedState(onTap)
    val open by rememberUpdatedState(onOpen)
    val longPress by rememberUpdatedState(onLongPress)

    Column(
        Modifier
            .width(width)
            .offset(y = lift)
            .onGloballyPositioned {
                cardBounds = it.boundsInWindow()
                if (selected) onBounds(cardBounds)
            }
            // La escala del tirón se lee en fase de dibujo: mover la card no
            // recompone ni la lista ni la pantalla.
            .graphicsLayer {
                scaleX = magnetic.value
                scaleY = magnetic.value
                // Atenuada sin capa aparte: una capa recortaría el halo de la selección.
                alpha = if (dimmed) 0.5f else 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .pointerInput(item.key) {
                detectTapGestures(
                    onPress = { press.track(this) },
                    onTap = { tap() },
                    onDoubleTap = { open() },
                    onLongPress = {
                        // Primero el tirón —la card se hunde y rebota— y solo
                        // cuando se ha sentido el agarre sale el menú.
                        scope.launch {
                            launch { magnetic.run() }
                            delay(MAGNETIC_PULL_MS.toLong())
                            longPress(cardBounds)
                        }
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(tile)
                .graphicsLayer {
                    scaleX = scale * pressed.value
                    scaleY = scale * pressed.value
                }
                // Solo por fuera: la card es cristal translúcido y una sombra de
                // elevación asomaba a través de ella, detrás del icono.
                .outerShadow(
                    if (press.pressed) 4.dp else if (selected) 12.dp else 8.dp,
                    shape,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
                // Tras la escala (la acompaña) y antes del recorte del cristal:
                // halo, luz en el estante y partículas asoman por fuera.
                .selectionFrame(selected, look, shape)
                // Sin fondo de serie: cristal semitransparente, que deja ver la
                // aurora de la pantalla por detrás del icono.
                .liquidGlass(shape, P.hairline),
        ) {
            // Ni un juego Android ni una carpeta de emulador usan carátula: se
            // representan con su icono — el elegido en "Personalizar icono" o, si
            // no hay ninguno, el de la app instalada / del emulador de la carpeta.
            // La carátula queda solo para las ROMs.
            val icon = when (item) {
                is LibraryItem.App -> item.app.meta.icon
                is LibraryItem.Folder -> item.iconPath
            }
            // Icono automático cuando no se ha elegido ninguno.
            val autoIconPackage = when (item) {
                is LibraryItem.App -> item.app.packageName
                is LibraryItem.Folder -> item.emulatorPackage
            }
            // Contenedor e icono son cuadrados: el icono lo llena entero,
            // centrado, sin dejar huecos y sin deformarse.
            if (icon != null || autoIconPackage != null) {
                GameIcon(icon, autoIconPackage, Modifier.fillMaxSize(), ContentScale.Fit)
                val unnamed = item is LibraryItem.App && remember(item.app.displayTitle) { !NameCheck.isNameUsable(item.app.displayTitle) }
                if (unnamed) NeedsNameBadge(Modifier.align(Alignment.TopStart).padding(6.dp), onDark = true)
            } else if (item is LibraryItem.Folder) {
                // Carpeta sin icono y sin emulador instalado: rótulo de consola.
                // Debajo, el arte de reserva de la carpeta; encima, el velo del rótulo.
                FallbackArt(fallback, ArtVariant.Square, Modifier.fillMaxSize(), showIcon = false)
                Column(
                    Modifier
                        .fillMaxSize()
                        .drawBehind { drawRect(consoleFaceBrush(size)) }
                        .padding(horizontal = 8.dp),
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
                        // La card de carpeta ya no es apaisada, sino una carátula 2:3:
                        // el rótulo se mide contra su ancho, no contra su alto.
                        size = (tile.value * if (landscape) 0.2f else 0.18f).coerceAtMost(26f),
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
