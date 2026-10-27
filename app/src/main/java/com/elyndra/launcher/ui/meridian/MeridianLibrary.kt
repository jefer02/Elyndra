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
import com.elyndra.launcher.ui.DotTabsLayout
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
import com.elyndra.launcher.ui.components.rememberDotTabPills
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
import com.elyndra.launcher.ui.screens.SectionSortButton
import com.elyndra.launcher.ui.screens.SectionTabs
import com.elyndra.launcher.ui.selection.rememberSelectionLook
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.screens.rememberTopBarFit
import com.elyndra.launcher.ui.theme.consoleFaceBrush
import com.elyndra.launcher.ui.theme.liquidGlass
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.ceil
import com.elyndra.launcher.ui.theme.LightGlass
import com.elyndra.launcher.ui.theme.lightGlass

/**
 * La biblioteca en Meridian (ventana apaisada y ancha): la rueda vertical a
 * la izquierda con el dock de secciones encima, alineado con el eje; el arte
 * del juego enfocado a pantalla completa y su bloque a la derecha, y la barra
 * arriba a la derecha (Masha la primera, el orden junto al buscador), en la
 * misma fila que el dock. Filtros, orden, selección y búsqueda son los mismos del
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
    // La barra de arriba a la derecha (Masha, hora, Abrir, orden, buscar, Ajustes): el bocadillo va debajo, sin tocarla.
    val topAnchor = remember { mutableStateOf(Rect.Zero) }
    // El bloque del hero (fichas, título, Jugar/Detalles/Opciones): el bocadillo nunca lo tapa.
    val heroAnchor = remember { mutableStateOf(Rect.Zero) }
    val fogArc = remember { mutableStateOf(FogArc.NONE) }
    val mashaAnchor = remember { mutableStateOf(Rect.Zero) }
    val mashaHost = remember { mutableStateOf(Offset.Zero) }
    val hostBounds = remember { mutableStateOf(Rect.Zero) }
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
            railHeader = { RailHeader(vm, onDock = { dockAnchor.value = it }) },
            topEnd = {
                TopEnd(vm, m, enabled = onAdd || hero != null, onMasha = { mashaAnchor.value = it }, onSort = { sortAnchor.value = it }, onBounds = { topAnchor.value = it }) {
                    openKey(if (onAdd) WHEEL_ADD_KEY else hero?.key)
                }
            },
            hints = {
                PadHints(
                    hints = meridianHints(vm),
                    visible = vm.input.gamepadPresent,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                    // En la columna estrecha de un móvil, en dos filas antes que cortar el rótulo.
                    wrap = true,
                )
            },
            hero = {
                Box(Modifier.fillMaxWidth().onGloballyPositioned { heroAnchor.value = it.boundsInWindow() }) {
                    LibraryHero(vm, hero, onAdd, searchEmpty, items.isEmpty(), ink, openKey)
                }
            },
            adaptive = vm.settings.meridianAdaptiveColor,
            wheelGlass = vm.settings.wheelGlassFor(P.isDark),
            fogArc = fogArc,
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
                onSettle = { row ->
                    restOnAdd = row >= items.size
                    items.getOrNull(row)?.let { vm.select(it.key) }
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

        // El bocadillo, debajo del grupo de arriba a la derecha (Masha es su primera pieza), sobre
        // el arte: la línea entera en una fila. Nunca sobre la barra, el dock ni el bloque del hero.
        val host = hostBounds.value
        val density = LocalDensity.current
        val hostW = with(density) { host.width.toDp().value }
        val hostH = with(density) { host.height.toDp().value }
        val railW = MeridianGeometry.slimRailWidth(hostW, hostH, 1f, MeridianMode.compact(hostH))
        val railRight = with(density) { host.left + railW.dp.toPx() }
        MashaInsight(
            vm,
            mashaAnchor.value,
            mashaHost.value,
            region = if (host == Rect.Zero) null else Rect(railRight, host.top, host.right, host.bottom),
            obstacles = listOf(dockAnchor.value, topAnchor.value, heroAnchor.value),
            group = topAnchor.value,
            oneLine = true,
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
 * La cabecera de la rueda: el dock de secciones, alineado con el eje (ver
 * [RailHeaderRow]). Masha y el orden van en la barra de la derecha ([TopEnd]).
 */
