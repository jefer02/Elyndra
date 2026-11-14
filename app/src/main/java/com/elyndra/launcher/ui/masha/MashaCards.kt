package com.elyndra.launcher.ui.masha

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.elyndra.launcher.data.BrandTokens
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import com.elyndra.launcher.ui.theme.focusRing
import com.elyndra.launcher.ui.theme.shapeClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.masha.MashaAttachment
import com.elyndra.launcher.ui.ChatMessage
import com.elyndra.launcher.domain.ports.PortAction
import com.elyndra.launcher.domain.ports.PortDisplay
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.PortLink
import com.elyndra.launcher.masha.ReplyShape
import com.elyndra.launcher.ui.screens.ports.StoreImage
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.MashaGameRef
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.components.PlayGlyph
import com.elyndra.launcher.ui.components.TypingDots
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.animMsgIn

/**
 * Colores del holotanque. La pantalla de Masha es siempre "de noche": el
 * holograma necesita oscuridad, así que no sigue al tema claro/oscuro.
 */
internal object Holo {
    val bg = Color(BrandTokens.HOLO_BG)
    val panel = Color(BrandTokens.HOLO_PANEL)
    val line = Color(BrandTokens.HOLO_LINE)
    val text = Color(BrandTokens.HOLO_TEXT)
    val dim = Color(BrandTokens.HOLO_DIM)
    val user = Color(BrandTokens.HOLO_USER)
}

internal fun Modifier.holoPanel(shape: RoundedCornerShape, glow: Color, alpha: Float = 0.72f): Modifier =
    this
        .clip(shape)
        .background(Holo.panel.copy(alpha = alpha))
        .border(1.dp, glow.copy(alpha = 0.35f), shape)

/** Un mensaje de la conversación, con lo que Masha haya adjuntado. */
@Composable
internal fun HoloMessage(vm: ElyndraViewModel, msg: ChatMessage, glow: Color, landscape: Boolean) {
    val key = "msg-${msg.id}"
    val delay = if (msg.restored) 0 else 60
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.fromMasha) Arrangement.Start else Arrangement.End) {
        if (msg.fromMasha) {
            if (msg.pending && msg.text.isEmpty() && msg.attachment == null && msg.done.isEmpty()) return@Row
            val shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp)
            Box(
                Modifier
                    .fillMaxWidth(if (landscape) 0.94f else 0.9f)
                    .animMsgIn(delayMs = delay, key = key)
                    .holoPanel(shape, glow)
                    .drawBehind { drawRect(glow, size = Size(2.dp.toPx(), size.height)) }
                    .padding(horizontal = 13.dp, vertical = 11.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    // Con tarjetas, solo la frase corta que dice: los detalles van en ellas.
                    val text = ReplyShape.display(msg.text, msg.attachment, msg.done)
                    if (text.isNotEmpty()) {
                        ElyText(text + if (msg.pending) " ▍" else "", size = 12f, color = Holo.text, lineHeightRatio = 1.55f)
                    }
                    if (msg.done.isNotEmpty()) DoneChips(msg.done, glow)
                    when (val a = msg.attachment) {
                        is MashaAttachment.Games -> GamesStrip(vm, a)
                        is MashaAttachment.Plan -> PlanCard(vm, a, glow)
                        is MashaAttachment.ArcCard -> ArcStrip(vm, a)
                        is MashaAttachment.Confirm -> ConfirmCard(vm, a, glow)
                        is MashaAttachment.Ports -> PortsStrip(vm, a)
                        is MashaAttachment.Picks -> PicksCard(vm, a, glow)
                        is MashaAttachment.Done, null -> msg.game?.let { GameMention(it) { vm.showDetails(it.key) } }
                    }
                    if (msg.offline) {
                        ElyText(
                            stringResource(R.string.masha_offline_mode),
                            size = 8.5f,
                            weight = FontWeight.SemiBold,
                            color = Holo.dim,
                            letterSpacing = tracking(0.12f),
                            uppercase = true,
                            maxLines = 1,
                        )
                    }
                }
            }
        } else {
            val shape = RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
            Box(
                Modifier
                    .fillMaxWidth(if (landscape) 0.8f else 0.78f)
                    .animMsgIn(delayMs = delay, key = key),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .clip(shape)
                        .background(Holo.user.copy(alpha = 0.85f))
                        .border(1.dp, glow.copy(alpha = 0.5f), shape)
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    ElyText(msg.text, size = 12f, color = Color.White, lineHeightRatio = 1.45f)
                }
            }
        }
    }
}

