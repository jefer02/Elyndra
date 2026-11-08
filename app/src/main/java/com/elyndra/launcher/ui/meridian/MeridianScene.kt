package com.elyndra.launcher.ui.meridian

import android.os.Build
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.layout.Layout
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
import com.elyndra.launcher.ui.components.edgeBleed
import com.elyndra.launcher.ui.components.SafeAreaInsets
import com.elyndra.launcher.ui.components.edgeMirror
import com.elyndra.launcher.ui.theme.HeroText
import com.elyndra.launcher.ui.theme.LocalPerformance
import com.elyndra.launcher.ui.theme.LocalHeroText
import com.elyndra.launcher.ui.theme.GlassTokens
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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

/** Detrás de la rueda va el cristal (no el difuminado): las marcas del dial, sin velo, van blancas con sombra. */
internal val LocalWheelGlass = staticCompositionLocalOf { false }

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
 * ese [top]. Sin medir aún, sin curva. [reach] (dp) es hasta dónde tiene que
 * llegar la niebla en la cabecera (el final del dock y su aire; ver
 * [RailHeaderLayout.plan] y [MeridianFog.band]); 0 = sin banda. [screenTop]
 * y [screenBottom] (dp en la escena) son los filos de la pantalla, con la
 * franja de la barra de estado y la de abajo: entre ellos va el arco del
 * cristal claro ([MeridianScrims.glassArc]); sin medir (0 y 0), recto.
 */
@Immutable
internal data class FogArc(
    val top: Float,
    val focusY: Float,
    val radius: Float,
    val reach: Float = 0f,
    val screenTop: Float = 0f,
    val screenBottom: Float = 0f,
) {

    /** La línea de foco en la escena (dp). */
    fun focusIn(density: Float): Float = top / density + focusY

    /** El canto del cristal claro: el arco simétrico de la pantalla, abierto lo que pida la cabecera. */
    fun glassArc(axisX: Float, density: Float): GlassArc =
        MeridianScrims.glassArc(axisX, focusIn(density), screenTop, screenBottom, radius, reach)

    /** El canto que se ve de la niebla a la altura [y] de la escena (dp): el arco del cristal o el velo con la banda de arriba. */
    fun edgeAt(y: Float, axisX: Float, glass: Boolean, density: Float): Float =
        if (glass) glassArc(axisX, density).edgeAt(y)
        else MeridianFog.visibleEdge(y, axisX, focusIn(density), radius, MeridianFog.band(top / density, reach, axisX, glass = false))

    companion object {
        val NONE = FogArc(0f, 0f, 0f)

        /**
         * El círculo de una rueda de [railHeight] dp que empieza en [top] (px)
         * bajo su cabecera, en una ventana de [windowWidth] dp: lo mismo que
         * calcula la rueda al medirse ([MeridianGeometry.compute]). La cabecera
         * lo usa antes de que la rueda se mida, así el dock tiene su ancho de
         * verdad desde el primer fotograma.
         */
        fun forRail(windowWidth: Float, railHeight: Float, top: Float, aspect: Float, compact: Boolean, railWidth: Float, screenTop: Float = 0f, screenBottom: Float = 0f): FogArc {
            val geo = MeridianGeometry.compute(windowWidth, railHeight, aspect, compact, railWidth)
            return FogArc(top, geo.focusY, geo.arcRadius, screenTop = screenTop, screenBottom = screenBottom)
        }
    }
}

/**
 * La niebla de la rueda para la cabecera: [arc] (lo mide la rueda; la
 * cabecera escribe ahí su [FogArc.reach]), el eje [axisX] (dp) y si detrás
 * va el cristal claro ([glass]). Sin escena (vistas previas sueltas), null.
 *
 * Antes de que la rueda se mida, [arc] aún no tiene curva: la cabecera la
 * calcula ella misma ([FogArc.forRail]) con la ventana ([windowWidth] dp,
 * [compact], [tileAspect]) y lo que mide la fila de pistas ([hintsPx], que la
 * columna mide antes que la cabecera; ver [RailColumn]). Sin eso, el primer
 * fotograma medía el dock contra un canto recto y al siguiente encogía.
 */
