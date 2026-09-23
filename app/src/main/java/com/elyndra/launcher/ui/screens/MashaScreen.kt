package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.data.pairIndexFor
import com.elyndra.launcher.masha.MashaAttachment
import com.elyndra.launcher.ui.ChatMessage
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.MashaGameRef
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.components.AccentButton
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.PlayGlyph
import com.elyndra.launcher.ui.components.TypingDots
import com.elyndra.launcher.ui.components.inputStyle
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.AuroraBackdrop
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.animFadeUp
import com.elyndra.launcher.ui.theme.animMsgIn
import com.elyndra.launcher.ui.theme.animPopIn
import com.elyndra.launcher.ui.theme.animRiseSheet
import com.elyndra.launcher.ui.theme.auraAngle
import com.elyndra.launcher.ui.theme.barPlayScale
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.livePulseScale
import com.elyndra.launcher.ui.theme.ringProgress
import kotlin.math.abs

@Composable
fun MashaScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val skin = LocalSkin.current
    val masha = vm.masha
    val scroll = rememberScrollState()
    val lang = vm.settings.lang
    val greeting = stringResource(R.string.masha_greeting)
    val stats = masha.stats()
    val noData = stringResource(R.string.masha_no_data)
    val suggestions = listOf(
        stringResource(R.string.masha_chip_session),
        stringResource(R.string.masha_chip_light),
        stringResource(R.string.masha_chip_continue),
        stringResource(R.string.masha_suggest_next),
        stringResource(R.string.masha_suggest_week),
        stringResource(R.string.masha_chip_cleanup),
        stringResource(R.string.masha_chip_art),
        stringResource(R.string.masha_suggest_fact),
    )
    val cards = listOf(
        Triple(
            stringResource(R.string.masha_stat_week),
            fmtMinutes(stats.weekMinutes),
            if (stats.weekSessions == 0) noData
            else stringResource(R.string.masha_vs_previous, (if (stats.deltaMinutes >= 0) "+" else "−") + fmtMinutes(abs(stats.deltaMinutes))),
        ),
        Triple(
            stringResource(R.string.masha_stat_top),
            if (stats.topTitle != null) fmtMinutes(stats.topMinutes) else "—",
            stats.topTitle ?: noData,
        ),
        Triple(
            stringResource(R.string.masha_stat_sessions),
            stats.weekSessions.toString(),
            if (stats.weekSessions == 0) noData else stringResource(R.string.masha_average, fmtMinutes(stats.averageMinutes)),
        ),
    )

    LaunchedEffect(greeting) { masha.onOpen(greeting) }

    // El chat se mantiene abajo mientras llega texto, cuando entra un mensaje o Masha se pone a trabajar.
    val last = masha.messages.lastOrNull()
    LaunchedEffect(masha.messages.size, last?.text?.length, last?.attachment, masha.working) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Box(Modifier.fillMaxSize().animRiseSheet(key = Screen.Masha)) {
        AuroraBackdrop()

        Column(Modifier.fillMaxSize().imePadding()) {

            // ── CABECERA ──
            Row(
                Modifier
                    .padding(start = m.pad, end = m.pad, top = if (m.landscape) 10.dp else 14.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .animFadeUp(450, key = Screen.Masha)
                    .glass(RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(onClick = { vm.go(Screen.Library) }) { BackChevron() }
                Spacer(Modifier.width(11.dp))
                MashaAvatar()
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    ElyText("Masha", size = 19f, weight = FontWeight.Bold, color = P.ink, letterSpacing = tracking(-0.01f))
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pulse = livePulseScale()
                        Box(
                            Modifier
                                .size(6.dp)
                                .graphicsLayer {
                                    scaleX = pulse
                                    scaleY = pulse
                                    alpha = 1f - (pulse - 1f) * 1.3f
                                }
                                .clip(CircleShape)
                                .background(if (masha.online) P.green else skin.a2),
                        )
                        Spacer(Modifier.width(6.dp))
                        ElyText(
                            stringResource(if (masha.online) R.string.masha_online else R.string.masha_offline_mode),
                            size = 9f,
                            weight = FontWeight.SemiBold,
                            color = skin.a2,
                            letterSpacing = tracking(0.14f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            uppercase = true,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Equalizer(active = masha.busy)
                Spacer(Modifier.width(8.dp))
                GlassIconButton(onClick = { masha.clearConversation(greeting) }, size = 34.dp) { NewChatGlyph() }
            }

            // ── CUERPO ──
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(start = m.pad, end = m.pad, top = 2.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cards.forEachIndexed { i, (label, value, sub) ->
                        StatCard(label, value, sub, i, Modifier.weight(1f))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    masha.messages.forEachIndexed { i, msg ->
                        MessageBubble(vm, msg, i, m.landscape)
                    }
                    if (masha.busy && masha.messages.lastOrNull()?.let { it.pending && it.text.isNotEmpty() } != true) {
                        Row(
                            Modifier
                                .animMsgIn(350, key = "typing")
                                .glass(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TypingDots()
                            masha.working?.let {
                                Spacer(Modifier.width(10.dp))
                                ElyText(it.resolve(), size = 11f, color = P.ink2, maxLines = 1)
                            }
                        }
                    }
                }

                WrapRow(gap = 7.dp) {
                    suggestions.forEachIndexed { i, q ->
                        SuggestionChip(q, i) { masha.sendSuggestion(q, lang) }
                    }
                }
            }

            // ── DOCK ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = m.pad, end = m.pad, top = 10.dp, bottom = if (m.landscape) 12.dp else 18.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .glass(RoundedCornerShape(16.dp), borderColor = skin.a1.copy(alpha = 0.2f))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = masha.draft,
                        onValueChange = masha::updateDraft,
                        singleLine = true,
                        textStyle = inputStyle(12.5f),
                        cursorBrush = SolidColor(skin.a2),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { masha.send(lang) }),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (masha.draft.isEmpty()) {
                                ElyText(
                                    stringResource(R.string.masha_placeholder),
                                    size = 12.5f,
                                    color = P.ink2.copy(alpha = 0.55f),
                                    maxLines = 1,
                                )
                            }
                            inner()
                        },
                    )
                }

                // Mientras contesta, el botón de enviar pasa a ser el de parar.
                Box(
                    Modifier
                        .size(46.dp)
                        .shadow(12.dp, RoundedCornerShape(16.dp), clip = false, ambientColor = P.shade.copy(alpha = 0.24f), spotColor = P.shade.copy(alpha = 0.24f))
                        .clip(RoundedCornerShape(16.dp))
                        .drawBehind { drawRect(accentGradient(skin, 145f, size)) }
                        .clickable { if (masha.busy) masha.stop() else masha.send(lang) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (masha.busy) StopGlyph() else SendArrow()
                }
            }
        }
    }
}

