package com.elyndra.launcher.ui.screens.ports

import com.elyndra.launcher.ui.components.edgeBleed
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.domain.ports.GameGroup
import com.elyndra.launcher.domain.ports.PlatformGroup
import com.elyndra.launcher.domain.ports.PortDisplay
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.StoreFilter
import com.elyndra.launcher.domain.ports.StoreRow
import com.elyndra.launcher.domain.ports.StoreRowKind
import com.elyndra.launcher.domain.ports.StoreSort
import com.elyndra.launcher.ports.PortCatalogRepository
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.PortsController
import com.elyndra.launcher.ui.StoreLayout
import com.elyndra.launcher.ui.StoreTab
import com.elyndra.launcher.ui.TileStatus
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GlassTextField
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.padInitialFocus
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.Store

/**
 * Lo que la tienda necesita para pintarse, sin ViewModel (así la pintan
 * también las vistas previas).
 */
class StoreHome(
    val tab: StoreTab,
    /** +1: la pestaña nueva está a la derecha de la anterior (el contenido entra desde ahí). */
    val tabDirection: Int,
    /** Novedades: las filas de la portada (destacados, recién actualizados, familias…). */
    val rows: List<StoreRow>,
    val browse: List<GameGroup>,
    val updates: List<GameGroup>,
    val installed: List<GameGroup>,
    val collections: List<Pair<PlatformGroup, List<GameGroup>>>,
    val loading: Boolean,
    /** Sin conexión de verdad: el aviso de "sin conexión" con "Reintentar". */
    val offline: Boolean,
    val rateLimited: Boolean,
    /** Hay red, pero el repositorio no dio catálogo: una nota discreta, no un aviso. */
    val builtIn: Boolean = false,
    val filter: StoreFilter,
    val searchOpen: Boolean,
    /** Algo bajándose o instalándose: la baldosa de descargas lleva su punto. */
    val downloading: Boolean,
    /** Lo que dice cada baldosa abajo a la izquierda (ver [TileStatus]). */
    val status: (PortEntry) -> TileStatus,
    /** El arte grande del juego original (carteles), o null: la imagen del port. */
    val heroArt: (PortEntry) -> Any?,
    /** La carátula del juego original (baldosas), o null: la imagen del port. */
    val coverArt: (PortEntry) -> Any? = { null },
)

/** Lo que se puede hacer en la tienda. */
class StoreHomeActions(
    val open: (PortEntry) -> Unit = {},
    /** Una baldosa a la vista o con el foco: se piden sus releases. */
    val focus: (PortEntry) -> Unit = {},
    val selectTab: (StoreTab) -> Unit = {},
    val toggleSearch: () -> Unit = {},
    val query: (String) -> Unit = {},
    val openSort: () -> Unit = {},
    val openDownloads: () -> Unit = {},
    val openUtilities: () -> Unit = {},
    /** El corazón: la lista de deseos, en una hoja. */
    val openWishlist: () -> Unit = {},
    /** El botón de volver de la cabecera: a la biblioteca. */
    val exit: () -> Unit = {},
    val openCollection: (PlatformGroup) -> Unit = {},
    val retry: () -> Unit = {},
    val updateAll: (List<PortEntry>) -> Unit = {},
)

/** La tienda de ports: la portada con sus pestañas o la ficha de un port. */
@Composable
fun PortsScreen(vm: ElyndraViewModel) {
    val ports = vm.ports
    val detail = ports.detail
    val reduced = LocalReducedMotion.current
    Box(Modifier.fillMaxSize()) {
        // El fondo llega al filo de la pantalla (bajo el recorte de la cámara); el contenido, en el área segura.
        Box(Modifier.fillMaxSize().edgeBleed().storeBackground())
        AnimatedContent(
            targetState = detail?.id,
            transitionSpec = { fadeIn(tween(if (reduced) 0 else 200)) togetherWith fadeOut(tween(if (reduced) 0 else 160)) },
            label = "portsPage",
        ) { id ->
            val port = id?.let { ports.catalog.firstOrNull { p -> p.id == it } }
            if (port == null) {
                PortsHome(storeHome(vm), storeActions(vm), padHints = vm.input.gamepadPresent)
            } else {
                PortDetailScreen(vm, port)
            }
        }
    }
}

