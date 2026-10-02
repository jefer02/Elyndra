package com.elyndra.launcher.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.data.Palettes
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.ActionSheetSpec
import com.elyndra.launcher.ui.GroupStyle
import com.elyndra.launcher.ui.InputController
import com.elyndra.launcher.ui.SheetAction
import com.elyndra.launcher.ui.SheetHero
import com.elyndra.launcher.ui.SheetIcon
import com.elyndra.launcher.ui.SheetLayout
import com.elyndra.launcher.ui.SheetLayoutBinding
import com.elyndra.launcher.ui.UiText
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.LocalLandscape
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.consoleFocus
import com.elyndra.launcher.ui.theme.motion
import com.elyndra.launcher.ui.theme.outerShadow
import com.elyndra.launcher.ui.theme.shapeClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ─────────────────────────────────────────────────────────────
   Menú de acciones: el overlay que sale al mantener pulsada una card
   (o con Y en el mando).

   El fondo se aparta —desenfocado y oscurecido, a sangre, de borde a
   borde de la ventana— y delante aparece el panel.

   El menú de un juego es una TARJETA DE JUEGO, no una lista de ajustes:
     · Su arte manda. El fondo del juego es una banda arriba que se
       funde con el cristal; encima, su logo (si no hay, icono y título).
     · Su color. Acento, halo y aro de foco salen del propio arte
       ([ArtPalette]); si es gris, el acento del tema.
     · "Jugar" es el protagonista: un botón grande con la pista del
       botón A. El resto de acciones son piezas compactas.
     · Una línea de datos: tiempo jugado, última partida, plataforma y
       emulador.
     · Las imágenes, como tarjetas con la imagen de verdad a sangre, que
       se inclinan al recibir el foco.
     · Lo que borra, aparte y en rojo. Lo que se hace al momento (quitar
       una imagen) pide mantener pulsado o una segunda pulsación.

   SALE DE LA CARD. La superficie se transforma desde el rectángulo de
   la card (escala y posición) y el contenido entra escalonado; al
   cerrar, lo mismo al revés. Con "reducir movimiento", un fundido.

   Los demás menús (ordenar, emuladores, menú de la app) son una lista
   con el mismo cristal. El reparto y el foco del mando los decide
   [SheetLayout], el mismo que usa el InputController.
   ───────────────────────────────────────────────────────────── */

/**
 * Envuelve la pantalla y monta encima el menú de acciones.
 *
 * [content] es lo que queda detrás, desenfocado (Android 12+) y oscurecido
 * mientras el overlay está abierto. [origin] es el rectángulo, en coordenadas
 * de ventana, de la card de la que sale el panel.
 */
@Composable
fun GameActionOverlayContainer(
    isOverlayVisible: Boolean,
    onOverlayDismissed: () -> Unit,
    spec: ActionSheetSpec?,
    input: InputController,
    modifier: Modifier = Modifier,
    origin: Rect? = null,
    /**
     * Hay otra capa modal encima (un diálogo). El fondo se aparta igual
     * —desenfoque— pero sin velo ni panel: de eso se encarga la capa que esté
     * delante, y dos velos encima del mismo fondo lo dejarían negro.
     */
    dimForOtherLayer: Boolean = false,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    // Un solo progreso 0→1 gobierna velo y panel: entran y salen acompasados.
    // Se lee en fase de dibujo (graphicsLayer), así la animación no recompone.
    val open = animateFloatAsState(
        targetValue = if (isOverlayVisible) 1f else 0f,
        animationSpec = if (reduced) tween(REDUCED_FADE_MS) else Springs.enter(),
        label = "overlay",
    )
    val backdrop = animateFloatAsState(
        targetValue = if (isOverlayVisible || dimForOtherLayer) 1f else 0f,
        animationSpec = if (reduced) tween(REDUCED_FADE_MS) else Springs.fade(),
        label = "backdrop",
    )
    // Lo último que se enseñó: al cerrar, `spec` pasa a null enseguida y el
    // panel tiene que poder volver a su card con la animación de salida.
    val held = remember { HeldSheet() }
    if (spec != null) {
        held.spec = spec
        held.origin = origin
    }
    val shown = held.spec
    val visible by remember { derivedStateOf { open.value > 0.001f } }

    Box(modifier.fillMaxSize()) {
        // El fondo solo se desenfoca: nada de retroceso de escala, que dejaba
        // ver por los bordes lo que hay debajo (un marco claro sin desenfocar).
        Box(Modifier.fillMaxSize().backdropBlur(backdrop)) {
            content()
        }

        if (visible && shown != null) {
            // Velo a sangre: toda la ventana, también bajo las barras y la muesca.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = open.value.coerceIn(0f, 1f) }
                    .background(P.shade.copy(alpha = SCRIM_ALPHA))
                    // Tocar fuera cierra. El panel se come sus propios toques.
                    .consumeClicks { onOverlayDismissed() },
            )
            // El panel sí respeta las barras y la muesca.
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                    .padding(PANEL_MARGIN),
                contentAlignment = Alignment.Center,
            ) {
                ActionPanel(shown, held.origin, open, input, maxHeight)
            }
        }
    }
}