@Stable
internal class RailFog(
    val arc: MutableState<FogArc>,
    val axisX: Float,
    val glass: Boolean,
    val windowWidth: Float,
    val compact: Boolean,
    val tileAspect: Float,
) {
    /** Alto de la fila de pistas (px) en esta medida: no es estado, se escribe justo antes de medir la cabecera. */
    var hintsPx: Int = 0
}

internal val LocalRailFog = staticCompositionLocalOf<RailFog?> { null }

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
    /** La cabecera de la rueda es la de la carpeta (dos filas): la columna se calcula con su alto. */
    folderHeader: Boolean = false,
    adaptive: Boolean = true,
    washPreset: ArtColors? = null,
    /** Sustituye al arte del juego (las vistas previas pintan un degradado). */
    backdrop: (@Composable () -> Unit)? = null,
    /** Detrás de la rueda: cristal curvo o el difuminado de siempre (ver [WheelBackdrop]). */
    wheelGlass: Boolean = WheelBackdrop.glass(null, P.isDark),
    /** Dónde está el círculo de la rueda (para curvar la niebla): lo fija la rueda al medirse; fuera, para el bocadillo de Masha. */
    fogArc: MutableState<FogArc> = remember { mutableStateOf(FogArc.NONE) },
    rail: @Composable BoxScope.(MeridianGeometry, WashState) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val skin = LocalSkin.current
    val ink = rememberMeridianInk()
    val lite = rememberMeridianLite()
    // El ajuste "Desenfoque" escala el del cristal; a 0, el cristal deja ver el arte nítido.
    val userBlur = skin.blur
    val dark = P.isDark
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth.value
        val screenHeight = maxHeight.value
        // El cristal que toca: el mismo en cualquier móvil con Android 12+ (ya no depende de la RAM),
        // salvo en Rendimiento: ahí va por su camino sin RenderEffect (el de Android 8–11),
        // el mismo panel con más tinte y velo, sin recalcular un desenfoque por fotograma.
        val liveBlur = LocalPerformance.current.liveBlur
        val glassSdk = if (liveBlur) Build.VERSION.SDK_INT else GlassPanelLayout.RENDER_EFFECT_API - 1
        val glassSpec = remember(width, wheelGlass, dark, userBlur, glassSdk) { GlassPanelLayout.resolve(width, glassSdk, wheelGlass, dark, userBlur) }
        val blurred = glassSpec.path == GlassPanelLayout.Path.Blur
        val compact = MeridianMode.compact(screenHeight)
        // Solo iconos en la rueda (el nombre está en el hero): la columna se estrecha y el arte gana sitio.
        val railWidth = MeridianGeometry.slimRailWidth(width, screenHeight, tileAspect, compact, folderHeader)
        val heroLeft = railWidth + MeridianGeometry.HERO_GAP
        val wash = rememberWash(art.imagePath, P.isDark, ink.primary, adaptive, washPreset)
        // El arte más alto y más ancho que la ventana, con el paralaje contrario a la rueda.
        val artLayer: (Any) -> Modifier = { key -> Modifier.meridianArtFrame(maxWidth).meridianParallax(reduced, wheel, artIndex, key) }
        val frame = remember(compact, width, railWidth, lite) { MeridianFrame(compact, width - railWidth, lite) }
        // Sin arte ni reserva (fila "Añadir", biblioteca vacía) el fondo es el escenario, que ya
        // lleva su luz: el óvalo y la franja de arriba, pensados para leer sobre arte, se apagan.
        val artShown by animateFloatAsState(if (backdrop != null || art.imagePath != null || art.fallback != null) 1f else 0f, motion(Springs.fade()), label = "artShown")
        val railFog = remember(fogArc, railWidth, wheelGlass, width, compact, tileAspect) { RailFog(fogArc, railWidth, wheelGlass, width, compact, tileAspect) }
        // El arte de verdad, grabado una vez por fotograma para que el cristal lo pinte desenfocado (ver FrostedArt).
        val heroLayer = rememberGraphicsLayer()
        val source = remember(heroLayer) { ArtBackdrop(heroLayer) }
        // Los filos de la pantalla (con la barra de estado y la franja de abajo): entre ellos va el arco del cristal.
        val density = LocalDensity.current
        val insets = SafeAreaInsets
        val screenTop = -insets.getTop(density) / density.density
        val screenBottom = screenHeight + insets.getBottom(density) / density.density
        SideEffect {
            val a = fogArc.value
            if (a.screenTop != screenTop || a.screenBottom != screenBottom) fogArc.value = a.copy(screenTop = screenTop, screenBottom = screenBottom)
        }

        CompositionLocalProvider(
            LocalMeridianFrame provides frame,
            LocalGlassFloor provides MeridianGlass.FLOOR,
            LocalWheelGlass provides wheelGlass,
            LocalRailFog provides railFog,
        ) {
            // Hasta el filo de la ventana, bajo la muesca. Con el cristal desenfocado se
            // graba aquí, dentro del fundido de entrada (el cristal lleva el suyo aparte).
            val heroModifier = Modifier
                .fillMaxSize()
                .edgeBleed()
                .clipToBounds()
                .graphicsLayer { alpha = MeridianMotion.heroFade(enterMs()) }
                .then(if (blurred) Modifier.recordBackdrop(source) else Modifier)
            if (backdrop != null) Box(heroModifier) { backdrop() } else HeroArtLayer(
                fallback = art.fallback,
                heroKey = art.key,
                imagePath = art.imagePath,
                backgroundVanishing = art.vanishing,
                onBackgroundVanished = art.onVanished,
                stage = { EmptyStageBackdrop(MERIDIAN_STAGE, Modifier.fillMaxSize()) },
                modifier = heroModifier,
                layer = artLayer,
            )
            Box(Modifier.fillMaxSize().edgeMirror(left = false, right = false, top = true).meridianScrims(heroLeft, skin.scrim, wash, if (compact) TOP_BAR_COMPACT else TOP_BAR, clean = true) { artShown })
            // Detrás de la rueda, lo elegido en Ajustes: el cristal curvo como la lista
            // (ver WheelGlass) o el difuminado de siempre.
            if (wheelGlass) {
                WheelGlassLayer(
                    state = wash,
                    spec = glassSpec,
                    axisX = railWidth,
                    arc = { fogArc.value },
                    frosted = if (!blurred) null else {
                        { FrostedArt(source, glassSpec.blurDp, glassSpec.blurDp * GlassPanelLayout.BLUR_BLEED, Modifier.graphicsLayer { alpha = MeridianMotion.heroFade(enterMs()) }) }
                    },
                    // Arriba y abajo crece hasta el filo de la pantalla (su arco va de punta a
                    // punta; reflejado, el canto inclinado hacía un pico bajo la barra de estado).
                    // A la izquierda, su canto se refleja en la franja de la muesca.
                    modifier = Modifier
                        .edgeMirror(right = false)
                        .edgeBleed(sides = false)
                        .fillMaxHeight()
                        .width(MeridianScrims.glassRight(fogArc.value.glassArc(railWidth, density.density), width, fogArc.value.reach).dp)
                        .graphicsLayer { alpha = MeridianMotion.heroFade(enterMs()) },
                )
            } else {
                MeridianWashLayer(
                    state = wash,
                    windowWidth = width,
                    axisX = railWidth,
                    artExtra = ART_EXTRA.value,
                    lite = lite,
                    arc = { fogArc.value },
                    modifier = Modifier.edgeMirror(right = false, top = true).fillMaxHeight().width(MeridianScrims.clearAt(railWidth, width).dp),
                )
            }

            Row(Modifier.fillMaxSize()) {
                RailColumn(
                    fog = railFog,
                    modifier = Modifier.width(railWidth.dp).fillMaxHeight(),
                    header = railHeader,
                    rail = {
                        BoxWithConstraints(
                            Modifier
                                .fillMaxSize()
                                .onPlaced { c ->
                                    val top = c.positionInParent().y
                                    if (fogArc.value.top != top) fogArc.value = fogArc.value.copy(top = top)
                                },
                        ) {
                            val geo = MeridianGeometry.compute(width, maxHeight.value, tileAspect, compact, railWidth)
                            SideEffect {
                                val a = fogArc.value
                                if (a.focusY != geo.focusY || a.radius != geo.arcRadius) fogArc.value = a.copy(focusY = geo.focusY, radius = geo.arcRadius)
                            }
                            rail(geo, wash)
                        }
                    },
                    hints = hints,
                )
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
                    // El texto del hero, blanco, sin el óvalo oscuro detrás, con su sombra sutil.
                    CompositionLocalProvider(LocalHeroText provides HeroText.CLEAN) {
                        Box(Modifier.fillMaxWidth().graphicsLayer { alpha = MeridianMotion.heroFade(enterMs()) }) { hero() }
                    }
                }
            }
            // En la misma fila que la cabecera de la rueda (mismo tope, misma línea central) y sin
            // llegar a donde puede asomar el dock (ver MeridianTopRow.barMaxWidth).
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = MeridianTopRow.top(compact).dp, end = MeridianTopRow.END.dp)
                    .widthIn(max = MeridianTopRow.barMaxWidth(width, railWidth).dp),
            ) { topEnd() }
            // Solo en depuración (src/debug): qué cristal se pinta y con qué valores, en el log y, si se pide, en pantalla.
            GlassDebug.Report(glassSpec, lite)
        }
    }
}

