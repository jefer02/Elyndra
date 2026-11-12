package com.elyndra.launcher.ui.masha

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.domain.ports.PortDisplay
import com.elyndra.launcher.masha.CardTiles
import com.elyndra.launcher.masha.ConfirmKind
import com.elyndra.launcher.masha.ConfirmState
import com.elyndra.launcher.masha.MashaAttachment
import com.elyndra.launcher.ui.ChatMessage
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.screens.ports.StoreImage
import com.elyndra.launcher.ui.theme.animMsgIn
import com.elyndra.launcher.ui.theme.focusRing
import com.elyndra.launcher.ui.theme.shapeClickable

/**
 * Lo que se ve de una respuesta de Lucy con el chat oculto: solo imágenes
 * flotando en la parte de abajo (carátulas, arte de ports, iconos), sin
 * títulos, motivos ni párrafos. Tocar una baldosa hace lo suyo (la ficha del
 * juego o la página del port); el título y el motivo están en su etiqueta de
 * accesibilidad y salen en un pie pequeño solo al enfocarla (mando) o
 * mantenerla. Una confirmación es una cuadrícula de iconos con Confirmar /
 * Cancelar grandes; contestada, desaparece (lo cuenta su voz).
 */
@Composable
internal fun HoloTiles(vm: ElyndraViewModel, msg: ChatMessage, glow: Color, modifier: Modifier = Modifier) {
    when (val a = msg.attachment) {
        is MashaAttachment.Confirm -> ConfirmTiles(vm, a, glow, modifier.animMsgIn(delayMs = if (msg.restored) 0 else 60, key = "confirm-${msg.id}"))
        else -> {
            val tiles = CardTiles.from(a)
            if (tiles.isNotEmpty()) TileRow(vm, tiles, glow, modifier.animMsgIn(delayMs = if (msg.restored) 0 else 60, key = "tiles-${msg.id}"))
        }
    }
}

/** Una baldosa ya resuelta: su imagen, lo que dice al enfocarla y qué hace al tocarla. */
private class ResolvedTile(
    val key: String,
    val label: String,
    val wide: Boolean,
    val image: @Composable () -> Unit,
    val onOpen: () -> Unit,
)

@Composable
private fun resolve(vm: ElyndraViewModel, tile: CardTiles.Tile): ResolvedTile? = when (tile.kind) {
    CardTiles.Kind.Game -> vm.masha.gameRef(tile.id)?.let { ref ->
        ResolvedTile(
            key = "g:${ref.key}",
            label = CardTiles.label(ref.title, tile.reason),
            // Las apps llevan icono cuadrado; las ROMs, carátula vertical.
            wide = ref.packageName != null,
            image = {
                if (ref.packageName != null) GameIcon(ref.artPath, ref.packageName, Modifier.fillMaxSize(), ContentScale.Crop)
                else ArtImage(ref.artPath, ArtFallback(ref.key, ref.title), Modifier.fillMaxSize(), showTitle = false)
            },
            onOpen = { vm.showDetails(ref.key) },
        )
    }
    CardTiles.Kind.Port -> vm.ports.catalog.firstOrNull { it.id == tile.id }?.let { port ->
        val title = PortDisplay.title(port)
        ResolvedTile(
            key = "p:${port.id}",
            label = CardTiles.label(title, tile.reason),
            wide = false,
            image = { StoreImage(vm.ports.coverArt(port), title, Modifier.fillMaxSize()) },
            onOpen = { vm.ports.openPort(port.id) },
        )
    }
}

