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
                    if (msg.text.isNotEmpty()) {
                        ElyText(msg.text + if (msg.pending) " ▍" else "", size = 12f, color = Holo.text, lineHeightRatio = 1.55f)
                    }
                    if (msg.done.isNotEmpty()) DoneChips(msg.done, glow)
                    when (val a = msg.attachment) {
                        is MashaAttachment.Games -> GamesStrip(vm, a)
                        is MashaAttachment.Plan -> PlanCard(vm, a, glow)
                        is MashaAttachment.ArcCard -> ArcStrip(vm, a)
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

/** Plan de sesión: cada juego con sus minutos y el botón para empezar por el primero. */
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
