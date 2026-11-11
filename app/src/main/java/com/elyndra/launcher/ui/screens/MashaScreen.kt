package com.elyndra.launcher.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.elyndra.launcher.input.HoldOrTap
import com.elyndra.launcher.ui.InputController
import com.elyndra.launcher.ui.masha.MashaMood
import com.elyndra.launcher.ui.masha.lipsync.RouteKind
import com.elyndra.launcher.ui.masha.live.LiveMic
import com.elyndra.launcher.ui.masha.live.LiveTurns
import com.elyndra.launcher.ui.masha.live.LiveVoice
import com.elyndra.launcher.ui.theme.focusRing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.elyndra.launcher.ui.components.edgeBleed
import com.elyndra.launcher.ui.components.safeAreaPadding
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.PadHint
import android.Manifest
import android.content.pm.PackageManager
import com.elyndra.launcher.BuildConfig
import com.elyndra.launcher.ui.masha.MashaDebugSay
import com.elyndra.launcher.ui.masha.AffinityHeart
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.heightIn
import com.elyndra.launcher.ui.masha.HoloCards
import com.elyndra.launcher.ui.masha.SubtitleLine
import com.elyndra.launcher.ui.masha.captionMs
import com.elyndra.launcher.ui.masha.AffinityPanel
import com.elyndra.launcher.ui.masha.FloatingGain
import com.elyndra.launcher.ui.masha.LevelUpCard
import com.elyndra.launcher.ui.LocalUiSounds
import com.elyndra.launcher.sound.UiSound
import com.elyndra.launcher.input.Pad
import androidx.compose.ui.BiasAlignment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.DisposableEffect
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
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.masha.MashaFraming
import com.elyndra.launcher.ui.theme.shapeClickable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.elyndra.launcher.ui.components.padTextField
import com.elyndra.launcher.ui.components.rememberPadField
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.elyndra.launcher.R
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.MashaQuietsSounds
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.UiText
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GlowingSwitch
import com.elyndra.launcher.ui.components.inputStyle
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.touchTarget
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.masha.Holo
import com.elyndra.launcher.ui.masha.HoloAtmosphere
import com.elyndra.launcher.ui.masha.HoloBacklight
import com.elyndra.launcher.ui.masha.MashaBacklight
import com.elyndra.launcher.ui.masha.HoloFallback
import com.elyndra.launcher.ui.masha.HoloMessage
import com.elyndra.launcher.ui.masha.HoloWorking
import com.elyndra.launcher.ui.masha.MashaEars
import com.elyndra.launcher.ui.masha.MashaPresence
import com.elyndra.launcher.ui.masha.MashaQuality
import com.elyndra.launcher.ui.masha.MashaShot
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
    // Mientras Masha habla o escucha, la interfaz no suena.
    MashaQuietsSounds(vm, presence)
    val quality = remember { MashaQuality.detect(context) }
    var stage by remember { mutableStateOf(StageStatus.Loading) }
    // Al entrar, cuerpo entero (en vertical y en horizontal); el botón de
    // encuadre acerca al primer plano y vuelve (ver MashaFraming.shotFor). Se
    // recuerda solo mientras se está en la pantalla; al girar, se reencuadra el
    // mismo plano para la nueva orientación.
    var alternate by remember { mutableStateOf(false) }
    var recenter by remember { mutableIntStateOf(0) }
    // La cámara la lleva el plano (true) o el usuario la orbita (false): la luz de fondo solo cuadra con el plano.
    var framed by remember { mutableStateOf(true) }
    val shot = MashaFraming.shotFor(m.landscape, alternate)
    // El hueco que deja libre la interfaz para su cara (coordenadas de la raíz).
    val area = remember { FaceAreaProbe() }
    // El chat a la vista (se recuerda; por defecto oculto: se la ve a ella, con subtítulos).
    val chatOpen = settings.lucyChatVisible
    // Con el chat oculto, el campo de texto se abre con el botón del teclado (o Select).
    var typing by remember { mutableStateOf(false) }
    var soundOpen by remember { mutableStateOf(false) }
    // El panel "Lucy y tú" (el corazón de la cabecera o L1).
    var bondOpen by remember { mutableStateOf(false) }

    // ── voz, oído y ambiente ──
    val voice = rememberMashaVoice(presence, lang, settings.mashaVoice)
    rememberAmbientSoundscape(
        enabled = settings.mashaSoundscape,
        level = settings.mashaSoundscapeVolume / 100f,
        ducked = presence.speaking || presence.listening,
    )
    // La conversación en vivo se crea abajo; los oídos y el micrófono le pasan lo que oyen.
    val liveRef = remember { arrayOfNulls<LiveVoice>(1) }
    // Pulsar para hablar (mantener el micrófono o Y): una frase que se manda sola.
    var pushToTalk by remember { mutableStateOf(false) }
    val ears = rememberMashaEars(
        presence,
        onPartial = { text ->
            val live = liveRef[0]
            if (live != null && live.active) live.onPartial(text) else masha.updateDraft(text)
        },
        onFinal = { text ->
            pushToTalk = false
            val live = liveRef[0]
            if (live != null && live.active) {
                live.onHeard(text)
            } else {
                masha.updateDraft(text)
                masha.send(lang)
            }
        },
        onError = { failure ->
            pushToTalk = false
            val live = liveRef[0]
            val inLive = live != null && live.active
            live?.takeIf { inLive }?.onEarsFailure(failure)
            // En vivo, una escucha en silencio no es un error que contar: ya lo cuentan los turnos.
            val quiet = failure == MashaEars.Failure.StreamUnsupported ||
                inLive && (failure == MashaEars.Failure.NoMatch || failure == MashaEars.Failure.Busy)
            if (!quiet) {
                val res = when (failure) {
                    MashaEars.Failure.Unavailable -> R.string.masha_mic_unavailable
                    MashaEars.Failure.Permission -> R.string.masha_mic_denied
                    MashaEars.Failure.Network -> R.string.masha_mic_network
                    MashaEars.Failure.NoMatch, MashaEars.Failure.Busy, MashaEars.Failure.StreamUnsupported -> R.string.masha_mic_no_match
                }
                vm.showToast(UiText.res(res))
            }
        },
    )
    val scope = rememberCoroutineScope()
    // El micrófono siempre abierto de la conversación en vivo: oye al usuario sin confundirlo con la voz de ella.
    val liveMic = remember(voice) {
        LiveMic(
            context,
            reference = voice::soundingLevelDb,
            onSignal = { liveRef[0]?.onSignal(it) },
            onLevel = { level -> presence.micLevel = level },
        )
    }
    val live = remember(ears, voice, liveMic) {
        LiveVoice(
            ears = ears,
            voice = voice,
            presence = presence,
            masha = masha,
            mic = liveMic,
            scope = scope,
            language = { vm.settings.lang },
            // Con auriculares su voz no vuelve al micrófono: hablar encima la corta siempre.
            voiceBargeIn = { vm.settings.lucyVoiceInterrupt || voice.audioRoute.kind != RouteKind.Speaker },
            onEnded = { reason -> if (reason == LiveTurns.EndReason.Silence) vm.showToast(UiText.res(R.string.lucy_live_ended)) },
        ).also { liveRef[0] = it }
    }
    DisposableEffect(live) { onDispose { live.release() } }
    // La app al fondo o la pantalla apagada (ON_STOP): se suelta el micrófono (en vivo o al pulsar).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, live) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                live.background()
                ears.stop()
                pushToTalk = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // El micrófono solo se pide la primera vez que se toca; denegado, el chat sigue por escrito.
    var pendingMic by remember { mutableStateOf<MicAction?>(null) }
    fun runMic(action: MicAction) {
        when (action) {
            MicAction.Live -> live.toggle()
            MicAction.Talk -> {
                pushToTalk = true
                voice.stop()
                ears.start(vm.settings.lang)
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingMic
        pendingMic = null
        if (granted && action != null) runMic(action) else if (!granted) vm.showToast(UiText.res(R.string.masha_mic_denied))
    }
    fun withMic(action: MicAction) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runMic(action)
        } else {
            pendingMic = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    /** Tocar: la conversación en vivo, encendida o apagada. */
    fun tapMic() = if (live.active) live.stop() else withMic(MicAction.Live)
    /** Mantener: en vivo interrumpe a Lucy y escucha ya; si no, una frase. */
    fun holdMic() = if (live.active) live.bargeIn() else withMic(MicAction.Talk)
    /** Soltar: se cierra la frase (lo dicho se manda solo). */
    fun releaseMic() {
        if (pushToTalk) ears.finish()
    }
    val mic = MicActions(
        live = live.active,
        liveState = live.state,
        systemStt = ears.processedBySystem && (live.active || presence.listening),
        onTap = ::tapMic,
        onHoldStart = ::holdMic,
        onHoldEnd = ::releaseMic,
    )
    // Y en el mando: tocar = en vivo; mantener = hablar (ver InputController.mashaMic).
    val holdOrTap = remember { HoldOrTap() }
    val tapMicNow by rememberUpdatedState(::tapMic)
    val holdMicNow by rememberUpdatedState(::holdMic)
    val releaseMicNow by rememberUpdatedState(::releaseMic)
    DisposableEffect(holdOrTap) {
        fun onGesture(g: HoldOrTap.Gesture) = when (g) {
            HoldOrTap.Gesture.Tap -> tapMicNow()
            HoldOrTap.Gesture.HoldStart -> holdMicNow()
            HoldOrTap.Gesture.HoldEnd -> releaseMicNow()
        }
        vm.input.mashaMic = object : InputController.MicHook {
            override fun press() {
                holdOrTap.press(SystemClock.uptimeMillis())
                scope.launch {
                    delay(holdOrTap.holdMs)
                    holdOrTap.tick(SystemClock.uptimeMillis())?.let(::onGesture)
                }
            }

            override fun release() {
                holdOrTap.release()?.let(::onGesture)
            }
        }
        onDispose {
            vm.input.mashaMic = null
            holdOrTap.cancel()
        }
    }

    // ── conversación ──
    val greeting = stringResource(R.string.masha_greeting)
    LaunchedEffect(greeting) { masha.onOpen(greeting, lang) }
    // Al salir, la conversación se da por terminada y se resume (para retomarla al volver).
    DisposableEffect(Unit) { onDispose { masha.onClose() } }
    // Lo que ya estaba en pantalla al entrar no se vuelve a leer en voz alta.
    val alreadyShown = remember { masha.messages.lastOrNull()?.id }
    val last = masha.messages.lastOrNull()

    SideEffect {
        presence.thinking = masha.busy && (last == null || !last.fromMasha || (last.text.isEmpty() && last.attachment == null))
        // En vivo, el ánimo dice de quién es el turno: curiosa al escuchar, pensativa al pensar.
        presence.mood = when (live.state) {
            LiveTurns.State.Waiting, LiveTurns.State.UserSpeaking, LiveTurns.State.Held -> MashaMood.Curious
            LiveTurns.State.Thinking -> MashaMood.Thinking
            else -> masha.mood
        }
    }
    // Una pregunta nueva corta lo que estuviera diciendo.
    LaunchedEffect(masha.busy) { if (masha.busy) voice.stop() }
    // Habla mientras llega el texto: frase a frase. En vivo habla siempre (aunque la voz esté apagada).
    val speakReplies = settings.mashaVoice || live.active
    LaunchedEffect(last?.id, last?.text?.length, last?.pending, speakReplies) {
        if (last == null || !last.fromMasha || last.restored || last.id == alreadyShown || !speakReplies) return@LaunchedEffect
        voice.feed(last.id, last.text, final = !last.pending)
    }
    // Lo que se lee con el chat oculto: la frase que suena (subtítulo) o, sin voz, la respuesta un rato.
    var caption by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(last?.id, last?.pending, speakReplies) {
        caption = null
        if (speakReplies || last == null || !last.fromMasha || last.pending || last.restored || last.id == alreadyShown) return@LaunchedEffect
        val text = last.text.trim().takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        caption = text
        delay(captionMs(text))
        caption = null
    }
    val subtitle = when {
        chatOpen -> null
        speakReplies -> voice.nowSaying?.takeIf { settings.lucySubtitles }
        else -> caption
    }
    // Solo en debug: `adb shell am broadcast` hace hablar a Masha con un texto fijo (QA del lip-sync).
    if (BuildConfig.DEBUG) MashaDebugSay(voice)
    // Gestos: saluda al materializarse; explica cuando trae una tarjeta.
    LaunchedEffect(stage) { if (stage == StageStatus.Ready) presence.play(MashaPresence.Cue.Wave) }
    // Sube la relación de nivel: un gesto contento (el corazón late en la cabecera). Al bajar, nada.
    LaunchedEffect(masha.levelUps) { if (masha.levelUps > 0) presence.play(MashaPresence.Cue.Happy) }
    // Cada subida de puntos: el "+N" flotante y un toque suave. Las bajadas no suenan ni se ven.
    val uiSounds = LocalUiSounds.current
    LaunchedEffect(masha.gainSeq) { if (masha.gainSeq > 0) uiSounds?.play(UiSound.ToggleOn) }
    LaunchedEffect(masha.celebration) { if (masha.celebration != null) uiSounds?.play(UiSound.Open) }
    // Mando: atajos solo con la pantalla libre (sin teclado abierto; diálogos y hojas van antes en InputController).
    val padHandler by rememberUpdatedState { pad: Pad ->
        when {
            masha.celebration != null && (pad == Pad.Back || pad == Pad.Confirm) -> { masha.dismissCelebration(); true }
            bondOpen && (pad == Pad.Back || pad == Pad.PagePrev) -> { bondOpen = false; true }
            vm.input.imeVisible -> false
            pad == Pad.PagePrev -> { bondOpen = true; true }
            pad == Pad.Details -> { settings.toggleLucyChat(); true }
            pad == Pad.Search -> { typing = true; true }
            else -> false
        }
    }
    DisposableEffect(Unit) {
        vm.input.mashaPad = { padHandler(it) }
        onDispose { vm.input.mashaPad = null }
    }
    LaunchedEffect(last?.id, last?.pending) {
        if (last != null && last.fromMasha && !last.pending && last.attachment != null && last.id != alreadyShown) {
            presence.play(MashaPresence.Cue.Explain)
        }
    }

    val glow = Color(presence.visibleMood.glow)

    // El escenario 3D, la luz, la atmósfera y el velo llegan al filo de la pantalla
    // (bajo el recorte de la cámara); la barra, la conversación y los paneles vuelven
    // al área segura con safeAreaPadding (una vez, aquí).
    Box(Modifier.fillMaxSize().edgeBleed().background(Holo.bg)) {
        if (stage == StageStatus.Failed) {
            // En horizontal, en su columna (la de la izquierda): a pantalla
            // completa con 180 dp abajo, en 360 dp de alto el núcleo de 230 dp
            // subía hasta la barra de arriba y quedaba detrás de la conversación.
            HoloFallback(
                presence,
                if (m.landscape) Modifier.fillMaxHeight().fillMaxWidth(0.5f).padding(top = LANDSCAPE_BAR)
                else Modifier.fillMaxSize().padding(bottom = 180.dp),
            )
        } else {
            MashaStage(
                presence,
                quality,
                shot,
                Modifier.fillMaxSize().onGloballyPositioned { area.stage = it.boundsInRoot() },
                recenter = recenter,
                faceArea = area.faceArea(m.landscape),
                onFramed = { framed = it },
            ) { stage = it }
            // La luz de fondo que la separa del azul oscuro de la sala (ver MashaBacklight).
            HoloBacklight(
                halo = { area.halo(shot, m.landscape) },
                presence = presence,
                visible = stage == StageStatus.Ready && framed,
                lite = quality == MashaQuality.Lite,
            )
        }
        HoloAtmosphere(presence)
        // Oscurece abajo para que el panel se lea sobre el holograma. En
        // horizontal, solo detrás de la conversación (con un fundido de 32 dp a
        // su izquierda): a todo el ancho apagaba sus piernas y la base, y ella
        // quedaba en la zona más oscura de la pantalla.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawBehind {
                    drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Holo.bg.copy(alpha = 0.88f)))
                    val chatLeft = area.chatLeft()
                    if (m.landscape && chatLeft != null) {
                        val fade = 32.dp.toPx()
                        drawRect(
                            Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = chatLeft - fade, endX = chatLeft),
                            blendMode = BlendMode.DstIn,
                        )
                    }
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

        Column(Modifier.fillMaxSize().safeAreaPadding().imePadding()) {
            TopBar(
                vm = vm,
                presence = presence,
                glow = glow,
                pad = m.pad,
                landscape = m.landscape,
                chatOpen = chatOpen,
                onToggleChat = settings::toggleLucyChat,
                onSound = { soundOpen = !soundOpen },
                onRecenter = {
                    alternate = !alternate
                    recenter++
                },
                onNewChat = {
                    voice.stop()
                    masha.clearConversation(greeting)
                },
                onBond = { bondOpen = true },
            )
            if (m.landscape) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // A la izquierda queda ella; la conversación, a la derecha.
                    Spacer(Modifier.weight(0.5f).fillMaxHeight().onGloballyPositioned { area.side = it.boundsInRoot() })
                    Column(Modifier.weight(0.5f).fillMaxHeight().padding(end = m.pad)) {
                        Conversation(vm, presence, glow, chatOpen, landscape = true, mic = mic, area = area, subtitle = subtitle, typing = typing, onTyping = { typing = it })
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = m.pad)) {
                    Conversation(vm, presence, glow, chatOpen, landscape = false, mic = mic, area = area, subtitle = subtitle, typing = typing, onTyping = { typing = it })
                }
            }
        }

        AnimatedVisibility(
            soundOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).safeAreaPadding().padding(top = 64.dp, end = m.pad),
        ) {
            SoundPanel(vm, glow)
        }

        // El "+N" junto a ella (a la altura del pecho en el plano entero).
        FloatingGain(
            masha.gainSeq,
            masha.lastGain,
            glow,
            Modifier.align(if (m.landscape) BiasAlignment(-0.5f, -0.1f) else BiasAlignment(0.35f, -0.35f)),
        )
        // "Lucy y tú": sobre un velo; tocar fuera lo cierra.
        AnimatedVisibility(bondOpen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(Holo.bg.copy(alpha = 0.55f)).shapeClickable(RoundedCornerShape(0.dp), onClick = { bondOpen = false }),
                contentAlignment = Alignment.Center,
            ) {
                AffinityPanel(
                    masha.affinityProgress,
                    masha.recentGains,
                    glow,
                    onClose = { bondOpen = false },
                    modifier = Modifier.safeAreaPadding().padding(m.pad),
                )
            }
        }
        // La primera vez en un nivel: su tarjeta (una sola vez por nivel).
        val celebration = masha.celebration
        AnimatedVisibility(celebration != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            if (celebration != null) LevelUpCard(celebration, glow, onDismiss = masha::dismissCelebration, modifier = Modifier.padding(m.pad))
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
    onBond: () -> Unit,
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
            ElyText("LUCY", size = 17f, weight = FontWeight.Bold, color = Holo.text, letterSpacing = tracking(0.32f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(glow))
                Spacer(Modifier.width(6.dp))
                ElyText(
                    status,
                    modifier = Modifier.weight(1f, fill = false),
                    size = 9.5f,
                    weight = FontWeight.Medium,
                    color = Holo.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = tracking(0.06f),
                )
                Spacer(Modifier.width(10.dp))
                AffinityHeart(masha.affinityLevel, masha.levelUps, glow, Holo.dim, onClick = onBond)
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
    mic: MicActions,
    area: FaceAreaProbe,
    subtitle: String?,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
) {
    val masha = vm.masha
    val lang = vm.settings.lang
    // Con el chat oculto, ni las cifras: toda la pantalla es para ella.
    Box(Modifier.onGloballyPositioned { area.statsBottom = it.boundsInRoot().bottom }) { if (open) StatsRow(vm, glow) }
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
    } else {
        // Chat oculto: su subtítulo y, si la última respuesta trae tarjetas, solo las tarjetas.
        SubtitleLine(subtitle, maxLines = if (presence.speaking) 1 else 3)
        val cards = masha.messages.lastOrNull()?.takeIf { it.fromMasha && !it.greeting }
        if (cards != null) {
            Box(Modifier.fillMaxWidth().heightIn(max = if (landscape) 220.dp else 300.dp).padding(top = 8.dp)) {
                Box(Modifier.verticalScroll(rememberScrollState())) { HoloCards(vm, cards, glow) }
            }
        }
        if (masha.busy && masha.working != null) {
            Spacer(Modifier.height(8.dp))
            HoloWorking(masha.working?.resolve(), glow)
        }
    }
    Spacer(Modifier.height(8.dp))
    Box(Modifier.onGloballyPositioned { area.chipsTop = it.boundsInRoot().top }) {
        Suggestions(vm, glow) { masha.sendSuggestion(it, lang) }
    }
    Spacer(Modifier.height(8.dp))
    Dock(vm, presence, glow, mic, compact = !open && !typing, typing = typing, onTyping = onTyping)
    LiveStrip(mic, glow)
    PadHints(hints = MASHA_HINTS, visible = vm.input.gamepadPresent, modifier = Modifier.padding(top = 6.dp), onDark = true)
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
                // Con la letra a 1,3 y media pantalla de ancho (horizontal) no caben enteros: "…", no un corte a media letra.
                ElyText(label, size = 7.5f, weight = FontWeight.SemiBold, color = Holo.dim, letterSpacing = tracking(0.18f), maxLines = 1, overflow = TextOverflow.Ellipsis, uppercase = true)
                ElyText(value, size = 14f, weight = FontWeight.Bold, color = Holo.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    // La fila se desliza: el canto que tiene más detrás se apaga en 28 dp, para
    // que la sugerencia cortada se lea como "hay más" y no como un recorte (la
    // tercera, "Quiero seguir con algo", parecía rota en el canto de la columna).
    val scroll = rememberScrollState()
    val fade = 28.dp
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val f = fade.toPx()
                if (scroll.canScrollForward) {
                    drawRect(Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - f, endX = size.width), blendMode = BlendMode.DstIn)
                }
                if (scroll.canScrollBackward) {
                    drawRect(Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = f), blendMode = BlendMode.DstIn)
                }
            }
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
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
        // La última, al final del todo, sale entera de la zona que se apaga.
        Spacer(Modifier.width(fade - 7.dp))
    }
}