private class HeldSheet {
    var spec: ActionSheetSpec? = null
    var origin: Rect? = null
}

/**
 * Desenfoque del fondo, en fase de dibujo. `TileMode.Clamp`: el borde del
 * desenfoque repite los píxeles del borde, así que llega entero hasta el
 * filo de la ventana, sin marco transparente ni esquinas redondeadas.
 */
private fun Modifier.backdropBlur(amount: State<Float>): Modifier =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) this else graphicsLayer {
        val r = BACKGROUND_BLUR.dp.toPx() * amount.value.coerceIn(0f, 1f)
        renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
    }

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Rectángulo del panel en la ventana. No es estado: solo lo lee la transformación al dibujar. */
private class Bounds {
    var rect: Rect = Rect.Zero
}

/** El panel: superficie que se transforma desde la card y contenido encima. */
@Composable
private fun ActionPanel(
    spec: ActionSheetSpec,
    origin: Rect?,
    open: State<Float>,
    input: InputController,
    maxHeight: Dp,
) {
    val landscape = LocalLandscape.current
    val layout = remember(spec, landscape) { SheetLayout.of(spec, landscape) }
    SheetLayoutBinding(input, layout)
    val skin = LocalSkin.current
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val hero = spec.hero

    // El color del juego: sale de su arte (fondo, carátula, icono, logo) o del
    // icono de la app. Mientras se calcula, y si el arte es gris, el del tema.
    var artAccent by remember(hero) { mutableStateOf<Color?>(null) }
    LaunchedEffect(hero) {
        if (hero == null) return@LaunchedEffect
        val paths = listOfNotNull(hero.backgroundPath, hero.coverPath, hero.iconPath, hero.logoPath)
        val argb = runCatching { ArtPalette.load(context, paths) }.getOrNull()
            ?: hero.packageName?.let { runCatching { appIconAccent(context, it) }.getOrNull() }
        artAccent = argb?.let { Color(it) }
    }
    // Sin color en el arte, el primario de la marca (el relleno del acento).
    val base by animateColorAsState(artAccent ?: skin.a1, motion(Springs.fade()), label = "panelAccent")
    // Como contenido (texto, glifos, aro de foco) tiene que leerse sobre el panel en los dos temas.
    val accent = Color(Palettes.contentFor(base.argb(), P.isDark))
    val tone = panelTone(accent)
    val bounds = remember { Bounds() }
    val shape = RoundedCornerShape(PANEL_RADIUS)
    val maxWidth = if (layout.isHero && layout.twoColumns) HERO_WIDE_MAX else PANEL_MAX

    Box(
        Modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .onGloballyPositioned { bounds.rect = it.boundsInWindow() },
    ) {
        // La superficie: se transforma desde el rectángulo de la card.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { emergeSurface(bounds.rect, origin, open.value, reduced) }
                .outerShadow(30.dp, shape, ambientColor = P.shade, spotColor = P.shade)
                .clip(shape)
                .background(tone)
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = if (P.isDark) 0.22f else 0.7f), accent.copy(alpha = 0.4f))),
                    shape,
                ),
        )
        // El contenido, recortado a la misma forma: la banda del arte sigue las esquinas del panel.
        Box(
            Modifier
                .graphicsLayer {
                    emergeContent(bounds.rect, origin, open.value, reduced)
                    this.shape = shape
                    clip = true
                }
                .consumeClicks(),
        ) {
            if (hero != null) {
                HeroContent(spec, hero, layout, input, accent, tone, open, reduced)
            } else {
                ListContent(spec, layout, input, accent, tone, open, reduced)
            }
        }
    }
}

