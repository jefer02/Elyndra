package com.elyndra.launcher.ui.meridian

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import com.elyndra.launcher.ui.EmptyStage
import com.elyndra.launcher.ui.components.EmptyStageBackdrop
import com.elyndra.launcher.ui.theme.motion
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.HeroArtLayer
import com.elyndra.launcher.ui.masha.MashaQuality
import com.elyndra.launcher.ui.theme.LocalGlassFloor
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Springs

/** El arte del hero de Meridian: el del juego enfocado (o su reserva, o nada). */
internal data class MeridianArt(
    val key: Any,
    val imagePath: String?,
    val fallback: ArtFallback?,
    val vanishing: Boolean,
    val onVanished: () -> Unit,
)

/**
 * Lo que el resto de la escena necesita saber del reparto: si la ventana es
 * baja ([compact]), el ancho de la zona del hero (de la rueda al canto
 * derecho, dp; de ahí sale la caja del logo) y si va en calidad ligera ([lite]).
 */
@Immutable
internal data class MeridianFrame(val compact: Boolean, val heroWidth: Float, val lite: Boolean)

internal val LocalMeridianFrame = staticCompositionLocalOf { MeridianFrame(compact = false, heroWidth = 600f, lite = false) }

/**
 * El reloj de la entrada de Meridian (ms, ver [MeridianMotion]). Espera mientras
 * la intro tapa la pantalla ([introCovering]) y arranca cuando empieza a
 * fundirse: el dial se enciende mientras se apaga su rótulo, con el mismo
 * brillo, y la entrega se lee como un solo movimiento. En las vistas previas,
 * ya terminado.
 */
@Composable
internal fun rememberMeridianEnter(introCovering: Boolean): Animatable<Float, AnimationVector1D> {
    val still = LocalInspectionMode.current
    val enter = remember { Animatable(if (still) MeridianMotion.TOTAL_MS else 0f) }
    LaunchedEffect(introCovering) {
        if (introCovering || enter.value > 0f) return@LaunchedEffect
        enter.animateTo(MeridianMotion.TOTAL_MS, tween(MeridianMotion.TOTAL_MS.toInt(), easing = LinearEasing))
    }
    return enter
}

/** Calidad ligera (la de Masha): solo ±2 filas, sin giro, sin niebla y sin grano. */
@Composable
internal fun rememberMeridianLite(): Boolean {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    return remember { !preview && MashaQuality.detect(context) == MashaQuality.Lite }
}

/**
 * Cambio de sección (LB/RB o el dock): la rueda nueva entra deslizando en
 * vertical desde el lado hacia el que se va, con un fundido; la vieja sale por
 * el otro. Con "reducir movimiento", solo el fundido.
 */
internal fun <S> AnimatedContentTransitionScope<S>.sectionSwap(forward: Boolean, reduced: Boolean): ContentTransform {
    if (reduced) return (fadeIn(Springs.fade()) togetherWith fadeOut(snap()))
    val dir = if (forward) 1 else -1
    return (slideInVertically(Springs.enter()) { h -> dir * h / 6 } + fadeIn(Springs.fade()))
        .togetherWith(slideOutVertically(Springs.enter()) { h -> -dir * h / 6 } + fadeOut(Springs.exit()))
        .using(SizeTransform(clip = false))
}

/**
 * Las luces del escenario vacío en Meridian. El arte (y con él el escenario)
 * es un [MeridianArtFrame.FOCAL_SHIFT] más ancho que la ventana y va anclado
 * a la izquierda: las fracciones de [EmptyStage.MERIDIAN] se pasan a ese lienzo.
 */
private val MERIDIAN_STAGE = EmptyStage.MERIDIAN.let {
    val k = 1f + MeridianArtFrame.FOCAL_SHIFT
    it.copy(glowX = it.glowX / k, haloX = it.haloX / k, glowRadius = it.glowRadius / k, haloRadius = it.haloRadius / k)
}

/**
 * El círculo de la rueda visto desde la escena: [top] es donde empieza la
 * rueda (px, bajo su cabecera); [focusY] y [radius] van en dp, medidos desde
 * ese [top]. Sin medir aún, sin curva.
 */
@Immutable
internal data class FogArc(val top: Float, val focusY: Float, val radius: Float) {
    companion object {
        val NONE = FogArc(0f, 0f, 0f)
    }
}

/** Lo que se desplaza el arte en el paralaje (dp) por fila de distancia. */
private val PARALLAX = 18.dp

/** Lo que el arte sobresale arriba y abajo para que el paralaje no enseñe su canto. */
private val ART_EXTRA = PARALLAX * 1.3f

/** Alto de la barra de arriba (dp) que cubre el velo de arriba antes de apagarse: normal y en ventana baja. */
private const val TOP_BAR = 60f
private const val TOP_BAR_COMPACT = 50f

/**
 * Meridian: el arte del juego a pantalla completa (con su fundido y el
 * paralaje contrario al recorrido de la rueda), el fondo adaptable del arte
 * detrás de la rueda ([MeridianWashLayer]) y los velos, a la izquierda la
 * columna de la rueda —su cabecera arriba ([railHeader]), la rueda ([rail],
 * con la geometría de su hueco y el fondo) y las pistas del mando abajo
 * ([hints])— y a la derecha el bloque del hero ([hero]) y la barra ([topEnd]).
 *
 * El reparto sale del tamaño de la ventana, nunca del aparato: por debajo de
 * 480 dp de alto, todo en su versión compacta ([MeridianFrame]). Las píldoras
 * de cristal oscuro llevan aquí un suelo de tinta más alto ([MeridianGlass]).
 *
 * [adaptive] es "Color de fondo adaptable"; [washPreset] sustituye a la
 * lectura del arte (vistas previas).
 */
