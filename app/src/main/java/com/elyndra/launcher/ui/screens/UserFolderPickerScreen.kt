package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.domain.library.UserFolderPickLogic
import com.elyndra.launcher.domain.library.UserFolderPickLogic.Candidate
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.GridNav
import com.elyndra.launcher.ui.components.AccentButton
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.ConsoleGlyph
import com.elyndra.launcher.ui.components.ConsoleGlyphIcon
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.LocalPadInput
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.inputStyle
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.padTextField
import com.elyndra.launcher.ui.components.rememberPadField
import com.elyndra.launcher.ui.theme.AuroraBackdrop
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.MinTouch
import com.elyndra.launcher.ui.theme.Space
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.TypeScale
import com.elyndra.launcher.ui.theme.consoleFocus
import com.elyndra.launcher.ui.theme.shapeClickable
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Elegir juegos para una carpeta del usuario. Como el selector de arte, con
 * mando no usa el foco de Compose: la lista la lleva el InputController, así
 * recorrer miles de filas no depende de cuáles estén compuestas.
 */
@Composable
fun UserFolderPickerScreen(vm: ElyndraViewModel) {
    val picker = vm.userFolderPicker
    val input = vm.input
    val m = metrics()
    Box(Modifier.fillMaxSize()) {
        AuroraBackdrop()
        UserFolderPickerContent(
            folderName = picker.folderName,
            query = picker.query,
            loading = picker.loading,
            results = picker.results,
            picked = picker.picked,
            focus = if (input.active) input.pickerFocus else -1,
            hintsVisible = input.gamepadPresent,
            sidePadding = m.pad,
            holding = { input.repeats > 0 },
            onPageRows = input::bindPickerPage,
            onQuery = picker::updateQuery,
            onToggle = picker::toggle,
            onBack = vm::back,
            onConfirm = picker::commit,
        )
    }
}

/** La pantalla sin ViewModel (la usan las vistas previas). [focus]: la fila señalada por el mando; −1 ninguna. */
@Composable
internal fun UserFolderPickerContent(
    folderName: String,
    query: String,
    loading: Boolean,
    results: List<Candidate>,
    picked: Set<String>,
    focus: Int,
    hintsVisible: Boolean,
    sidePadding: Dp,
    holding: () -> Boolean,
    onPageRows: (Int) -> Unit,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val backLabel = stringResource(R.string.hint_back)
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .padding(start = sidePadding, end = sidePadding, top = sidePadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = backLabel },
                size = MinTouch,
                cornerRadius = 15.dp,
            ) { BackChevron() }
            Spacer(Modifier.width(12.dp))
            ElyText(
                stringResource(R.string.user_folder_picker_title, folderName),
                size = TypeScale.Headline,
                weight = FontWeight.SemiBold,
                color = P.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Space.s))
        SearchBox(query, onQuery)
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = Space.s), contentAlignment = Alignment.Center) {
            when {
                loading -> ArcSpinner()
                results.isEmpty() -> ElyText(
                    stringResource(if (query.isBlank()) R.string.user_folder_picker_none else R.string.no_results),
                    size = 11f,
                    color = P.ink2,
                    align = TextAlign.Center,
                )
                else -> PickList(results, picked, focus, holding, onPageRows, onToggle)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PadHints(hints = PICKER_HINTS, visible = hintsVisible, wrap = true)
            }
            Spacer(Modifier.width(10.dp))
            AccentButton(
                pluralStringResource(R.plurals.add_n_games, picked.size, picked.size),
                onConfirm,
                enabled = picked.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit) {
    val skin = LocalSkin.current
    val focus = remember { FocusRequester() }
    val field = rememberPadField()
    val pad = LocalPadInput.current
    val keyboard = LocalSoftwareKeyboardController.current
    // SELECT abre el teclado aquí; A y la cruceta siguen siendo de la lista.
    DisposableEffect(pad, field) {
        pad?.pickerKeyboard = {
            runCatching { focus.requestFocus() }
            field.arm()
            keyboard?.show()
        }
        onDispose { pad?.pickerKeyboard = null }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (P.isDark) P.chip else Color.White.copy(alpha = 0.7f))
            .border(1.dp, if (field.focused) skin.a2 else P.ink.copy(alpha = 0.14f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (query.isEmpty()) ElyText(stringResource(R.string.search_hint), size = 12f, color = P.ink2.copy(alpha = 0.6f), maxLines = 1)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            readOnly = field.readOnly(pad?.active == true),
            textStyle = inputStyle(12f),
            cursorBrush = SolidColor(skin.a2),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).padTextField(field),
        )
    }
}