/**
 * La columna de la rueda: [header] arriba, [rail] con lo que queda y [hints]
 * abajo, como una `Column` con la rueda en `weight(1f)`, pero midiendo las
 * pistas antes que la cabecera: así la cabecera sabe cuánto alto le quedará a
 * la rueda y calcula la curva de la niebla ya en la primera medida (ver
 * [RailFog.hintsPx]). [rail] tiene que emitir un solo nodo.
 */
@Composable
private fun RailColumn(
    fog: RailFog,
    modifier: Modifier,
    header: @Composable () -> Unit,
    rail: @Composable () -> Unit,
    hints: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { header() }
            // Sin envoltorio: la rueda lee su `positionInParent` (el alto de la cabecera) para la niebla.
            rail()
            // Las pistas, ocultas, no emiten nada: la caja mantiene el sitio en la lista.
            Box { hints() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val hintsP = measurables[2].measure(loose)
        fog.hintsPx = hintsP.height
        val headerP = measurables[0].measure(loose)
        val railH = (constraints.maxHeight - headerP.height - hintsP.height).coerceAtLeast(0)
        val railP = measurables[1].measure(Constraints.fixed(constraints.maxWidth, railH))
        layout(constraints.maxWidth, constraints.maxHeight) {
            headerP.place(0, 0)
            railP.place(0, headerP.height)
            hintsP.place(0, constraints.maxHeight - hintsP.height)
        }
    }
}

