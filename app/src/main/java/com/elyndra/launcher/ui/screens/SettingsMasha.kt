package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.clickable
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
        SettingsGroup(padding = 0.dp, cornerRadius = 16.dp) {
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

        // Presencia: la línea sobre el carrusel y los avisos.
        SettingsGroup(padding = 0.dp, cornerRadius = 16.dp) {
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

        // Tiempo de juego exacto (acceso de uso, opcional).
        SettingsGroup(padding = 0.dp, cornerRadius = 16.dp) {
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
                    ElyText(stringResource(R.string.settings_masha_usage_on), size = 10f, weight = FontWeight.SemiBold, color = P.green, uppercase = true)
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
                    modifier = Modifier.clickable { reveal = !reveal },
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
    SettingsGroup(padding = 0.dp, cornerRadius = 16.dp) {
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
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ElyText(if (up) "▲" else "▼", size = 10f, color = P.ink)
    }
}
