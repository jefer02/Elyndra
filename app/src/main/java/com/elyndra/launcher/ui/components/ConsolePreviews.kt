package com.elyndra.launcher.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.theme.DefaultSkin
import com.elyndra.launcher.ui.theme.ElyndraTheme
import com.elyndra.launcher.ui.theme.consoleSurface

/** Cada vista previa sale en claro y en oscuro (el modo noche de la vista previa elige el tema). */
@Preview(name = "Claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 380)
@Preview(name = "Oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 380)
annotation class ConsolePreviews

/** El tema de la app sobre su papel, con el modo claro u oscuro de la vista previa. */
@Composable
fun PreviewTheme(content: @Composable () -> Unit) {
    val night = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    remember(night) { P.isDark = night }
    ElyndraTheme(DefaultSkin, landscape = false) {
        Box(Modifier.background(P.paper).padding(16.dp)) { content() }
    }
}

@ConsolePreviews
@Composable
private fun SettingRowsPreview() = PreviewTheme {
    var on by remember { mutableStateOf(true) }
    var fps by remember { mutableIntStateOf(0) }
    Column(Modifier.consoleSurface().padding(horizontal = 16.dp)) {
        SectionHeader("Pantalla")
        SwitchRow("Modo oscuro", "Usa una interfaz oscura, sea cual sea el tema del sistema.", on, { on = !on })
        SettingsDivider()
        SettingRow("Fotogramas por segundo", description = "120 fps se ve más fluido; 60 fps ahorra batería.")
        SegmentedControl(listOf("120 fps", "60 fps"), fps, { fps = it })
        SettingsDivider()
        NavRow("Licencias de código abierto", {}, glyph = ConsoleGlyph.About, value = "›")
    }
}

@ConsolePreviews
@Composable
private fun AccordionPreview() = PreviewTheme {
    var open by remember { mutableStateOf(true) }
    Column(Modifier.consoleSurface().padding(horizontal = 16.dp)) {
        AccordionHeader("Sonidos propios", open, { open = !open }, summary = "9 eventos · 2 propios")
        Expandable(open) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconAction(ConsoleGlyph.Play, "Probar", {}, filled = true)
                IconAction(ConsoleGlyph.Folder, "Elegir", {})
                IconAction(ConsoleGlyph.Reset, "Restablecer", {})
                IconAction(ConsoleGlyph.Handle, "Arrastrar", {})
            }
        }
    }
}

@ConsolePreviews
@Composable
private fun TabsAndHintsPreview() = PreviewTheme {
    var tab by remember { mutableIntStateOf(1) }
    Column {
        SlidingTabs(listOf("Todo", "Android", "Consolas"), tab, { tab = it })
        PadHints(
            listOf(PadHint("A", R.string.hint_select), PadHint("B", R.string.hint_back), PadHint("LB / RB", R.string.hint_section)),
            visible = true,
        )
        PadHints(listOf(PadHint("A", R.string.open), PadHint("Y", R.string.hint_options)), visible = true, onDark = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            ConsoleGlyph.entries.take(8).forEachIndexed { i, g -> GlyphBadge(g, active = i == 0) }
        }
    }
}

@ConsolePreviews
@Composable
private fun EmptyStatePreview() = PreviewTheme {
    EmptyState(
        title = "Biblioteca vacía",
        message = "Añade tus juegos Android o una carpeta de ROMs y aparecerán aquí.",
        actionLabel = "Añadir juegos o ROMs",
        onAction = {},
    )
}

@ConsolePreviews
@Composable
private fun ActionBarPreview() = PreviewTheme {
    Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionBar("3 seleccionados", "Añadir 3 juegos", {})
        ActionBar("", "Añadir", {}, enabled = false, reason = "Elige al menos un juego")
    }
}
