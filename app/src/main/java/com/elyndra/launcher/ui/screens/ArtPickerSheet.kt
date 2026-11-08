package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.metadata.ArtCandidate
import com.elyndra.launcher.ui.ArtPickerLayout
import com.elyndra.launcher.ui.ArtPickerState
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.GridNav
import com.elyndra.launcher.ui.UiTags
import com.elyndra.launcher.ui.components.AccentButton
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.ArtVariant
import com.elyndra.launcher.ui.components.Backdrop
import com.elyndra.launcher.ui.components.ConsoleGlyph
import com.elyndra.launcher.ui.components.ConsoleGlyphIcon
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.FallbackArt
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.PadFocusGroup
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.ScrimLayer
import com.elyndra.launcher.ui.components.consumeClicks
import com.elyndra.launcher.ui.components.overlayEmerge
import com.elyndra.launcher.ui.label
import com.elyndra.launcher.ui.selection.SelectionLook
import com.elyndra.launcher.ui.selection.rememberSelectionLook
import com.elyndra.launcher.ui.selection.selectionFrame
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.consoleFocus
import com.elyndra.launcher.ui.theme.drawArcSpinner
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.selectionScale
import com.elyndra.launcher.ui.theme.shapeClickable
import com.elyndra.launcher.ui.theme.spinAngle
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Hoja con las imágenes que ofrece un servicio para la carátula, el fondo, el
 * logo o el icono: una sola hoja para las cuatro clases (ver [ArtPickerLayout]).
 *
 * Con el mando no se usa el foco de Compose: lo lleva el [com.elyndra.launcher.ui.InputController]
 * (`artFocus`), igual que el menú de acciones, para que las columnas, los
 * cantos y la fila incompleta se comporten siempre igual ([GridNav]) y la
 * imagen señalada luzca el mismo marco que las cards de la biblioteca.
 */
@Composable
fun ArtPickerSheet(vm: ElyndraViewModel, state: ArtPickerState, open: Boolean, progress: State<Float>) {
    val reduced = LocalReducedMotion.current
    val input = vm.input
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    // Mientras llegan las miniaturas, cada celda enseña el color del juego.
    val fallback = remember(state.key) { vm.fallbackForKey(state.key, state.title).colorOnly() }
    val look = rememberSelectionLook(vm.settings.selectionParticleColor, vm.settings.selectionGlow, vm.settings.selectionParticles)
    // Al irse la hoja, el mando no se queda con las columnas de una rejilla que ya no está.
    DisposableEffect(input) { onDispose { input.bindArtGrid(1, 1) } }

    ScrimLayer(onDismiss = vm::closeArtPicker, alignment = Alignment.BottomCenter, open = open, progress = progress, z = Backdrop.Z_ART_PICKER) {
        // La frontera de la capa para el foco de Compose (Tab de un teclado):
        // no sale de la hoja. No coloca foco propio: el mando va por artFocus.
        PadFocusGroup(modal = true, padFocus = false) {
            ArtPickerPanel(
                state = state,
                fallback = fallback,
                look = look,
                // El marco del mando solo mientras se usa el mando: con el dedo sobra.
                focus = if (input.active) input.artFocus else -1,
                hintsVisible = input.gamepadPresent,
                maxHeight = maxHeight,
                holding = { input.repeats > 0 },
                onGrid = input::bindArtGrid,
                onPick = vm::applyArt,
                onRetry = vm::retryArtSearch,
                onClose = vm::closeArtPicker,
                modifier = Modifier.overlayEmerge(progress, reduced).testTag(UiTags.ART_PICKER),
            )
        }
    }
}

/**
 * El contenido de la hoja, sin ViewModel (lo usan las vistas previas).
 *
 * [focus]: lo señalado por el mando —índice de imagen, o de botón sin
 * rejilla—; −1 nada. [holding]: la cruceta está mantenida (la rejilla salta
 * en vez de animar, para no ir por detrás de la repetición). [onGrid] recibe
 * las columnas que se pintan y las filas que caben a la vista.
 */
