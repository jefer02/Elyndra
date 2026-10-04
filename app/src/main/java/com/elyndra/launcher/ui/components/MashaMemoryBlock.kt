package com.elyndra.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.MatchMethod
import com.elyndra.launcher.data.P
import com.elyndra.launcher.domain.profile.GameProfile
import com.elyndra.launcher.domain.profile.PlayState
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.daysAgoText
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.screens.serviceName
import com.elyndra.launcher.ui.theme.LocalSkin

/**
 * "Masha recuerda": el perfil vivo del juego en la ficha — la última sesión y
 * con qué emulador, cuánto suelen durar, si el emulador va bien o se sale al
 * minuto, y cómo se identificó el juego. Es lo mismo que Masha usa para
 * hablar de él, contado en tres o cuatro líneas.
 *
 * En el cristal de la ficha: pieza con el radio de las demás, filo del color
 * del juego ([accent]) a la izquierda y el avatar de Masha en su aro.
 * [modifier] va por fuera (la ficha lo usa para el foco de lectura); sin
 * recuerdos no se pinta nada, ni el hueco.
 */
@Composable
fun MashaMemoryBlock(vm: ElyndraViewModel, key: String, meta: GameMeta, modifier: Modifier = Modifier, accent: Color? = null) {
    val tint = accent ?: LocalSkin.current.a2
    var profile by remember(key) { mutableStateOf<GameProfile?>(null) }
    LaunchedEffect(key, vm.library) {
        profile = runCatching { vm.brain.knowledge.snapshot().profiles[key] }.getOrNull()
    }
    val p = profile ?: return
    val lines = memoryLines(vm, p, meta)
    if (lines.isEmpty()) return

    val shape = RoundedCornerShape(TILE_RADIUS)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tileFill())
            .border(1.dp, P.hairline.copy(alpha = 0.8f), shape)
            .drawBehind { drawRect(tint, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 15.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
    ) {
        Image(
            painterResource(R.drawable.masha),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(30.dp)
                .border(1.5.dp, tint.copy(alpha = 0.7f), CircleShape)
                .padding(2.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ElyText(
                stringResource(R.string.masha_profile_title),
                size = 9f,
                weight = FontWeight.Bold,
                color = tint,
                letterSpacing = tracking(0.18f),
                uppercase = true,
            )
            lines.forEach { ElyText(it, size = 10.5f, color = P.ink, lineHeightRatio = 1.45f) }
        }
    }
}

@Composable
private fun memoryLines(vm: ElyndraViewModel, p: GameProfile, meta: GameMeta): List<String> {
    val lines = ArrayList<String>()
    val now = System.currentTimeMillis()
    val last = p.lastSession
    if (p.state == PlayState.New) {
        lines += stringResource(R.string.masha_profile_never)
    } else if (last != null) {
        val whenText = daysAgoText(((now - last.start) / DAY_MS).toInt()).resolve()
        val emulator = last.emulatorId
        lines += if (emulator != null) {
            stringResource(R.string.masha_profile_last, last.minutes, vm.emulatorName(emulator), whenText)
        } else {
            stringResource(R.string.masha_profile_last_app, last.minutes, whenText)
        }
    }
    if (p.sessions >= 2 && p.medianMinutes > 0) lines += stringResource(R.string.masha_profile_typical, p.medianMinutes, p.sessions)
    p.emulators.firstOrNull()?.let { u ->
        val name = vm.emulatorName(u.emulatorId)
        when {
            u.goodSessions >= 2 && u.earlyExits == 0 && u.failedLaunches == 0 -> lines += stringResource(R.string.masha_profile_emulator_good, name)
            u.goodSessions == 0 && u.earlyExits + u.failedLaunches >= 2 -> lines += stringResource(R.string.masha_profile_emulator_bad, name)
        }
    }
    meta.matchedBy?.let { method ->
        val how = stringResource(
            when (method) {
                MatchMethod.HASH -> R.string.masha_match_hash
                MatchMethod.PACKAGE -> R.string.masha_match_package
                MatchMethod.MANUAL -> R.string.masha_match_manual
                MatchMethod.FUZZY -> R.string.masha_match_fuzzy
                else -> R.string.masha_match_name
            },
        )
        val sources = meta.sources.mapNotNull { id -> Service.entries.firstOrNull { it.id == id }?.let(::serviceName) }
        if (sources.isNotEmpty()) lines += stringResource(R.string.masha_profile_matched, how, sources.joinToString(" · "))
    }
    return lines
}

private const val DAY_MS = 24L * 60 * 60 * 1000
