package com.elyndra.launcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.sound.CustomSoundRules
import com.elyndra.launcher.sound.SoundPack
import com.elyndra.launcher.sound.UiSound
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlowingSwitch
import com.elyndra.launcher.ui.components.Pill
import com.elyndra.launcher.ui.components.SettingsDivider
import com.elyndra.launcher.ui.components.SettingsGroup

/**
 * Ajustes → Sonidos: interruptor general, volumen, sonido al navegar, el
 * paquete (Console, Soft, Retro o ninguno) y, por evento, un sonido propio
 * elegido con el selector del sistema, con su vista previa y "restablecer".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SoundsSection(vm: ElyndraViewModel) {
    val s = vm.sounds
    // El selector del sistema no dice para qué evento se abrió: se recuerda aquí.
    var picking by remember { mutableStateOf<UiSound?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking?.let { s.onPicked(it, uri) }
        picking = null
    }

    SectionLabel(stringResource(R.string.section_sounds))
    SettingsGroup {
        SwitchRow(stringResource(R.string.sound_enabled), stringResource(R.string.sound_enabled_desc), s.enabled, s::toggleEnabled)
        if (s.enabled) {
            SliderRow(stringResource(R.string.sound_volume), "${s.volume} %", s.volume, 0..100, s::updateVolume, topPadding = 14.dp)
            Spacer(Modifier.height(14.dp))
            SwitchRow(stringResource(R.string.sound_navigation), stringResource(R.string.sound_navigation_desc), s.navigation, s::toggleNavigation)

            Spacer(Modifier.height(14.dp))
            ElyText(stringResource(R.string.sound_pack), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SoundPack.entries.forEach { pack ->
                    Pill(stringResource(pack.nameRes), active = s.pack == pack, onClick = { s.choosePack(pack) })
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsDivider()
            Spacer(Modifier.height(12.dp))
            ElyText(stringResource(R.string.sound_custom_title), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
            Spacer(Modifier.height(4.dp))
            ElyText(
                stringResource(
                    R.string.sound_custom_desc,
                    (CustomSoundRules.MAX_BYTES / 1024).toInt(),
                    "%.1f".format(CustomSoundRules.MAX_MS / 1000f),
                    "%.0f".format(CustomSoundRules.MAX_LAUNCH_MS / 1000f),
                ),
                size = 10f,
                color = P.ink2,
                lineHeightRatio = 1.45f,
            )
            UiSound.entries.forEach { sound ->
                EventRow(
                    name = stringResource(sound.nameRes),
                    source = when {
                        s.importing == sound -> stringResource(R.string.sound_checking)
                        s.custom[sound] != null -> stringResource(R.string.sound_source_custom)
                        s.pack == SoundPack.Off -> stringResource(R.string.sound_pack_off)
                        else -> stringResource(s.pack.nameRes)
                    },
                    custom = s.custom[sound] != null,
                    onPreview = { s.preview(sound) },
                    onChoose = {
                        picking = sound
                        picker.launch(arrayOf("audio/*"))
                    },
                    onReset = { s.reset(sound) },
                )
            }
        }
    }
}

/** Ajustes → Música de fondo: encendida o no, su volumen y de dónde sale. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MusicSection(vm: ElyndraViewModel) {
    val s = vm.sounds
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), s::onMusicPicked)

    SectionLabel(stringResource(R.string.section_music))
    SettingsGroup {
        SwitchRow(stringResource(R.string.music_enabled), stringResource(R.string.music_enabled_desc), s.musicEnabled, s::toggleMusic)
        if (s.musicEnabled) {
            SliderRow(stringResource(R.string.music_volume), "${s.musicVolume} %", s.musicVolume, 0..100, s::updateMusicVolume, topPadding = 14.dp)
            Spacer(Modifier.height(14.dp))
            ElyText(stringResource(R.string.music_source), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
            Spacer(Modifier.height(4.dp))
            ElyText(
                s.musicName ?: stringResource(R.string.music_source_builtin),
                size = 10.5f,
                color = if (s.musicUri != null) P.ink else P.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GhostButton(stringResource(R.string.music_choose), { picker.launch(arrayOf("audio/*")) })
                if (s.musicUri != null) GhostButton(stringResource(R.string.music_use_builtin), s::useBuiltInMusic)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ElyText(title, size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
            Spacer(Modifier.height(4.dp))
            ElyText(desc, size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
        }
        Spacer(Modifier.width(12.dp))
        GlowingSwitch(checked, onToggle)
    }
}

/** Un evento: su nombre y de dónde sale el sonido, y debajo probar, elegir y restablecer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventRow(
    name: String,
    source: String,
    custom: Boolean,
    onPreview: () -> Unit,
    onChoose: () -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ElyText(name, modifier = Modifier.weight(1f), size = 11.5f, weight = FontWeight.Medium, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ElyText(source, size = 10f, color = if (custom) P.ink else P.ink2, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GhostButton(stringResource(R.string.sound_preview), onPreview)
            GhostButton(stringResource(R.string.sound_choose), onChoose)
            if (custom) GhostButton(stringResource(R.string.sound_reset), onReset)
        }
    }
}