/** Color del cristal del panel: el papel con un toque del acento. Es también el de los fundidos. */
@Composable
@ReadOnlyComposable
private fun panelTone(accent: Color): Color {
    val base = if (P.isDark) P.surface else P.paper
    return accent.copy(alpha = if (P.isDark) 0.10f else 0.07f).compositeOver(base).copy(alpha = PANEL_ALPHA)
}

private fun GraphicsLayerScope.emergeSurface(panel: Rect, origin: Rect?, p: Float, reduced: Boolean) {
    if (reduced) {
        alpha = p.coerceIn(0f, 1f)
        return
    }
    if (origin == null || panel.width <= 0f || panel.height <= 0f) {
        val s = lerp(PANEL_MIN_SCALE, 1f, p)
        scaleX = s
        scaleY = s
        alpha = p.coerceIn(0f, 1f)
        return
    }
    // Del rectángulo de la card al del panel: la esquina de arriba a la
    // izquierda va de una a otra y el tamaño crece en cada eje por separado.
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = lerp(origin.width / panel.width, 1f, p)
    scaleY = lerp(origin.height / panel.height, 1f, p)
    translationX = lerp(origin.left - panel.left, 0f, p)
    translationY = lerp(origin.top - panel.top, 0f, p)
    alpha = (p * 2.5f).coerceIn(0f, 1f)
}

/** El contenido no se deforma: escala uniforme y un deslizamiento corto desde la card. */
private fun GraphicsLayerScope.emergeContent(panel: Rect, origin: Rect?, p: Float, reduced: Boolean) {
    if (reduced) {
        alpha = p.coerceIn(0f, 1f)
        return
    }
    val s = lerp(CONTENT_MIN_SCALE, 1f, p)
    scaleX = s
    scaleY = s
    if (origin != null && panel.width > 0f) {
        translationX = (origin.center.x - panel.center.x) * (1f - p) * CONTENT_TRAVEL
        translationY = (origin.center.y - panel.center.y) * (1f - p) * CONTENT_TRAVEL
    }
}

/**
 * Entrada escalonada de la parte [index] (0 = la de arriba), con el mismo
 * progreso que el panel: al cerrar, se van en orden inverso. En fase de dibujo.
 */
private fun Modifier.staggered(open: State<Float>, index: Int, reduced: Boolean): Modifier =
    if (reduced) this else graphicsLayer {
        val t = ((open.value - STAGGER_START - index * STAGGER_STEP) / STAGGER_SPAN).coerceIn(0f, 1f)
        alpha = t
        translationY = (1f - t) * 14.dp.toPx()
    }

/** Con el mando, lo señalado se trae a la vista si el panel se desplaza. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.revealWhen(focused: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) {
        if (focused) runCatching { requester.bringIntoView() }
    }
    return bringIntoViewRequester(requester)
}

/* ── menú de un juego ─────────────────────────────────────────── */

