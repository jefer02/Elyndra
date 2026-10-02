package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.elyndra.launcher.ui.masha.MashaShot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.MashaQuietsSounds
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.components.AccentSlider
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.masha.Holo
import com.elyndra.launcher.ui.masha.HoloFallback
import com.elyndra.launcher.ui.masha.MashaPresence
import com.elyndra.launcher.ui.masha.MashaQuality
import com.elyndra.launcher.ui.masha.MashaStage
import com.elyndra.launcher.ui.masha.StageStatus
import com.elyndra.launcher.ui.masha.holoPanel
import com.elyndra.launcher.ui.masha.lipsync.AudioRoute
import com.elyndra.launcher.ui.masha.lipsync.AudioRouteOffsets
import com.elyndra.launcher.ui.masha.lipsync.RouteKind
import com.elyndra.launcher.ui.masha.rememberMashaVoice

/**
 * Opción de desarrollador: calibrar `audioOffsetMs` para la salida de audio
 * actual (altavoz, cable o cada auricular Bluetooth). Masha en el escenario
 * de siempre, arriba, para ver la boca; abajo, la salida detectada, el ajuste
 * (−150…+400 ms, pasos de 10) y "Probar", que le hace decir una frase llena
 * de P/B/M: los labios deben cerrarse justo en cada una.
 *
 * Cada cambio se guarda al momento para esa salida y la voz lo aplica en
 * vivo; si la salida cambia (se conectan unos auriculares), la pantalla pasa
 * a mostrar y editar el de la nueva.
 */
@Composable
fun VoiceSyncScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val s = vm.settings
    val context = LocalContext.current

    val presence = remember { MashaPresence() }
    // Mientras Masha habla o escucha, la interfaz no suena.
    MashaQuietsSounds(vm, presence, hold = true)
    val quality = remember { MashaQuality.detect(context) }
    var stage by remember { mutableStateOf(StageStatus.Loading) }
    val voice = rememberMashaVoice(presence, s.lang, enabled = true)

    var route by remember { mutableStateOf(voice.audioRoute) }
    DisposableEffect(voice) {
        voice.onRouteChanged = { route = it }
        route = voice.audioRoute
        onDispose { voice.onRouteChanged = null }
    }
    var offset by remember(route) { mutableIntStateOf(s.voiceOffset(route)) }
    var testId by remember { mutableLongStateOf(-1_000L) }
    val phrase = stringResource(R.string.voice_sync_phrase)

    fun apply(ms: Int) {
        offset = AudioRouteOffsets.clamp(ms)
        s.setVoiceOffset(route, offset)
        voice.refreshOffset()
    }

    fun reset() {
        s.resetVoiceOffset(route)
        offset = s.voiceOffset(route)
        voice.refreshOffset()
    }

    Box(Modifier.fillMaxSize().background(Holo.bg)) {
        if (stage == StageStatus.Failed) {
            HoloFallback(presence, Modifier.fillMaxSize().padding(bottom = 220.dp))
        } else {
            // Primer plano: los labios se ven para juzgar la sincronía.
            MashaStage(presence, quality, MashaShot.CloseUp, Modifier.fillMaxSize()) { stage = it }
        }

        Row(
            Modifier.align(Alignment.TopStart).padding(m.pad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(onClick = { vm.go(Screen.Settings) }, size = com.elyndra.launcher.ui.theme.MinTouch, cornerRadius = 15.dp, contentDescription = stringResource(R.string.hint_back)) { BackChevron() }
            Spacer(Modifier.width(11.dp))
            ElyText(stringResource(R.string.dev_voice_sync), size = 16f, weight = FontWeight.SemiBold, color = Holo.text)
        }

        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier
                .align(if (m.landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                .padding(m.pad)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .holoPanel(shape, Holo.line, alpha = 0.8f)
                .padding(14.dp),
        ) {
            ElyText(stringResource(R.string.voice_sync_route), size = 9.5f, weight = FontWeight.SemiBold, color = Holo.dim, uppercase = true)
            Spacer(Modifier.height(3.dp))
            ElyText(routeLabel(route), size = 12.5f, weight = FontWeight.SemiBold, color = Holo.text)

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ElyText(stringResource(R.string.voice_sync_offset), size = 11.5f, weight = FontWeight.Medium, color = Holo.text)
                Spacer(Modifier.weight(1f))
                ElyText(formatMs(offset), size = 12.5f, weight = FontWeight.Bold, color = Holo.line)
            }
            Spacer(Modifier.height(7.dp))
            val step = AudioRouteOffsets.STEP_MS
            AccentSlider(
                offset / step,
                AudioRouteOffsets.MIN_MS / step..AudioRouteOffsets.MAX_MS / step,
                { apply(it * step) },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("−$step ms", { apply(offset - step) })
                GhostButton("+$step ms", { apply(offset + step) })
                Spacer(Modifier.weight(1f))
                GhostButton(stringResource(R.string.voice_sync_reset), ::reset)
            }
            Spacer(Modifier.height(10.dp))
            ElyText(stringResource(R.string.voice_sync_hint), size = 9.5f, color = Holo.dim, lineHeightRatio = 1.45f)
            Spacer(Modifier.height(10.dp))
            GhostButton(
                stringResource(R.string.voice_sync_test),
                {
                    voice.stop()
                    voice.feed(testId--, phrase, final = true)
                },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun routeLabel(route: AudioRoute): String = when (route.kind) {
    RouteKind.Speaker -> stringResource(R.string.route_speaker)
    RouteKind.Wired -> stringResource(R.string.route_wired) + route.name.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    RouteKind.Bluetooth -> stringResource(R.string.route_bluetooth) +
        (route.name.ifBlank { route.address }).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    RouteKind.Other -> stringResource(R.string.route_other)
}

private fun formatMs(ms: Int): String = if (ms > 0) "+$ms ms" else "$ms ms"