/* ── Piezas ───────────────────────────────────────────────────── */

/** Avatar con halo cónico girando y aro que late. */
@Composable
private fun MashaAvatar() {
    val skin = LocalSkin.current
    val angle = auraAngle()
    val ring = ringProgress(2800)
    val sweep = remember(skin.a1, skin.a2) {
        Brush.sweepGradient(listOf(skin.a1, skin.a2, P.green, skin.a1))
    }

    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angle; alpha = 0.75f }
                .clip(CircleShape)
                .background(sweep)
                .alpha(0.75f),
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val k = 1f + 0.85f * ring
                    scaleX = k
                    scaleY = k
                    alpha = 0.5f * (1f - ring)
                }
                .border(1.5.dp, skin.a1, RoundedCornerShape(16.dp)),
        )
        Box(
            Modifier
                .size(40.dp)
                .shadow(8.dp, RoundedCornerShape(15.dp), clip = false, ambientColor = P.shade.copy(alpha = 0.3f), spotColor = P.shade.copy(alpha = 0.3f))
                .clip(RoundedCornerShape(15.dp))
                .drawBehind { drawRect(accentGradient(skin, 145f, size)) },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.masha),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Ecualizador de cinco barras; se apaga al 45 % cuando Masha no escribe. */
@Composable
private fun Equalizer(active: Boolean) {
    val skin = LocalSkin.current
    Row(
        Modifier.height(22.dp).alpha(if (active) 1f else 0.45f),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { i ->
            val s = barPlayScale(i)
            Box(
                Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .graphicsLayer { scaleY = s }
                    .clip(RoundedCornerShape(2.dp))
                    .drawBehind { drawRect(accentGradient(skin, 180f, size)) },
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, sub: String, index: Int, modifier: Modifier) {
    val skin = LocalSkin.current
    Box(
        modifier
            .animPopIn(500, delayMs = 120 + index * 90, key = label)
            .glass(RoundedCornerShape(16.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .alpha(0.9f)
                .drawBehind { drawRect(accentGradient(skin, 90f, size)) },
        )
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 10.dp)) {
            ElyText(label, size = 8f, weight = FontWeight.SemiBold, color = P.ink2, letterSpacing = tracking(0.18f), maxLines = 1, uppercase = true)
            Spacer(Modifier.height(5.dp))
            ElyText(value, size = 16f, weight = FontWeight.Bold, color = P.ink, letterSpacing = tracking(-0.01f), maxLines = 1)
            Spacer(Modifier.height(2.dp))
            ElyText(sub, size = 9.5f, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MessageBubble(vm: ElyndraViewModel, msg: ChatMessage, index: Int, landscape: Boolean) {
    val skin = LocalSkin.current
    // Lo que viene del hilo guardado entra sin animación; lo nuevo, escalonado.
    val delay = if (msg.restored) 0 else minOf(index, 4) * 70
    // La clave es el id del mensaje, no su texto: en streaming el texto cambia
    // a cada trozo y la burbuja no debe volver a animarse con cada uno.
    val key = "msg-${msg.id}"

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromMasha) Arrangement.Start else Arrangement.End,
    ) {
        if (msg.fromMasha) {
            if (msg.pending && msg.text.isEmpty() && msg.attachment == null && msg.done.isEmpty()) return@Row
            Box(
                Modifier
                    .fillMaxWidth(if (landscape) 0.64f else 0.9f)
                    .animMsgIn(delayMs = delay, key = key)
                    .glass(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                    .drawBehind {
                        drawRect(color = skin.a1, size = Size(2.dp.toPx(), size.height))
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (msg.text.isNotEmpty()) {
                        ElyText(msg.text + if (msg.pending) " ▍" else "", size = 12f, color = P.ink, lineHeightRatio = 1.6f)
                    }
                    if (msg.done.isNotEmpty()) DoneChips(msg.done)
                    when (val a = msg.attachment) {
                        is MashaAttachment.Games -> GamesStrip(vm, a)
                        is MashaAttachment.Plan -> PlanCard(vm, a)
                        is MashaAttachment.ArcCard -> ArcStrip(vm, a)
                        is MashaAttachment.Done, null -> msg.game?.let { GameMentionCard(it) { vm.showDetails(it.key) } }
                    }
                    if (msg.offline) {
                        ElyText(
                            stringResource(R.string.masha_offline_mode),
                            size = 8.5f,
                            weight = FontWeight.SemiBold,
                            color = P.ink2.copy(alpha = 0.7f),
                            letterSpacing = tracking(0.12f),
                            uppercase = true,
                            maxLines = 1,
                        )
                    }
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth(if (landscape) 0.56f else 0.82f)
                    .animMsgIn(delayMs = delay, key = key)
                    .shadow(
                        10.dp,
                        RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp),
                        clip = false,
                        ambientColor = skin.a2.copy(alpha = 0.27f),
                        spotColor = skin.a2.copy(alpha = 0.27f),
                    )
                    .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                    .drawBehind { drawRect(accentGradient(skin, 145f, size)) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                ElyText(msg.text, size = 12f, color = Color.White, lineHeightRatio = 1.5f)
            }
        }
    }
}

/** Fichas de lo que Masha hizo en este turno ("▶ Okami", "⚙ Okami → PCSX2"). */
@Composable
private fun DoneChips(done: List<String>) {
    val skin = LocalSkin.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        done.forEach { label ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(skin.a1.copy(alpha = 0.14f))
                    .border(1.dp, skin.a1.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                ElyText(label, size = 10f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 1)
            }
        }
    }
}

/** Juegos de una búsqueda o una lista, en tarjetas que abren su ficha. */
@Composable
private fun GamesStrip(vm: ElyndraViewModel, a: MashaAttachment.Games) {
    val refs = a.keys.mapNotNull { vm.masha.gameRef(it) }
    if (refs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            refs.forEach { ref -> GameThumb(ref) { vm.showDetails(ref.key) } }
        }
        if (a.total > refs.size) {
            ElyText(
                pluralStringResource(R.plurals.masha_off_list, a.total, a.total),
                size = 9.5f,
                color = P.ink2,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GameThumb(ref: MashaGameRef, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .width(76.dp)
                .height(if (ref.packageName != null) 76.dp else 102.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.45f)),
        ) {
            if (ref.packageName != null) {
                GameIcon(ref.artPath, ref.packageName, Modifier.fillMaxSize().padding(6.dp), ContentScale.Fit)
            } else {
                ArtImage(ref.artPath, pairIndexFor(ref.title), Modifier.fillMaxSize())
            }
        }
        ElyText(ref.title, size = 9.5f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Plan de sesión: cada juego con sus minutos, y el botón para empezar por el primero. */
@Composable
private fun PlanCard(vm: ElyndraViewModel, plan: MashaAttachment.Plan) {
    val items = plan.blocks.mapNotNull { b -> vm.masha.gameRef(b.key)?.let { it to b.minutes } }
    if (items.isEmpty()) return
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.38f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (ref, minutes) ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.showDetails(ref.key) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(34.dp, 44.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.5f))) {
                    if (ref.packageName != null) {
                        GameIcon(ref.artPath, ref.packageName, Modifier.fillMaxSize(), ContentScale.Crop)
                    } else {
                        ArtImage(ref.artPath, pairIndexFor(ref.title), Modifier.fillMaxSize())
                    }
                }
                Column(Modifier.weight(1f)) {
                    ElyText(ref.title, size = 11.5f, weight = FontWeight.Bold, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ElyText(ref.subtitle, size = 9.5f, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(skin.a1.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    ElyText("$minutes min", size = 10f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ElyText(
                "Σ ${items.sumOf { it.second }} min",
                size = 10.5f,
                weight = FontWeight.SemiBold,
                color = P.ink2,
                modifier = Modifier.weight(1f),
            )
            AccentButton(stringResource(R.string.widget_play), onClick = { vm.openByKey(items.first().first.key) }, fontSize = 11.5f)
        }
    }
}

/** Un arco: sus juegos en orden, con los pasos hechos apagados. */
@Composable
private fun ArcStrip(vm: ElyndraViewModel, arc: MashaAttachment.ArcCard) {
    val refs = arc.keys.mapNotNull { vm.masha.gameRef(it) }
    if (refs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ElyText("${arc.title} · ${(arc.done + 1).coerceAtMost(arc.total)}/${arc.total}", size = 11f, weight = FontWeight.Bold, color = P.ink, maxLines = 1)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            refs.forEachIndexed { i, ref ->
                Box(Modifier.alpha(if (i < arc.done) 0.4f else 1f)) {
                    GameThumb(ref) { vm.showDetails(ref.key) }
                }
            }
        }
    }
}

/**
 * El juego al que se refiere Masha, enganchado bajo su mensaje: carátula (o
 * icono, si no tiene) y su nombre bien claro, en vez de dejarlo solo en el
 * texto. Tocarlo abre su ficha.
 */
@Composable
private fun GameMentionCard(game: MashaGameRef, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.38f))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            GameIcon(game.artPath, game.packageName, Modifier.fillMaxSize(), ContentScale.Crop)
        }
        Column(Modifier.weight(1f)) {
            ElyText(game.title, size = 11.5f, weight = FontWeight.Bold, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ElyText(game.subtitle, size = 9.5f, weight = FontWeight.Medium, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlayGlyph(color = P.ink2, size = 8.dp)
    }
}

@Composable
private fun SuggestionChip(label: String, index: Int, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .animFadeUp(450, delayMs = 200 + index * 60, key = label)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.65f))
            .border(1.dp, skin.a1.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        ElyText(label, size = 11f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 1)
    }
}

/** Flecha "↑" del botón de enviar. */
@Composable
private fun SendArrow() {
    Box(
        Modifier.size(18.dp).drawBehind {
            val t = 2.dp.toPx()
            val cx = size.width / 2f
            drawLine(Color.White, Offset(cx, size.height * 0.82f), Offset(cx, size.height * 0.18f), t, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(cx, size.height * 0.18f), Offset(cx - size.width * 0.26f, size.height * 0.44f), t, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(cx, size.height * 0.18f), Offset(cx + size.width * 0.26f, size.height * 0.44f), t, cap = StrokeCap.Round)
        },
    )
}

/** Cuadrado de "parar", mientras Masha contesta. */
@Composable
private fun StopGlyph() {
    Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
}

/** "+" de conversación nueva. */
@Composable
private fun NewChatGlyph() {
    Box(
        Modifier.size(14.dp).drawBehind {
            val t = 1.8.dp.toPx()
            drawLine(P.ink, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), t, cap = StrokeCap.Round)
            drawLine(P.ink, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), t, cap = StrokeCap.Round)
        },
    )
}