@Composable
private fun RailHeader(vm: ElyndraViewModel, onDock: (Rect) -> Unit) {
    val labels = vm.availableFilters().map { stringResource(it.label) }
    RailHeaderRow(
        compact = LocalMeridianFrame.current.compact,
        pills = rememberDotTabPills(labels),
        dock = {
            // Sobre el fondo de la rueda: el cristal del dock más denso, como en la costura
            // (en claro, el cristal claro, como todo lo que va sobre el arte en la rueda).
            SectionTabs(
                vm,
                modifier = Modifier.onGloballyPositioned { onDock(it.boundsInWindow()) },
                glassMinAlpha = DockPalettes.SEAM_GLASS_MIN,
                light = !P.isDark,
            )
        },
    )
}

/**
 * La cabecera de la rueda sin ViewModel (también para las vistas previas):
 * [dock] alineado con el eje, en una sola fila con el mismo tope y la misma
 * línea central que la barra de la derecha ([MeridianTopRow]); si la columna
 * es estrecha asoma sobre el hueco del hero (ver [RailHeaderLayout]).
 *
 * [pills] son las píldoras naturales del dock ([rememberDotTabPills]): con
 * ellas se sabe cuánto medirá antes de medirlo. Su ancho sale del canto
 * curvo de la niebla a la altura de la cabecera ([RailHeaderLayout.plan]),
 * que da la escena ([LocalRailFog]); y la cabecera le devuelve hasta dónde
 * tiene que llegar la niebla en su banda ([FogArc.reach]). Antes de que la
 * rueda se mida, la curva la calcula aquí con lo mismo que usará la rueda
 * ([FogArc.forRail]): el dock tiene su ancho final desde el primer fotograma.
 */
@Composable
internal fun RailHeaderRow(compact: Boolean, pills: FloatArray, dock: @Composable () -> Unit) {
    val fog = LocalRailFog.current
    val padTop = MeridianTopRow.top(compact).dp
    val padBottom = MeridianTopRow.bottom(compact).dp
    Layout(
        content = dock,
        modifier = Modifier.fillMaxWidth().padding(top = padTop, bottom = padBottom),
    ) { measurables, constraints ->
        val rail = constraints.maxWidth.toDp().value
        val dens = density
        val arc = fog?.let { f ->
            val measured = f.arc.value
            if (measured.radius > 0f && measured.top > 0f) {
                measured
            } else {
                // Aún sin rueda medida: su curva, con el alto que le dejarán la cabecera y las pistas.
                // El alto de la cabecera no depende de la curva, así que ya se sabe.
                val headerPx = padTop.roundToPx() + DotTabsLayout.HEIGHT.dp.roundToPx() + padBottom.roundToPx()
                val totalPx = constraints.maxHeight + padTop.roundToPx() + padBottom.roundToPx()
                FogArc.forRail(f.windowWidth, (totalPx - headerPx - f.hintsPx) / dens, headerPx.toFloat(), f.tileAspect, f.compact, f.axisX)
            }
        }?.copy(reach = 0f) // La curva sin la banda que pide la propia cabecera (si no, el dock se iría ensanchando con ella).
        val edge: ((Float) -> Float)? = if (fog == null || arc == null) null else { y -> arc.edgeAt(padTop.value + y, fog.axisX, fog.glass, dens) }
        val plan = RailHeaderLayout.plan(rail, DotTabsLayout.HEIGHT, pills, edge)
        val dockP = measurables[0].measure(Constraints(maxWidth = ceil(plan.dockWidth * dens).toInt()))
        val height = plan.height.dp.roundToPx()
        layout(constraints.maxWidth, height) {
            // Centrado en la fila (el dock mide lo que la barra: la misma línea central).
            dockP.place(plan.dockX.dp.roundToPx(), plan.dockTop.dp.roundToPx() + (height - dockP.height) / 2)
            fog?.arc?.let { state -> if (state.value.reach != plan.reach) state.value = state.value.copy(reach = plan.reach) }
        }
    }
}

