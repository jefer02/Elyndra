package com.elyndra.launcher.ui.screens

import com.elyndra.launcher.ui.components.SwitchRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.collectAsState
import com.elyndra.launcher.ui.masha.voice.VoicePack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.elyndra.launcher.ui.theme.shapeClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.elyndra.launcher.ElyndraApplication
import com.elyndra.launcher.domain.notify.LucyNotice
import com.elyndra.launcher.notify.LucyNoticeDebug
import com.elyndra.launcher.notify.LucyNotifier
import com.elyndra.launcher.data.P
import com.elyndra.launcher.domain.relationship.AffinityLevel
import com.elyndra.launcher.ui.masha.AffinityBar
import com.elyndra.launcher.ui.masha.HeartGlyph
import com.elyndra.launcher.ui.masha.labelRes
import com.elyndra.launcher.ui.masha.progressLine
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.SettingsController
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.SettingsGroup
import com.elyndra.launcher.ui.components.GlassTextField
import com.elyndra.launcher.ui.components.SecretToggle
import com.elyndra.launcher.ui.components.GlowingSwitch
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.LocalSkin

/* ── Columna de Masha: IA, clave, presencia, tiempo exacto y privacidad ── */

@Composable
internal fun MashaColumn(vm: ElyndraViewModel) {
    val s = vm.settings
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.settings_masha))

        // IA en línea y su clave.
        SettingsGroup(padding = 0.dp) {
            Column(Modifier.padding(vertical = 12.dp)) {
                ToggleRow(
                    title = stringResource(R.string.settings_masha_ai),
                    desc = stringResource(R.string.settings_masha_ai_desc),
                    checked = s.mashaOnline,
                    onToggle = s::toggleMashaOnline,
                )
                Spacer(Modifier.height(10.dp))
                KeyField(vm)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.mashaTest == SettingsController.MashaTest.Checking) {
                        ArcSpinner(size = 18.dp)
                    } else {
                        GhostButton(stringResource(R.string.settings_masha_test), s::testMasha)
                    }
                    Spacer(Modifier.width(10.dp))
                    ElyText(
                        text = when (val t = s.mashaTest) {
                            SettingsController.MashaTest.Idle -> when {
                                !s.mashaHasKey -> stringResource(R.string.settings_masha_status_none)
                                s.mashaBuiltInKey -> stringResource(R.string.settings_masha_key_builtin)
                                else -> ""
                            }
                            SettingsController.MashaTest.Checking -> stringResource(R.string.settings_masha_status_checking)
                            is SettingsController.MashaTest.Ok ->
                                t.balance?.let { stringResource(R.string.settings_masha_status_ok, it) }
                                    ?: stringResource(R.string.settings_masha_status_ok_plain)
                            is SettingsController.MashaTest.Failed -> t.message.resolve()
                        },
                        size = 9.5f,
                        weight = FontWeight.Medium,
                        color = if (s.mashaTest is SettingsController.MashaTest.Failed) P.red else P.ink2,
                        lineHeightRatio = 1.4f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Voz: la natural (descargable) o la del sistema, cuál de las cinco y a qué velocidad.
        VoiceGroup(vm)

        // Presencia: la línea sobre el carrusel y los avisos.
        SettingsGroup(padding = 0.dp) {
            Column(Modifier.padding(vertical = 12.dp)) {
                ToggleRow(
                    title = stringResource(R.string.settings_masha_ambient),
                    desc = null,
                    checked = s.mashaAmbient,
                    onToggle = s::toggleMashaAmbient,
                )
                Spacer(Modifier.height(12.dp))
                LucyNotificationsRow(vm)
            }
        }

        // La estela de partículas del botón de Masha: su color.
        ParticleColorGroup(vm)

        // Tiempo de juego exacto (acceso de uso, opcional).
        SettingsGroup(padding = 0.dp) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    ElyText(stringResource(R.string.settings_masha_usage), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
                    Spacer(Modifier.height(4.dp))
                    ElyText(stringResource(R.string.settings_masha_usage_desc), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
                }
                Spacer(Modifier.width(12.dp))
                if (s.usageAccess) {
                    ElyText(stringResource(R.string.settings_masha_usage_on), size = 10f, weight = FontWeight.SemiBold, color = P.success, uppercase = true)
                } else {
                    GhostButton(stringResource(R.string.settings_masha_usage_off), s::openUsageAccess)
                }
            }
        }

        // El vínculo con Lucy: nivel, corazón y lo que falta para el siguiente.
        SettingsGroup {
            val progress = vm.masha.affinityProgress
            val accent = LocalSkin.current.a2
            ElyText(stringResource(R.string.settings_lucy_bond), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeartGlyph(fill = (progress.level.ordinal + 1) / AffinityLevel.entries.size.toFloat(), glow = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    ElyText(stringResource(progress.level.labelRes()), size = 12f, weight = FontWeight.Bold, color = P.ink)
                    ElyText(progressLine(progress), size = 10f, color = P.ink2)
                }
            }
            Spacer(Modifier.height(8.dp))
            AffinityBar(progress, accent, P.ink2.copy(alpha = 0.2f), Modifier.fillMaxWidth())
        }

        // Privacidad y borrado.
        SettingsGroup {
            ElyText(stringResource(R.string.settings_masha_privacy), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
            Spacer(Modifier.height(10.dp))
            GhostButton(stringResource(R.string.settings_lucy_forget_conversations), s::forgetLucyConversations)
            Spacer(Modifier.height(8.dp))
            GhostButton(stringResource(R.string.settings_masha_forget), s::forgetMasha)
        }
    }
}

