package com.elyndra.launcher.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import com.elyndra.launcher.BuildConfig
import com.elyndra.launcher.ui.masha.MashaDebugSay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.elyndra.launcher.R
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.UiText
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GlowingSwitch
import com.elyndra.launcher.ui.components.inputStyle
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.masha.Holo
import com.elyndra.launcher.ui.masha.HoloAtmosphere
import com.elyndra.launcher.ui.masha.HoloFallback
import com.elyndra.launcher.ui.masha.HoloMessage
import com.elyndra.launcher.ui.masha.HoloWorking
import com.elyndra.launcher.ui.masha.MashaEars
import com.elyndra.launcher.ui.masha.MashaPresence
import com.elyndra.launcher.ui.masha.MashaQuality
import com.elyndra.launcher.ui.masha.MashaStage
import com.elyndra.launcher.ui.masha.StageStatus
import com.elyndra.launcher.ui.masha.holoPanel
import com.elyndra.launcher.ui.masha.rememberAmbientSoundscape
import com.elyndra.launcher.ui.masha.rememberMashaEars
import com.elyndra.launcher.ui.masha.rememberMashaVoice
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.animFadeUp
import kotlin.math.abs

/**
 * Masha: el holotanque.
 *
 * Ella en 3D en el centro (SceneView/Filament), la atmósfera encima, y la
 * conversación en un panel holográfico que se puede ocultar para verla
 * entera. Habla con voz (TTS) mientras llega la respuesta y escucha con el
 * micrófono (STT); el ambiente sonoro del núcleo suena debajo y se aparta
 * cuando ella habla o escucha.
 *
 * Todo lo que sabe hacer sigue viniendo del mismo sitio: [MashaController]
 * (DeepSeek con sus herramientas, o las respuestas sin conexión), la misma
 * memoria y los mismos datos de la biblioteca.
 */
