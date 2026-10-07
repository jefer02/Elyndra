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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.motion
import com.elyndra.launcher.ui.theme.heroEdgeScrimBrush
import com.elyndra.launcher.ui.theme.heroScrimBrush
import com.elyndra.launcher.ui.theme.heroScrimBottom
import com.elyndra.launcher.ui.theme.shelfColor
import com.elyndra.launcher.ui.ShelfLayout
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
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

/** Hay un mando conectado: Biblioteca y Carpeta reservan el alto de la barra de pistas. */
val LocalPadHints = staticCompositionLocalOf { false }

/** Alto de la barra de pistas del mando al pie de Biblioteca y Carpeta. */
val PAD_HINTS_HEIGHT = 30.dp

@Composable
fun metrics(): Metrics {
    val l = LocalLandscape.current
    val screen = LocalScreenSize.current
    val w = screen.width.value
    val h = screen.height.value
    // Alto fijo que no es ni hero ni card: el nombre bajo la card y el aire
    // entre medias, más el alto de la antigua fila de filtros. Esa fila ya no
    // existe (el dock va en la barra o en la costura), pero se sigue
    // descontando para que el hero no cambie: ese alto es ahora del estante,
    // y `ShelfLayout` lo reparte entre una card algo mayor y aire alrededor.
    val fixed = if (l) 66f else 78f
    val bottom = (if (l) 6f else 10f) + if (LocalPadHints.current) PAD_HINTS_HEIGHT.value else 0f
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
 * Sin fondo, la banda es el arte de reserva del juego ([FallbackArt], con
 * una deriva muy lenta) y encima el velo entero del diseño, para que el
 * texto blanco se lea. Sin juego ([fallback] null), el cristal, que deja ver
 * la aurora.
 *
 * Con [extension] el fondo sigue hacia abajo, por detrás del estante: es la
 * misma imagen (el mismo `AsyncImage`, anclado arriba y recortado), más alta,
 * y se funde con su velo en el color liso del estante ([shelfColor]). Así el
 * estante es parte de la escena y no un panel suelto. El estante tiene que
 * pintarse con `shelfSurface(extension)`, que deja ese tramo sin fondo. El
 * alto que ocupa el hero en la columna sigue siendo [height].
 */
@Composable
fun Hero(
    fallback: ArtFallback?,
    heroKey: Any,
    modifier: Modifier = Modifier,
    height: Dp,
    imagePath: String? = null,
    extension: Dp = 0.dp,
    /** El fondo se está quitando: se deshace en polvo antes de irse. */
    backgroundVanishing: Boolean = false,
    onBackgroundVanished: () -> Unit = {},
    topBar: @Composable BoxScope.() -> Unit,
    info: @Composable BoxScope.() -> Unit,
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    // Un fondo que *acaba de llegar* —lo ha bajado el motor de metadatos, o lo
    // acaba de poner el usuario— se monta desde el polvo. Uno que solo cambia
    // porque se ha seleccionado otro juego no: ahí el fondo no es una novedad,
    // es otro juego (ver [rememberArtArrival]).
    val arriving = rememberArtArrival(imagePath, heroKey)
    val target = HeroBackdrop(imagePath, fallback, heroKey)
    val shelf = shelfColor()
    Box(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        // El fondo mide hero + extensión, pero en la columna solo cuenta el hero:
        // el resto asoma por debajo, detrás del estante.
        Box(
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val h = height.roundToPx() + extension.roundToPx()
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, constraints.maxHeight) { placeable.place(0, 0) }
                }
                .clipToBounds(),
        ) {
            // Cambiar de juego funde un fondo en otro, y el nuevo se asienta con un
            // acercamiento muy lento (en la capa: no recompone nada).
            Crossfade(targetState = target, animationSpec = motion(Springs.fade()), label = "heroBackdrop") { bg ->
                val current = bg == target
                Box(Modifier.fillMaxSize().slowSettle(reduced)) {
                    if (bg.imagePath != null) {
                        val file = remember(bg.imagePath) { File(context.filesDir, bg.imagePath) }
                        MaterializingContainer(
                            isMaterializing = current && arriving.active,
                            onAnimationEnd = arriving::done,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            DisintegratingContainer(
                                isDisintegrating = current && backgroundVanishing,
                                onAnimationEnd = onBackgroundVanished,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    alignment = Alignment.TopCenter,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    } else if (bg.fallback != null) {
                        FallbackArt(bg.fallback, ArtVariant.Banner, Modifier.fillMaxSize(), drift = true)
                    } else {
                        // El cristal es material de la pantalla, no del juego.
                        Box(Modifier.fillMaxSize().liquidGlass(RectangleShape, Color.Transparent))
                    }
                }
                // Con fondo, solo los cantos; sin él, el velo entero del diseño.
                // Debajo del hero, la franja del titular se apaga.
                Box(Modifier.fillMaxSize().heroScrims(height, art = bg.imagePath != null, skin.scrim))
            }
            if (extension > 0.dp) Box(Modifier.fillMaxSize().shelfRise(height, shelf))
        }
        topBar()
        info()
    }
}

/**
 * Los velos del hero y de su extensión, con mezcla normal y sin capas aparte:
 * el del hero en su alto y, debajo, la franja oscura del titular que se apaga
 * ([ShelfLayout.TITLE_BAND]). Van con cada fondo, dentro del fundido.
 */
internal fun Modifier.heroScrims(heroHeight: Dp, art: Boolean, scrim: Float): Modifier = drawWithCache {
    val heroPx = heroHeight.roundToPx().toFloat().coerceAtMost(size.height)
    val ext = size.height - heroPx
    val heroSize = Size(size.width, heroPx)
    val hero = if (art) heroEdgeScrimBrush(scrim, heroSize) else heroScrimBrush(scrim, heroSize)
    val band = rampBrush(ShelfLayout.TITLE_BAND, heroScrimBottom(scrim, art), heroPx, size.height)
    val top = Offset(0f, heroPx)
    val extSize = Size(size.width, ext)
    onDrawBehind {
        drawRect(hero, size = heroSize)
        if (ext > 0f) drawRect(band, topLeft = top, size = extSize)
    }
}

/**
 * El color del estante que sube de transparente a liso sobre la extensión
 * ([ShelfLayout.SHELF_RAMP]). Se pinta **una vez**, encima del fundido entre
 * fondos: dentro de él, a mitad de un cambio de juego, las dos capas a medio
 * fundir dejaban ver el fondo justo en la costura con el estante.
 */
internal fun Modifier.shelfRise(heroHeight: Dp, shelf: Color): Modifier = drawWithCache {
    val heroPx = heroHeight.roundToPx().toFloat().coerceAtMost(size.height)
    val rise = rampBrush(ShelfLayout.SHELF_RAMP, shelf, heroPx, size.height)
    val top = Offset(0f, heroPx)
    val extSize = Size(size.width, size.height - heroPx)
    onDrawBehind {
        if (extSize.height > 0f) drawRect(rise, topLeft = top, size = extSize)
    }
}

/** Un degradado vertical de [color] con la rampa de alfas [stops] (pares posición, factor) entre [startY] y [endY]. */
private fun rampBrush(stops: FloatArray, color: Color, startY: Float, endY: Float): Brush {
    val pairs = Array(stops.size / 2) { i -> stops[i * 2] to color.copy(alpha = color.alpha * stops[i * 2 + 1]) }
    return Brush.verticalGradient(*pairs, startY = startY, endY = endY)
}

/** Lo que pinta el fondo del hero: cambia (y se funde) cuando cambia cualquiera de los tres. */
private data class HeroBackdrop(val imagePath: String?, val fallback: ArtFallback?, val key: Any)

/** Acercamiento de 1,04 a 1 muy lento al llegar un fondo; con "reducir movimiento", quieto. */
@Composable
private fun Modifier.slowSettle(reduced: Boolean): Modifier {
    val zoom = remember { Animatable(if (reduced) 1f else 1.04f) }
    LaunchedEffect(Unit) {
        if (!reduced) zoom.animateTo(1f, spring(dampingRatio = 1f, stiffness = 10f))
    }
    return graphicsLayer {
        scaleX = zoom.value
        scaleY = zoom.value
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