@Composable
private fun HeroContent(
    spec: ActionSheetSpec,
    hero: SheetHero,
    layout: SheetLayout,
    input: InputController,
    accent: Color,
    tone: Color,
    open: State<Float>,
    reduced: Boolean,
) {
    val scroll = rememberScrollState()
    // El aro del mando solo mientras se usa el mando: con el dedo sobra.
    val focus = if (input.active) input.sheetFocus else -1
    val armed = input.sheetArmed
    val run: (SheetAction) -> Unit = { a -> input.runSheetAction(layout.indexOf(a), a) }
    val artHeader = spec.groups.firstOrNull { it.style == GroupStyle.Thumbnails }?.header

    Box {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            HeroBanner(
                spec = spec,
                hero = hero,
                height = if (layout.twoColumns) BANNER_WIDE else BANNER_TALL,
                tone = tone,
                open = open,
                reduced = reduced,
                modifier = Modifier.staggered(open, 0, reduced),
            )
            val play: @Composable () -> Unit = {
                Column(Modifier.staggered(open, 1, reduced)) {
                    InfoLine(hero.info, accent)
                    layout.primary?.let { primary ->
                        Spacer(Modifier.height(10.dp))
                        PlayButton(primary, focused = focus == layout.indexOf(primary), accent = accent, gamepad = input.active) { run(primary) }
                    }
                }
            }
            val tiles: @Composable () -> Unit = {
                if (layout.tiles.isNotEmpty()) {
                    Column(Modifier.staggered(open, 2, reduced), verticalArrangement = Arrangement.spacedBy(GAP)) {
                        layout.tileRows().forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                                row.forEach { a ->
                                    ActionTile(a, focused = focus == layout.indexOf(a), accent = accent, modifier = Modifier.weight(1f)) { run(a) }
                                }
                            }
                        }
                    }
                }
            }
            val images: @Composable () -> Unit = {
                if (layout.art.isNotEmpty()) {
                    Column(Modifier.staggered(open, 3, reduced)) {
                        artHeader?.let { SectionLabel(it.resolve()) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                            layout.art.forEach { a ->
                                ArtTile(a, hero, focused = focus == layout.indexOf(a), accent = accent, reduced = reduced, modifier = Modifier.weight(1f)) { run(a) }
                            }
                        }
                    }
                }
            }
            val danger: @Composable () -> Unit = {
                if (layout.danger.isNotEmpty()) {
                    Column(Modifier.staggered(open, 4, reduced), verticalArrangement = Arrangement.spacedBy(GAP)) {
                        // La zona de borrado se separa con un filo rojo: no se llega a ella por inercia.
                        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(P.red.copy(alpha = 0.2f)))
                        layout.danger.forEach { a ->
                            val i = layout.indexOf(a)
                            DangerRow(a, i, focused = focus == i, armed = armed == i, input = input)
                        }
                    }
                }
            }

            if (layout.twoColumns) {
                Row(
                    Modifier.fillMaxWidth().padding(start = PAD, end = PAD, bottom = PAD),
                    horizontalArrangement = Arrangement.spacedBy(PAD),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                        play()
                        tiles()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                        images()
                        danger()
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(start = PAD, end = PAD, bottom = PAD),
                    verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
                ) {
                    play()
                    tiles()
                    images()
                    danger()
                }
            }
        }
        ScrollHints(scroll, tone, accent)
    }
}

/**
 * La banda del arte: el fondo del juego a sangre por arriba (se acerca un
 * poco al abrir), un velo que deja leer el logo y, en el último tramo, el
 * fundido al color del cristal. Sin arte, el arte de reserva del juego.
 */