/** Masha está pensando o usando una herramienta ("Buscando en tu biblioteca…"). */
@Composable
internal fun HoloWorking(label: String?, glow: Color) {
    Row(
        Modifier
            .animMsgIn(300, key = "working")
            .holoPanel(RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp), glow)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypingDots()
        if (label != null) {
            ElyText(label, modifier = Modifier.padding(start = 10.dp), size = 11f, color = Holo.dim, maxLines = 1)
        }
    }
}

/** Lo que hizo en este turno ("▶ Okami", "⚙ Okami → PCSX2"). */
@Composable
private fun DoneChips(done: List<String>, glow: Color) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        done.forEach { label ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(glow.copy(alpha = 0.14f))
                    .border(1.dp, glow.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                ElyText(label, size = 10f, weight = FontWeight.SemiBold, color = Holo.text, maxLines = 1)
            }
        }
    }
}

/** Ports de la tienda: una ficha por juego (el juego original y su port) que abre su página. */
@Composable
private fun PortsStrip(vm: ElyndraViewModel, a: MashaAttachment.Ports) {
    val ports = a.ids.mapNotNull { id -> vm.ports.catalog.firstOrNull { it.id == id } }
    if (ports.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ports.forEach { port ->
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .width(150.dp)
                    .clip(shape)
                    .background(Holo.text.copy(alpha = 0.06f))
                    .shapeClickable(shape) { vm.ports.openPort(port.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                ElyText(PortDisplay.title(port), size = 10.5f, weight = FontWeight.SemiBold, color = Holo.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Nunca un id, un paquete o un archivo: sin nombre presentable, solo la plataforma.
                val line = listOfNotNull(PortDisplay.platformBadge(port.platform), PortDisplay.portName(port)).joinToString(" · ")
                ElyText(line, size = 9f, color = Holo.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GamesStrip(vm: ElyndraViewModel, a: MashaAttachment.Games) {
    val refs = a.keys.mapNotNull { vm.masha.gameRef(it) }
    if (refs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            refs.forEach { ref -> GameThumb(ref) { vm.showDetails(ref.key) } }
        }
        if (a.total > refs.size) {
            ElyText(pluralStringResource(R.plurals.masha_off_list, a.total, a.total), size = 9.5f, color = Holo.dim, maxLines = 1)
        }
    }
}

@Composable
private fun GameThumb(ref: MashaGameRef, onClick: () -> Unit) {
    // Se pulsa la miniatura y su título; el realce va solo en la miniatura, con su forma.
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(74.dp).clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .width(74.dp)
                .height(if (ref.packageName != null) 74.dp else 100.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.08f))
                .indication(interaction, focusRing(shape)),
        ) {
            if (ref.packageName != null) {
                // El icono llena la miniatura: sin margen ni caja clara alrededor.
                GameIcon(ref.artPath, ref.packageName, Modifier.fillMaxSize(), ContentScale.Crop)
            } else {
                ArtImage(ref.artPath, ArtFallback(ref.key, ref.title), Modifier.fillMaxSize(), showTitle = false)
            }
        }
        ElyText(ref.title, size = 9.5f, weight = FontWeight.SemiBold, color = Holo.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Lo que Lucy recomienda: una fila por juego o port con su carátula, el
 * sistema, el tiempo jugado y el estado (o, en un port, si está instalado y
 * si necesita archivos del juego original), el emulador si lo hay, su motivo
 * y el botón para jugar o abrirlo.
 */
@Composable
private fun PicksCard(vm: ElyndraViewModel, picks: MashaAttachment.Picks, glow: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        picks.items.forEach { pick ->
            when (pick.kind) {
                MashaAttachment.Pick.PORT -> vm.ports.catalog.firstOrNull { it.id == pick.id }?.let { PortPickRow(vm, it, pick, glow) }
                else -> vm.masha.gameRef(pick.id)?.let { GamePickRow(vm, it, pick, glow) }
            }
        }
    }
}

@Composable
private fun GamePickRow(vm: ElyndraViewModel, ref: MashaGameRef, pick: MashaAttachment.Pick, glow: Color) {
    val facts = listOfNotNull(
        ref.subtitle,
        pick.minutes?.takeIf { it > 0 }?.let { playtime(it) },
        pick.state?.let { stateLabel(it) },
        pick.emulator,
    ).joinToString(" · ")
    PickRow(
        cover = { Thumb(ref) },
        title = ref.title,
        facts = facts,
        reason = pick.reason,
        onOpen = { vm.showDetails(ref.key) },
    ) {
        HoloButton(stringResource(R.string.widget_play), glow) { vm.openByKey(ref.key) }
    }
}

@Composable
private fun PortPickRow(vm: ElyndraViewModel, port: PortEntry, pick: MashaAttachment.Pick, glow: Color) {
    val action = vm.ports.action(port)
    val needs = port.requires.userRom
    val facts = listOfNotNull(
        PortDisplay.platformBadge(port.platform),
        stringResource(
            when (action) {
                PortAction.Installed -> R.string.lucy_port_installed
                PortAction.Update -> R.string.lucy_port_update
                PortAction.NotOnAndroid, PortAction.VrOnly, PortAction.Unavailable -> R.string.lucy_port_not_android
                else -> R.string.lucy_port_available
            },
        ),
    ).joinToString(" · ")
    PickRow(
        cover = {
            StoreImage(vm.ports.coverArt(port), PortDisplay.title(port), Modifier.fillMaxSize())
        },
        title = PortDisplay.title(port),
        facts = facts,
        reason = pick.reason,
        note = if (needs) stringResource(R.string.lucy_port_needs_files, port.game.name) else null,
        onOpen = { vm.ports.openPort(port.id) },
    ) {
        val installedKey = vm.ports.installed[port.id]?.packageName?.let { PortLink.key(it) }
        if (action == PortAction.Installed && installedKey != null) {
            HoloButton(stringResource(R.string.widget_play), glow) { vm.openByKey(installedKey) }
        } else {
            HoloButton(stringResource(R.string.open), glow) { vm.ports.openPort(port.id) }
        }
    }
}

@Composable
private fun PickRow(
    cover: @Composable () -> Unit,
    title: String,
    facts: String,
    reason: String,
    onOpen: () -> Unit,
    note: String? = null,
    action: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().shapeClickable(RoundedCornerShape(10.dp), onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(46.dp, 62.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))) { cover() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ElyText(title, size = 12f, weight = FontWeight.Bold, color = Holo.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ElyText(facts, size = 9.5f, color = Holo.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (reason.isNotEmpty()) ElyText(reason, size = 10.5f, color = Holo.text, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeightRatio = 1.3f)
            if (note != null) ElyText(note, size = 9f, weight = FontWeight.SemiBold, color = Holo.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        action()
    }
}

@Composable
private fun Thumb(ref: MashaGameRef) {
    if (ref.packageName != null) {
        GameIcon(ref.artPath, ref.packageName, Modifier.fillMaxSize(), ContentScale.Crop)
    } else {
        ArtImage(ref.artPath, ArtFallback(ref.key, ref.title), Modifier.fillMaxSize(), showTitle = false)
    }
}

@Composable
private fun playtime(minutes: Int): String =
    if (minutes >= 60) stringResource(R.string.lucy_pick_hours, minutes / 60) else stringResource(R.string.lucy_pick_minutes, minutes)

@Composable
private fun stateLabel(state: String): String? = when (state) {
    "new" -> stringResource(R.string.lucy_state_new)
    "tried" -> stringResource(R.string.lucy_state_tried)
    "in_progress" -> stringResource(R.string.lucy_state_in_progress)
    "abandoned" -> stringResource(R.string.lucy_state_abandoned)
    "dormant" -> stringResource(R.string.lucy_state_dormant)
    else -> null
}

/** Plan de sesión: cada juego con sus minutos y el botón para empezar por el primero. */
/**
 * Lo que Masha va a cambiar, a la espera del visto bueno: qué es, la lista
 * (si son varios juegos) y Confirmar / Cancelar. Contestada, solo el estado.
 */
@Composable
private fun ConfirmCard(vm: ElyndraViewModel, c: MashaAttachment.Confirm, glow: Color) {
    val live = c.state == com.elyndra.launcher.masha.ConfirmState.PENDING && vm.masha.isWritePending(c.id)
    val title = when (c.kind) {
        com.elyndra.launcher.masha.ConfirmKind.ADD_GAMES -> pluralStringResource(R.plurals.masha_confirm_add, c.items.size, c.items.size)
        com.elyndra.launcher.masha.ConfirmKind.RENAME -> stringResource(R.string.masha_confirm_rename, c.items.getOrElse(0) { "?" }, c.items.getOrElse(1) { "?" })
        com.elyndra.launcher.masha.ConfirmKind.SET_EMULATOR -> stringResource(R.string.masha_confirm_emulator, c.items.getOrElse(0) { "?" }, c.items.getOrElse(1) { "?" })
        com.elyndra.launcher.masha.ConfirmKind.INSTALL_PORT -> stringResource(R.string.lucy_confirm_install_port, c.items.getOrElse(0) { "?" })
        else -> c.kind
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ElyText(title, size = 11.5f, weight = FontWeight.Bold, color = Holo.text)
        if (c.kind == com.elyndra.launcher.masha.ConfirmKind.INSTALL_PORT) {
            c.items.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { ElyText(stringResource(R.string.lucy_port_needs_files, it), size = 10f, color = Holo.dim) }
            ElyText(stringResource(R.string.lucy_confirm_install_port_flow), size = 9.5f, color = Holo.dim)
        }
        if (c.kind == com.elyndra.launcher.masha.ConfirmKind.ADD_GAMES) {
            c.items.take(12).forEach { ElyText("• $it", size = 10f, color = Holo.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (c.items.size > 12) ElyText(stringResource(R.string.masha_confirm_more, c.items.size - 12), size = 10f, color = Holo.dim)
            if (c.skipped > 0) ElyText(stringResource(R.string.masha_confirm_skipped, c.skipped), size = 9.5f, color = Holo.dim)
        }
        when {
            live -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HoloButton(stringResource(R.string.masha_confirm_ok), glow) { vm.masha.confirmWrite(c.id) }
                HoloButton(stringResource(R.string.cancel), Holo.dim) { vm.masha.cancelWrite(c.id) }
            }
            else -> ElyText(
                stringResource(
                    when (c.state) {
                        com.elyndra.launcher.masha.ConfirmState.DONE -> R.string.masha_confirm_state_done
                        com.elyndra.launcher.masha.ConfirmState.CANCELLED -> R.string.masha_confirm_state_cancelled
                        else -> R.string.masha_confirm_state_expired
                    },
                ),
                size = 9f,
                weight = FontWeight.SemiBold,
                color = Holo.dim,
                uppercase = true,
            )
        }
    }
}

@Composable
private fun PlanCard(vm: ElyndraViewModel, plan: MashaAttachment.Plan, glow: Color) {
    val items = plan.blocks.mapNotNull { b -> vm.masha.gameRef(b.key)?.let { it to b.minutes } }
    if (items.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (ref, minutes) ->
            Row(
                Modifier.fillMaxWidth().shapeClickable(RoundedCornerShape(8.dp)) { vm.showDetails(ref.key) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(34.dp, 44.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.08f))) {
                    if (ref.packageName != null) {
                        GameIcon(ref.artPath, ref.packageName, Modifier.fillMaxSize(), ContentScale.Crop)
                    } else {
                        ArtImage(ref.artPath, ArtFallback(ref.key, ref.title), Modifier.fillMaxSize(), showTitle = false)
                    }
                }
                Column(Modifier.weight(1f)) {
                    ElyText(ref.title, size = 11.5f, weight = FontWeight.Bold, color = Holo.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ElyText(ref.subtitle, size = 9.5f, color = Holo.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(glow.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    ElyText("$minutes min", size = 10f, weight = FontWeight.SemiBold, color = Holo.text, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ElyText("Σ ${items.sumOf { it.second }} min", size = 10.5f, weight = FontWeight.SemiBold, color = Holo.dim, modifier = Modifier.weight(1f))
            HoloButton(stringResource(R.string.widget_play), glow) { vm.openByKey(items.first().first.key) }
        }
    }
}

@Composable
private fun ArcStrip(vm: ElyndraViewModel, arc: MashaAttachment.ArcCard) {
    val refs = arc.keys.mapNotNull { vm.masha.gameRef(it) }
    if (refs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ElyText("${arc.title} · ${(arc.done + 1).coerceAtMost(arc.total)}/${arc.total}", size = 11f, weight = FontWeight.Bold, color = Holo.text, maxLines = 1)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            refs.forEachIndexed { i, ref ->
                Box(Modifier.alpha(if (i < arc.done) 0.4f else 1f)) { GameThumb(ref) { vm.showDetails(ref.key) } }
            }
        }
    }
}

/** El juego que nombra Masha, enganchado bajo su mensaje. */
@Composable
private fun GameMention(game: MashaGameRef, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.08f))) {
            GameIcon(game.artPath, game.packageName, Modifier.fillMaxSize(), ContentScale.Crop)
        }
        Column(Modifier.weight(1f)) {
            ElyText(game.title, size = 11.5f, weight = FontWeight.Bold, color = Holo.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ElyText(game.subtitle, size = 9.5f, weight = FontWeight.Medium, color = Holo.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlayGlyph(color = Holo.dim, size = 8.dp)
    }
}

@Composable
internal fun HoloButton(label: String, glow: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(glow.copy(alpha = 0.22f))
            .border(1.dp, glow.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .shapeClickable(RoundedCornerShape(10.dp), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        ElyText(label, size = 11.5f, weight = FontWeight.Bold, color = Holo.text, maxLines = 1)
    }
}