/**
 * El marco del arte de Meridian: algo más alto que la ventana (al desplazarse
 * no asoma el canto) y más ancho ([MeridianArtFrame]), anclado a la izquierda:
 * su motivo cae a la derecha, fuera del velo. [window] es el ancho de la
 * ventana (la copia desenfocada va en una capa más estrecha y tiene que caer
 * igual que el arte de verdad).
 */
private fun Modifier.meridianArtFrame(window: Dp): Modifier = layout { measurable, constraints ->
    val extra = ART_EXTRA.roundToPx()
    val h = constraints.maxHeight + extra * 2
    val w = MeridianArtFrame.width(window.toPx()).toInt()
    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h, minWidth = w, maxWidth = w))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -extra) }
}

/** El paralaje del arte [key]: contrario al recorrido de la rueda, leído en la capa. */
private fun Modifier.meridianParallax(reduced: Boolean, wheel: () -> WheelState?, artIndex: (Any) -> Int?, key: Any): Modifier = graphicsLayer {
    if (reduced) return@graphicsLayer
    val w = wheel() ?: return@graphicsLayer
    val index = artIndex(key) ?: return@graphicsLayer
    val d = (w.position - index).coerceIn(-1.2f, 1.2f)
    translationY = -d * PARALLAX.toPx()
}

