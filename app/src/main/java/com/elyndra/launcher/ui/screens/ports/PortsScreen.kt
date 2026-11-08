package com.elyndra.launcher.ui.screens.ports

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.domain.ports.PortAction
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.StoreFilter
import com.elyndra.launcher.domain.ports.StoreRow
import com.elyndra.launcher.domain.ports.StoreRowKind
import com.elyndra.launcher.ports.PortCatalogRepository
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.PortsLayout
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.GlassTextField
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.padInitialFocus
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.MinTouch
import com.elyndra.launcher.ui.theme.Store
import com.elyndra.launcher.ui.theme.shapeClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lo que la portada de la tienda necesita para pintarse, sin ViewModel (así
 * la pintan también las vistas previas).
 */
class StoreHome(
    val rows: List<StoreRow>,
    val loading: Boolean,
    val offline: Boolean,
    val rateLimited: Boolean,
    val filter: StoreFilter,
    val searchOpen: Boolean,
    val platforms: List<String>,
    val tags: List<String>,
    val action: (PortEntry) -> PortAction,
    val progress: (PortEntry) -> Float?,
    val owned: (PortEntry) -> Boolean,
    /** El arte grande del juego original para el destacado (null: la imagen del port). */
    val heroArt: (PortEntry) -> Any?,
)

/** Lo que se puede hacer en la portada. */
class StoreHomeActions(
    val open: (PortEntry) -> Unit,
    val focus: (PortEntry) -> Unit,
    val back: () -> Unit,
    val toggleSearch: () -> Unit,
    val query: (String) -> Unit,
    val togglePlatform: (String) -> Unit,
    val toggleTag: (String) -> Unit,
    val togglePcOnly: () -> Unit,
    val retry: () -> Unit,
)

/** La tienda de ports: portada o ficha de un port. */
@Composable
fun PortsScreen(vm: ElyndraViewModel) {
    val ports = vm.ports
    val detail = ports.detail
    Box(Modifier.fillMaxSize().storeBackground()) {
        val reduced = LocalReducedMotion.current
        AnimatedContent(
            targetState = detail?.id,
            transitionSpec = { if (reduced) fadeIn(androidx.compose.animation.core.snap()) togetherWith fadeOut(androidx.compose.animation.core.snap()) else fadeIn() togetherWith fadeOut() },
            label = "portsPage",
        ) { id ->
            val port = id?.let { ports.catalog.firstOrNull { p -> p.id == it } }
            if (port == null) {
                PortsHome(storeHome(vm), storeActions(vm), padHints = vm.input.gamepadPresent)
            } else {
                PortDetailScreen(vm, port)
            }
        }
        PortDownloadsBar(vm, Modifier.align(Alignment.BottomCenter))
    }
}

private fun storeHome(vm: ElyndraViewModel): StoreHome {
    val ports = vm.ports
    val owned = ports.owned().map { it.port.id }.toSet()
    return StoreHome(
        rows = ports.rows(),
        loading = ports.loading && ports.catalog.isEmpty(),
        offline = ports.source == PortCatalogRepository.Source.Bundled,
        rateLimited = ports.rateLimited,
        filter = ports.filter,
        searchOpen = ports.searchOpen,
        platforms = com.elyndra.launcher.domain.ports.StoreRows.platforms(ports.catalog),
        tags = com.elyndra.launcher.domain.ports.StoreRows.tags(ports.catalog).take(10),
        action = ports::action,
        progress = { vm.portInstall.progress(it.id) },
        owned = { it.id in owned },
        heroArt = ports::heroArt,
    )
}

private fun storeActions(vm: ElyndraViewModel) = StoreHomeActions(
    open = { vm.ports.openDetail(it.id) },
    focus = { vm.ports.ensureReleases(it) },
    back = vm::back,
    toggleSearch = vm.ports::toggleSearch,
    query = vm.ports::updateQuery,
    togglePlatform = vm.ports::togglePlatform,
    toggleTag = vm.ports::toggleTag,
    togglePcOnly = vm.ports::togglePcOnly,
    retry = { vm.ports.load(force = true) },
)

private val HOME_HINTS = listOf(
    PadHint("A", R.string.hint_select),
    PadHint("LB / RB", R.string.hint_section),
    PadHint("B", R.string.hint_back),
)

