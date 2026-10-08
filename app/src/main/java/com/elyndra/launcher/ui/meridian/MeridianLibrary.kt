package com.elyndra.launcher.ui.meridian

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.library.NameCheck
import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.ui.BarItem
import com.elyndra.launcher.ui.DockPalettes
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.TapAction
import com.elyndra.launcher.ui.TapGate
import com.elyndra.launcher.ui.rememberTapSlop
import android.os.SystemClock
import com.elyndra.launcher.ui.LibraryItem
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.SortMode
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.ArtVariant
import com.elyndra.launcher.ui.components.ConsoleIconButton
import com.elyndra.launcher.ui.components.DisintegratingContainer
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.EmptyLibraryHero
import com.elyndra.launcher.ui.components.LocalScreenSize
import com.elyndra.launcher.ui.components.FallbackArt
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.components.MaterializingContainer
import com.elyndra.launcher.ui.components.Metrics
import com.elyndra.launcher.ui.components.NeedsNameBadge
import com.elyndra.launcher.ui.components.OpenButton
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.SettingsGlyph
import com.elyndra.launcher.ui.components.SortPopover
import com.elyndra.launcher.ui.components.StatusStrip
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.heroDescription
import com.elyndra.launcher.ui.rememberDescription
import com.elyndra.launcher.ui.screens.DOCK_HINTS
import com.elyndra.launcher.ui.screens.MashaButton
import com.elyndra.launcher.ui.screens.MashaInsight
import com.elyndra.launcher.ui.screens.SORT_HINTS
import com.elyndra.launcher.ui.screens.SORT_MENU_HINTS
import com.elyndra.launcher.ui.screens.SearchField
import com.elyndra.launcher.ui.screens.SectionDock
import com.elyndra.launcher.ui.selection.rememberSelectionLook
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.consoleFaceBrush
import com.elyndra.launcher.ui.theme.liquidGlass

/**
 * La biblioteca en Meridian (ventana apaisada y ancha): la rueda vertical a
 * la izquierda con el dock de secciones y el orden encima, alineados con el
 * eje; el arte del juego enfocado a pantalla completa y su bloque a la
 * derecha. Filtros, orden, selección y búsqueda son los mismos del
 * ViewModel que usa el carrusel: girar el aparato no pierde nada.
 */