@Composable
private fun PickList(
    results: List<Candidate>,
    picked: Set<String>,
    focus: Int,
    holding: () -> Boolean,
    onPageRows: (Int) -> Unit,
    onToggle: (String) -> Unit,
) {
    val list = rememberLazyListState()
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val publish = rememberUpdatedState(onPageRows)
    val gapPx = with(density) { ROW_GAP.roundToPx() }
    val marginPx = with(density) { REVEAL_MARGIN.roundToPx() }
    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo
            GridNav.pageRows(info.viewportSize.height, info.visibleItemsInfo.firstOrNull()?.size ?: 0, gapPx)
        }.distinctUntilChanged().collect { publish.value(it) }
    }
    // Otra búsqueda empieza por arriba, aunque la anterior se hubiera desplazado.
    LaunchedEffect(results) { list.scrollToItem(0) }
    LaunchedEffect(focus) {
        if (focus in results.indices) list.reveal(focus, marginPx, animate = !reduced && !holding())
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        itemsIndexed(results, key = { _, c -> c.key }, contentType = { _, _ -> "game" }) { i, c ->
            PickRow(c, checked = c.key in picked, focused = i == focus, onToggle = onToggle)
        }
    }
}

private suspend fun LazyListState.reveal(index: Int, margin: Int, animate: Boolean) {
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    var smooth = animate
    if (item == null) {
        scrollToItem(index)
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        smooth = false
    }
    val info = layoutInfo
    val delta = GridNav.revealDelta(item.offset, item.offset + item.size, info.viewportStartOffset, info.viewportEndOffset, margin)
    if (delta == 0) return
    if (smooth) animateScrollBy(delta.toFloat(), Springs.snappy()) else scrollBy(delta.toFloat())
}

@Composable
private fun PickRow(candidate: Candidate, checked: Boolean, focused: Boolean, onToggle: (String) -> Unit) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(12.dp)
    val platform = when (candidate.kind) {
        UserFolderPickLogic.Kind.Rom -> candidate.platform.orEmpty()
        UserFolderPickLogic.Kind.App -> stringResource(R.string.filter_android)
        UserFolderPickLogic.Kind.Port -> stringResource(R.string.ports_port_badge)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .semantics { selected = checked }
            .consoleFocus(focused, shape)
            .clip(shape)
            .background(if (checked) skin.a2.copy(alpha = 0.16f) else P.ink.copy(alpha = 0.04f))
            .shapeClickable(shape) { onToggle(candidate.key) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(candidate)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ElyText(candidate.title, size = 11.5f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (platform.isNotEmpty()) ElyText(platform, size = 9f, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) skin.a2 else Color.Transparent)
                .border(1.5.dp, if (checked) Color.White else P.ink.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) ConsoleGlyphIcon(ConsoleGlyph.Check, Color.White, size = 13.dp)
        }
    }
}

@Composable
private fun Thumb(candidate: Candidate) {
    val modifier = Modifier.size(THUMB_WIDTH, THUMB_HEIGHT).clip(RoundedCornerShape(6.dp))
    if (candidate.cover == null && (candidate.icon != null || candidate.packageName != null)) {
        GameIcon(candidate.icon, candidate.packageName, modifier, ContentScale.Fit)
    } else {
        val fallback = remember(candidate.key) { ArtFallback(candidate.key, candidate.title, candidate.icon, candidate.packageName) }
        ArtImage(candidate.cover, fallback, modifier, showTitle = false)
    }
}

private val PICKER_HINTS = listOf(
    PadHint("A", R.string.hint_select),
    PadHint("Y", R.string.user_folder_hint_add),
    PadHint("SELECT", R.string.hint_keyboard),
    PadHint("LB / RB", R.string.hint_page),
    PadHint("B", R.string.hint_back),
)

private val ROW_HEIGHT = 58.dp
private val ROW_GAP = 4.dp
private val REVEAL_MARGIN = 8.dp
private val THUMB_WIDTH = 36.dp
private val THUMB_HEIGHT = 48.dp
