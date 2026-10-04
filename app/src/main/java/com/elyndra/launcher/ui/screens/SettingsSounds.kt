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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
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
import com.elyndra.launcher.ui.SoundSummary
import com.elyndra.launcher.ui.components.AccordionHeader
import com.elyndra.launcher.ui.components.ConsoleGlyph
import com.elyndra.launcher.ui.components.CssGrid
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.Expandable
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.IconAction
import com.elyndra.launcher.ui.components.SegmentedControl
import com.elyndra.launcher.ui.components.SettingRow
import com.elyndra.launcher.ui.components.SettingsGroup
import com.elyndra.launcher.ui.components.SwitchRow
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.MinTouch
import com.elyndra.launcher.ui.theme.Space
import com.elyndra.launcher.ui.theme.TypeScale

/**
 * Ajustes → Sonido: interruptor general, volumen, sonido al navegar, el
 * paquete (Console, Soft, Retro o ninguno) y, plegados, los sonidos propios
 * de cada evento: una fila compacta por evento con probar, elegir y
 * restablecer; en dos columnas si la ventana es ancha.
 */
@Composable
internal fun SoundsSection(vm: ElyndraViewModel, wide: Boolean) {
    val s = vm.sounds
    // El selector del sistema no dice para qué evento se abrió: se recuerda aquí.
    var picking by remember { mutableStateOf<UiSound?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking?.let { s.onPicked(it, uri) }
        picking = null
    }
    var customOpen by rememberSaveable { mutableStateOf(false) }

    SectionLabel(stringResource(R.string.section_sounds))
    SettingsGroup {
        SwitchRow(stringResource(R.string.sound_enabled), stringResource(R.string.sound_enabled_desc), s.enabled, s::toggleEnabled)
        if (s.enabled) {
            SliderRow(stringResource(R.string.sound_volume), "${s.volume} %", s.volume, 0..100, s::updateVolume, topPadding = 4.dp)
            Spacer(Modifier.height(Space.s))
            SwitchRow(stringResource(R.string.sound_navigation), stringResource(R.string.sound_navigation_desc), s.navigation, s::toggleNavigation)
            SettingRow(stringResource(R.string.sound_pack))
            SegmentedControl(
                options = SoundPack.entries.map { stringResource(it.nameRes) },
                selected = SoundPack.entries.indexOf(s.pack),
                onSelect = { s.choosePack(SoundPack.entries[it]) },
            )
            Spacer(Modifier.height(Space.s))
        }
    }

    if (s.enabled) {
        val events = UiSound.entries.size
        val custom = s.custom.size
        val eventsText = pluralStringResource(R.plurals.sound_events_count, events, events)
        val customText = pluralStringResource(R.plurals.sound_custom_count, custom, custom)
        SettingsGroup(padding = 0.dp) {
            AccordionHeader(
                title = stringResource(R.string.sound_custom_title),
                expanded = customOpen,
                onToggle = { customOpen = !customOpen },
                summary = SoundSummary.text(events, custom, { eventsText }, { customText }),
            )
            Expandable(customOpen) {
                ElyText(
                    stringResource(
                        R.string.sound_custom_desc,
                        (CustomSoundRules.MAX_BYTES / 1024).toInt(),
                        "%.1f".format(CustomSoundRules.MAX_MS / 1000f),
                        "%.0f".format(CustomSoundRules.MAX_LAUNCH_MS / 1000f),
                    ),
                    size = TypeScale.Caption,
                    color = P.ink2,
                    lineHeightRatio = 1.45f,
                )
                Spacer(Modifier.height(Space.s))
                CssGrid(
                    columns = if (wide) 2 else 1,
                    horizontalGap = Space.m,
                    verticalGap = 0.dp,
                    items = UiSound.entries.map { sound ->
                        {
                            val file = s.custom[sound]
                            SoundEventRow(
                                name = stringResource(sound.nameRes),
                                source = when {
                                    s.importing == sound -> stringResource(R.string.sound_checking)
                                    file != null -> file
                                    s.pack == SoundPack.Off -> stringResource(R.string.sound_pack_off)
                                    else -> stringResource(s.pack.nameRes)
                                },
                                custom = file != null,
                                onPreview = { s.preview(sound) },
                                onChoose = {
                                    picking = sound
                                    picker.launch(arrayOf("audio/*"))
                                },
                                onReset = { s.reset(sound) },
                            )
                        }
                    },
                )
                Spacer(Modifier.height(Space.s))
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
            SliderRow(stringResource(R.string.music_volume), "${s.musicVolume} %", s.musicVolume, 0..100, s::updateMusicVolume, topPadding = 4.dp)
            Spacer(Modifier.height(Space.s))
            SettingRow(stringResource(R.string.music_source), description = s.musicName ?: stringResource(R.string.music_source_builtin))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                GhostButton(stringResource(R.string.music_choose), { picker.launch(arrayOf("audio/*")) })
                if (s.musicUri != null) GhostButton(stringResource(R.string.music_use_builtin), s::useBuiltInMusic)
            }
            Spacer(Modifier.height(Space.s))
        }
    }
}

/**
 * Un evento: su nombre y el sonido que suena ahora (el del paquete o el
 * archivo propio), y a la derecha probar, elegir y, si es propio, restablecer.
 */
@Composable
private fun SoundEventRow(
    name: String,
    source: String,
    custom: Boolean,
    onPreview: () -> Unit,
    onChoose: () -> Unit,
    onReset: () -> Unit,
) {
    val skin = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ElyText(name, size = 11.5f, weight = FontWeight.Medium, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ElyText(source, size = 9.5f, color = if (custom) skin.a2 else P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction(ConsoleGlyph.Play, "${stringResource(R.string.sound_preview)}: $name", onPreview, filled = true)
        IconAction(ConsoleGlyph.Folder, "${stringResource(R.string.sound_choose)} $name", onChoose)
        if (custom) {
            IconAction(ConsoleGlyph.Reset, "${stringResource(R.string.sound_reset)}: $name", onReset)
        } else {
            // El hueco del botón de restablecer se queda: así las filas no bailan.
            Spacer(Modifier.width(MinTouch))
        }
    }
}