@Composable
private fun HeroBanner(
    spec: ActionSheetSpec,
    hero: SheetHero,
    height: Dp,
    tone: Color,
    open: State<Float>,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(height)) {
        val art = hero.backgroundPath ?: hero.coverPath
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (!reduced) {
                        val z = 1.12f - 0.12f * open.value.coerceIn(0f, 1f)
                        scaleX = z
                        scaleY = z
                    }
                },
        ) {
            // Sin arte, la banda de reserva del juego (con su icono desenfocado detrás).
            ArtImage(art, hero.fallback, Modifier.fillMaxSize(), variant = ArtVariant.Banner, alignment = Alignment.TopCenter)
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // Velo de abajo arriba: el logo y el título se leen sobre cualquier arte.
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.4f to P.shade.copy(alpha = 0.12f),
                            1f to P.shade.copy(alpha = 0.62f),
                        ),
                    )
                    // Fundido al cristal: la banda no acaba en un corte, se disuelve en el panel.
                    val fade = FADE_INTO_GLASS.toPx()
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, tone), startY = size.height - fade, endY = size.height),
                        topLeft = Offset(0f, size.height - fade),
                        size = Size(size.width, fade),
                    )
                },
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = FADE_INTO_GLASS + 2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val logo = hero.logoPath
            if (logo == null && (hero.iconPath != null || hero.packageName != null)) {
                val iconShape = RoundedCornerShape(13.dp)
                GameIcon(
                    hero.iconPath,
                    hero.packageName,
                    Modifier
                        .size(50.dp)
                        .shadow(8.dp, iconShape, clip = false, ambientColor = P.shade, spotColor = P.shade)
                        .clip(iconShape),
                    ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (logo != null) {
                    LogoImage(
                        logo,
                        Modifier.fillMaxWidth(0.72f).height(56.dp),
                        alignment = Alignment.BottomStart,
                    )
                } else {
                    ElyText(
                        spec.title.resolve(),
                        size = 18f,
                        weight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = tracking(-0.01f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeightRatio = 1.15f,
                    )
                }
                spec.subtitle?.let {
                    Spacer(Modifier.height(3.dp))
                    ElyText(
                        it.resolve(),
                        size = 8.5f,
                        weight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.65f),
                        letterSpacing = tracking(0.04f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Tiempo jugado · última partida · plataforma · emulador, separados por puntos del acento. */
@Composable
private fun InfoLine(info: List<UiText>, accent: Color) {
    if (info.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        info.forEachIndexed { i, text ->
            if (i > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 7.dp)
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.8f)),
                )
            }
            ElyText(
                text.resolve(),
                modifier = Modifier.weight(1f, fill = false),
                size = 10f,
                weight = FontWeight.Medium,
                color = P.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Jugar": el protagonista. Degradado del color del juego, brillo arriba y la pista del botón A. */
@Composable
private fun PlayButton(action: SheetAction, focused: Boolean, accent: Color, gamepad: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(TILE_RADIUS)
    // Relleno con blanco encima: el color del juego oscurecido hasta el 4,5:1.
    val fill = remember(accent) { Color(Palettes.fillFor(accent.argb())) }
    val deep = remember(fill) { Color(ArtPalette.deeper(fill.toArgb())) }
    val lift = animateFloatAsState(if (focused) 1f else 0f, Springs.snappy(), label = "playLift")
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .revealWhen(focused)
            .graphicsLayer {
                val s = 1f + 0.02f * lift.value
                scaleX = s
                scaleY = s
            }
            .shadow(14.dp, shape, clip = false, ambientColor = fill, spotColor = fill)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(fill, deep)))
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), Color.Transparent), endY = size.height * 0.6f))
            }
            .consoleFocus(focused, shape, Color.White)
            .shapeClickable(shape, color = Color.White, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayGlyph(Color.White, size = 14.dp)
        Spacer(Modifier.width(12.dp))
        ElyText(
            action.label.resolve(),
            modifier = Modifier.weight(1f),
            size = 15f,
            weight = FontWeight.Bold,
            color = Color.White,
            letterSpacing = tracking(0.02f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // La pista del mando: se ve siempre, más viva mientras se usa el mando.
        PadHint("A", Color.White, strong = gamepad)
    }
}

/** El botón del mando que hace esto, en su círculo. */
@Composable
private fun PadHint(label: String, color: Color, strong: Boolean = true) {
    Box(
        Modifier
            .size(24.dp)
            .alpha(if (strong) 1f else 0.7f)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f))
            .border(1.2.dp, color.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        ElyText(label, size = 11f, weight = FontWeight.Bold, color = color)
    }
}

/** Una acción secundaria como pieza compacta: glifo, rótulo y, si lo hay, su dato. */
@Composable
private fun ActionTile(action: SheetAction, focused: Boolean, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(TILE_RADIUS)
    Column(
        modifier
            .height(TILE_H)
            .revealWhen(focused)
            .clip(shape)
            .background(tileFill())
            .border(1.dp, P.hairline.copy(alpha = 0.8f), shape)
            .consoleFocus(focused, shape, accent)
            .shapeClickable(shape, color = accent, onClick = onClick)
            .alpha(if (action.dimmed) 0.5f else 1f)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        action.icon?.let { SheetGlyph(it, accent, size = 20.dp) }
        Spacer(Modifier.height(5.dp))
        ElyText(
            action.label.resolve(),
            size = 9.5f,
            weight = FontWeight.SemiBold,
            color = P.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            align = TextAlign.Center,
        )
        action.detail?.let {
            ElyText(
                it.resolve(),
                size = 8f,
                color = P.ink2.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                align = TextAlign.Center,
            )
        }
    }
}

@Composable
@ReadOnlyComposable
private fun tileFill(): Color = if (P.isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.6f)

/**
 * Una clase de imagen con la imagen de verdad, a sangre (el logo, entero sobre
 * un fondo del color del juego). Con el foco se inclina y la imagen se mueve
 * un poco dentro, como una tarjeta que se toma en la mano.
 */
