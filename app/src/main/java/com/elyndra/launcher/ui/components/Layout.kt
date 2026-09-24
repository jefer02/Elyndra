package com.elyndra.launcher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import com.elyndra.launcher.ui.theme.LocalLandscape
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.SelectionLift
import com.elyndra.launcher.ui.theme.SelectionScale
import com.elyndra.launcher.ui.theme.animHeroIn
import com.elyndra.launcher.ui.theme.heroEdgeScrimBrush
import com.elyndra.launcher.ui.theme.heroScrimBrush
import com.elyndra.launcher.ui.theme.liquidGlass
import java.io.File

/**
 * Detecta que a *este mismo* juego le acaba de llegar una imagen.
 *
 * El hero cambia de imagen por dos motivos muy distintos: porque se ha
 * seleccionado otro juego (ahí no hay novedad que celebrar) o porque al juego
 * que ya se estaba mirando le ha entrado arte —del motor de metadatos, de una
 * descarga automática o de una asignación a mano—. Solo el segundo caso monta
 * partículas, y por eso se compara contra [owner]: si el dueño cambia, la
 * ruta anterior se olvida sin animar nada.
 */
@Composable
fun rememberArtArrival(path: String?, owner: Any): ArtArrival {
    val arrival = remember { ArtArrival() }
    LaunchedEffect(owner, path) {
        arrival.onChanged(owner, path)
    }
    return arrival
}

/** Estado de [rememberArtArrival]: si toca montar y cómo cerrarlo. */
@Stable
class ArtArrival {
    private var owner: Any? = null
    private var path: String? = null

    /** Cierto mientras haya que montar la imagen recién llegada. */
    var active by mutableStateOf(false)
        private set

    internal fun onChanged(newOwner: Any, newPath: String?) {
        // Mismo juego y antes no había imagen: ha llegado ahora. Todo lo
        // demás —cambio de juego, primera composición, imagen que se va— solo
        // actualiza la referencia.
        active = owner == newOwner && path == null && newPath != null
        owner = newOwner
        path = newPath
    }

    fun done() {
        active = false
    }
}

/* ─────────────────────────────────────────────────────────────
     Escalan igual en móvil y en tableta:
     libre = alto − (filtros + márgenes + nombre + hueco de selección)

   El reparto es exacto: **hero + card == libre**, siempre. El hero se
   lleva su porcentaje y la card se queda con el resto; si algún tope
   la mueve, el hero absorbe la diferencia. Así no hay forma de que la
   suma se pase del alto de la pantalla, que era justo lo que recortaba
   el carrusel en vertical (antes hero y card se calculaban por separado
   y un mínimo posterior podía deshacer el tope).

   Ya no hay dock en ninguna pantalla —"Abrir" vive en la barra del hero—,
   así que ese alto se reparte entre hero y cards, y una parte se la lleva el
   hueco de arriba del carrusel (`carouselTop`), que es lo que evita que la
   card seleccionada se suba al rótulo de su fila.

   Todas las cards miden igual —carpetas de emulador, juegos Android y
   ROMs— con proporción de carátula 2:3.
   ───────────────────────────────────────────────────────────── */

data class Metrics(
    val landscape: Boolean,
    val pad: Dp,
    val heroH: Dp,
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
    /** Hueco sobre las cards del carrusel: lo que la seleccionada sube y crece. */
    val carouselTop: Dp,
    /** Hueco bajo las cards del carrusel. */
    val carouselBottom: Dp,
)

/** Lado máximo de la card de icono: por encima se ve desproporcionada en tablet. */
private const val ICON_TILE_MAX = 168f