/**
 * La barra de la derecha: Masha, hora y batería, "Abrir", el orden, buscar y
 * Ajustes; compacta si no cabe (ver TopBarLayout: primero "Abrir" se queda en
 * su icono, luego la píldora; Masha y Ajustes nunca se van). Masha abre el
 * grupo: en la cabecera de la rueda, sola sobre el dock, se veía apretada y
 * le quitaba alto a la rueda. El orden va justo antes del buscador: los dos
 * sirven para encontrar un juego. [onMasha] da el emblema (el bocadillo va
 * debajo del grupo), [onSort] dónde anclar su menú y [onBounds] lo que ocupa
 * la barra entera.
 */
@Composable
private fun TopEnd(vm: ElyndraViewModel, m: Metrics, enabled: Boolean, onMasha: (Rect) -> Unit, onSort: (Rect) -> Unit, onBounds: (Rect) -> Unit, onOpen: () -> Unit) = BoxWithConstraints {
    val fit = rememberTopBarFit(vm, available = if (constraints.hasBoundedWidth) maxWidth.value else Float.POSITIVE_INFINITY, landscape = m.landscape, masha = true, sort = true)
    Row(Modifier.onGloballyPositioned { onBounds(it.boundsInWindow()) }, verticalAlignment = Alignment.CenterVertically) {
        MashaButton(vm, grouped = true, onBounds = onMasha)
        Spacer(Modifier.width(8.dp))
        if (vm.settings.statusVisible && !vm.searchOpen && fit.status) {
            StatusStrip(vm.settings.statusMode, showPercent = fit.statusPercent)
            Spacer(Modifier.width(8.dp))
        }
        if (!vm.searchOpen) {
            // "Abrir" hace lo mismo que la acción principal del hero (también en la fila de "Añadir").
            OpenButton(enabled = enabled, focused = vm.input.isBarFocused(BarItem.Open), showLabel = fit.openLabel, onClick = onOpen)
            Spacer(Modifier.width(8.dp))
        }
        SectionSortButton(vm, onBounds = onSort)
        Spacer(Modifier.width(8.dp))
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
        val dark = P.isDark
        ElyText(
            text,
            modifier = if (dark) Modifier else Modifier.lightGlass(RoundedCornerShape(LightGlass.CARD_RADIUS_DP.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            size = 13f,
            weight = FontWeight.SemiBold,
            color = if (dark) P.ink2 else Color(LightGlass.INK),
            align = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Las pistas del mando bajo la rueda: moverse y cambiar de sección (A, X e Y
 * ya están en el hero). En la fila de arriba (dock, Masha, Abrir, orden,
 * buscar, Ajustes: una sola fila, en el orden en que se ve, ver
 * LibraryFocus.meridianRows) ↑↓ no mueven la rueda: ←→ la recorren, A elige
 * y B vuelve a ella.
 */
private fun meridianHints(vm: ElyndraViewModel): List<PadHint> = when {
    vm.sortMenuOpen -> SORT_MENU_HINTS
    vm.input.isBarFocused(BarItem.Sections) -> DOCK_HINTS
    vm.input.isBarFocused(BarItem.Sort) -> SORT_HINTS
    vm.input.active && vm.input.barFocus != null -> MERIDIAN_BAR_HINTS
    else -> MERIDIAN_HINTS
}

private val MERIDIAN_BAR_HINTS = listOf(
    PadHint("A", R.string.hint_select),
    PadHint("←→", R.string.hint_move),
    PadHint("B", R.string.hint_back),
)

private val MERIDIAN_HINTS = listOf(
    PadHint("↑↓", R.string.hint_move),
    PadHint("LB / RB", R.string.hint_section),
)