@Composable
private fun ArtTile(
    action: SheetAction,
    hero: SheetHero,
    focused: Boolean,
    accent: Color,
    reduced: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(TILE_RADIUS)
    val tilt = animateFloatAsState(if (focused && !reduced) 1f else 0f, Springs.snappy(), label = "artTilt")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = animateFloatAsState(
        if (pressed) 0.95f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "artPress",
    )
    val preview = action.preview
    val kind = action.icon

    Box(
        modifier
            .height(ART_TILE_H)
            .revealWhen(focused)
            .graphicsLayer {
                val t = tilt.value
                cameraDistance = 14f * density
                rotationX = 7f * t
                rotationY = -9f * t
                val s = press.value * (1f + 0.05f * t)
                scaleX = s
                scaleY = s
            }
            .shadow(6.dp, shape, clip = false, ambientColor = P.shade, spotColor = P.shade)
            .clip(shape)
            .background(ART_TILE_BACK)
            .consoleFocus(focused, shape, accent)
            .shapeClickable(shape, interactionSource = interaction, color = accent, onClick = onClick),
    ) {
        // La imagen se mueve contra la inclinación: el paralaje que da profundidad.
        val image = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val t = tilt.value
                val z = 1.06f + 0.05f * t
                scaleX = z
                scaleY = z
                translationX = -5.dp.toPx() * t
                translationY = 3.dp.toPx() * t
            }
        when {
            kind == SheetIcon.Logo && preview != null -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent.copy(alpha = 0.7f), P.shade))))
                LogoImage(preview, image.padding(horizontal = 10.dp, vertical = 12.dp), alignment = Alignment.Center)
            }
            preview != null -> ArtImage(preview, hero.fallback, image, showTitle = false)
            // Sin icono propio, el de la app (o del emulador): también es lo que se ve en la card.
            kind == SheetIcon.Icon && hero.packageName != null -> AppIconImage(hero.packageName, image, ContentScale.Crop)
            else -> {
                Box(Modifier.fillMaxSize().background(accent.copy(alpha = if (P.isDark) 0.22f else 0.16f)))
                kind?.let {
                    Box(Modifier.align(Alignment.Center).padding(bottom = 14.dp)) { SheetGlyph(it, accent, size = 22.dp) }
                }
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, P.shade.copy(alpha = 0.75f))))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ElyText(
                action.label.resolve(),
                modifier = Modifier.weight(1f),
                size = 9f,
                weight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Puesta a mano: un punto del acento.
            if (preview != null) Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        }
    }
}

/**
 * Una acción que borra. Si lo que hace no se confirma en ningún otro sitio
 * ([SheetAction.holdToConfirm]), pide mantener pulsado (la barra roja se
 * llena) o una segunda pulsación; con mando, A dos veces. Las demás (quitar el
 * juego) abren su diálogo de confirmación.
 */