private fun storeHome(vm: ElyndraViewModel): StoreHome {
    val ports = vm.ports
    val now = System.currentTimeMillis()
    return StoreHome(
        tab = ports.tab,
        tabDirection = ports.tabDirection,
        rows = ports.rows(),
        browse = ports.browseGroups(),
        updates = ports.updateGroups(),
        installed = ports.installedGroups(),
        collections = ports.collections(),
        loading = ports.loading && ports.catalog.isEmpty(),
        offline = ports.source == PortCatalogRepository.Source.Bundled && ports.fallback == PortCatalogRepository.Fallback.Offline,
        builtIn = ports.source == PortCatalogRepository.Source.Bundled && ports.fallback != PortCatalogRepository.Fallback.Offline,
        rateLimited = ports.rateLimited,
        filter = ports.filter,
        searchOpen = ports.searchOpen,
        downloading = vm.portInstall.tasks.isNotEmpty(),
        status = { p -> tileStatus(vm, p, now) },
        heroArt = ports::heroArt,
        coverArt = ports::coverArt,
    )
}

/** La línea de estado de [port] con lo que ya se sabe (sus releases, si se leyeron; si no, lo del catálogo). */
internal fun tileStatus(vm: ElyndraViewModel, port: PortEntry, now: Long): TileStatus {
    val ports = vm.ports
    val latest = ports.latest(port)
    val installed = ports.installed[port.id]
    return TileStatus.of(
        action = ports.action(port),
        progress = vm.portInstall.progress(port.id),
        installedTag = installed?.tag,
        latestTag = latest?.release?.tag ?: installed?.latestTag ?: port.release.tag,
        sizeBytes = latest?.asset?.size ?: 0,
        publishedAt = latest?.release?.publishedAt ?: port.release.publishedAtMillis,
        now = now,
        addedAt = ports.addedAt(port.id),
    )
}

private fun storeActions(vm: ElyndraViewModel) = StoreHomeActions(
    open = { vm.ports.openDetail(it.id) },
    focus = { vm.ports.ensureReleases(it) },
    selectTab = vm.ports::selectTab,
    toggleSearch = vm.ports::toggleSearch,
    query = vm.ports::updateQuery,
    openSort = vm.ports::openSortSheet,
    openDownloads = vm.ports::openDownloads,
    openUtilities = vm.ports::openUtilities,
    openWishlist = vm.ports::openWishlist,
    exit = { vm.go(com.elyndra.launcher.ui.Screen.Library) },
    openCollection = vm.ports::openCollection,
    retry = { vm.ports.load(force = true) },
    updateAll = vm.portInstall::updateAll,
)

/** Las pistas del mando de la portada: Y utilidades, LB / RB pestañas. */
private val HOME_HINTS = listOf(
    PadHint("Y", R.string.store_utilities),
    PadHint("LB / RB", R.string.store_switch_tabs),
)

/**
 * La portada: cabecera (la baldosa de la tienda y su título, y la de
 * descargas), la fila de pestañas con los botones de icono y el contenido de
 * la pestaña, que cambia con un cruce y un pequeño deslizamiento.
 */