/** La portada: cabecera, filtros, destacado y filas de cápsulas. */
@Composable
fun PortsHome(state: StoreHome, actions: StoreHomeActions, modifier: Modifier = Modifier, padHints: Boolean = false) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        val pad = PortsLayout.pad(w).dp
        val capsule = PortsLayout.capsuleWidth(w).dp
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") { StoreHeader(state, actions, Modifier.statusBarsPadding().padding(horizontal = pad).padding(top = 10.dp)) }
            item(key = "filters") { StoreFilters(state, actions, pad) }
            if (state.offline || state.rateLimited) {
                item(key = "banner") { StoreBanner(state, actions, Modifier.padding(horizontal = pad)) }
            }
            if (state.loading) {
                item(key = "loading") {
                    Row(Modifier.padding(horizontal = pad), verticalAlignment = Alignment.CenterVertically) {
                        ArcSpinner(size = 18.dp, color = Store.link)
                        Spacer(Modifier.width(10.dp))
                        ElyText(stringResource(R.string.ports_loading), size = 12f, color = Store.textDim)
                    }
                }
            }
            val featured = state.rows.firstOrNull { it.kind == StoreRowKind.Featured }
            if (featured != null) {
                item(key = "hero") {
                    StoreHero(featured.ports, state, actions, PortsLayout.heroHeight(w, h), w, Modifier.padding(horizontal = pad))
                }
            }
            val rest = state.rows.filter { it.kind != StoreRowKind.Featured }
            if (!state.loading && rest.isEmpty() && featured == null) {
                item(key = "empty") { ElyText(stringResource(R.string.ports_empty), Modifier.padding(horizontal = pad), size = 12.5f, color = Store.textDim) }
            }
            items(rest, key = { "row_" + it.kind.name }) { row ->
                StoreShelf(row, state, actions, capsule, pad)
            }
            item(key = "never") {
                ElyText(stringResource(R.string.ports_never_roms), Modifier.padding(horizontal = pad), size = 10f, color = Store.textFaint)
            }
        }
        PadHints(HOME_HINTS, padHints, Modifier.align(Alignment.BottomStart).padding(pad), onDark = true)
    }
}

@Composable
private fun StoreHeader(state: StoreHome, actions: StoreHomeActions, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(onClick = actions.back, size = MinTouch, cornerRadius = 15.dp, contentDescription = stringResource(R.string.hint_back)) {
                BackChevron(color = Store.text)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                ElyText(stringResource(R.string.ports_title), size = 20f, weight = FontWeight.Bold, color = Store.text)
                ElyText(stringResource(R.string.ports_subtitle), size = 10.5f, color = Store.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(onClick = actions.toggleSearch, size = MinTouch, cornerRadius = 15.dp, contentDescription = stringResource(R.string.ports_search)) {
                SearchGlyph(Store.text)
            }
        }
        if (state.searchOpen) {
            Spacer(Modifier.height(10.dp))
            GlassTextField(
                value = state.filter.query,
                onValueChange = actions.query,
                placeholder = stringResource(R.string.ports_search_hint),
                modifier = Modifier.fillMaxWidth(),
                textSize = 12f,
            )
        }
    }
}

/** Una lupa de trazo, del color de la tienda. */
@Composable
private fun SearchGlyph(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val r = size.minDimension * 0.34f
        drawCircle(color, radius = r, center = androidx.compose.ui.geometry.Offset(r + 1f, r + 1f), style = androidx.compose.ui.graphics.drawscope.Stroke(size.minDimension * 0.12f))
        drawLine(
            color,
            androidx.compose.ui.geometry.Offset(r * 1.75f + 1f, r * 1.75f + 1f),
            androidx.compose.ui.geometry.Offset(size.width - 1f, size.height - 1f),
            strokeWidth = size.minDimension * 0.14f,
        )
    }
}

@Composable
private fun StoreFilters(state: StoreHome, actions: StoreHomeActions, pad: androidx.compose.ui.unit.Dp) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = pad),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(state.platforms, key = { "p_$it" }) { p ->
            StoreChip(platformLabel(p), selected = p in state.filter.platforms, onClick = { actions.togglePlatform(p) })
        }
        item(key = "pc") {
            StoreChip(stringResource(R.string.ports_filter_pc_only), selected = state.filter.showPcOnly, onClick = actions.togglePcOnly)
        }
        items(state.tags, key = { "t_$it" }) { t ->
            StoreChip(t, selected = t in state.filter.tags, onClick = { actions.toggleTag(t) })
        }
    }
}

@Composable
private fun StoreBanner(state: StoreHome, actions: StoreHomeActions, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .storePanel()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ElyText(
            stringResource(if (state.rateLimited) R.string.ports_rate_limited else R.string.ports_offline_catalog),
            Modifier.weight(1f),
            size = 11f,
            color = Store.warn,
        )
        if (state.offline) {
            Spacer(Modifier.width(8.dp))
            StoreChip(stringResource(R.string.ports_retry), onClick = actions.retry)
        }
    }
}