@Composable
fun MashaScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val masha = vm.masha
    val settings = vm.settings
    val lang = settings.lang
    val context = LocalContext.current

    val presence = remember { MashaPresence() }
    val quality = remember { MashaQuality.detect(context) }
    var stage by remember { mutableStateOf(StageStatus.Loading) }
    var recenter by remember { mutableIntStateOf(0) }
    var chatOpen by rememberSaveable { mutableStateOf(true) }
    var soundOpen by remember { mutableStateOf(false) }

    // ── voz, oído y ambiente ──
    val voice = rememberMashaVoice(presence, lang, settings.mashaVoice)
    rememberAmbientSoundscape(
        enabled = settings.mashaSoundscape,
        level = settings.mashaSoundscapeVolume / 100f,
        ducked = presence.speaking || presence.listening,
    )
    val ears = rememberMashaEars(
        presence,
        onPartial = masha::updateDraft,
        onFinal = { text ->
            masha.updateDraft(text)
            masha.send(lang)
        },
        onError = { failure ->
            val res = when (failure) {
                MashaEars.Failure.Unavailable -> R.string.masha_mic_unavailable
                MashaEars.Failure.Permission -> R.string.masha_mic_denied
                MashaEars.Failure.Network -> R.string.masha_mic_network
                MashaEars.Failure.NoMatch, MashaEars.Failure.Busy -> R.string.masha_mic_no_match
            }
            vm.showToast(UiText.res(res))
        },
    )
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            voice.stop()
            ears.start(lang)
        } else {
            vm.showToast(UiText.res(R.string.masha_mic_denied))
        }
    }
    fun toggleMic() {
        when {
            presence.listening -> ears.finish()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                voice.stop()
                ears.start(lang)
            }
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ── conversación ──
    val greeting = stringResource(R.string.masha_greeting)
    LaunchedEffect(greeting) { masha.onOpen(greeting) }
    // Lo que ya estaba en pantalla al entrar no se vuelve a leer en voz alta.
    val alreadyShown = remember { masha.messages.lastOrNull()?.id }
    val last = masha.messages.lastOrNull()

    SideEffect {
        presence.thinking = masha.busy && (last == null || !last.fromMasha || (last.text.isEmpty() && last.attachment == null))
        presence.mood = masha.mood
    }
    // Una pregunta nueva corta lo que estuviera diciendo.
    LaunchedEffect(masha.busy) { if (masha.busy) voice.stop() }
    // Habla mientras llega el texto: frase a frase.
    LaunchedEffect(last?.id, last?.text?.length, last?.pending, settings.mashaVoice) {
        if (last == null || !last.fromMasha || last.restored || last.id == alreadyShown || !settings.mashaVoice) return@LaunchedEffect
        voice.feed(last.id, last.text, final = !last.pending)
    }
    // Solo en debug: `adb shell am broadcast` hace hablar a Masha con un texto fijo (QA del lip-sync).
    if (BuildConfig.DEBUG) MashaDebugSay(voice)
    // Gestos: saluda al materializarse; explica cuando trae una tarjeta.
    LaunchedEffect(stage) { if (stage == StageStatus.Ready) presence.play(MashaPresence.Cue.Wave) }
    LaunchedEffect(last?.id, last?.pending) {
        if (last != null && last.fromMasha && !last.pending && last.attachment != null && last.id != alreadyShown) {
            presence.play(MashaPresence.Cue.Explain)
        }
    }

    val glow = Color(presence.visibleMood.glow)

    Box(Modifier.fillMaxSize().background(Holo.bg)) {
        if (stage == StageStatus.Failed) {
            HoloFallback(presence, Modifier.fillMaxSize().padding(bottom = 180.dp))
        } else {
            MashaStage(presence, quality, recenter, m.landscape, Modifier.fillMaxSize()) { stage = it }
        }
        HoloAtmosphere(presence)
        // Oscurece abajo para que el panel se lea sobre el holograma.
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Holo.bg.copy(alpha = 0.88f)))
            },
        )
        AnimatedVisibility(stage == StageStatus.Loading, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            ElyText(
                stringResource(R.string.masha_loading),
                size = 11f,
                weight = FontWeight.SemiBold,
                color = Holo.line,
                letterSpacing = tracking(0.2f),
                uppercase = true,
            )
        }
        if (stage == StageStatus.Failed) {
            ElyText(
                stringResource(R.string.masha_stage_fallback),
                modifier = Modifier.align(Alignment.Center).padding(top = 170.dp, start = 32.dp, end = 32.dp),
                size = 10f,
                color = Holo.dim,
            )
        }

        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(
                vm = vm,
                presence = presence,
                glow = glow,
                pad = m.pad,
                landscape = m.landscape,
                chatOpen = chatOpen,
                onToggleChat = { chatOpen = !chatOpen },
                onSound = { soundOpen = !soundOpen },
                onRecenter = { recenter++ },
                onNewChat = {
                    voice.stop()
                    masha.clearConversation(greeting)
                },
            )
            if (m.landscape) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // A la izquierda queda ella; la conversación, a la derecha.
                    Spacer(Modifier.weight(0.5f))
                    Column(Modifier.weight(0.5f).fillMaxHeight().padding(end = m.pad)) {
                        Conversation(vm, presence, glow, chatOpen, landscape = true, onMic = ::toggleMic)
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = m.pad)) {
                    Conversation(vm, presence, glow, chatOpen, landscape = false, onMic = ::toggleMic)
                }
            }
        }

        AnimatedVisibility(
            soundOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = m.pad),
        ) {
            SoundPanel(vm, glow)
        }
    }
}

/* ── cabecera ─────────────────────────────────────────────────── */