@Composable
fun PortsHome(state: StoreHome, actions: StoreHomeActions, modifier: Modifier = Modifier, padHints: Boolean = false) {
    val reduced = LocalReducedMotion.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        val margin = StoreLayout.margin(w, h).dp
        Column(Modifier.fillMaxSize()) {
            StoreHeader(state, actions, w, Modifier.statusBarsPadding().padding(horizontal = margin).padding(top = 14.dp))
            StoreTabRow(state, actions, w, Modifier.padding(horizontal = margin).padding(top = 10.dp, bottom = 6.dp))
            AnimatedContent(
                targetState = state.tab,
                transitionSpec = {
                    val slide = (StoreLayout.TAB_SLIDE * state.tabDirection).toInt()
                    if (reduced) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        (fadeIn(tween(StoreLayout.TAB_MS)) + slideInHorizontally(tween(StoreLayout.TAB_MS)) { slide * 3 }) togetherWith
                            (fadeOut(tween(StoreLayout.TAB_MS / 2)) + slideOutHorizontally(tween(StoreLayout.TAB_MS)) { -slide * 3 })
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = "storeTab",
            ) { tab ->
                when (tab) {
                    StoreTab.Latest -> LatestTab(state, actions, w, h)
                    StoreTab.Collections -> CollectionsTab(state, actions, w, h)
                    StoreTab.Updates -> GridTab(
                        state, actions, state.updates, w, h,
                        title = stringResource(R.string.store_tab_updates),
                        empty = stringResource(R.string.store_empty_updates),
                        updateAll = state.updates.takeIf { it.isNotEmpty() }?.map { it.lead },
                    )
                    StoreTab.Installed -> GridTab(
                        state, actions, state.installed, w, h,
                        title = stringResource(R.string.store_tab_installed),
                        empty = stringResource(R.string.store_empty_installed),
                    )
                    StoreTab.Browse -> GridTab(
                        state, actions, state.browse, w, h,
                        title = stringResource(R.string.store_all_ports),
                        empty = stringResource(R.string.ports_empty),
                        sortable = true,
                    )
                }
            }
        }
        PadHints(HOME_HINTS, padHints, Modifier.align(Alignment.BottomEnd).padding(16.dp), onDark = true)
    }
}

/* ── cabecera y pestañas ───────────────────────────────────────── */

@Composable
private fun StoreHeader(state: StoreHome, actions: StoreHomeActions, width: Float, modifier: Modifier) {
    val tile = StoreLayout.headerTile(width).dp
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Lo primero, a la izquierda: volver a la biblioteca (como en Masha).
        StoreBackButton(stringResource(R.string.store_back_to_library), actions.exit)
        Spacer(Modifier.width(12.dp))
        if (StoreLayout.headerTileVisible(width)) {
            StoreHeaderTile(tile) {
                Box(Modifier.size(tile * 0.42f).drawStoreGlyph())
            }
            Spacer(Modifier.width(16.dp))
        }
        ElyText(
            stringResource(R.string.ports_title),
            Modifier.weight(1f),
            size = StoreLayout.titleSize(width),
            weight = FontWeight.Light,
            color = Store.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // En estrecho, los botones de icono suben aquí (en la fila de pestañas no caben).
        if (!StoreLayout.iconsInTabRow(width)) {
            StoreIcons(state, actions, withDownloads = false)
            Spacer(Modifier.width(8.dp))
        }
        StoreHeaderTile(tile, onClick = actions.openDownloads, description = stringResource(R.string.ports_downloads)) {
            Box {
                StoreIconGlyph(StoreIcon.Download, Store.text, tile * 0.42f)
                if (state.downloading) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-6).dp)
                            .size(10.dp)
                            .background(com.elyndra.launcher.ui.theme.LocalSkin.current.a1, androidx.compose.foundation.shape.CircleShape),
                    )
                }
            }
        }
    }
}

/** La bolsa de la tienda, en blanco, para su baldosa. */
private fun Modifier.drawStoreGlyph(): Modifier = drawBehind { StoreGlyph(this, Store.text) }

/** Buscar, Lista de deseos, (Descargas) y Más: redondos, con el aro al recibir el foco. */
@Composable
private fun StoreIcons(state: StoreHome, actions: StoreHomeActions, withDownloads: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StoreIconButton(StoreIcon.Search, stringResource(R.string.ports_search), actions.toggleSearch, filled = state.searchOpen)
        StoreIconButton(StoreIcon.Heart, stringResource(R.string.store_wishlist), actions.openWishlist)
        if (withDownloads) {
            StoreIconButton(StoreIcon.Download, stringResource(R.string.ports_downloads), actions.openDownloads, badge = state.downloading)
        }
        StoreIconButton(StoreIcon.More, stringResource(R.string.store_utilities), actions.openUtilities)
    }
}

/**
 * La fila de pestañas: la activa en blanco y las demás apagadas. Con el
 * foco en la fila, la activa lleva una píldora gris con un canto claro; el
 * foco sobre una pestaña la abre (como en la tienda de una consola).
 */