/** Una fila de cápsulas con su título. */
@Composable
private fun StoreShelf(row: StoreRow, state: StoreHome, actions: StoreHomeActions, capsule: androidx.compose.ui.unit.Dp, pad: androidx.compose.ui.unit.Dp) {
    val title = when (row.kind) {
        StoreRowKind.BecauseYouHave -> stringResource(R.string.ports_row_because, row.subject.orEmpty())
        StoreRowKind.Nintendo -> stringResource(R.string.ports_row_nintendo)
        StoreRowKind.Xbox -> stringResource(R.string.ports_row_xbox)
        StoreRowKind.RecentlyUpdated -> stringResource(R.string.ports_row_recent)
        StoreRowKind.Installed -> stringResource(R.string.ports_row_installed)
        StoreRowKind.Results -> stringResource(R.string.ports_row_results)
        StoreRowKind.Featured -> stringResource(R.string.ports_row_featured)
    }
    Column {
        StoreRowTitle(title, Modifier.padding(horizontal = pad).padding(bottom = 8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = pad),
            horizontalArrangement = Arrangement.spacedBy(PortsLayout.ROW_GAP.dp),
        ) {
            items(row.ports, key = { row.kind.name + it.id }) { port ->
                // Componerse es estar a la vista (o casi): es cuando se piden sus releases.
                LaunchedEffect(port.id) { actions.focus(port) }
                PortCapsule(
                    port = port,
                    action = state.action(port),
                    width = capsule,
                    onClick = { actions.open(port) },
                    progress = state.progress(port),
                    owned = state.owned(port),
                    onFocus = { actions.focus(port) },
                )
            }
        }
    }
}

/**
 * El carrusel de destacados: el arte del juego original a sangre, la imagen
 * del port encima y, al lado (o debajo en estrecho), nombre, descripción,
 * etiquetas y el área de acción. Pasa solo cada pocos segundos mientras no
 * tiene el foco; con el mando, izquierda/derecha cambian de página.
 */
@Composable
private fun StoreHero(ports: List<PortEntry>, state: StoreHome, actions: StoreHomeActions, height: Float, width: Float, modifier: Modifier) {
    if (ports.isEmpty()) return
    val pager = rememberPagerState(pageCount = { ports.size })
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(pager, focused, reduced, ports.size) {
        if (reduced || ports.size < 2) return@LaunchedEffect
        while (!focused) {
            delay(6_500)
            if (!focused) pager.animateScrollToPage((pager.currentPage + 1) % ports.size)
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Column(modifier) {
        StoreRowTitle(stringResource(R.string.ports_row_featured), Modifier.padding(bottom = 8.dp))
        HorizontalPager(
            state = pager,
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp)
                .padInitialFocus()
                .onFocusChanged { focused = it.hasFocus || it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val delta = when (e.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        else -> return@onPreviewKeyEvent false
                    }
                    val next = pager.currentPage + delta
                    if (next !in ports.indices) return@onPreviewKeyEvent false
                    scope.launch { pager.animateScrollToPage(next) }
                    true
                },
        ) { page ->
            val port = ports[page]
            LaunchedEffect(port.id) { actions.focus(port) }
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .border(1.dp, if (focused && pager.currentPage == page) skin.a1 else Store.line, shape)
                    .shapeClickable(shape, color = skin.a1) { actions.open(port) },
            ) {
                HeroPage(port, state, width)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ports.indices.forEach { i ->
                Box(
                    Modifier
                        .size(width = if (i == pager.currentPage) 18.dp else 8.dp, height = 6.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) Store.text else Store.textFaint),
                )
            }
        }
    }
}

@Composable
private fun HeroPage(port: PortEntry, state: StoreHome, width: Float) {
    val side = PortsLayout.heroSideInfo(width)
    Box(Modifier.fillMaxSize()) {
        // El juego original a sangre, apagado; si no hay arte, la propia imagen del port.
        StoreImage(state.heroArt(port) ?: port.image, port.original.title, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (side) {
                        Brush.horizontalGradient(listOf(Color.Transparent, Store.bgBottom.copy(alpha = 0.92f)), startX = 0f)
                    } else {
                        Brush.verticalGradient(listOf(Color.Transparent, Store.bgBottom.copy(alpha = 0.95f)))
                    },
                ),
        )
        if (side) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(14.dp), contentAlignment = Alignment.CenterStart) {
                    StoreImage(
                        port.image,
                        port.name,
                        Modifier
                            .fillMaxHeight(0.82f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, Store.line, RoundedCornerShape(4.dp)),
                    )
                }
                HeroInfo(port, state, Modifier.width(PortsLayout.heroInfoWidth(width).dp).fillMaxHeight().padding(14.dp))
            }
        } else {
            HeroInfo(port, state, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp))
        }
    }
}

@Composable
private fun HeroInfo(port: PortEntry, state: StoreHome, modifier: Modifier) {
    val lang = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        ElyText(port.name, size = 17f, weight = FontWeight.Bold, color = Store.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        ElyText(platformLabel(port.original.platform) + " · " + port.original.title, size = 10.5f, color = Store.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        ElyText(port.shortText(lang), size = 11f, color = Store.text.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            port.tags.take(3).forEach { StoreChip(it) }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.owned(port)) {
                ElyText(stringResource(R.string.ports_in_library), size = 10.5f, weight = FontWeight.SemiBold, color = Store.ok)
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            PortActionPill(state.action(port), progress = state.progress(port))
        }
    }
}