/** La voz natural de Masha: descarga, elección de voz, velocidad y prueba. */
@Composable
private fun VoiceGroup(vm: ElyndraViewModel) {
    val s = vm.settings
    val skin = LocalSkin.current
    val pack by s.voicePack.collectAsState()
    val sample = stringResource(R.string.settings_masha_voice_sample)
    SettingsGroup(padding = 0.dp) {
        Column(Modifier.padding(vertical = 12.dp)) {
            ToggleRow(
                title = stringResource(R.string.settings_masha_voice_natural),
                desc = stringResource(R.string.settings_masha_voice_natural_desc),
                checked = s.mashaVoiceNatural,
                onToggle = s::toggleMashaVoiceNatural,
            )
            Spacer(Modifier.height(10.dp))
            // El modelo: descargar, progreso, instalado o error.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val mb = (VoicePack.TOTAL_BYTES / 1_000_000).toInt()
                val status: String = when (val p = pack) {
                    VoicePack.State.Missing -> stringResource(R.string.settings_masha_voice_missing, mb)
                    is VoicePack.State.Downloading -> stringResource(R.string.settings_masha_voice_downloading, (p.done * 100 / p.total.coerceAtLeast(1)).toInt())
                    VoicePack.State.Installed -> when {
                        !s.voiceHardwareOk -> stringResource(R.string.settings_masha_voice_unsupported)
                        !s.mashaVoiceNatural -> stringResource(R.string.settings_masha_voice_off)
                        s.voiceTooSlow -> stringResource(R.string.settings_masha_voice_slow)
                        else -> stringResource(R.string.settings_masha_voice_installed)
                    }
                    is VoicePack.State.Failed -> stringResource(R.string.settings_masha_voice_failed, p.reason)
                }
                ElyText(
                    status,
                    size = 10f,
                    color = if (pack is VoicePack.State.Failed) P.red else P.ink2,
                    lineHeightRatio = 1.4f,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                when (pack) {
                    VoicePack.State.Missing, is VoicePack.State.Failed ->
                        if (s.voiceHardwareOk) GhostButton(stringResource(R.string.settings_masha_voice_download), s::downloadVoice)
                    is VoicePack.State.Downloading -> GhostButton(stringResource(R.string.cancel), s::cancelVoiceDownload)
                    VoicePack.State.Installed ->
                        Row {
                            if (s.voiceTooSlow && s.mashaVoiceNatural) {
                                GhostButton(stringResource(R.string.settings_masha_voice_retry), s::retryVoiceSpeed)
                                Spacer(Modifier.width(6.dp))
                            }
                            GhostButton(stringResource(R.string.settings_masha_voice_delete), s::deleteVoice)
                        }
                }
            }
            if (pack == VoicePack.State.Installed && s.voiceHardwareOk && !s.voiceTooSlow) {
                Spacer(Modifier.height(12.dp))
                ElyText(stringResource(R.string.settings_masha_voice_choice), size = 9.5f, weight = FontWeight.SemiBold, color = P.ink2, uppercase = true)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sid in 0..4) {
                        val on = sid == s.mashaVoiceSpeaker
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) skin.a2.copy(alpha = 0.25f) else P.chip)
                                .border(1.dp, if (on) skin.a2 else P.ink.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                                .shapeClickable(RoundedCornerShape(10.dp), onClickLabel = stringResource(R.string.settings_masha_voice_n, sid + 1)) {
                                    s.updateMashaVoiceSpeaker(sid)
                                    s.previewVoice(sample)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            ElyText("${sid + 1}", size = 11f, weight = FontWeight.Bold, color = if (on) skin.a2 else P.ink)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ElyText(stringResource(R.string.settings_masha_voice_speed), size = 9.5f, weight = FontWeight.SemiBold, color = P.ink2, uppercase = true, modifier = Modifier.weight(1f))
                ElyText("${Math.round(s.mashaVoiceRate * 100)} %", size = 10f, color = P.ink2)
            }
            Slider(
                value = s.mashaVoiceRate,
                onValueChange = s::updateMashaVoiceRate,
                valueRange = 0.8f..1.25f,
                colors = SliderDefaults.colors(thumbColor = skin.a2, activeTrackColor = skin.a2, inactiveTrackColor = P.ink.copy(alpha = 0.15f)),
            )
            if (pack == VoicePack.State.Installed && s.voiceHardwareOk && !s.voiceTooSlow) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    GhostButton(stringResource(R.string.settings_masha_voice_test), { s.previewVoice(sample) })
                }
            }
            Spacer(Modifier.height(8.dp))
            ElyText(stringResource(R.string.settings_masha_voice_notice), size = 9f, color = P.ink2, lineHeightRatio = 1.45f)
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                title = stringResource(R.string.settings_lucy_voice_interrupt),
                desc = stringResource(R.string.settings_lucy_voice_interrupt_desc),
                checked = s.lucyVoiceInterrupt,
                onToggle = s::toggleLucyVoiceInterrupt,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, desc: String?, checked: Boolean, onToggle: () -> Unit) {
    SwitchRow(title, desc, checked, onToggle)
}

