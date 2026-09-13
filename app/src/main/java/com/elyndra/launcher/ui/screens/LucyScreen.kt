package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.ui.ChatMessage
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.TypingDots
import com.elyndra.launcher.ui.components.inputStyle
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
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
fun LucyScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val skin = LocalSkin.current
    val lucy = vm.lucy
    val scroll = rememberScrollState()
    val lang = vm.settings.lang
    val greeting = stringResource(R.string.lucy_greeting)
    val stats = lucy.stats()
    val noData = stringResource(R.string.lucy_no_data)
    val suggestions = listOf(
        stringResource(R.string.lucy_suggest_week),
        stringResource(R.string.lucy_suggest_fact),
        stringResource(R.string.lucy_suggest_next),
    )
    val cards = listOf(
        Triple(
            stringResource(R.string.lucy_stat_week),
            fmtMinutes(stats.weekMinutes),
            if (stats.weekSessions == 0) noData
            else stringResource(R.string.lucy_vs_previous, (if (stats.deltaMinutes >= 0) "+" else "−") + fmtMinutes(abs(stats.deltaMinutes))),
        ),
        Triple(
            stringResource(R.string.lucy_stat_top),
            if (stats.topTitle != null) fmtMinutes(stats.topMinutes) else "—",
            stats.topTitle ?: noData,
        ),
        Triple(
            stringResource(R.string.lucy_stat_sessions),
            stats.weekSessions.toString(),
            if (stats.weekSessions == 0) noData else stringResource(R.string.lucy_average, fmtMinutes(stats.averageMinutes)),
        ),
    )

    LaunchedEffect(greeting) { lucy.ensureGreeting(greeting) }

    // El chat se mantiene abajo cuando llega un mensaje o Lucy empieza a escribir.
    LaunchedEffect(lucy.messages.size, lucy.typing) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Box(Modifier.fillMaxSize().animRiseSheet(key = Screen.Lucy)) {
        AuroraBackdrop()

        Column(Modifier.fillMaxSize().imePadding()) {

            // ── CABECERA ──
            Row(
                Modifier
                    .padding(start = m.pad, end = m.pad, top = if (m.landscape) 10.dp else 14.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .animFadeUp(450, key = Screen.Lucy)
                    .glass(RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(onClick = { vm.go(Screen.Library) }) { BackChevron() }
                Spacer(Modifier.width(11.dp))
                LucyAvatar()
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        ElyText("Lucy", size = 19f, weight = FontWeight.Bold, color = P.ink, letterSpacing = tracking(-0.01f))
                        Spacer(Modifier.width(8.dp))
                        ElyText(
                            stringResource(R.string.lucy_subtitle),
                            size = 7.5f,
                            weight = FontWeight.SemiBold,
                            color = P.ink2.copy(alpha = 0.8f),
                            letterSpacing = tracking(0.2f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            uppercase = true,
                        )
                    }
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
                                .background(if (lucy.online) P.green else skin.a2),
                        )
                        Spacer(Modifier.width(6.dp))
                        ElyText(
                            stringResource(if (lucy.online) R.string.lucy_online else R.string.lucy_demo),
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
                Equalizer(active = lucy.typing)
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
                    lucy.messages.forEachIndexed { i, msg ->
                        MessageBubble(msg, i, m.landscape)
                    }
                    if (lucy.typing) {
                        Box(
                            Modifier
                                .animMsgIn(350, key = "typing")
                                .glass(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) { TypingDots() }
                    }
                }

                WrapRow(gap = 7.dp) {
                    suggestions.forEachIndexed { i, q ->
                        SuggestionChip(q, i) { lucy.sendSuggestion(q, lang) }
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
                        value = lucy.draft,
                        onValueChange = lucy::updateDraft,
                        singleLine = true,
                        textStyle = inputStyle(12.5f),
                        cursorBrush = SolidColor(skin.a2),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { lucy.send(lang) }),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (lucy.draft.isEmpty()) {
                                ElyText(
                                    stringResource(R.string.lucy_placeholder),
                                    size = 12.5f,
                                    color = P.ink2.copy(alpha = 0.55f),
                                    maxLines = 1,
                                )
                            }
                            inner()
                        },
                    )
                }

                Box(
                    Modifier
                        .size(46.dp)
                        .shadow(12.dp, RoundedCornerShape(16.dp), clip = false, ambientColor = P.shade.copy(alpha = 0.24f), spotColor = P.shade.copy(alpha = 0.24f))
                        .clip(RoundedCornerShape(16.dp))
                        .drawBehind { drawRect(accentGradient(skin, 145f, size)) }
                        .clickable { lucy.send(lang) },
                    contentAlignment = Alignment.Center,
                ) {
                    SendArrow()
                }
            }
        }
    }
}

/* ── Piezas ───────────────────────────────────────────────────── */

/** Avatar con halo cónico girando y aro que late. */
@Composable
private fun LucyAvatar() {
    val skin = LocalSkin.current
    val angle = auraAngle()
    val ring = ringProgress(2800)
    val sweep = remember(skin.a1, skin.a2) {
        Brush.sweepGradient(listOf(skin.a1, skin.a2, P.green, skin.a1))
    }

    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        // `luciaAura`: conic-gradient con blur(6px) — aquí, el mismo barrido difuminado por la escala.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angle; alpha = 0.75f }
                .clip(CircleShape)
                .background(sweep)
                .alpha(0.75f),
        )
        // `luciaRing`
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
        // `luciaAvatar`: el logo de Lucy, sobre el degradado por si trae alfa.
        Box(
            Modifier
                .size(40.dp)
                .shadow(8.dp, RoundedCornerShape(15.dp), clip = false, ambientColor = P.shade.copy(alpha = 0.3f), spotColor = P.shade.copy(alpha = 0.3f))
                .clip(RoundedCornerShape(15.dp))
                .drawBehind { drawRect(accentGradient(skin, 145f, size)) },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.lucy),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Ecualizador de cinco barras; se apaga al 45 % cuando Lucy no escribe. */
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
        // `rule`: la línea de acento de 3dp en el borde superior.
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
private fun MessageBubble(msg: ChatMessage, index: Int, landscape: Boolean) {
    val skin = LocalSkin.current
    val delay = minOf(index, 4) * 70

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromLucy) Arrangement.Start else Arrangement.End,
    ) {
        if (msg.fromLucy) {
            Box(
                Modifier
                    .fillMaxWidth(if (landscape) 0.60f else 0.86f)
                    .animMsgIn(delayMs = delay, key = msg.text)
                    .glass(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                    .drawBehind {
                        // `border-left: 2px solid a1`
                        drawRect(
                            color = skin.a1,
                            size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height),
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                ElyText(msg.text, size = 12f, color = P.ink, lineHeightRatio = 1.6f)
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth(if (landscape) 0.56f else 0.82f)
                    .animMsgIn(delayMs = delay, key = msg.text)
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

@Composable
private fun SuggestionChip(label: String, index: Int, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .animFadeUp(450, delayMs = 200 + index * 80, key = label)
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
            drawLine(Color.White, Offset(cx, size.height * 0.82f), Offset(cx, size.height * 0.18f), t, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color.White, Offset(cx, size.height * 0.18f), Offset(cx - size.width * 0.26f, size.height * 0.44f), t, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color.White, Offset(cx, size.height * 0.18f), Offset(cx + size.width * 0.26f, size.height * 0.44f), t, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        },
    )
}