@Composable
private fun DangerRow(action: SheetAction, index: Int, focused: Boolean, armed: Boolean, input: InputController) {
    val shape = RoundedCornerShape(TILE_RADIUS)
    val hold = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val fired = remember { BooleanArray(1) }
    val label = action.label.resolve()
    val armedGlow = animateFloatAsState(if (armed) 1f else 0f, Springs.fade(), label = "armed")
    LaunchedEffect(armed) {
        if (!armed) return@LaunchedEffect
        delay(ARM_TIMEOUT_MS)
        if (input.sheetArmed == index) input.disarmSheet()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .revealWhen(focused)
            .clip(shape)
            .background(P.red.copy(alpha = if (P.isDark) 0.14f else 0.07f))
            .drawBehind {
                val p = hold.value
                if (p > 0f) drawRect(P.red.copy(alpha = 0.22f), size = Size(size.width * p, size.height))
                val a = armedGlow.value
                if (a > 0f) drawRect(P.red.copy(alpha = 0.10f * a))
            }
            .border(1.dp, P.red.copy(alpha = if (armed) 0.75f else 0.32f), shape)
            .consoleFocus(focused, shape, P.red)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label) {
                    input.runSheetAction(index, action)
                    true
                }
            }
            .pointerInput(action) {
                detectTapGestures(
                    onPress = {
                        fired[0] = false
                        if (!action.holdToConfirm) {
                            tryAwaitRelease()
                            return@detectTapGestures
                        }
                        val job = scope.launch {
                            hold.snapTo(0f)
                            hold.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                            fired[0] = true
                            input.armSheet(index)
                            input.runSheetAction(index, action)
                        }
                        tryAwaitRelease()
                        if (!fired[0]) {
                            job.cancel()
                            scope.launch { hold.animateTo(0f, tween(200)) }
                        }
                    },
                    onTap = { if (!fired[0]) input.runSheetAction(index, action) },
                )
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        action.icon?.let { SheetGlyph(it, P.red, size = 17.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            ElyText(label, size = 12f, weight = FontWeight.SemiBold, color = P.red, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (action.holdToConfirm && armed) {
                ElyText(
                    stringResource(R.string.confirm_again),
                    size = 8.5f,
                    color = P.red.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (armed && input.active) PadHint("A", P.red)
    }
}

@Composable
private fun SectionLabel(text: String) {
    ElyText(
        text,
        modifier = Modifier.padding(start = 4.dp, bottom = 7.dp),
        size = 9f,
        weight = FontWeight.Bold,
        color = P.ink2.copy(alpha = 0.75f),
        letterSpacing = tracking(0.18f),
        uppercase = true,
    )
}

/* ── menús de lista ───────────────────────────────────────────── */

@Composable
private fun ListContent(
    spec: ActionSheetSpec,
    layout: SheetLayout,
    input: InputController,
    accent: Color,
    tone: Color,
    open: State<Float>,
    reduced: Boolean,
) {
    val scroll = rememberScrollState()
    val focus = if (input.active) input.sheetFocus else -1
    val armed = input.sheetArmed
    Column {
        // Cabecera en el mismo cristal que el resto: no es una capa aparte.
        Column(Modifier.fillMaxWidth().staggered(open, 0, reduced).padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp)) {
            ElyText(
                spec.title.resolve(),
                size = 16f,
                weight = FontWeight.ExtraBold,
                color = P.ink,
                letterSpacing = tracking(-0.01f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            spec.subtitle?.let {
                Spacer(Modifier.height(3.dp))
                ElyText(
                    it.resolve(),
                    size = 9f,
                    weight = FontWeight.Medium,
                    color = P.ink2.copy(alpha = 0.6f),
                    letterSpacing = tracking(0.04f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.weight(1f, fill = false)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(start = PAD, end = PAD, bottom = PAD),
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
            ) {
                layout.list.forEachIndexed { g, group ->
                    Column(Modifier.fillMaxWidth().staggered(open, 1 + g, reduced)) {
                        group.header?.let { SectionLabel(it.resolve()) }
                        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                            group.actions.forEach { a ->
                                val i = layout.indexOf(a)
                                if (a.destructive) {
                                    DangerRow(a, i, focused = focus == i, armed = armed == i, input = input)
                                } else {
                                    ActionRow(a, focused = focus == i, accent = accent) { input.runSheetAction(i, a) }
                                }
                            }
                        }
                    }
                }
            }
            ScrollHints(scroll, tone, accent)
        }
    }
}

/** Una fila de menú: glifo en su círculo, rótulo, dato y marca o galón. */
@Composable
private fun ActionRow(action: SheetAction, focused: Boolean, accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Hundirse al pulsar, con muelle al soltar.
    val press = animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val shape = RoundedCornerShape(TILE_RADIUS)
    Row(
        Modifier
            .fillMaxWidth()
            .revealWhen(focused)
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
            }
            .clip(shape)
            .background(tileFill())
            .border(1.dp, P.hairline.copy(alpha = 0.8f), shape)
            .consoleFocus(focused, shape, accent)
            .shapeClickable(shape, interactionSource = interaction, color = accent, onClick = onClick)
            .alpha(if (action.dimmed) 0.5f else 1f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (action.icon != null) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (pressed) 0.34f else if (P.isDark) 0.24f else 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                SheetGlyph(action.icon, accent, size = 17.dp)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            ElyText(
                action.label.resolve(),
                size = 13f,
                weight = if (action.selected) FontWeight.Bold else FontWeight.SemiBold,
                color = P.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            action.detail?.let {
                Spacer(Modifier.height(2.dp))
                ElyText(it.resolve(), size = 9.5f, color = P.ink2.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (action.selected || action.opensSheet) {
            Spacer(Modifier.width(10.dp))
            if (action.selected) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
            } else {
                SheetChevron(P.ink2.copy(alpha = 0.55f))
            }
        }
    }
}

/* ── pistas de desplazamiento ─────────────────────────────────── */

/**
 * Si el contenido no cabe: fundidos del color del cristal en el filo por el
 * que sigue y, abajo, un galón que late y que baja al tocarlo. Nada de barras.
 */
@Composable
private fun BoxScope.ScrollHints(scroll: ScrollState, tone: Color, accent: Color) {
    val up by remember { derivedStateOf { scroll.canScrollBackward } }
    val down by remember { derivedStateOf { scroll.canScrollForward } }
    if (up) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(22.dp)
                .background(Brush.verticalGradient(listOf(tone, tone.copy(alpha = 0f)))),
        )
    }
    if (down) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(46.dp)
                .background(Brush.verticalGradient(listOf(tone.copy(alpha = 0f), tone))),
        )
        MoreHint(scroll, accent, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
    }
}

@Composable
private fun MoreHint(scroll: ScrollState, accent: Color, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val bob = if (reduced) null else rememberInfiniteTransition(label = "more")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "bob")
    Box(
        modifier
            .graphicsLayer { translationY = (bob?.value ?: 0f) * 3.dp.toPx() }
            .size(width = 38.dp, height = 22.dp)
            .shapeClickable(CircleShape, color = accent) { scope.launch { scroll.animateScrollBy(scroll.viewportSize * 0.6f) } }
            .background(accent.copy(alpha = 0.16f), CircleShape)
            .border(1.dp, accent.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .graphicsLayer { rotationZ = 90f },
        ) { SheetChevron(accent, size = 12.dp) }
    }
}

/* ── el tirón de la card ──────────────────────────────────────── */

/**
 * El tirón magnético de la pulsación larga.
 *
 * Devuelve la escala que hay que aplicarle a la card y la función que lo
 * dispara. El gesto son dos tiempos: primero se hunde deprisa —el dedo
 * "agarra" la card— y luego un muelle la suelta por encima de su tamaño antes
 * de asentarla. Ese rebote es lo que hace que el menú parezca salir *de* la
 * card y no delante de ella.
 */
@Composable
fun rememberMagneticPress(): MagneticPress {
    val scale = remember { Animatable(1f) }
    return remember {
        MagneticPress(
            scale = scale,
            pull = {
                scale.animateTo(PULL_SCALE, Springs.snappy())
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
            },
        )
    }
}

/** La escala viva de la card y el gesto que la dispara (ver [rememberMagneticPress]). */
class MagneticPress(
    private val scale: Animatable<Float, *>,
    private val pull: suspend () -> Unit,
) {
    /** Se lee en fase de dibujo: mover la card no recompone nada. */
    val value: Float get() = scale.value

    suspend fun run() = pull()
}

/** Lo que tarda el tirón antes de soltar el muelle (lo espera quien abre el menú). */
const val MAGNETIC_PULL_MS = 120

private const val PULL_SCALE = 0.9f

/** Radio del desenfoque del fondo, en dp (Android 12+). */
private const val BACKGROUND_BLUR = 18f

private const val SCRIM_ALPHA = 0.5f

/** Con "reducir movimiento": un fundido corto y nada más. */
private const val REDUCED_FADE_MS = 160

/** Escala de la que sale el panel cuando no viene de una card. */
private const val PANEL_MIN_SCALE = 0.9f

/** El contenido crece desde un poco más pequeño y recorre parte del camino desde la card. */
private const val CONTENT_MIN_SCALE = 0.92f
private const val CONTENT_TRAVEL = 0.35f

/** Escalonado de la entrada: cuándo empieza, cuánto se retrasa cada parte y cuánto dura. */
private const val STAGGER_START = 0.25f
private const val STAGGER_STEP = 0.07f
private const val STAGGER_SPAN = 0.45f

/** Opacidad del cristal del panel: se transparenta un pelo del fondo desenfocado. */
private const val PANEL_ALPHA = 0.93f

private const val HOLD_MS = 850
private const val ARM_TIMEOUT_MS = 3_000L

private val PANEL_MARGIN = 14.dp
private val PANEL_RADIUS = 28.dp
private val PANEL_MAX = 460.dp
private val HERO_WIDE_MAX = 780.dp
private val PAD = 16.dp

/** Radio de las piezas de dentro: el del panel menos su margen (concéntricas). */
private val TILE_RADIUS = PANEL_RADIUS - PAD
private val GAP = 8.dp
private val SECTION_GAP = 14.dp
private val TILE_H = 68.dp
private val ART_TILE_H = 76.dp
private val BANNER_TALL = 152.dp
private val BANNER_WIDE = 112.dp
private val FADE_INTO_GLASS = 22.dp
private val ART_TILE_BACK get() = P.mediaBack