/** Clave propia de DeepSeek (opcional): oculta, con opción de mostrarla, como las de los servicios. */
@Composable
private fun KeyField(vm: ElyndraViewModel) {
    val s = vm.settings
    var reveal by remember { mutableStateOf(false) }
    GlassTextField(
        value = s.mashaKey,
        onValueChange = s::updateMashaKey,
        label = stringResource(R.string.settings_masha_key),
        placeholder = stringResource(R.string.settings_masha_key_hint),
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        trailing = if (s.mashaKey.isNotEmpty()) {
            { SecretToggle(reveal) { reveal = !reveal } }
        } else {
            null
        },
    )
}

/**
 * Los avisos de Lucy. El permiso de Android 13+ no se pide al arrancar la app:
 * se pide aquí, al encenderlos. Si se deniega, el interruptor se queda apagado
 * y no se insiste (los avisos simplemente no salen).
 *
 * En builds de depuración, debajo, un botón por tipo de aviso para lanzarlo a
 * mano (ver LucyNoticeDebug; en release no existe).
 */
@Composable
private fun LucyNotificationsRow(vm: ElyndraViewModel) {
    val s = vm.settings
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(LucyNotifier.permitted(context)) }
    var pendingDebug by remember { mutableStateOf<LucyNotice?>(null) }
    val notices = (context.applicationContext as ElyndraApplication).notices

    fun simulate(kind: LucyNotice) {
        val verdict = LucyNoticeDebug.simulate(notices, kind)
        Toast.makeText(context, "${kind.name}: ${verdict.name}", Toast.LENGTH_SHORT).show()
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        if (granted) s.changeMashaNudges(true)
        pendingDebug?.let { if (granted) simulate(it) }
        pendingDebug = null
    }
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permitted

    ToggleRow(
        title = stringResource(R.string.settings_masha_nudges),
        desc = stringResource(R.string.settings_masha_nudges_desc),
        checked = s.mashaNudges && !needsPermission,
        onToggle = {
            when {
                s.mashaNudges && !needsPermission -> s.changeMashaNudges(false)
                needsPermission -> ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> s.changeMashaNudges(true)
            }
        },
    )

    LucyNoticeDebug.label?.let { label ->
        Spacer(Modifier.height(12.dp))
        ElyText(label, size = 10f, color = P.ink2)
        Spacer(Modifier.height(6.dp))
        // De dos en dos: cuatro botones seguidos no caben a lo ancho de un móvil.
        LucyNotice.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                pair.forEach { kind ->
                    GhostButton(kind.name, {
                        if (needsPermission) {
                            pendingDebug = kind
                            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            simulate(kind)
                        }
                    })
                }
            }
        }
    }
}
