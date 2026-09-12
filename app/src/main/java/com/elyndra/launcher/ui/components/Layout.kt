package com.elyndra.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.theme.LocalLandscape
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.animHeroIn
import com.elyndra.launcher.ui.theme.drawArt
import com.elyndra.launcher.ui.theme.heroScrimBrush
import java.io.File

/* ─────────────────────────────────────────────────────────────
   Métricas del diseño.

   Parten de la bandera `L` (horizontal) de Elyndra.dc.html, pero el
   hero y las cards se reparten el alto real de la ventana, así que
   escalan igual en móvil y en tableta:
     libre  = alto − (filtros + márgenes + nombre + dock)
     L:  tileH = 40 % de libre ; heroH = el resto
     P:  tileH = 34 % de libre (máx. 46 % del ancho) ; heroH = 46 % de libre
     appW = tileH ; conW = tileH*1.5 ; romW = romTileH*0.625 (L) / 0.56 (P)
   ───────────────────────────────────────────────────────────── */

data class Metrics(
    val landscape: Boolean,
    val pad: Dp,
    val heroH: Dp,
    val tileH: Dp,
    val appW: Dp,
    val consoleW: Dp,
    val romW: Dp,
    val romTileH: Dp,
    /** Tamaño (sp) del título del juego seleccionado en el hero. */
    val titleSize: Float,
    /** Alto del logo que sustituye a ese título. */
    val logoH: Dp,
)

/** Espacio útil de la ventana (sin barras del sistema); lo provee ElyndraApp. */
val LocalScreenSize = staticCompositionLocalOf { DpSize(412.dp, 892.dp) }

@Composable
fun metrics(): Metrics {
    val l = LocalLandscape.current
    val screen = LocalScreenSize.current
    val w = screen.width.value
    val h = screen.height.value
    // Alto que no es ni hero ni card: filtros, márgenes del carrusel, nombre bajo la card y dock.
    val chrome = if (l) 142f else 160f
    val free = (h - chrome).coerceAtLeast(176f)
    val tile: Float
    val hero: Float
    if (l) {
        tile = (free * 0.40f).coerceIn(66f, 220f)
        hero = (free - tile).coerceAtLeast(110f)
    } else {
        tile = minOf(free * 0.34f, w * 0.46f).coerceIn(96f, 300f)
        hero = (free * 0.46f).coerceAtMost(free - tile).coerceAtLeast(200f)
    }
    val titleSize = if (l) (hero * 0.30f).coerceIn(44f, 72f) else (hero * 0.16f).coerceIn(48f, 80f)
    val tileH = tile.dp
    return Metrics(
        landscape = l,
        pad = if (l) 22.dp else 18.dp,
        heroH = hero.dp,
        tileH = tileH,
        appW = tileH,
        consoleW = tileH * 1.5f,
        romW = tileH * (if (l) 0.625f else 0.56f),
        romTileH = tileH,
        titleSize = titleSize,
        logoH = (titleSize * if (l) 1.25f else 1.6f).dp,
    )
}

/**
 * El bloque de hero compartido por Biblioteca y Carpeta: carátula ampliada,
 * velo degradado y, encima, la barra superior y el bloque de título. Si el
 * juego tiene fondo descargado (fanart, hero de SteamGridDB, captura), se
 * pinta sobre la carátula procedural con la misma ampliación del 115 %.
 */
@Composable
fun Hero(
    pairIndex: Int,
    heroKey: Any,
    modifier: Modifier = Modifier,
    height: Dp,
    imagePath: String? = null,
    topBar: @Composable BoxScope.() -> Unit,
    info: @Composable BoxScope.() -> Unit,
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .background(P.ink),
    ) {
        // `heroArt`: la misma carátula procedural, al 115 % y saturada.
        Box(
            Modifier
                .fillMaxSize()
                .animHeroIn(key = heroKey)
                .drawBehind {
                    // `transform: scale(1.15)` sobre la carátula, desde el centro.
                    withTransform({ scale(1.15f, 1.15f, Offset(size.width / 2f, size.height / 2f)) }) {
                        drawArt(pairIndex)
                    }
                },
        ) {
            if (imagePath != null) {
                val file = remember(imagePath) { File(context.filesDir, imagePath) }
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.15f
                            scaleY = 1.15f
                        },
                )
            }
        }
        // `heroScrim`
        Box(Modifier.fillMaxSize().drawBehind { drawRect(heroScrimBrush(skin.scrim, size)) })
        topBar()
        info()
    }
}

/**
 * Rejilla equivalente a `display:grid; grid-template-columns: 1fr 1fr`.
 * Coloca por filas, como CSS: los elementos van llenando de izquierda a derecha.
 * Con [columns] = 1 se comporta como una columna normal.
 */
@Composable
fun CssGrid(
    columns: Int,
    horizontalGap: Dp,
    verticalGap: Dp,
    modifier: Modifier = Modifier,
    items: List<@Composable () -> Unit>,
) {
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(verticalGap),
    ) {
        items.chunked(columns.coerceAtLeast(1)).forEach { row ->
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(horizontalGap),
                verticalAlignment = androidx.compose.ui.Alignment.Top,
            ) {
                row.forEach { cell ->
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { cell() }
                }
                // Rellena la última fila incompleta para que no se estiren las celdas.
                repeat(columns - row.size) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