/** Aire de cortesía entre la card seleccionada y el rótulo de su fila. */
private const val TILE_SLACK = 6f

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
    // Alto fijo que no es ni hero ni card: la fila de filtros, el nombre bajo la
    // card y el aire entre medias.
    val fixed = if (l) 62f else 74f
    val bottom = if (l) 6f else 10f
    // La card seleccionada sube [SelectionLift] y se amplía [SelectionScale]
    // desde su centro, o sea que la mitad de lo que crece se le va por arriba.
    // Ese hueco lo reserva el carrusel; sin él la card elegida se metía encima
    // del rótulo de la fila (ROMS en Carpeta, los filtros en Biblioteca).
    val grow = (SelectionScale - 1f) / 2f
    val headroom = SelectionLift.value + TILE_SLACK
    // El alto de la card sale de despejar `card = (libre − hueco) · f` con
    // `hueco = headroom + card · grow`: así el hueco crece con la card y la
    // suma sigue cuadrando con la pantalla, también en tablet.
    val f = 1f - (if (l) 0.60f else 0.61f)
    val room = (h - fixed - bottom - headroom).coerceAtLeast(150f)

    // El hero manda —con logo ocupa menos que una carátula, así que se le da
    // más sitio— y la card se queda con el resto exacto.
    // Topes de la card: su carátula 2:3 no puede comerse la fila, y por debajo
    // de un mínimo no se distingue.
    var tile = room * f / (1f + grow * f)
    val maxTileByWidth = w * (if (l) 0.30f else 0.50f) / COVER_RATIO
    if (tile > maxTileByWidth) tile = maxTileByWidth
    val minTile = if (l) 72f else 104f
    // En una pantalla muy baja la card se queda como mucho con la mitad:
    // repartir a medias es preferible a que el hero la deje sin sitio.
    if (tile < minTile) tile = minTile.coerceAtMost(room * 0.5f)

    val top = headroom + tile * grow
    val free = (h - fixed - bottom - top).coerceAtLeast(150f)
    val hero = free - tile

    // La biblioteca no usa carátulas: sus cards son cuadradas (formato de
    // icono). El lado lo manda el ancho —una card cuadrada tan alta como una
    // carátula se comería la fila— y el alto que sobra se lo queda el hero.
    // Tres topes y un factor:
    //   · el alto libre de la fila,
    //   · una fracción del ancho y otra del alto de la pantalla,
    //   · y un tope absoluto, que es lo que arregla las tablets: sin él la card
    //     crece con la pantalla y en horizontal quedaba una fila de cromos
    //     enormes; un icono no necesita más de ~170dp en ninguna pantalla.
    // El factor final la baja algo en horizontal y un poco más en vertical.
    val iconSide = minOf(
        tile,
        w * (if (l) 0.30f else 0.46f),
        h * (if (l) 0.26f else 0.22f),
        ICON_TILE_MAX,
    ) * (if (l) 0.91f else 0.76f)
    val iconHero = free - iconSide

    val titleSize = if (l) (hero * 0.34f).coerceIn(50f, 96f) else (hero * 0.20f).coerceIn(56f, 112f)
    val tileH = tile.dp
    // Carátula vertical: el ancho sale del alto, no al revés, así nunca se recorta.
    val cardW = tileH * COVER_RATIO
    return Metrics(
        landscape = l,
        pad = if (l) 22.dp else 18.dp,
        heroH = hero.dp,
        romW = cardW,
        romTileH = tileH,
        iconTile = iconSide.dp,
        iconHeroH = iconHero.dp,
        titleSize = titleSize,
        logoH = (titleSize * if (l) 1.25f else 1.6f).dp,
        carouselTop = top.dp,
        carouselBottom = bottom.dp,
    )
}

/**
 * El bloque de hero compartido por Biblioteca y Carpeta: el fondo del juego
 * y, encima, la barra superior y el bloque de título.
 *
 * Con fondo (fanart, hero de SteamGridDB, captura) la imagen se pinta limpia:
 * ni cristal ni ampliación, que era lo que la dejaba lechosa y blanda. Encima
 * solo va el velo de los cantos, que no toca el centro (ver
 * [heroEdgeScrimBrush]).
 *
 * Sin fondo no hay nada que enseñar y el texto blanco se quedaría sobre el
 * papel de la app: ahí sí entran el cristal —que además deja ver la aurora—
 * y el velo entero del diseño.
 */
@Composable
fun Hero(
    pairIndex: Int,
    heroKey: Any,
    modifier: Modifier = Modifier,
    height: Dp,
    imagePath: String? = null,
    /** El fondo se está quitando: se deshace en polvo antes de irse. */
    backgroundVanishing: Boolean = false,
    onBackgroundVanished: () -> Unit = {},
    topBar: @Composable BoxScope.() -> Unit,
    info: @Composable BoxScope.() -> Unit,
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds(),
    ) {
        if (imagePath != null) {
            val file = remember(imagePath) { File(context.filesDir, imagePath) }
            // Un fondo que *acaba de llegar* —lo ha bajado el motor de
            // metadatos, o lo acaba de poner el usuario— se monta desde el
            // polvo. Uno que solo cambia porque se ha seleccionado otro juego
            // no: ahí el fondo no es una novedad, es otro juego (ver
            // [rememberArtArrival]).
            val arriving = rememberArtArrival(imagePath, heroKey)
            MaterializingContainer(
                isMaterializing = arriving.active,
                onAnimationEnd = arriving::done,
                modifier = Modifier.fillMaxSize(),
            ) {
                DisintegratingContainer(
                    isDisintegrating = backgroundVanishing,
                    onAnimationEnd = onBackgroundVanished,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .animHeroIn(key = heroKey),
                    )
                }
            }
            Box(Modifier.fillMaxSize().drawBehind { drawRect(heroEdgeScrimBrush(skin.scrim, size)) })
        } else {
            // Sin animar: el cristal es material de la pantalla, no del juego,
            // y encenderlo en cada cambio de selección se lee como parpadeo.
            Box(Modifier.fillMaxSize().liquidGlass(RectangleShape, Color.Transparent))
            // `heroScrim`
            Box(Modifier.fillMaxSize().drawBehind { drawRect(heroScrimBrush(skin.scrim, size)) })
        }
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