@Composable
private fun TileRow(vm: ElyndraViewModel, tiles: List<CardTiles.Tile>, glow: Color, modifier: Modifier) {
    val resolved = tiles.mapNotNull { resolve(vm, it) }
    if (resolved.isEmpty()) return
    // El pie: solo la baldosa enfocada o mantenida.
    var selected by remember(tiles) { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            resolved.forEach { t ->
                Tile(
                    label = t.label,
                    width = TILE_W,
                    height = if (t.wide) TILE_W else TILE_H,
                    glow = glow,
                    selected = selected == t.key,
                    onSelect = { on -> selected = if (on) t.key else selected.takeIf { it != t.key } },
                    onClick = t.onOpen,
                    image = t.image,
                )
            }
        }
        val caption = resolved.firstOrNull { it.key == selected }?.label
        if (caption != null) {
            ElyText(caption, size = 10f, weight = FontWeight.Medium, color = Holo.text, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeightRatio = 1.3f)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    label: String,
    width: Dp,
    height: Dp,
    glow: Color,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onClick: () -> Unit,
    image: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val hint = stringResource(R.string.lucy_tile_hint)
    Box(
        Modifier
            .size(width, height)
            .semantics { contentDescription = label }
            .onFocusChanged { onSelect(it.isFocused) }
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(if (selected) 2.dp else 1.dp, glow.copy(alpha = if (selected) 1f else 0.45f), shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = focusRing(shape),
                onClickLabel = hint,
                onLongClick = { onSelect(!selected) },
                onClick = onClick,
            ),
    ) { image() }
}

/**
 * La confirmación sin chat: los iconos o carátulas de lo que va a cambiar (sin
 * lista) y Confirmar / Cancelar grandes. Solo mientras espera respuesta.
 */
@Composable
private fun ConfirmTiles(vm: ElyndraViewModel, c: MashaAttachment.Confirm, glow: Color, modifier: Modifier) {
    val pending = c.state == ConfirmState.PENDING && vm.masha.isWritePending(c.id)
    if (!pending) return
    val images: List<Pair<String, @Composable () -> Unit>> = when (c.kind) {
        ConfirmKind.ADD_GAMES -> c.items.mapIndexed { i, title ->
            val pkg = c.packages.getOrNull(i)?.takeIf { it.isNotEmpty() }
            title to @Composable {
                if (pkg != null) GameIcon(null, pkg, Modifier.fillMaxSize(), ContentScale.Crop)
                else ArtImage(null, ArtFallback(title, title), Modifier.fillMaxSize(), showTitle = false)
            }
        }
        ConfirmKind.INSTALL_PORT -> {
            val title = c.items.getOrNull(0).orEmpty()
            val port = vm.ports.catalog.firstOrNull { PortDisplay.title(it) == title }
            listOf(title to @Composable { StoreImage(port?.let { vm.ports.coverArt(it) }, title, Modifier.fillMaxSize()) })
        }
        ConfirmKind.RENAME -> {
            val ref = vm.library.roms.firstOrNull { it.displayTitle == c.items.getOrNull(0) }?.key?.let { vm.masha.gameRef(it) }
                ?: vm.library.apps.firstOrNull { it.displayTitle == c.items.getOrNull(0) }?.key?.let { vm.masha.gameRef(it) }
            listOfNotNull(
                ref?.let {
                    it.title to @Composable {
                        if (it.packageName != null) GameIcon(it.artPath, it.packageName, Modifier.fillMaxSize(), ContentScale.Crop)
                        else ArtImage(it.artPath, ArtFallback(it.key, it.title), Modifier.fillMaxSize(), showTitle = false)
                    }
                },
            )
        }
        else -> emptyList()
    }
    Column(
        modifier
            .fillMaxWidth()
            .holoPanel(RoundedCornerShape(16.dp), glow, alpha = 0.55f)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (images.isNotEmpty()) {
            val shown = images.take(MAX_CONFIRM_ICONS)
            shown.chunked(ICONS_PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (title, image) ->
                        Box(
                            Modifier
                                .size(ICON)
                                .semantics { contentDescription = title }
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.08f)),
                        ) { image() }
                    }
                }
            }
            if (images.size > shown.size) {
                Box(Modifier.clip(CircleShape).background(glow.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    ElyText("+${images.size - shown.size}", size = 11f, weight = FontWeight.Bold, color = Holo.text)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(stringResource(R.string.masha_confirm_ok), glow, filled = true, Modifier.weight(1f)) { vm.masha.confirmWrite(c.id) }
            BigButton(stringResource(R.string.cancel), Holo.dim, filled = false, Modifier.weight(1f)) { vm.masha.cancelWrite(c.id) }
        }
    }
}

@Composable
private fun BigButton(label: String, color: Color, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(color.copy(alpha = if (filled) 0.3f else 0.08f))
            .border(1.5.dp, color.copy(alpha = 0.8f), shape)
            .shapeClickable(shape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ElyText(label, size = 14f, weight = FontWeight.Bold, color = Holo.text, maxLines = 1)
    }
}

private val TILE_W = 84.dp
private val TILE_H = 116.dp
private val ICON = 52.dp
private const val ICONS_PER_ROW = 5
private const val MAX_CONFIRM_ICONS = 15

