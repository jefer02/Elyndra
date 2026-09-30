package com.elyndra.launcher.ui.screens

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
import com.elyndra.launcher.data.P
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.SettingsController
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.SettingsGroup
import com.elyndra.launcher.ui.components.GlassTextField
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
                ToggleRow(
                    title = stringResource(R.string.settings_masha_nudges),
                    desc = stringResource(R.string.settings_masha_nudges_desc),
                    checked = s.mashaNudges,
                    onToggle = s::toggleMashaNudges,
                )
            }
        }

        // La estela de partículas del botón de Masha: su color.
        ParticleColorGroup(vm)

        // Las chispas de neón de la selección: encendidas o no, y su color.
        SelectionParticlesGroup(vm)

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

        // Privacidad y borrado.
        SettingsGroup {
            ElyText(stringResource(R.string.settings_masha_privacy), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
            Spacer(Modifier.height(10.dp))
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
        }
    }
}

@Composable
private fun ToggleRow(title: String, desc: String?, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ElyText(title, size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
            if (desc != null) {
                Spacer(Modifier.height(4.dp))
                ElyText(desc, size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
            }
        }
        Spacer(Modifier.width(12.dp))
        GlowingSwitch(checked, onToggle)
    }
}

/** Clave propia de DeepSeek (opcional): oculta, con opción de mostrarla, como las de los servicios. */
@Composable
private fun KeyField(vm: ElyndraViewModel) {
    val s = vm.settings
    val skin = LocalSkin.current
    var reveal by remember { mutableStateOf(false) }
    GlassTextField(
        value = s.mashaKey,
        onValueChange = s::updateMashaKey,
        label = stringResource(R.string.settings_masha_key),
        placeholder = stringResource(R.string.settings_masha_key_hint),
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        trailing = if (s.mashaKey.isNotEmpty()) {
            {
                ElyText(
                    stringResource(if (reveal) R.string.hide else R.string.show),
                    size = 8.5f,
                    weight = FontWeight.SemiBold,
                    color = skin.a2,
                    uppercase = true,
                    // Con recorte y aire: el aro del mando no se pega a las letras.
                    modifier = Modifier
                        .shapeClickable(RoundedCornerShape(6.dp)) { reveal = !reveal }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        } else {
            null
        },
    )
}

/* ── Prioridad de fuentes de metadatos ───────────────────────── */

/**
 * Dos listas ordenables —textos e imágenes— con flechas para subir y bajar
 * cada servicio. Flechas y no arrastrar: así se maneja igual con el dedo que
 * con la cruceta de un mando.
 */
@Composable
internal fun MetadataPriorityPanel(vm: ElyndraViewModel) {
    val s = vm.settings
    SettingsGroup(padding = 0.dp) {
        Column(Modifier.padding(vertical = 12.dp)) {
            ElyText(stringResource(R.string.settings_priority), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
            Spacer(Modifier.height(4.dp))
            ElyText(stringResource(R.string.settings_priority_desc), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
            Spacer(Modifier.height(10.dp))
            PriorityList(stringResource(R.string.settings_priority_text), s.priority.text) { service, delta -> s.movePriority(false, service, delta) }
            Spacer(Modifier.height(10.dp))
            PriorityList(stringResource(R.string.settings_priority_art), s.priority.art) { service, delta -> s.movePriority(true, service, delta) }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GhostButton(stringResource(R.string.settings_priority_reset), s::resetPriority)
            }
        }
    }
}

@Composable
private fun PriorityList(title: String, order: List<Service>, onMove: (Service, Int) -> Unit) {
    val skin = LocalSkin.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ElyText(title, size = 9.5f, weight = FontWeight.SemiBold, color = P.ink2, uppercase = true)
        order.forEachIndexed { i, service ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ElyText("${i + 1}", size = 10.5f, weight = FontWeight.Bold, color = skin.a2, modifier = Modifier.width(18.dp))
                ElyText(serviceName(service), size = 11.5f, weight = FontWeight.Medium, color = P.ink, modifier = Modifier.weight(1f))
                ArrowButton(up = true, enabled = i > 0, label = stringResource(R.string.move_up)) { onMove(service, -1) }
                Spacer(Modifier.width(6.dp))
                ArrowButton(up = false, enabled = i < order.lastIndex, label = stringResource(R.string.move_down)) { onMove(service, +1) }
            }
        }
    }
}

@Composable
private fun ArrowButton(up: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .shapeClickable(RoundedCornerShape(9.dp), enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ElyText(if (up) "▲" else "▼", size = 10f, color = P.ink)
    }
}