@Composable
internal fun MeridianLibrary(vm: ElyndraViewModel) {
    val m = metrics()
    val reduced = LocalReducedMotion.current
    val ink = rememberMeridianInk()
    val items = vm.items()
    val sel = vm.selected()
    val addFocused = vm.input.isAddFocused()
    val look = rememberSelectionLook(vm.settings.selectionParticleColor, vm.settings.selectionGlow, vm.settings.selectionParticles)
    val searchEmpty = vm.query.isNotBlank() && items.isEmpty() && vm.loaded
    // "Añadir" es la última fila en cuanto la biblioteca está leída (salvo en una búsqueda sin resultados).
    val hasAdd = vm.loaded && !searchEmpty
    // El dedo dejó la rueda en "Añadir": ahí se queda hasta que se elija otra cosa.
    var restOnAdd by remember { mutableStateOf(false) }
    // Cualquier otra selección (el mando, la búsqueda, otra sección) la saca de ahí.
    LaunchedEffect(sel?.key, vm.filter) { restOnAdd = false }
    val onAdd = hasAdd && (addFocused || restOnAdd || items.isEmpty())
    val focused = if (onAdd) items.size else items.indexOfFirst { it.key == sel?.key }.coerceAtLeast(0)
    val hero = if (onAdd || searchEmpty) null else sel
    val enter = rememberMeridianEnter(vm.intro.visible && !vm.intro.fading)
    val wheel = remember { mutableStateOf<WheelState?>(null) }
    val sortAnchor = remember { mutableStateOf(Rect.Zero) }
    val dockAnchor = remember { mutableStateOf(Rect.Zero) }
    val mashaAnchor = remember { mutableStateOf(Rect.Zero) }
    val mashaHost = remember { mutableStateOf(Offset.Zero) }
    val hostBounds = remember { mutableStateOf(Rect.Zero) }
    // El bocadillo de Masha mientras se ve (en la ventana): la rueda le deja sitio.
    val bubble = remember { mutableStateOf<Rect?>(null) }
    val filters = vm.availableFilters()
    val previous = remember { arrayOf(vm.filter) }
    val forward = remember(vm.filter) {
        val f = filters.indexOf(vm.filter) >= filters.indexOf(previous[0])
        previous[0] = vm.filter
        f
    }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val entries = remember(items, configuration) { items.map { wheelEntry(context.resources, it) } }
    val keyIndex = remember(items) { items.withIndex().associate { (i, it) -> it.key to i } }
    val index = remember(items, vm.settings.sortMode) {
        if (IndexStrip.visible(vm.settings.sortMode == SortMode.Name, items.size)) IndexStrip.entries(items.map { it.name }) else null
    }
    val add = if (hasAdd) WheelAdd(stringResource(R.string.meridian_add_short), stringResource(R.string.add_long)) { if (vm.tryOpen()) vm.go(Screen.Add) } else null
    // Tocar: seleccionar o abrir (ver TapGate); nada se abre dos veces seguidas (vm.tryOpen).
    val gate = remember { TapGate() }
    val slop = rememberTapSlop()
    val openKey: (String?) -> Unit = { key ->
        when {
            key == null -> Unit
            key == WHEEL_ADD_KEY -> if (vm.tryOpen()) vm.go(Screen.Add)
            else -> items.firstOrNull { it.key == key }?.let {
                if (vm.tryOpen()) {
                    restOnAdd = false
                    vm.select(it.key)
                    vm.requestOpen(it)
                }
            }
        }
    }

    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            mashaHost.value = it.positionInWindow()
            hostBounds.value = it.boundsInWindow()
        },
    ) {
        MeridianScene(
            enterMs = { enter.value },
            art = MeridianArt(
                key = hero?.key ?: WHEEL_ADD_KEY,
                imagePath = when (hero) {
                    is LibraryItem.App -> hero.app.meta.hero ?: hero.app.meta.screenshot
                    is LibraryItem.Folder -> hero.heroPath
                    null -> null
                },
                fallback = hero?.let { vm.fallbackOf(it) },
                vanishing = hero?.let { vm.isVanishingArt(it.key, ArtKind.Background) } == true,
                onVanished = { vm.finishVanish() },
            ),
            wheel = { wheel.value },
            artIndex = { key -> if (key == WHEEL_ADD_KEY) items.size else keyIndex[key] },
            railHeader = { RailHeader(vm, onMasha = { mashaAnchor.value = it }, onSort = { sortAnchor.value = it }, onDock = { dockAnchor.value = it }) },
            topEnd = { TopEnd(vm, m, enabled = onAdd || hero != null) { openKey(if (onAdd) WHEEL_ADD_KEY else hero?.key) } },
            hints = {
                PadHints(
                    hints = meridianHints(vm),
                    visible = vm.input.gamepadPresent,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
            },
            hero = { LibraryHero(vm, hero, onAdd, searchEmpty, items.isEmpty(), ink, openKey) },
            adaptive = vm.settings.meridianAdaptiveColor,
        ) { geo, wash ->
            MeridianWheelArea(
                geo = geo,
                section = vm.filter,
                forward = forward,
                entries = entries,
                add = add,
                focused = focused,
                selectedKey = sel?.key,
                addFocused = addFocused,
                tileAspect = 1f,
                look = look,
                ink = ink,
                wash = wash,
                enterMs = { enter.value },
                reduced = reduced,
                lite = LocalMeridianFrame.current.lite,
                current = wheel,
                index = index,
                bubbleBottom = bubble.value?.bottom,
                onSettle = { row ->
                    restOnAdd = row >= items.size
                    items.getOrNull(row)?.let { vm.select(it.key) }
                },
                onJump = { i ->
                    restOnAdd = false
                    items.getOrNull(i)?.let { vm.select(it.key) }
                },
                tile = { e, _, _ -> (e.payload as? LibraryItem)?.let { LibraryWheelTile(it, vm.fallbackOf(it)) } },
                wrap = { e, content ->
                    MaterializingContainer(isMaterializing = e.key in vm.materializing, onAnimationEnd = { vm.finishMaterialize(e.key) }) {
                        DisintegratingContainer(isDisintegrating = vm.vanishing == e.key, onAnimationEnd = { vm.finishVanish() }) { content() }
                    }
                },
                onTap = { row, at, moving ->
                    val key = items.getOrNull(row)?.key ?: WHEEL_ADD_KEY
                    val current = if (onAdd) WHEEL_ADD_KEY else sel?.key
                    val d = gate.tap(key, current, moving, vm.settings.tapOpensSelected, SystemClock.uptimeMillis(), at.x, at.y, slop)
                    when (d.action) {
                        TapAction.Select -> if (key == WHEEL_ADD_KEY) restOnAdd = true else {
                            restOnAdd = false
                            vm.select(key)
                        }
                        TapAction.Open -> openKey(d.key)
                        TapAction.Ignore -> Unit
                    }
                },
                onOpen = { row -> openKey(items.getOrNull(row)?.key ?: WHEEL_ADD_KEY) },
                onLongPress = { e, bounds ->
                    restOnAdd = false
                    vm.select(e.key)
                    vm.markSheetOrigin(bounds)
                    (e.payload as? LibraryItem)?.let(vm::itemOptions)
                },
                onBounds = vm::noteSelectedCard,
                // Sin filas solo hay dos casos: una búsqueda sin resultados o la biblioteca aún leyéndose (nada que decir).
                empty = { focusY -> if (searchEmpty) RailMessage(stringResource(R.string.no_results), focusY) },
            )
        }

        // El bocadillo va en la columna de la rueda, bajo el botón de Masha, sin pisar el dock ni el orden.
        val host = hostBounds.value
        val compact = with(LocalDensity.current) { MeridianMode.compact(host.height.toDp().value) }
        val railRight = with(LocalDensity.current) { host.left + MeridianGeometry.railWidthFor(host.width.toDp().value, compact).dp.toPx() }
        MashaInsight(
            vm,
            mashaAnchor.value,
            mashaHost.value,
            region = if (host == Rect.Zero) null else Rect(host.left, host.top, railRight, host.bottom),
            obstacles = listOf(dockAnchor.value, sortAnchor.value),
            oneLine = compact,
            onPlaced = { bubble.value = it },
        )

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

/** La fila de un juego o carpeta: nombre, "Android · 2h 10m jugado" o "12 ROMs · Emulador", y sus fichas. */
private fun wheelEntry(res: Resources, item: LibraryItem): WheelEntry = when (item) {
    is LibraryItem.Folder -> WheelEntry(
        key = item.key,
        title = item.name,
        subline = res.getQuantityString(R.plurals.roms_with_emulator, item.romCount, item.romCount, item.emulatorName ?: "—"),
        // Plataforma y cantidad; el emulador va en el hero.
        chips = listOf(item.system.short, res.getQuantityString(R.plurals.meridian_roms, item.romCount, item.romCount)),
        payload = item,
        art = item.iconPath,
        pkg = item.emulatorPackage,
    )
    is LibraryItem.App -> {
        val played = when {
            !item.installed -> res.getString(R.string.app_not_installed_badge)
            item.app.stats.minutes > 0 -> res.getString(R.string.played_time, fmtMinutes(item.app.stats.minutes))
            else -> res.getString(R.string.never_played)
        }
        val android = res.getString(R.string.filter_android)
        WheelEntry(
            key = item.key,
            title = item.name,
            subline = "$android · $played",
            chips = listOf(android, played),
            dimmed = !item.installed,
            payload = item,
            art = item.app.meta.icon,
            pkg = item.app.packageName,
        )
    }
}

/** El arte de una fila: el icono del juego o del emulador; sin icono, el rótulo de la consola. */
@Composable
private fun LibraryWheelTile(item: LibraryItem, fallback: ArtFallback) {
    val icon = when (item) {
        is LibraryItem.App -> item.app.meta.icon
        is LibraryItem.Folder -> item.iconPath
    }
    val pkg = when (item) {
        is LibraryItem.App -> item.app.packageName
        is LibraryItem.Folder -> item.emulatorPackage
    }
    Box(Modifier.fillMaxSize().liquidGlass(RectangleShape, P.hairline)) {
        if (icon != null || pkg != null) {
            GameIcon(icon, pkg, Modifier.fillMaxSize(), ContentScale.Fit)
            val unnamed = item is LibraryItem.App && remember(item.app.displayTitle) { !NameCheck.isNameUsable(item.app.displayTitle) }
            if (unnamed) NeedsNameBadge(Modifier.align(Alignment.TopStart).padding(4.dp), onDark = true)
        } else if (item is LibraryItem.Folder) {
            FallbackArt(fallback, ArtVariant.Square, Modifier.fillMaxSize(), showIcon = false)
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(consoleFaceBrush(size)) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                ElyText(item.system.abbr, size = 11f, weight = FontWeight.Bold, color = Color.White, letterSpacing = tracking(0.12f), maxLines = 1)
            }
        }
    }
}

/**
 * La cabecera de la rueda: el emblema de Masha a la izquierda y, alineados
 * con el eje, el dock de secciones y el orden. Si no caben juntos (ventana
 * justa o rótulos largos), el dock baja a una segunda línea, pegado al eje.
 */
@Composable
private fun RailHeader(vm: ElyndraViewModel, onMasha: (Rect) -> Unit, onSort: (Rect) -> Unit, onDock: (Rect) -> Unit) {
    val compact = LocalMeridianFrame.current.compact
    Layout(
        content = {
            MashaButton(vm, onMasha)
            // Sobre el fondo de la rueda: el cristal del dock más denso, como en la costura.
            Box(Modifier.onGloballyPositioned { onDock(it.boundsInWindow()) }) {
                SectionDock(vm, glassMinAlpha = DockPalettes.SEAM_GLASS_MIN, onSortBounds = onSort)
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 8.dp, bottom = if (compact) 2.dp else 6.dp),
    ) { measurables, constraints ->
        val start = 22.dp.roundToPx()
        val end = 16.dp.roundToPx()
        val gap = 10.dp.roundToPx()
        val masha = measurables[0].measure(Constraints())
        val dock = measurables[1].measure(Constraints(maxWidth = (constraints.maxWidth - start - end).coerceAtLeast(0)))
        val inline = start + masha.width + gap + dock.width <= constraints.maxWidth - end
        val height = if (inline) maxOf(masha.height, dock.height) else masha.height + 6.dp.roundToPx() + dock.height
        layout(constraints.maxWidth, height) {
            masha.place(start, if (inline) (height - masha.height) / 2 else 0)
            val x = (constraints.maxWidth - end - dock.width).coerceAtLeast(start)
            dock.place(x, if (inline) (height - dock.height) / 2 else height - dock.height)
        }
    }
}

/** La barra de la derecha, la de siempre: hora y batería, "Abrir", buscar y Ajustes. */
@Composable
private fun TopEnd(vm: ElyndraViewModel, m: Metrics, enabled: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (vm.settings.statusVisible && !vm.searchOpen) {
            StatusStrip(vm.settings.statusMode)
            Spacer(Modifier.width(8.dp))
        }
        if (!vm.searchOpen) {
            // "Abrir" hace lo mismo que la acción principal del hero (también en la fila de "Añadir").
            OpenButton(enabled = enabled, focused = vm.input.isBarFocused(BarItem.Open), onClick = onOpen)
            Spacer(Modifier.width(8.dp))
        }
        SearchField(vm, m)
        Spacer(Modifier.width(8.dp))
        ConsoleIconButton(onClick = { vm.go(Screen.Settings) }, focused = vm.input.isBarFocused(BarItem.Settings)) { glyph -> SettingsGlyph(glyph) }
    }
}

/** El bloque del hero de la biblioteca: el juego enfocado, "Añadir" o lo que pasa (vacía, sin resultados). */
@Composable
private fun LibraryHero(vm: ElyndraViewModel, item: LibraryItem?, onAdd: Boolean, searchEmpty: Boolean, empty: Boolean, ink: MeridianInk, openKey: (String?) -> Unit) {
    val app = item as? LibraryItem.App
    val description = rememberDescription(vm, app?.key, app?.app?.meta)
    val libraryEmpty = vm.library.apps.isEmpty() && vm.library.folders.isEmpty()
    if (empty && vm.loaded && !searchEmpty) {
        // El estado vacío compartido con el diseño clásico, sobre el escenario de la escena.
        val frame = LocalMeridianFrame.current
        val screen = LocalScreenSize.current
        val density = LocalDensity.current.density
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            EmptyLibraryHero(
                title = stringResource(if (libraryEmpty) R.string.empty_library_title else R.string.empty_filter_title),
                message = if (frame.compact) null else stringResource(R.string.empty_library_message),
                action = stringResource(R.string.add_long),
                onAction = { openKey(WHEEL_ADD_KEY) },
                titleBox = LogoFit.box(frame.heroWidth, maxWidth.value, screen.height.value, frame.compact, density = density),
                padGlyphs = vm.input.gamepadPresent,
                compact = frame.compact,
            )
        }
        return
    }
    val content = when {
        searchEmpty -> MeridianHeroContent("search", emptyList(), stringResource(R.string.no_results), null)
        !vm.loaded -> MeridianHeroContent("loading", emptyList(), "", null)
        onAdd || item == null -> MeridianHeroContent(WHEEL_ADD_KEY, emptyList(), stringResource(R.string.add_long), stringResource(R.string.empty_library_hint))
        else -> MeridianHeroContent(
            key = item.key,
            chips = heroChips(item),
            title = item.name,
            description = description?.let { heroDescription(it, vm.settings.lang) },
            logo = when (item) {
                is LibraryItem.App -> item.app.meta.logo
                is LibraryItem.Folder -> item.logoPath
            },
        )
    }
    val actions = when {
        !vm.loaded || searchEmpty -> null
        onAdd || item == null -> MeridianActions(stringResource(R.string.add_long), { openKey(WHEEL_ADD_KEY) }, null, {}, null, {})
        else -> MeridianActions(
            primary = stringResource(if (item is LibraryItem.Folder) R.string.open else R.string.sheet_play),
            onPrimary = { openKey(item.key) },
            details = stringResource(R.string.details),
            onDetails = { vm.showDetails(item.key) },
            options = stringResource(R.string.hint_options),
            onOptions = { vm.itemOptions(item) },
        )
    }
    MeridianHeroPanel(content, actions, ink, vm.input.gamepadPresent) { shown, reveal, box ->
        val key = shown.key as? String ?: return@MeridianHeroPanel
        val logo = shown.logo ?: return@MeridianHeroPanel
        MaterializingContainer(isMaterializing = vm.isMaterializingArt(key, ArtKind.Logo), onAnimationEnd = { vm.finishMaterializeArt() }) {
            DisintegratingContainer(isDisintegrating = vm.isVanishingArt(key, ArtKind.Logo), onAnimationEnd = { vm.finishVanish() }) {
                MeridianLogo(logo, box, reveal)
            }
        }
    }
}

