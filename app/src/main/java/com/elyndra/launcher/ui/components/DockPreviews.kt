package com.elyndra.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * El dock de secciones en claro y en oscuro: tres y cuatro opciones, los
 * rótulos más largos (alemán) y japonés, sobre un arte claro y uno oscuro
 * (la cápsula tiene que leerse sobre los dos). Debajo, el botón de orden y
 * su menú.
 */

private val BrightArt = Brush.linearGradient(listOf(Color(0xFFFFF6D8), Color(0xFFBFE3FF), Color(0xFFFFFFFF)))
private val DarkArt = Brush.linearGradient(listOf(Color(0xFF05060A), Color(0xFF231338), Color(0xFF0B1A2E)))

@Composable
private fun DockRow(labels: List<String>, selected: Int, focusIndex: Int = -1) {
    var sel by remember { mutableIntStateOf(selected) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        DotTabs(labels, sel, { sel = it }, focused = focusIndex >= 0, focusIndex = focusIndex, feedback = false)
        Spacer(Modifier.width(8.dp))
        SortButton("Sort by: Platform", open = false, onClick = {})
    }
}

@Composable
private fun OnArt(art: Brush, content: @Composable () -> Unit) {
    Box(
        Modifier
            .background(art, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { content() }
}

@ConsolePreviews
@Composable
private fun DotTabsThreePreview() = PreviewTheme {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OnArt(BrightArt) { DockRow(listOf("Todo", "Android", "Consolas"), 1) }
        OnArt(DarkArt) { DockRow(listOf("All", "Android", "Consoles"), 2) }
        OnArt(DarkArt) { DockRow(listOf("Tout", "Android", "Consoles"), 0) }
    }
}

@ConsolePreviews
@Composable
private fun DotTabsFourPreview() = PreviewTheme {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Alemán: los rótulos más largos de los seis idiomas.
        OnArt(BrightArt) { DockRow(listOf("Alle", "Android", "Konsolen", "Ohne Namen"), 3) }
        OnArt(DarkArt) { DockRow(listOf("Alle", "Android", "Konsolen", "Ohne Namen"), 2) }
        // Japonés: sin glifos en Poppins, cae a la fuente del sistema.
        OnArt(BrightArt) { DockRow(listOf("すべて", "Android", "ゲーム機", "名前なし"), 2) }
        OnArt(DarkArt) { DockRow(listOf("Tudo", "Android", "Consoles", "Sem nome"), 0) }
    }
}

@ConsolePreviews
@Composable
private fun DotTabsGamepadPreview() = PreviewTheme {
    // Con el mando en el dock: aro en la cápsula y en el punto señalado.
    OnArt(DarkArt) { DockRow(listOf("Todo", "Android", "Consolas", "Sin nombre"), 0, focusIndex = 2) }
}

@ConsolePreviews
@Composable
private fun SortPopoverPreview() = PreviewTheme {
    Box(Modifier.background(DarkArt, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Column(horizontalAlignment = Alignment.End) {
            SortButton("Ordenar por: Plataforma", open = true, onClick = {})
            Box(Modifier.padding(top = 8.dp)) {
                SortPopoverContentPreview()
            }
        }
    }
}

/** El menú tal cual, sin la capa ni el ancla (la vista previa no tiene ventana). */
@Composable
private fun SortPopoverContentPreview() {
    SortMenu(
        title = "Ordenar por",
        caption = "24 elementos",
        options = listOf("Nombre (A–Z)", "Tiempo de juego", "Plataforma", "Fecha de alta"),
        selected = 2,
        focusIndex = 1,
        onPick = {},
    )
}