@Composable
private fun StoreTabRow(state: StoreHome, actions: StoreHomeActions, width: Float, modifier: Modifier) {
    var rowFocused by remember { mutableStateOf(false) }
    val size = StoreLayout.tabTextSize(width)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .onFocusChanged { rowFocused = it.hasFocus },
            horizontalArrangement = Arrangement.spacedBy(StoreLayout.TAB_GAP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StoreTab.ROW.forEach { tab ->
                val active = tab == state.tab
                val shape = RoundedCornerShape(10.dp)
                ElyText(
                    tabLabel(tab),
                    Modifier
                        .then(if (active) Modifier.padInitialFocus() else Modifier)
                        .onFocusChanged { if (it.isFocused) actions.selectTab(tab) }
                        .height(StoreLayout.TAB_PILL_H.dp)
                        .clip(shape)
                        .then(
                            if (active && rowFocused) {
                                Modifier.background(Store.tileHigh, shape).border(1.dp, Store.pillBorder, shape)
                            } else {
                                Modifier
                            },
                        )
                        .storeClickable { actions.selectTab(tab) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    size = size,
                    weight = FontWeight.Light,
                    color = if (active) Store.text else Store.textDim,
                    maxLines = 1,
                )
            }
        }
        if (StoreLayout.iconsInTabRow(width)) {
            Spacer(Modifier.width(12.dp))
            StoreIcons(state, actions, withDownloads = true)
        }
    }
}

@Composable
private fun tabLabel(tab: StoreTab): String = stringResource(
    when (tab) {
        StoreTab.Latest -> R.string.store_tab_latest
        StoreTab.Collections -> R.string.store_tab_collections
        StoreTab.Updates -> R.string.store_tab_updates
        StoreTab.Installed -> R.string.store_tab_installed
        StoreTab.Browse -> R.string.store_tab_browse
    },
)

/* ── Novedades ─────────────────────────────────────────────────── */

/**
 * Como la página de ofertas de una consola: dos carteles grandes lado a lado
 * (lo nuevo y lo recién actualizado) y, debajo, carruseles con título.
 * Arriba y abajo van de fila en fila; izquierda y derecha, dentro de una fila.
 */
@Composable
private fun LatestTab(state: StoreHome, actions: StoreHomeActions, width: Float, height: Float) {
    val margin = StoreLayout.margin(width, height).dp
    val gap = StoreLayout.gap(width).dp
    val featured = state.rows.firstOrNull { it.kind == StoreRowKind.Featured }?.groups.orEmpty()
    val recent = state.rows.firstOrNull { it.kind == StoreRowKind.RecentlyUpdated }?.groups.orEmpty()
    val first = featured.firstOrNull()
    val second = recent.firstOrNull { it.key != first?.key } ?: featured.getOrNull(1)
    val shelves = state.rows.filter { it.kind != StoreRowKind.Featured && it.kind != StoreRowKind.Results }
    val card = StoreLayout.cardWidth(width, height).dp
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 10.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "notes") { StoreNotes(state, actions, Modifier.padding(horizontal = margin)) }
        if (first != null) {
            item(key = "heroes") {
                val heroW = StoreLayout.heroWidth(width, height).dp
                val heroH = StoreLayout.heroHeight(width, height).dp
                val banners = listOfNotNull(
                    first to stringResource(R.string.store_callout_new),
                    second?.let { it to stringResource(R.string.store_callout_updated) },
                )
                Column(Modifier.padding(horizontal = margin)) {
                    StoreSectionTitle(stringResource(R.string.store_top_picks), Modifier.padding(bottom = 10.dp))
                    if (StoreLayout.heroColumns(width, height) == 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            banners.forEach { (g, callout) -> Banner(g, callout, state, actions, Modifier.width(heroW).height(heroH)) }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            banners.forEach { (g, callout) -> Banner(g, callout, state, actions, Modifier.fillMaxWidth().height(heroH)) }
                        }
                    }
                }
            }
        }
        if (state.loading) {
            item(key = "loading") { Loading(Modifier.padding(horizontal = margin)) }
        } else if (shelves.isEmpty() && first == null) {
            item(key = "empty") { EmptyNote(stringResource(R.string.ports_empty), Modifier.padding(horizontal = margin)) }
        }
        items(shelves, key = { "row_" + it.kind.name }) { row -> Shelf(row, state, actions, card, margin, gap) }
        item(key = "never") {
            ElyText(stringResource(R.string.ports_never_roms), Modifier.padding(horizontal = margin), size = 10f, color = Store.textFaint)
        }
    }
}

