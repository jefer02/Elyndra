package com.elyndra.launcher.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.domain.library.SystemArt

/*
 * El arte de serie de las carpetas: varios sistemas (uno por tipo de
 * aparato, y los que se pidieron por nombre), un mosaico y un degradado. El
 * mosaico no tiene imágenes en la vista previa: se ven sus huecos en color.
 */

private val SAMPLE = listOf("wii", "ps4", "switch", "switch2", "pc", "dos", "nes", "gb", "gba", "nds", "virtualboy", "arcade", "c64", "dreamcast")

@Composable
private fun Tile(content: @Composable (Modifier) -> Unit) = content(Modifier.size(96.dp, 144.dp).clip(RoundedCornerShape(14.dp)))

@Preview(name = "Arte de serie · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 760, heightDp = 360)
@Preview(name = "Arte de serie · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 760, heightDp = 360)
@Composable
private fun SystemArtTilesPreview() {
    Column(Modifier.background(Color(0xFF0E0E14)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SAMPLE.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { id ->
                    val system = Systems.byId(id) ?: return@forEach
                    Tile { m -> SystemArtTile(SystemArt.spec(id)!!, system.short, m) }
                }
            }
        }
    }
}

@Preview(name = "Mosaico y degradado", widthDp = 360, heightDp = 200)
@Composable
private fun FolderFallbacksPreview() {
    Row(Modifier.background(Color(0xFF0E0E14)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile { m -> FolderCollage(listOf("a.png", "b.png", "c.png", "d.png"), ArtFallback("f:custom", "Custom"), m) }
        Tile { m -> FolderCollage(listOf("a.png", "b.png"), ArtFallback("f:two", "Two"), m) }
        Tile { m -> FallbackArt(ArtFallback("f:none", "Custom"), ArtVariant.Square, m, showIcon = false) }
    }
}