@Composable
private fun Dock(
    vm: ElyndraViewModel,
    presence: MashaPresence,
    glow: Color,
    mic: MicActions,
    compact: Boolean,
    typing: Boolean,
    onTyping: (Boolean) -> Unit,
) {
    val masha = vm.masha
    val lang = vm.settings.lang
    // Con el chat oculto se habla con la voz: el micrófono manda y escribir queda a un toque.
    if (compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            MicButton(presence, glow, mic)
            HoloIcon(stringResource(R.string.lucy_keyboard), glow, onClick = { onTyping(true) }) { KeyboardGlyph() }
            Spacer(Modifier.weight(1f))
            if (masha.busy) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(glow, P.primary)))
                        .shapeClickable(RoundedCornerShape(16.dp)) { masha.stop() },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(Color.White)) }
            }
        }
        return
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Abierto con el botón del teclado: el foco y el teclado, directamente.
    LaunchedEffect(typing) {
        if (typing) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    fun send() {
        masha.send(lang)
        onTyping(false)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        MicButton(presence, glow, mic)
        Box(
            Modifier
                .weight(1f)
                .height(46.dp)
                .holoPanel(RoundedCornerShape(16.dp), glow)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Lo del teclado y el mando, como todos los campos (ver padTextField).
            val field = rememberPadField()
            BasicTextField(
                value = masha.draft,
                onValueChange = masha::updateDraft,
                singleLine = true,
                readOnly = field.readOnly(vm.input.active),
                textStyle = inputStyle(12.5f, Holo.text),
                cursorBrush = SolidColor(glow),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).padTextField(field),
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
                .background(Brush.linearGradient(listOf(glow, P.primary)))
                .shapeClickable(RoundedCornerShape(16.dp)) { if (masha.busy) masha.stop() else send() },
            contentAlignment = Alignment.Center,
        ) {
            if (masha.busy) Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(Color.White)) else SendArrow()
        }
    }
}