@Composable
private fun Banner(group: GameGroup, callout: String, state: StoreHome, actions: StoreHomeActions, modifier: Modifier) {
    val port = group.lead
    LaunchedEffect(port.id) { actions.focus(port) }
    StoreHeroBanner(
        callout = callout,
        title = PortDisplay.title(port),
        art = state.heroArt(port) ?: state.coverArt(port) ?: port.image,
        onClick = { actions.open(port) },
        modifier = modifier,
    )
}

/** Un carrusel con título: tarjetas apaisadas, una por juego. */
@Composable
private fun Shelf(row: StoreRow, state: StoreHome, actions: StoreHomeActions, card: Dp, margin: Dp, gap: Dp) {
    Column {
        Row(Modifier.padding(horizontal = margin).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            StoreSectionTitle(rowTitle(row), Modifier.weight(1f, fill = false))
            if (row.kind == StoreRowKind.UpdatesAvailable) {
                Spacer(Modifier.width(12.dp))
                SmallPill(stringResource(R.string.ports_update_all)) { actions.updateAll(row.ports) }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = margin), horizontalArrangement = Arrangement.spacedBy(gap)) {
            items(row.groups, key = { row.kind.name + it.key }) { group ->
                val port = group.lead
                // Componerse es estar a la vista (o casi): es cuando se piden sus releases.
                LaunchedEffect(port.id) { actions.focus(port) }
                StoreCard(
                    port = port,
                    status = state.status(port),
                    art = state.heroArt(port) ?: state.coverArt(port),
                    width = card,
                    onClick = { actions.open(port) },
                    onFocus = { actions.focus(port) },
                )
            }
        }
    }
}

@Composable
private fun rowTitle(row: StoreRow): String = when (row.kind) {
    StoreRowKind.BecauseYouHave -> stringResource(R.string.ports_row_because, row.subject.orEmpty())
    StoreRowKind.Nintendo -> stringResource(R.string.ports_row_nintendo)
    StoreRowKind.PlayStation -> stringResource(R.string.ports_row_playstation)
    StoreRowKind.Sega -> stringResource(R.string.ports_row_sega)
    StoreRowKind.Xbox -> stringResource(R.string.ports_row_xbox)
    StoreRowKind.Pc -> stringResource(R.string.ports_row_pc)
    StoreRowKind.UpdatesAvailable -> stringResource(R.string.ports_row_updates)
    StoreRowKind.RecentlyUpdated -> stringResource(R.string.ports_row_recent)
    StoreRowKind.Installed -> stringResource(R.string.ports_row_installed)
    StoreRowKind.ComingToAndroid -> stringResource(R.string.ports_row_coming)
    StoreRowKind.Results -> stringResource(R.string.ports_row_results)
    StoreRowKind.Featured -> stringResource(R.string.ports_row_featured)
}

/* ── Colecciones ───────────────────────────────────────────────── */

/** Una tarjeta por familia de consolas (Nintendo, Xbox…): abre Explorar con ese filtro. */
@Composable
private fun CollectionsTab(state: StoreHome, actions: StoreHomeActions, width: Float, height: Float) {
    val margin = StoreLayout.margin(width, height).dp
    val gap = StoreLayout.gap(width).dp
    val cols = StoreLayout.collectionColumns(width, height)
    val cardW = StoreLayout.collectionWidth(width, height).dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(cols),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = margin, end = margin, top = 10.dp, bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        item(key = "title", span = fullSpan) { StoreSectionTitle(stringResource(R.string.store_tab_collections)) }
        if (state.collections.isEmpty()) {
            item(key = "empty", span = fullSpan) { if (state.loading) Loading(Modifier) else EmptyNote(stringResource(R.string.ports_empty), Modifier) }
        }
        items(state.collections.size, key = { "col_" + state.collections[it].first.name }) { i ->
            val (group, games) = state.collections[i]
            StoreCollectionCard(
                name = stringResource(PortsController.groupLabel(group)),
                count = pluralStringResource(R.plurals.ports_results_count, games.size, games.size),
                arts = games.take(3).map { state.coverArt(it.lead) ?: it.lead.image },
                width = cardW,
                onClick = { actions.openCollection(group) },
            )
        }
    }
}

