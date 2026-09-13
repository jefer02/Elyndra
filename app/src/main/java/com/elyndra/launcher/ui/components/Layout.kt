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
     Escalan igual en móvil y en tableta:
     libre = alto − (filtros + márgenes + nombre + dock compacto)

   El reparto es exacto: **hero + card == libre**, siempre. El hero se
   lleva su porcentaje y la card se queda con el resto; si algún tope
   la mueve, el hero absorbe la diferencia. Así no hay forma de que la
   suma se pase del alto de la pantalla, que era justo lo que recortaba
   el carrusel en vertical (antes hero y card se calculaban por separado
   y un mínimo posterior podía deshacer el tope).

   Topes de la card:
     · ancho: su carátula 2:3 no puede comerse la fila.
     · mínimo: por debajo no se distingue la carátula.

   Todas las cards miden igual —carpetas de emulador, juegos Android y
   ROMs— con proporción de carátula 2:3.
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
    /** Lado de la card cuadrada de icono (juegos Android y carpetas de emulador). */
    val iconTile: Dp,
    /** Alto del hero en la biblioteca, donde la card es la cuadrada de icono. */
    val iconHeroH: Dp,
    /** Tamaño (sp) del título del juego seleccionado en el hero. */
    val titleSize: Float,
    /** Alto del logo que sustituye a ese título. */
    val logoH: Dp,
)

/** Proporción de las carátulas (2:3, el estándar de box art). */
const val COVER_RATIO = 2f / 3f

/** Espacio útil de la ventana (sin barras del sistema); lo provee ElyndraApp. */
val LocalScreenSize = staticCompositionLocalOf { DpSize(412.dp, 892.dp) }

@Composable
fun metrics(): Metrics {
    val l = LocalLandscape.current
    val screen = LocalScreenSize.current
    val w = screen.width.value
    val h = screen.height.value
    // Alto que no es ni hero ni card: filtros, márgenes del carrusel, nombre bajo
    // la card y el dock compacto (ver LibraryScreen: 34dp + aire).
    val chrome = if (l) 118f else 132f
    val free = (h - chrome).coerceAtLeast(150f)

    // El hero manda —con logo ocupa menos que una carátula, así que se le da
    // más sitio— y la card se queda con el resto exacto.
    var hero = free * (if (l) 0.60f else 0.64f)
    var tile = free - hero

    val maxTileByWidth = w * (if (l) 0.30f else 0.46f) / COVER_RATIO
    if (tile > maxTileByWidth) {
        tile = maxTileByWidth
        hero = free - tile
    }
    val minTile = if (l) 72f else 104f
    if (tile < minTile) {
        // En una pantalla muy baja la card se queda como mucho con la mitad:
        // repartir a medias es preferible a que el hero la deje sin sitio.
        tile = minTile.coerceAtMost(free * 0.5f)
        hero = free - tile
    }

    // La biblioteca no usa carátulas: sus cards son cuadradas (formato de
    // icono). El lado lo manda el ancho —una card cuadrada tan alta como una
    // carátula se comería la fila— y el alto que sobra se lo queda el hero.
    // El factor final la baja un poco más en vertical que en horizontal: en
    // vertical la fila tiene menos aire y la card cuadrada se comía la pantalla.
    val iconSide = minOf(tile, w * (if (l) 0.30f else 0.46f)) * (if (l) 0.92f else 0.84f)
    val iconHero = free - iconSide

    val titleSize = if (l) (hero * 0.34f).coerceIn(50f, 96f) else (hero * 0.20f).coerceIn(56f, 112f)
    val tileH = tile.dp
    // Carátula vertical: el ancho sale del alto, no al revés, así nunca se recorta.
    val cardW = tileH * COVER_RATIO
    return Metrics(
        landscape = l,
        pad = if (l) 22.dp else 18.dp,
        heroH = hero.dp,
        tileH = tileH,
        appW = cardW,
        // Las carpetas de emulador miden lo mismo que los juegos Android.
        consoleW = cardW,
        romW = cardW,
        romTileH = tileH,
        iconTile = iconSide.dp,
        iconHeroH = iconHero.dp,
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
            .background(P.shade),
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