/** Lo que hace el micrófono de Lucy (botón grande, o Y en el mando). */
private enum class MicAction { Live, Talk }

private class MicActions(
    /** La conversación en vivo está encendida. */
    val live: Boolean,
    /** De quién es el turno en vivo (para el indicador "escuchando"). */
    val liveState: LiveTurns.State,
    /** Se está oyendo con el servicio del sistema (el audio puede salir del móvil): se avisa. */
    val systemStt: Boolean,
    val onTap: () -> Unit,
    val onHoldStart: () -> Unit,
    val onHoldEnd: () -> Unit,
)

/**
 * Micrófono: tocar enciende o apaga la conversación en vivo; mantener es
 * pulsar para hablar (al soltar se manda). El aro sigue la voz; en vivo, un
 * segundo aro lo marca.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MicButton(presence: MashaPresence, glow: Color, mic: MicActions) {
    val listening = presence.listening
    val label = stringResource(if (mic.live) R.string.lucy_mic_live_stop else R.string.lucy_mic_live_start)
    val holdLabel = stringResource(R.string.lucy_mic_hold)
    val interaction = remember { MutableInteractionSource() }
    var held by remember { mutableStateOf(false) }
    val onHoldEnd by rememberUpdatedState(mic.onHoldEnd)
    // Soltar tras mantener: la pulsación larga no avisa al soltar, la interacción sí.
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            if ((it is PressInteraction.Release || it is PressInteraction.Cancel) && held) {
                held = false
                onHoldEnd()
            }
        }
    }
    Box(
        Modifier
            .size(54.dp)
            .semantics { contentDescription = label }
            .drawBehind {
                if (mic.live) drawCircle(glow.copy(alpha = 0.55f), size.minDimension / 2, style = Stroke(2.dp.toPx()))
            }
            .padding(4.dp)
            .clip(CircleShape)
            .background(if (listening || mic.live) glow.copy(alpha = 0.3f) else Holo.panel.copy(alpha = 0.75f))
            .border(1.5.dp, glow.copy(alpha = if (listening || mic.live) 1f else 0.5f), CircleShape)
            .drawBehind {
                if (listening) {
                    val r = size.minDimension / 2 * (0.7f + 0.3f * presence.micLevel)
                    drawCircle(glow.copy(alpha = 0.35f), r)
                }
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = focusRing(CircleShape),
                onClickLabel = label,
                onLongClickLabel = holdLabel,
                onLongClick = {
                    held = true
                    mic.onHoldStart()
                },
                onClick = mic.onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MicGlyph()
    }
}

/** Bajo el dock, en vivo: la marca "EN VIVO" y, si el audio lo procesa el sistema, el aviso. */
@Composable
private fun LiveStrip(mic: MicActions, glow: Color) {
    AnimatedVisibility(mic.live || mic.systemStt, enter = fadeIn(), exit = fadeOut()) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mic.live) {
                // El micrófono está abierto toda la sesión: el punto late siempre, y el texto dice de quién es el turno.
                val pulse = rememberInfiniteTransition(label = "live")
                val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "liveDot")
                Box(Modifier.size(7.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(glow))
                Spacer(Modifier.width(5.dp))
                ElyText(stringResource(R.string.lucy_live), size = 8.5f, weight = FontWeight.Bold, color = glow, uppercase = true, letterSpacing = tracking(0.2f))
                Spacer(Modifier.width(6.dp))
                ElyText(stringResource(liveStateLabel(mic.liveState)), size = 8.5f, color = Holo.text)
                Spacer(Modifier.width(10.dp))
            }
            if (mic.systemStt) {
                ElyText(stringResource(R.string.lucy_live_system_stt), size = 8.5f, color = Holo.dim, maxLines = 2, lineHeightRatio = 1.3f)
            }
        }
    }
}