/* ── rejillas: Explorar, Actualizaciones, Instalados, deseos ───── */

private val fullSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/**
 * Una rejilla de baldosas cuadradas con su cabecera de sección ("Todos los
 * ports" a la izquierda, "Ordenar por: … | Filtrar por: …" a la derecha), el
 * botón redondo de orden en el margen izquierdo a la altura de la primera
 * fila y un indicador fino de desplazamiento en el borde derecho. El foco
 * lleva la rejilla consigo: la fila con el foco siempre queda a la vista.
 */
@Composable
private fun GridTab(
    state: StoreHome,
    actions: StoreHomeActions,
    groups: List<GameGroup>,
    width: Float,
    height: Float,
    title: String,
    empty: String,
    sortable: Boolean = false,
    updateAll: List<PortEntry>? = null,
) {
    val margin = StoreLayout.margin(width, height)
    val cols = StoreLayout.columns(width, height)
    val side = StoreLayout.tileSide(width, height, cols)
    val gap = StoreLayout.gap(width)
    val grid = rememberLazyGridState()
    val sortInMargin = sortable && StoreLayout.sortButtonInMargin(width, height)
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = margin.dp, end = margin.dp, top = 6.dp, bottom = 88.dp),
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalArrangement = Arrangement.spacedBy(gap.dp),
        ) {
            item(key = "head", span = fullSpan) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StoreNotes(state, actions, Modifier)
                    if (sortable && state.searchOpen) {
                        GlassTextField(
                            value = state.filter.query,
                            onValueChange = actions.query,
                            placeholder = stringResource(R.string.ports_search_hint),
                            modifier = Modifier.fillMaxWidth(),
                            textSize = 13f,
                        )
                    }
                    SectionHeader(title, state.filter, sortable, showButton = sortable && !sortInMargin, updateAll, actions)
                }
            }
            if (groups.isEmpty()) {
                item(key = "empty", span = fullSpan) { if (state.loading) Loading(Modifier) else EmptyNote(empty, Modifier) }
            }
            items(groups.size, key = { "t_" + groups[it].key }) { i ->
                val port = groups[i].lead
                LaunchedEffect(port.id) { actions.focus(port) }
                StoreTile(
                    port = port,
                    status = state.status(port),
                    art = state.coverArt(port),
                    side = side.dp,
                    onClick = { actions.open(port) },
                    onFocus = { actions.focus(port) },
                )
            }
        }
        if (sortInMargin) {
            // En el margen izquierdo, a la altura del centro de la primera fila (y se va con ella al bajar).
            val gone = grid.firstVisibleItemIndex > 1
            if (!gone) {
                StoreIconButton(
                    StoreIcon.Sort,
                    stringResource(R.string.store_sort_filter),
                    actions.openSort,
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = ((margin - StoreLayout.SORT_BUTTON) / 2f).dp,
                            y = (HEADER_ROW_H + side / 2f - StoreLayout.SORT_BUTTON / 2f).dp,
                        ),
                    size = StoreLayout.SORT_BUTTON.dp,
                    filled = true,
                )
            }
        }
        ScrollIndicator(grid, cols, groups.size, Modifier.align(Alignment.TopEnd).padding(end = (margin / 2f).coerceAtLeast(4f).dp, top = 6.dp, bottom = 88.dp))
    }
}

/** El alto aproximado de la cabecera de sección (para alinear el botón de orden con la primera fila). */
private const val HEADER_ROW_H = 48f