@Composable
private fun TopBar(
    vm: ElyndraViewModel,
    presence: MashaPresence,
    glow: Color,
    pad: Dp,
    landscape: Boolean,
    chatOpen: Boolean,
    onToggleChat: () -> Unit,
    onSound: () -> Unit,
    onRecenter: () -> Unit,
    onNewChat: () -> Unit,
) {
    val masha = vm.masha
    val status = when {
        presence.listening -> stringResource(R.string.masha_status_listening)
        presence.speaking -> stringResource(R.string.masha_status_speaking)
        presence.thinking -> masha.working?.resolve() ?: stringResource(R.string.masha_status_thinking)
        vm.brain.canGoOnline() -> stringResource(R.string.masha_status_online)
        else -> stringResource(R.string.masha_status_offline)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = pad, end = pad, top = if (landscape) 8.dp else 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HoloIcon(stringResource(R.string.masha_back), glow, onClick = { vm.go(Screen.Library) }) { Box(Modifier.size(16.dp)) { BackChevron(color = Color.White) } }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ElyText("MASHA", size = 17f, weight = FontWeight.Bold, color = Holo.text, letterSpacing = tracking(0.32f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(glow))
                Spacer(Modifier.width(6.dp))
                ElyText(status, size = 9.5f, weight = FontWeight.Medium, color = Holo.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = tracking(0.06f))
            }
        }
        HoloIcon(stringResource(if (chatOpen) R.string.masha_hide_chat else R.string.masha_show_chat), glow, onClick = onToggleChat) { ChatGlyph(chatOpen) }
        Spacer(Modifier.width(8.dp))
        HoloIcon(stringResource(R.string.masha_sound), glow, onClick = onSound) { SpeakerGlyph(vm.settings.mashaSoundscape) }
        Spacer(Modifier.width(8.dp))
        HoloIcon(stringResource(R.string.masha_recenter), glow, onClick = onRecenter) { RecenterGlyph() }
        Spacer(Modifier.width(8.dp))
        HoloIcon(stringResource(R.string.masha_new_chat), glow, onClick = onNewChat) { PlusGlyph() }
    }
}

/* ── conversación y dock ──────────────────────────────────────── */