private fun liveStateLabel(state: LiveTurns.State): Int = when (state) {
    LiveTurns.State.Speaking -> R.string.lucy_live_talking
    LiveTurns.State.Thinking -> R.string.lucy_live_thinking
    LiveTurns.State.UserSpeaking, LiveTurns.State.Held -> R.string.lucy_live_hearing
    LiveTurns.State.Waiting, LiveTurns.State.Idle -> R.string.lucy_live_listening
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
    // Se ve de 38 dp pero se toca en 48 (como las píldoras de las barras): con 38 y 8 entre medias se fallaba el vecino.
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .touchTarget(Modifier.semantics { contentDescription = description }.clickable(interaction, indication = null, onClick = onClick))
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Holo.panel.copy(alpha = 0.6f))
            .border(1.dp, glow.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .indication(interaction, LocalIndication.current),
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
/** Un teclado: rectángulo con dos filas de teclas y la barra espaciadora. */
@Composable
private fun KeyboardGlyph() {
    Box(
        Modifier
            .size(18.dp)
            .drawBehind {
                val w = size.width
                val h = size.height
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(w * 0.04f, h * 0.2f),
                    size = androidx.compose.ui.geometry.Size(w * 0.92f, h * 0.6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = Stroke(1.4.dp.toPx()),
                )
                val key = androidx.compose.ui.geometry.Size(w * 0.07f, w * 0.07f)
                for (row in 0..1) for (col in 0..4) {
                    drawRect(Color.White, topLeft = Offset(w * (0.16f + col * 0.15f), h * (0.32f + row * 0.14f)), size = key)
                }
                drawRect(Color.White, topLeft = Offset(w * 0.3f, h * 0.62f), size = androidx.compose.ui.geometry.Size(w * 0.4f, w * 0.07f))
            },
    )
}

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

/**
 * Dónde queda libre la pantalla para la cara de Masha: en vertical, entre las
 * tarjetas de arriba y las sugerencias; en horizontal, la mitad izquierda.
 * Las medidas llegan de la maquetación (coordenadas de la raíz) y se leen en
 * estado, así el escenario se vuelve a encuadrar si cambian (teclado, giro).
 */
private class FaceAreaProbe {
    var stage by mutableStateOf(Rect.Zero)
    var statsBottom by mutableFloatStateOf(0f)
    var chipsTop by mutableFloatStateOf(0f)
    var side by mutableStateOf(Rect.Zero)

    /** El hueco en coordenadas del escenario; null mientras no se ha medido. */
    fun faceArea(landscape: Boolean): Rect? {
        val s = stage
        if (s.width <= 0f) return null
        val r = if (landscape) {
            side.takeIf { it.width > 0f && it.height > 0f }
        } else {
            if (chipsTop > statsBottom && statsBottom > 0f) Rect(s.left, statsBottom, s.right, chipsTop) else null
        } ?: return null
        return Rect(r.left - s.left, r.top - s.top, r.right - s.left, r.bottom - s.top)
    }

    /** La luz de fondo para [shot] en su hueco (coordenadas del escenario); null mientras no se ha medido. */
    fun halo(shot: MashaShot, landscape: Boolean): MashaBacklight.Halo? {
        val a = faceArea(landscape) ?: return null
        return MashaBacklight.place(stage.width, stage.height, a.left, a.top, a.right, a.bottom, shot, landscape)
    }

    /** En horizontal, dónde empieza la conversación (el canto derecho de su columna), en coordenadas del escenario. */
    fun chatLeft(): Float? = side.takeIf { it.width > 0f }?.let { it.right - stage.left }
}

/** Lo que mide la barra de arriba en horizontal (8 + 38 + 8 dp). */
private val LANDSCAPE_BAR = 54.dp

private val MASHA_HINTS = listOf(
    PadHint("A", R.string.hint_select),
    PadHint("Y", R.string.hint_talk),
    PadHint("X", R.string.hint_chat),
    PadHint("L1", R.string.hint_bond),
    PadHint("B", R.string.hint_back),
)
