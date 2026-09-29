package com.elyndra.launcher.ui.components

import androidx.compose.foundation.Image
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
import com.elyndra.launcher.ui.theme.glass

/**
 * "Masha recuerda": el perfil vivo del juego en la ficha — la última sesión y
 * con qué emulador, cuánto suelen durar, si el emulador va bien o se sale al
 * minuto, y cómo se identificó el juego. Es lo mismo que Masha usa para
 * hablar de él, contado en tres o cuatro líneas.
 */
@Composable
fun MashaMemoryBlock(vm: ElyndraViewModel, key: String, meta: GameMeta) {
    val skin = LocalSkin.current
    var profile by remember(key) { mutableStateOf<GameProfile?>(null) }
    LaunchedEffect(key, vm.library) {
        profile = runCatching { vm.brain.knowledge.snapshot().profiles[key] }.getOrNull()
    }
    val p = profile ?: return
    val lines = memoryLines(vm, p, meta)
    if (lines.isEmpty()) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .glass(RoundedCornerShape(14.dp))
            .drawBehind { drawRect(skin.a1, size = Size(2.dp.toPx(), size.height)) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.masha),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(18.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(7.dp))
            ElyText(stringResource(R.string.masha_profile_title), size = 9f, weight = FontWeight.SemiBold, color = skin.a2, uppercase = true)
        }
        lines.forEach { ElyText(it, size = 10.5f, color = P.ink, lineHeightRatio = 1.45f) }
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