@Composable
private fun ColumnScope.Conversation(
    vm: ElyndraViewModel,
    presence: MashaPresence,
    glow: Color,
    open: Boolean,
    landscape: Boolean,
    onMic: () -> Unit,
) {
    val masha = vm.masha
    val lang = vm.settings.lang
    StatsRow(vm, glow)
    // En vertical, el hueco de arriba es para verla a ella; en horizontal ella
    // está a la izquierda y la conversación ocupa toda su columna.
    if (!landscape || !open) Spacer(Modifier.weight(1f))
    if (open) {
        Box(
            Modifier
                .fillMaxWidth()
                // Peso de verdad (no `fill = false`, que perdía el hueco sobrante y
                // lo dejaba bajo el dock); el contenido se pega abajo.
                .then(if (landscape) Modifier.weight(1f).padding(top = 8.dp) else Modifier.weight(1.2f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val scroll = rememberScrollState()
            val last = masha.messages.lastOrNull()
            LaunchedEffect(masha.messages.size, last?.text?.length, last?.attachment, masha.working) {
                scroll.animateScrollTo(scroll.maxValue)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                masha.messages.forEach { msg -> HoloMessage(vm, msg, glow, landscape) }
                if (masha.busy && last?.let { it.pending && it.text.isNotEmpty() } != true) {
                    HoloWorking(masha.working?.resolve(), glow)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Suggestions(vm, glow) { masha.sendSuggestion(it, lang) }
    Spacer(Modifier.height(8.dp))
    Dock(vm, presence, glow, onMic)
    Spacer(Modifier.height(if (landscape) 10.dp else 16.dp))
}

@Composable
private fun StatsRow(vm: ElyndraViewModel, glow: Color) {
    val stats = vm.masha.stats()
    val noData = stringResource(R.string.masha_no_data)
    val cards = listOf(
        Triple(
            stringResource(R.string.masha_stat_week),
            fmtMinutes(stats.weekMinutes),
            if (stats.weekSessions == 0) noData
            else stringResource(R.string.masha_vs_previous, (if (stats.deltaMinutes >= 0) "+" else "−") + fmtMinutes(abs(stats.deltaMinutes))),
        ),
        Triple(stringResource(R.string.masha_stat_top), if (stats.topTitle != null) fmtMinutes(stats.topMinutes) else "—", stats.topTitle ?: noData),
        Triple(
            stringResource(R.string.masha_stat_sessions),
            stats.weekSessions.toString(),
            if (stats.weekSessions == 0) noData else stringResource(R.string.masha_average, fmtMinutes(stats.averageMinutes)),
        ),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        cards.forEachIndexed { i, (label, value, sub) ->
            Column(
                Modifier
                    .weight(1f)
                    .animFadeUp(450, delayMs = 120 + i * 80, key = label)
                    .holoPanel(RoundedCornerShape(12.dp), glow, alpha = 0.55f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                ElyText(label, size = 7.5f, weight = FontWeight.SemiBold, color = Holo.dim, letterSpacing = tracking(0.18f), maxLines = 1, uppercase = true)
                ElyText(value, size = 14f, weight = FontWeight.Bold, color = Holo.text, maxLines = 1)
                ElyText(sub, size = 8.5f, color = Holo.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Suggestions(vm: ElyndraViewModel, glow: Color, onPick: (String) -> Unit) {
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
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        suggestions.forEach { q ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Holo.panel.copy(alpha = 0.6f))
                    .border(1.dp, glow.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .clickable(enabled = !vm.masha.busy) { onPick(q) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                ElyText(q, size = 10.5f, weight = FontWeight.SemiBold, color = Holo.text, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Dock(vm: ElyndraViewModel, presence: MashaPresence, glow: Color, onMic: () -> Unit) {
    val masha = vm.masha
    val lang = vm.settings.lang
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        MicButton(presence, glow, onMic)
        Box(
            Modifier
                .weight(1f)
                .height(46.dp)
                .holoPanel(RoundedCornerShape(16.dp), glow)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = masha.draft,
                onValueChange = masha::updateDraft,
                singleLine = true,
                textStyle = inputStyle(12.5f, Holo.text),
                cursorBrush = SolidColor(glow),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { masha.send(lang) }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (masha.draft.isEmpty()) {
                        ElyText(
                            stringResource(if (presence.listening) R.string.masha_listening_hint else R.string.masha_placeholder),
                            size = 12.5f,
                            color = Holo.dim.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                    inner()
                },
            )
        }
        // Mientras contesta, enviar pasa a ser parar.
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(glow, Color(0xFF3A5BFF))))
                .clickable { if (masha.busy) masha.stop() else masha.send(lang) },
            contentAlignment = Alignment.Center,
        ) {
            if (masha.busy) Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(Color.White)) else SendArrow()
        }
    }
}

/** Micrófono: un toque para hablar, otro para terminar. El aro sigue la voz. */
@Composable
private fun MicButton(presence: MashaPresence, glow: Color, onClick: () -> Unit) {
    val listening = presence.listening
    val label = stringResource(if (listening) R.string.masha_mic_stop else R.string.masha_mic)
    Box(
        Modifier
            .size(46.dp)
            .semantics { contentDescription = label }
            .clip(CircleShape)
            .background(if (listening) glow.copy(alpha = 0.3f) else Holo.panel.copy(alpha = 0.75f))
            .border(1.5.dp, glow.copy(alpha = if (listening) 1f else 0.5f), CircleShape)
            .drawBehind {
                if (listening) {
                    val r = size.minDimension / 2 * (0.7f + 0.3f * presence.micLevel)
                    drawCircle(glow.copy(alpha = 0.35f), r)
                }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MicGlyph()
    }
}

/* ── panel de sonido ──────────────────────────────────────────── */

@Composable
private fun SoundPanel(vm: ElyndraViewModel, glow: Color) {
    val s = vm.settings
    Column(
        Modifier
            .widthIn(max = 280.dp)
            .holoPanel(RoundedCornerShape(16.dp), glow, alpha = 0.9f)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ElyText(stringResource(R.string.masha_sound), size = 12f, weight = FontWeight.Bold, color = Holo.text, letterSpacing = tracking(0.1f))
        SwitchLine(stringResource(R.string.masha_sound_voice), s.mashaVoice, s::toggleMashaVoice)
        SwitchLine(stringResource(R.string.masha_sound_ambient), s.mashaSoundscape, s::toggleMashaSoundscape)
        Column {
            ElyText(stringResource(R.string.masha_sound_volume), size = 10f, color = Holo.dim)
            Slider(
                value = s.mashaSoundscapeVolume / 100f,
                onValueChange = { s.updateMashaSoundscapeVolume((it * 100).toInt()) },
                enabled = s.mashaSoundscape,
                colors = SliderDefaults.colors(thumbColor = glow, activeTrackColor = glow, inactiveTrackColor = Holo.dim.copy(alpha = 0.3f)),
            )
        }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ElyText(label, size = 11f, color = Holo.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        GlowingSwitch(checked, onToggle)
    }
}

/* ── piezas ───────────────────────────────────────────────────── */

@Composable
private fun HoloIcon(description: String, glow: Color, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(12.dp))
            .background(Holo.panel.copy(alpha = 0.6f))
            .border(1.dp, glow.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

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

@Composable
private fun MicGlyph() {
    Box(
        Modifier.size(18.dp).drawBehind {
            val t = 1.8.dp.toPx()
            val w = size.width
            val h = size.height
            drawRoundRect(
                Color.White,
                topLeft = Offset(w * 0.34f, h * 0.06f),
                size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.5f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.16f),
            )
            drawArc(Color.White, 0f, 180f, false, topLeft = Offset(w * 0.2f, h * 0.24f), size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.5f), style = Stroke(t, cap = StrokeCap.Round))
            drawLine(Color.White, Offset(w / 2, h * 0.74f), Offset(w / 2, h * 0.94f), t, cap = StrokeCap.Round)
        },
    )
}

@Composable
private fun SpeakerGlyph(on: Boolean) {
    Box(
        Modifier.size(16.dp).drawBehind {
            val t = 1.6.dp.toPx()
            val w = size.width
            val h = size.height
            val body = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.1f, h * 0.38f); lineTo(w * 0.3f, h * 0.38f); lineTo(w * 0.52f, h * 0.16f)
                lineTo(w * 0.52f, h * 0.84f); lineTo(w * 0.3f, h * 0.62f); lineTo(w * 0.1f, h * 0.62f); close()
            }
            drawPath(body, Color.White)
            if (on) {
                drawArc(Color.White, -45f, 90f, false, topLeft = Offset(w * 0.45f, h * 0.28f), size = androidx.compose.ui.geometry.Size(w * 0.3f, h * 0.44f), style = Stroke(t, cap = StrokeCap.Round))
                drawArc(Color.White, -45f, 90f, false, topLeft = Offset(w * 0.45f, h * 0.12f), size = androidx.compose.ui.geometry.Size(w * 0.5f, h * 0.76f), style = Stroke(t, cap = StrokeCap.Round))
            } else {
                drawLine(Color.White, Offset(w * 0.64f, h * 0.36f), Offset(w * 0.94f, h * 0.64f), t, cap = StrokeCap.Round)
                drawLine(Color.White, Offset(w * 0.94f, h * 0.36f), Offset(w * 0.64f, h * 0.64f), t, cap = StrokeCap.Round)
            }
        },
    )
}

@Composable
private fun RecenterGlyph() {
    Box(
        Modifier.size(16.dp).drawBehind {
            val t = 1.6.dp.toPx()
            val c = center
            drawCircle(Color.White, size.minDimension * 0.3f, c, style = Stroke(t))
            drawCircle(Color.White, size.minDimension * 0.08f, c)
            val r0 = size.minDimension * 0.36f
            val r1 = size.minDimension * 0.5f
            for ((dx, dy) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
                drawLine(Color.White, Offset(c.x + dx * r0, c.y + dy * r0), Offset(c.x + dx * r1, c.y + dy * r1), t, cap = StrokeCap.Round)
            }
        },
    )
}

@Composable
private fun PlusGlyph() {
    Box(
        Modifier.size(14.dp).drawBehind {
            val t = 1.8.dp.toPx()
            drawLine(Color.White, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), t, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), t, cap = StrokeCap.Round)
        },
    )
}

/** Bocadillo: lleno con la conversación a la vista, en contorno si está oculta. */
@Composable
private fun ChatGlyph(open: Boolean) {
    Box(
        Modifier
            .size(16.dp)
            .graphicsLayer { alpha = if (open) 1f else 0.8f }
            .drawBehind {
                val t = 1.6.dp.toPx()
                val w = size.width
                val h = size.height
                val cr = androidx.compose.ui.geometry.CornerRadius(w * 0.2f)
                val bubble = androidx.compose.ui.geometry.Size(w, h * 0.72f)
                if (open) drawRoundRect(Color.White, size = bubble, cornerRadius = cr)
                else drawRoundRect(Color.White, size = bubble, cornerRadius = cr, style = Stroke(t))
                drawLine(Color.White, Offset(w * 0.3f, h * 0.7f), Offset(w * 0.22f, h * 0.98f), t, cap = StrokeCap.Round)
            },
    )
}