/** "Todos los ports" a la izquierda; a la derecha, el orden y el filtro de verdad (y "Actualizar todo"). */
@Composable
private fun SectionHeader(
    title: String,
    filter: StoreFilter,
    sortable: Boolean,
    showButton: Boolean,
    updateAll: List<PortEntry>?,
    actions: StoreHomeActions,
) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        StoreSectionTitle(title, Modifier.weight(1f))
        if (sortable) {
            val sort = stringResource(R.string.store_sort_by, stringResource(PortsController.sortLabel(filter.sort)))
            val label = filterLabel(filter)
            ElyText(
                if (label != null) sort + "  |  " + stringResource(R.string.store_filter_by, label) else sort,
                size = 13f,
                weight = FontWeight.Light,
                color = Store.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showButton) {
            Spacer(Modifier.width(10.dp))
            StoreIconButton(StoreIcon.Sort, stringResource(R.string.store_sort_filter), actions.openSort, size = 36.dp, filled = true)
        }
        if (updateAll != null) {
            Spacer(Modifier.width(10.dp))
            SmallPill(stringResource(R.string.ports_update_all)) { actions.updateAll(updateAll) }
        }
    }
}

/** El filtro puesto, en palabras ("Nintendo", "Nintendo Switch", "zelda"), o null sin filtro. */
@Composable
private fun filterLabel(f: StoreFilter): String? {
    val parts = buildList {
        f.platform?.let { add(Systems.byId(it)?.name ?: it) }
        f.groups.forEach { add(stringResource(PortsController.groupLabel(it))) }
        f.genres.forEach { add(stringResource(PortsController.genreLabel(it))) }
        if (f.notOnAndroid) add(stringResource(R.string.ports_chip_not_on_android))
        if (f.query.isNotBlank()) add("“" + f.query.trim() + "”")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** El indicador fino del borde derecho: cuánto de la rejilla se ve y por dónde va. */
@Composable
private fun ScrollIndicator(grid: LazyGridState, cols: Int, count: Int, modifier: Modifier) {
    val totalRows = (count + cols - 1) / cols
    BoxWithConstraints(modifier.width(3.dp).fillMaxSize()) {
        val track = maxHeight.value
        val info = grid.layoutInfo
        val visibleRows = (info.visibleItemsInfo.size / cols.coerceAtLeast(1)).coerceAtLeast(1)
        if (totalRows <= visibleRows) return@BoxWithConstraints
        val firstRow = ((grid.firstVisibleItemIndex - 1).coerceAtLeast(0)) / cols
        val (top, length) = StoreLayout.scrollThumb(track, firstRow, visibleRows, totalRows)
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(2.dp)))
        Box(
            Modifier
                .offset(y = top.dp)
                .width(3.dp)
                .height(length.dp)
                .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
        )
    }
}

/* ── avisos y piezas pequeñas ──────────────────────────────────── */

/** Sin conexión (con "Reintentar"), el límite de GitHub, o la nota del catálogo incluido. */
@Composable
private fun StoreNotes(state: StoreHome, actions: StoreHomeActions, modifier: Modifier) {
    when {
        state.offline || state.rateLimited -> Row(
            modifier
                .fillMaxWidth()
                .storePanel()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ElyText(
                stringResource(if (state.rateLimited) R.string.ports_rate_limited else R.string.ports_offline_catalog),
                Modifier.weight(1f),
                size = 11.5f,
                color = Store.warn,
            )
            if (state.offline) {
                Spacer(Modifier.width(8.dp))
                SmallPill(stringResource(R.string.ports_retry), actions.retry)
            }
        }
        state.builtIn -> ElyText(stringResource(R.string.ports_builtin_catalog), modifier, size = 10.5f, color = Store.textFaint)
    }
}

@Composable
private fun Loading(modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ArcSpinner(size = 18.dp, color = Store.text)
        Spacer(Modifier.width(10.dp))
        ElyText(stringResource(R.string.ports_loading), size = 12f, color = Store.textDim)
    }
}

@Composable
private fun EmptyNote(text: String, modifier: Modifier) {
    ElyText(text, modifier.padding(vertical = 24.dp), size = 14f, weight = FontWeight.Light, color = Store.textDim)
}

/** Una píldora de texto pequeña (Reintentar, Actualizar todo), con el aro de foco. */
@Composable
internal fun SmallPill(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    ElyText(
        text,
        Modifier
            .storeFocusRing(focused, 18.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Store.tileHigh else Store.tile)
            .storeClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        size = 12f,
        weight = FontWeight.Medium,
        color = Store.text,
        maxLines = 1,
    )
}