/** Más color: el cristal deja pasar el arte avivado, no un gris lechoso. */
private val GlassSaturation = android.graphics.ColorMatrix().apply { setSaturation(GlassTokens.SATURATION) }

/**
 * El arte de la escena, grabado en una capa ([layer]) donde se pinta: el
 * cristal la vuelve a pintar debajo de sí, desenfocada. Guarda dónde está el
 * arte ([origin]) y dónde el cristal ([target]) para pintarla con el mismo
 * desplazamiento, en coordenadas de pantalla. No es estado: se lee al pintar.
 */
@Stable
internal class ArtBackdrop(val layer: GraphicsLayer) {
    var origin: LayoutCoordinates? = null
    var target: LayoutCoordinates? = null

    /** Pinta el arte grabado en [scope] (el cristal), donde cae en la pantalla. */
    fun drawInto(scope: DrawScope) {
        val o = origin?.takeIf { it.isAttached } ?: return
        val t = target?.takeIf { it.isAttached } ?: return
        val at = t.localPositionOf(o, Offset.Zero)
        with(scope) { translate(at.x, at.y) { drawLayer(layer) } }
    }
}

/** Graba lo que pinta esta capa en [source] (y lo pinta igual que siempre). */
private fun Modifier.recordBackdrop(source: ArtBackdrop): Modifier = this
    .onPlaced { source.origin = it }
    .drawWithContent {
        source.layer.record { this@drawWithContent.drawContent() }
        drawLayer(source.layer)
    }

/**
 * El arte desenfocado dentro del cristal curvo del tema claro (API 31+): no
 * una segunda imagen, sino el mismo arte que se ve fuera ([ArtBackdrop]),
 * vuelto a pintar en el sitio del cristal, avivado ([GlassTokens.SATURATION])
 * y desenfocado [blurDp] (crece con la ventana, [GlassPanelLayout.blurDp]).
 *
 * Antes era otra imagen del arte con su propio marco: no llevaba el zoom de
 * llegada del hero (1,04 → 1 en unos dos segundos), así que, tras cada
 * cambio de juego (en una carpeta, a cada ROM que pasa), dentro del cristal
 * el arte iba un 4 % más pequeño y corrido (~35 dp en una tableta de
 * 1280 dp): el pelo, el logo o un contorno se veían duplicados y deformados.
 * Ahora, zoom, paralaje, fundidos, reserva y escenario vacío son los de fuera
 * por construcción, y no se decodifica nada más.
 *
 * Se pinta [bleed] dp más ancho que el cristal: el desenfoque del canto
 * derecho toma arte de verdad en vez de estirar su último píxel (lo que
 * sobra cae fuera del arco y se recorta). Solo es tan grande como el
 * cristal: la capa desenfocada ya no mide el marco entero del arte (1,35
 * veces la ventana).
 */
@Composable
private fun FrostedArt(source: ArtBackdrop, blurDp: Float, bleed: Float, modifier: Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val extra = bleed.dp.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + extra, constraints.maxHeight))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
            }
            .onPlaced { source.target = it }
            .graphicsLayer {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && GlassPanelLayout.blurs(blurDp)) {
                    val r = blurDp.dp.toPx()
                    val blur = android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP)
                    renderEffect = android.graphics.RenderEffect
                        .createColorFilterEffect(android.graphics.ColorMatrixColorFilter(GlassSaturation), blur)
                        .asComposeRenderEffect()
                }
            }
            .drawBehind { source.drawInto(this) },
    )
}
