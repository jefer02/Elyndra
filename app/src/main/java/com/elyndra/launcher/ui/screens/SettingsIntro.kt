package com.elyndra.launcher.ui.screens

import com.elyndra.launcher.ui.components.SwitchRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.components.CssGrid
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlowingSwitch
import com.elyndra.launcher.ui.components.SettingsGroup
import com.elyndra.launcher.ui.components.Swatch
import com.elyndra.launcher.ui.intro.IntroColor
import com.elyndra.launcher.ui.intro.IntroPalettes
import com.elyndra.launcher.ui.theme.LocalSkin

/**
 * Ajustes → Apariencia → intro de arranque: mostrarla o no, su color (con
 * muestras tal como se verán en el tema activo) y una vista previa.
 */
@Composable
internal fun IntroSection(vm: ElyndraViewModel) {
    val s = vm.settings
    val accent = LocalSkin.current.a1.argb()
    val dark = P.isDark

    SectionLabel(stringResource(R.string.section_intro))
    SettingsGroup {
        SwitchRow(stringResource(R.string.intro_enabled), stringResource(R.string.intro_enabled_desc), s.introEnabled, s::toggleIntro)

        Spacer(Modifier.height(6.dp))
        ElyText(stringResource(R.string.intro_color), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
        Spacer(Modifier.height(8.dp))
        CssGrid(
            columns = IntroColor.entries.size,
            horizontalGap = 8.dp,
            verticalGap = 8.dp,
            items = IntroColor.entries.map { color ->
                {
                    val brush = remember(color, accent, dark) {
                        val p = IntroPalettes.derive(color.base(accent), dark)
                        Brush.linearGradient(listOf(Color(p.rimLight), Color(p.rim), Color(p.rimDeep)))
                    }
                    Swatch(
                        brush = brush,
                        selected = s.introColor == color,
                        onClick = { s.updateIntroColor(color) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ElyText(
                stringResource(R.string.intro_color_label, stringResource(s.introColor.nameRes)),
                modifier = Modifier.weight(1f),
                size = 10.5f,
                color = P.ink2,
            )
            Spacer(Modifier.width(8.dp))
            GhostButton(stringResource(R.string.preview), s::previewIntro)
        }
    }
}