/** Las fichas del hero: qué es, cuánto tiene o cuánto se ha jugado y si falta algo. */
@Composable
private fun heroChips(item: LibraryItem): List<String> = when (item) {
    is LibraryItem.Folder -> listOfNotNull(
        stringResource(R.string.chip_emulator_folder),
        pluralStringResource(R.plurals.roms_with_emulator, item.romCount, item.romCount, item.emulatorName ?: "—"),
        if (item.minutes > 0) stringResource(R.string.played_time, fmtMinutes(item.minutes)) else null,
    )
    is LibraryItem.App -> listOfNotNull(
        stringResource(R.string.chip_android_app),
        if (item.app.stats.minutes > 0) stringResource(R.string.played_time, fmtMinutes(item.app.stats.minutes)) else stringResource(R.string.never_played),
        if (!item.installed) stringResource(R.string.app_not_installed_badge) else null,
    )
}

/** Un aviso en la rueda (búsqueda sin resultados), a la altura de la línea de foco. */
@Composable
internal fun RailMessage(text: String, focusY: Float) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 32.dp, top = with(LocalDensity.current) { (focusY.toDp() - 12.dp).coerceAtLeast(0.dp) }),
        verticalArrangement = Arrangement.Top,
    ) {
        ElyText(text, size = 13f, weight = FontWeight.SemiBold, color = P.ink2, align = TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Las pistas del mando bajo la rueda: moverse y cambiar de sección (A, X e Y ya están en el hero). */
private fun meridianHints(vm: ElyndraViewModel): List<PadHint> = when {
    vm.sortMenuOpen -> SORT_MENU_HINTS
    vm.input.isBarFocused(BarItem.Sections) -> DOCK_HINTS
    vm.input.isBarFocused(BarItem.Sort) -> SORT_HINTS
    else -> MERIDIAN_HINTS
}

private val MERIDIAN_HINTS = listOf(
    PadHint("↑↓", R.string.hint_move),
    PadHint("LB / RB", R.string.hint_section),
)
