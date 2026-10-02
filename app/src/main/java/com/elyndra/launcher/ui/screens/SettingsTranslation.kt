package com.elyndra.launcher.ui.screens

import com.elyndra.launcher.ui.components.SwitchRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.elyndra.launcher.ui.components.SettingsGroup

/**
 * Ajustes → Traducción de descripciones: traducir sola (apagado de serie),
 * bajar modelos solo por Wi-Fi (encendido de serie) y los modelos ya bajados,
 * para borrarlos.
 */
@Composable
internal fun TranslationSection(vm: ElyndraViewModel) {
    val c = vm.descriptions
    val lang = vm.settings.lang
    LaunchedEffect(Unit) { c.refreshModels() }

    SectionLabel(stringResource(R.string.section_translation))
    SettingsGroup {
        SwitchRow(stringResource(R.string.auto_translate), stringResource(R.string.auto_translate_desc), c.autoTranslate, c::toggleAuto)
        SwitchRow(stringResource(R.string.translate_wifi_only), null, c.wifiOnly, c::toggleWifiOnly)
        Spacer(Modifier.height(6.dp))
        ElyText(stringResource(R.string.translate_models), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
        if (c.models.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            ElyText(stringResource(R.string.translate_models_none), size = 10f, color = P.ink2)
        }
        c.models.forEach { model ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ElyText(DescriptionsController.languageName(model, lang), Modifier.weight(1f), size = 11.5f, color = P.ink)
                GhostButton(stringResource(R.string.translate_model_delete), { c.deleteModel(model) })
            }
        }
    }
}