@Composable
internal fun MeridianScene(
    enterMs: () -> Float,
    art: MeridianArt,
    wheel: () -> WheelState?,
    artIndex: (Any) -> Int?,
    railHeader: @Composable () -> Unit,
    topEnd: @Composable () -> Unit,
    hints: @Composable () -> Unit,
    hero: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    /** Ancho/alto del arte de las tarjetas (1 iconos, 2/3 carátulas): de ahí sale su tamaño. */
    tileAspect: Float = 1f,
    adaptive: Boolean = true,
    washPreset: ArtColors? = null,
    /** Sustituye al arte del juego (las vistas previas pintan un degradado). */
    backdrop: (@Composable () -> Unit)? = null,
    rail: @Composable BoxScope.(MeridianGeometry, WashState) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val skin = LocalSkin.current
    val ink = rememberMeridianInk()
    val lite = rememberMeridianLite()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth.value
        val screenHeight = maxHeight.value
        val compact = MeridianMode.compact(screenHeight)
        val railWidth = MeridianGeometry.railWidthFor(width, compact)
        val heroLeft = railWidth + MeridianGeometry.HERO_GAP
        val wash = rememberWash(art.imagePath, P.isDark, ink.primary, adaptive, washPreset)
        val frame = remember(compact, width, railWidth, lite) { MeridianFrame(compact, width - railWidth, lite) }
        // Sin arte ni reserva (fila "Añadir", biblioteca vacía) el fondo es el escenario, que ya
        // lleva su luz: el óvalo y la franja de arriba, pensados para leer sobre arte, se apagan.
        val artShown by animateFloatAsState(if (backdrop != null || art.imagePath != null || art.fallback != null) 1f else 0f, motion(Springs.fade()), label = "artShown")
        // Dónde está el círculo de la rueda (para curvar la niebla): lo fija la rueda al medirse.
        val fogArc = remember { mutableStateOf(FogArc.NONE) }

        CompositionLocalProvider(LocalMeridianFrame provides frame, LocalGlassFloor provides MeridianGlass.FLOOR) {
            if (backdrop != null) backdrop() else HeroArtLayer(
                fallback = art.fallback,
                heroKey = art.key,
                imagePath = art.imagePath,
                backgroundVanishing = art.vanishing,
                onBackgroundVanished = art.onVanished,
                stage = { EmptyStageBackdrop(MERIDIAN_STAGE, Modifier.fillMaxSize()) },
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .graphicsLayer { alpha = MeridianMotion.heroFade(enterMs()) },
                layer = { key ->
                    Modifier
                        // El arte es algo más alto que la pantalla (al desplazarse no asoma el canto) y
                        // más ancho, anclado a la izquierda: su motivo cae a la derecha, fuera del velo.
                        .layout { measurable, constraints ->
                            val extra = ART_EXTRA.roundToPx()
                            val h = constraints.maxHeight + extra * 2
                            val w = MeridianArtFrame.width(constraints.maxWidth.toFloat()).toInt()
                            val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h, minWidth = w, maxWidth = w))
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -extra) }
                        }
                        .graphicsLayer {
                            if (reduced) return@graphicsLayer
                            val w = wheel() ?: return@graphicsLayer
                            val index = artIndex(key) ?: return@graphicsLayer
                            val d = (w.position - index).coerceIn(-1.2f, 1.2f)
                            translationY = -d * PARALLAX.toPx()
                        }
                },
            )
            Box(Modifier.fillMaxSize().meridianScrims(heroLeft, skin.scrim, wash, if (compact) TOP_BAR_COMPACT else TOP_BAR) { artShown })
            MeridianWashLayer(
                state = wash,
                windowWidth = width,
                axisX = railWidth,
                artExtra = ART_EXTRA.value,
                lite = lite,
                arc = { fogArc.value },
                modifier = Modifier.fillMaxHeight().width(MeridianScrims.clearAt(railWidth, width).dp),
            )

            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(railWidth.dp).fillMaxHeight()) {
                    railHeader()
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onPlaced { c ->
                                val top = c.positionInParent().y
                                if (fogArc.value.top != top) fogArc.value = fogArc.value.copy(top = top)
                            },
                    ) {
                        val geo = MeridianGeometry.compute(width, maxHeight.value, tileAspect, compact)
                        SideEffect {
                            val a = fogArc.value
                            if (a.focusY != geo.focusY || a.radius != geo.arcRadius) fogArc.value = a.copy(focusY = geo.focusY, radius = geo.arcRadius)
                        }
                        rail(geo, wash)
                    }
                    hints()
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            start = MeridianGeometry.HERO_GAP.dp,
                            end = 22.dp,
                            top = if (compact) 52.dp else 64.dp,
                            bottom = (screenHeight * if (compact) MeridianGeometry.HERO_BOTTOM_COMPACT else MeridianGeometry.HERO_BOTTOM).dp,
                        ),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = MeridianMotion.heroFade(enterMs()) }) { hero() }
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (compact) 6.dp else 8.dp, end = 22.dp)
                    .widthIn(max = (width - railWidth - 40f).coerceAtLeast(120f).dp),
            ) { topEnd() }
        }
    }
}
