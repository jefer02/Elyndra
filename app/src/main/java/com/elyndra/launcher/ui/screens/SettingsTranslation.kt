package com.elyndra.launcher.ui.screens

import com.elyndra.launcher.ui.components.SwitchRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.DescriptionsController
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlowingSwitch
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.metadata.PackState
import com.elyndra.launcher.ui.components.SettingsGroup

/**
 * Ajustes → Metadatos → Traducción de descripciones: los paquetes de idioma
 * con su estado (instalado, descargando, esperando Wi-Fi, error) y la opción
 * de borrarlos, y si se pueden bajar con datos móviles (apagado de serie).
 * Con el paquete instalado, lo que no está en el idioma de la app se traduce
 * solo; bajarlo siempre lo pide el usuario.
 */
@Composable
internal fun TranslationSection(vm: ElyndraViewModel) {
    val c = vm.descriptions
    val lang = vm.settings.lang
    LaunchedEffect(Unit) { c.refreshModels() }

    SectionLabel(stringResource(R.string.section_translation))
    SettingsGroup {
        SwitchRow(stringResource(R.string.translate_allow_mobile), stringResource(R.string.translate_allow_mobile_desc), c.allowMobile, c::toggleAllowMobile)
        Spacer(Modifier.height(6.dp))
        ElyText(stringResource(R.string.translate_models), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
        val packs = (c.installed + c.packStates.keys).sorted()
        if (packs.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            ElyText(stringResource(R.string.translate_models_none), size = 10f, color = P.ink2)
        }
        packs.forEach { model ->
            val state = c.packStates[model]
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ElyText(DescriptionsController.languageName(model, lang), size = 11.5f, color = P.ink)
                    ElyText(
                        stringResource(
                            when (state) {
                                PackState.Downloading -> R.string.translate_state_downloading
                                PackState.WaitingForWifi -> R.string.translate_state_waiting_wifi
                                PackState.Error -> R.string.translate_state_error
                                null -> R.string.translate_state_installed
                            },
                        ),
                        size = 9.5f,
                        color = if (state == PackState.Error) P.red else P.ink2,
                    )
                }
                when (state) {
                    PackState.Downloading, PackState.WaitingForWifi -> ArcSpinner(size = 16.dp)
                    PackState.Error -> GhostButton(stringResource(R.string.retry), c::retry)
                    null -> GhostButton(stringResource(R.string.translate_model_delete), { c.deleteModel(model) })
                }
            }
        }
    }
}