@Composable
internal fun ArtPickerPanel(
    state: ArtPickerState,
    fallback: ArtFallback,
    look: SelectionLook,
    focus: Int,
    hintsVisible: Boolean,
    maxHeight: Dp,
    holding: () -> Boolean,
    onGrid: (columns: Int, pageRows: Int) -> Unit,
    onPick: (ArtCandidate) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = state.candidates.isNotEmpty()
    Column(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .padding(10.dp)
            .then(modifier)
            .glass(RoundedCornerShape(24.dp), solid = true)
            .consumeClicks()
            .padding(top = 16.dp, bottom = 12.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            ElyText(stringResource(state.kind.label()), size = 14.5f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            ElyText(
                serviceName(state.service) + " · " + state.title,
                size = 9.5f,
                color = P.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (grid) {
            CandidateGrid(state, fallback, look, focus, holding, onGrid, onPick)
        } else {
            Message(
                text = stringResource(
                    when {
                        state.loading -> R.string.art_searching
                        state.failed -> R.string.art_search_failed
                        else -> R.string.art_none_found
                    },
                ),
                spinner = state.loading,
                buttons = ArtPickerLayout.buttons(state),
                focus = focus,
                onRetry = onRetry,
                onClose = onClose,
            )
        }
        PadHints(
            hints = if (grid) GRID_HINTS else MESSAGE_HINTS,
            visible = hintsVisible,
            modifier = Modifier.padding(start = 18.dp, top = 8.dp),
            wrap = true,
        )
    }
}

@Composable
private fun ColumnScope.CandidateGrid(
    state: ArtPickerState,
    fallback: ArtFallback,
    look: SelectionLook,
    focus: Int,
    holding: () -> Boolean,
    onGrid: (columns: Int, pageRows: Int) -> Unit,
    onPick: (ArtCandidate) -> Unit,
) {
    val cell = ArtPickerLayout.cell(state.kind)
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val grid = rememberLazyGridState()
    val applied = ArtPickerLayout.appliedIndex(state)
    val publish = rememberUpdatedState(onGrid)
    // weight sin fill: la rejilla crece con su contenido pero deja sitio a las pistas de abajo.
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        // Las columnas se cuentan como lo haría GridCells.Adaptive y se pintan
        // fijas: así el mando se mueve con las que se ven en este ancho.
        val columns = with(density) {
            GridNav.columns(
                availablePx = (maxWidth - (ArtPickerLayout.SIDE_PADDING_DP * 2).dp).roundToPx(),
                minCellPx = cell.minWidthDp.dp.roundToPx(),
                spacingPx = ArtPickerLayout.H_SPACING_DP.dp.roundToPx(),
            )
        }
        val rowGapPx = with(density) { ArtPickerLayout.V_SPACING_DP.dp.roundToPx() }
        LaunchedEffect(grid, columns) {
            snapshotFlow {
                val info = grid.layoutInfo
                val viewport = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
                GridNav.pageRows(viewport, info.visibleItemsInfo.firstOrNull()?.size?.height ?: 0, rowGapPx)
            }.distinctUntilChanged().collect { rows -> publish.value(columns, rows) }
        }
        val marginPx = with(density) { ArtPickerLayout.REVEAL_MARGIN_DP.dp.roundToPx() }
        LaunchedEffect(focus) {
            // Cada paso nuevo cancela la animación del anterior: la rejilla
            // sigue al foco sin encolar desplazamientos.
            if (focus in state.candidates.indices) grid.reveal(focus, marginPx, animate = !reduced && !holding())
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = ArtPickerLayout.SIDE_PADDING_DP.dp, vertical = ArtPickerLayout.EDGE_PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(ArtPickerLayout.H_SPACING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(ArtPickerLayout.V_SPACING_DP.dp),
        ) {
            itemsIndexed(state.candidates, key = { _, c -> c.url }) { i, c ->
                CandidateCell(
                    candidate = c,
                    fallback = fallback,
                    cell = cell,
                    look = look,
                    focused = i == focus,
                    applied = i == applied,
                    applying = state.applying == c.url,
                    dimmed = state.applying != null && state.applying != c.url,
                ) { onPick(c) }
            }
        }
    }
}

/**
 * Desplaza lo justo para que el elemento [index] se vea entero, con [margin]
 * de aire (el marco de selección asoma por fuera de la celda). Si está lejos
 * —fuera de lo compuesto, tras un salto de página— primero salta a él.
 */
private suspend fun LazyGridState.reveal(index: Int, margin: Int, animate: Boolean) {
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    var smooth = animate
    if (item == null) {
        scrollToItem(index)
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        smooth = false
    }
    val info = layoutInfo
    val delta = GridNav.revealDelta(item.offset.y, item.offset.y + item.size.height, info.viewportStartOffset, info.viewportEndOffset, margin)
    if (delta == 0) return
    if (smooth) animateScrollBy(delta.toFloat(), Springs.snappy()) else scrollBy(delta.toFloat())
}

@Composable
private fun Message(
    text: String,
    spinner: Boolean,
    buttons: List<ArtPickerLayout.Button>,
    focus: Int,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spinner) {
            Spinner()
            Spacer(Modifier.height(12.dp))
        }
        ElyText(text, size = 11f, color = P.ink2, align = TextAlign.Center, lineHeightRatio = 1.5f)
        Spacer(Modifier.height(16.dp))
        // Siempre hay un botón: con el mando nunca se queda sin nada señalado ni sin salida.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            buttons.forEachIndexed { i, button ->
                val focused = i == focus
                when (button) {
                    // Sobre el relleno de acento, el aro va en blanco (en acento no se vería).
                    ArtPickerLayout.Button.Retry -> AccentButton(
                        stringResource(R.string.retry),
                        onRetry,
                        Modifier.consoleFocus(focused, RoundedCornerShape(15.dp), Color.White),
                    )
                    ArtPickerLayout.Button.Close -> GhostButton(
                        stringResource(R.string.close),
                        onClose,
                        Modifier.consoleFocus(focused, RoundedCornerShape(11.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun Spinner() {
    val skin = LocalSkin.current
    val angle = spinAngle(1000)
    Box(Modifier.size(26.dp).rotate(angle).drawBehind { drawArcSpinner(skin.a1, 2.dp, 45f) })
}

/**
 * Una imagen propuesta. Lo señalado por el mando y lo puesto se distinguen
 * a propósito: el foco es el marco de selección de las cards (escala, halo
 * y filo con contraste, que se lee sobre miniaturas blancas o negras); lo
 * puesto, un filo de acento, una insignia ✓ y "En uso" en el rótulo, que
 * se ven también sin mando.
 */
@Composable
private fun CandidateCell(
    candidate: ArtCandidate,
    fallback: ArtFallback,
    cell: ArtPickerLayout.Cell,
    look: SelectionLook,
    focused: Boolean,
    applied: Boolean,
    applying: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(10.dp)
    val scale = selectionScale(focused)
    val inUse = stringResource(R.string.art_in_use)
    // El halo de la señalada asoma sobre las vecinas, no debajo.
    Column(Modifier.zIndex(if (focused) 1f else 0f).alpha(if (dimmed) 0.45f else 1f)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(cell.ratio)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .selectionFrame(focused, look, shape)
                .clip(shape)
                .border(if (applied) 2.dp else 1.dp, if (applied) skin.a2 else Color.White.copy(alpha = 0.6f), shape)
                .shapeClickable(shape, enabled = !dimmed && !applying, onClick = onClick)
                .testTag(UiTags.ART_CANDIDATE),
            contentAlignment = Alignment.Center,
        ) {
            FallbackArt(fallback, if (cell.ratio > 1.2f) ArtVariant.Banner else ArtVariant.Cover, Modifier.fillMaxSize(), showTitle = false, showIcon = false)
            AsyncImage(
                model = candidate.thumb,
                contentDescription = null,
                contentScale = if (cell.fit) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(if (cell.fit) 8.dp else 0.dp),
            )
            if (applied) {
                // Círculo de acento con filo blanco: se lee sobre una miniatura clara o una oscura.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(20.dp)
                        .semantics { contentDescription = inUse }
                        .clip(CircleShape)
                        .background(skin.a2)
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ConsoleGlyphIcon(ConsoleGlyph.Check, Color.White, size = 12.dp)
                }
            }
            if (applying) {
                Box(Modifier.fillMaxSize().background(P.shade.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) { Spinner() }
            }
        }
        Spacer(Modifier.height(4.dp))
        ElyText(
            if (applied) "$inUse · ${candidate.label}" else candidate.label,
            size = 8.5f,
            weight = if (applied) FontWeight.SemiBold else FontWeight.Normal,
            color = if (applied) skin.a2 else P.ink2,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Con rejilla: poner, volver y saltar una pantalla de filas. */
private val GRID_HINTS = listOf(
    PadHint("A", R.string.hint_apply),
    PadHint("B", R.string.hint_back),
    PadHint("LB / RB", R.string.hint_page),
)

/** Sin rejilla (buscando, nada, error): el botón señalado y volver. */
private val MESSAGE_HINTS = listOf(PadHint("A", R.string.hint_select), PadHint("B", R.string.hint_back))
