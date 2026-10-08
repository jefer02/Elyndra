package com.elyndra.launcher.ui.meridian

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision
import com.elyndra.launcher.ui.TitleFit
import com.elyndra.launcher.ui.components.LocalScreenSize
import com.elyndra.launcher.ui.theme.LocalPoppins
import java.io.File
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.PadButtonGlyph
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.HeroTitleShadow
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.Swift
import com.elyndra.launcher.ui.theme.MinTouch
import com.elyndra.launcher.ui.theme.focusRing
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.outerShadow
import com.elyndra.launcher.ui.theme.pressFeedback
import com.elyndra.launcher.ui.theme.shapeClickable

/** Lo que va en el bloque del hero para un juego (o para "Añadir", o para nada). */
@Immutable
internal data class MeridianHeroContent(
    val key: Any?,
    val chips: List<String>,
    val title: String,
    val description: String?,
    /** Logo del juego (relativo a filesDir): sustituye al titular. */
    val logo: String? = null,
)

/** Las acciones del hero: la principal con la A y, si hay juego, la ficha (X) y las opciones (Y). */
@Immutable
internal data class MeridianActions(
    val primary: String,
    val onPrimary: () -> Unit,
    val details: String?,
    val onDetails: () -> Unit,
    val options: String?,
    val onOptions: () -> Unit,
)

/**
 * El bloque del hero, abajo a la izquierda del arte: fichas, logo (o
 * titular), la línea de sinopsis o datos (dos líneas como mucho) y las
 * acciones, con un ritmo de 8 dp. El logo se encaja en su caja
 * ([LogoFit]: hasta el 60 % de la zona del hero o 640 dp de ancho, entre el
 * 14 % y el 26 % del alto de la ventana); sin logo, el titular se ajusta a la
 * misma caja. Las fichas saltan de línea en vez de cortarse. En una ventana
 * baja ([MeridianFrame.compact]): dos fichas en una línea, sin sinopsis, logo
 * hasta el 20 % del alto y botones de 40 dp. Al cambiar de juego el bloque se funde y el titular se descubre con
 * un barrido de máscara y un brillo, como el rótulo de la intro; las acciones
 * se quedan quietas. Blanco sobre el óvalo oscuro de [meridianScrims].
 *
 * [padGlyphs]: hay un mando conectado (los botones enseñan su A, X e Y).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeridianHeroPanel(
    content: MeridianHeroContent,
    actions: MeridianActions?,
    ink: MeridianInk,
    padGlyphs: Boolean,
    modifier: Modifier = Modifier,
    logoSlot: @Composable (MeridianHeroContent, Modifier, LogoFit.Box) -> Unit = { _, _, _ -> },
) {
    val reduced = LocalReducedMotion.current
    val screen = LocalScreenSize.current
    val frame = LocalMeridianFrame.current
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier) {
        val box = LogoFit.box(frame.heroWidth, maxWidth.value, screen.height.value, frame.compact, density = density)
        Column(Modifier.fillMaxWidth()) {
            AnimatedContent(
                targetState = content,
                contentKey = { it.key },
                transitionSpec = {
                    if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                    else fadeIn(Springs.fade()) togetherWith fadeOut(Springs.exit())
                },
                contentAlignment = Alignment.BottomStart,
                label = "meridianInfo",
            ) { shown ->
                Column(Modifier.fillMaxWidth()) {
                    if (shown.chips.isNotEmpty()) {
                        val chips = if (frame.compact) shown.chips.take(COMPACT_CHIPS) else shown.chips
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            maxLines = if (frame.compact) 1 else Int.MAX_VALUE,
                        ) {
                            chips.forEachIndexed { i, chip -> MeridianChip(chip, lead = i == 0, ink = ink) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    val reveal = Modifier.wipeReveal(shown.key, ink.spark, reduced)
                    if (shown.logo != null) {
                        logoSlot(shown, reveal, box)
                    } else {
                        MeridianTitle(shown.title, box, reveal)
                    }
                    if (shown.description != null && !frame.compact) {
                        Spacer(Modifier.height(8.dp))
                        ElyText(
                            shown.description,
                            modifier = Modifier.widthIn(max = (box.maxWidth * 1.35f).dp),
                            size = 11.5f,
                            weight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.88f),
                            letterSpacing = tracking(0.02f),
                            lineHeightRatio = 1.32f,
                            shadow = HeroTitleShadow,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (actions != null) {
                Spacer(Modifier.height(if (frame.compact) 10.dp else 16.dp))
                MeridianActionRow(actions, ink, padGlyphs, compact = frame.compact)
            }
        }
    }
}

/**
 * El titular sin logo: el estilo del hero, en dos líneas como mucho y del
 * mayor cuerpo que quepa en [box] (entre [minSp] y [maxSp], ver [TitleFit]).
 * Nunca se corta con puntos suspensivos: si ni el mínimo cabe en dos
 * líneas, baja a una tercera.
 */
@Composable
internal fun MeridianTitle(
    text: String,
    box: LogoFit.Box,
    modifier: Modifier = Modifier,
    minSp: Float = TITLE_MIN_SP,
    maxSp: Float = TITLE_MAX_SP,
    align: TextAlign = TextAlign.Start,
) {
    val measurer = rememberTextMeasurer()
    val family = LocalPoppins.current
    val density = LocalDensity.current
    val shown = text.uppercase()
    val lineHeight = remember(shown) { TitleFit.lineHeight(shown) }
    val fitted = remember(shown, box, density, family, minSp, maxSp) {
        val maxW = with(density) { box.maxWidth.dp.roundToPx() }
        val maxH = with(density) { box.maxHeight.dp.toPx() }
        TitleFit.largest(minSp, maxSp) { sp ->
            val r = measurer.measure(
                shown,
                TextStyle(fontFamily = family, fontWeight = FontWeight.ExtraBold, fontSize = sp.sp, letterSpacing = (-0.03f).em, lineHeight = (sp * lineHeight).sp, lineBreak = LineBreak.Heading),
                maxLines = 2,
                constraints = Constraints(maxWidth = maxW.coerceAtLeast(1)),
                density = density,
            )
            !r.hasVisualOverflow && r.size.height <= maxH
        }
    }
    ElyText(
        text,
        modifier = modifier.widthIn(max = box.maxWidth.dp),
        size = fitted ?: minSp,
        weight = FontWeight.ExtraBold,
        color = Color.White,
        letterSpacing = tracking(-0.03f),
        lineHeightRatio = lineHeight,
        shadow = HeroTitleShadow,
        uppercase = true,
        align = align,
        maxLines = TitleFit.lines(fitted),
        overflow = TextOverflow.Clip,
        lineBreak = LineBreak.Heading,
    )
}

internal const val TITLE_MIN_SP = 22f
internal const val TITLE_MAX_SP = 88f

/**
 * El logo del juego en su caja. Se pide la imagen al tamaño de la caja (sin
 * ampliar al decodificar, y nunca más grande que ella) y se encaja sin
 * deformar; si es pequeña se amplía hasta [LogoFit.Box.maxUpscale] veces, con
 * filtrado de calidad. Lleva una sombra suave y un filo claro para leerse
 * sobre arte muy claro.
 */
@Composable
internal fun MeridianLogo(path: String, box: LogoFit.Box, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val w = with(density) { box.maxWidth.dp.roundToPx() }.coerceAtLeast(1)
    val h = with(density) { box.maxHeight.dp.roundToPx() }.coerceAtLeast(1)
    val request = remember(path, w, h) {
        ImageRequest.Builder(context)
            .data(File(context.filesDir, path))
            .size(w, h)
            .precision(Precision.INEXACT)
            .build()
    }
    val painter = rememberAsyncImagePainter(request, filterQuality = FilterQuality.High)
    MeridianLogoImage(painter, box, modifier)
}

/** El dibujo del logo (también para las vistas previas): caja, sombra, filo e imagen. */
@Composable
internal fun MeridianLogoImage(painter: Painter, box: LogoFit.Box, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val intrinsic = painter.intrinsicSize
    if (!intrinsic.isSpecified || intrinsic.width <= 0f || intrinsic.height <= 0f) {
        Spacer(modifier.height(box.minHeight.dp))
        return
    }
    val px = density.density
    val (fw, fh) = LogoFit.fit(box.copy(maxWidth = box.maxWidth * px, maxHeight = box.maxHeight * px, minHeight = box.minHeight * px), intrinsic.width, intrinsic.height)
    val shadow = remember { ColorFilter.tint(Color.Black) }
    val rim = remember { ColorFilter.tint(Color.White) }
    Canvas(modifier.size(with(density) { fw.toDp() }, with(density) { fh.toDp() })) {
        val step = 1.5.dp.toPx()
        for (k in 1..3) {
            translate(0f, step * k) { with(painter) { draw(size, alpha = 0.16f, colorFilter = shadow) } }
        }
        val one = 1.dp.toPx()
        for ((dx, dy) in RIM_OFFSETS) {
            translate(dx * one, dy * one) { with(painter) { draw(size, alpha = 0.22f, colorFilter = rim) } }
        }
        with(painter) { draw(size) }
    }
}

private val RIM_OFFSETS = listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)

/**
 * Las acciones del hero: "Jugar" (o "Abrir"), Ficha y Opciones, las tres del
 * mismo tamaño (mismo alto, el ancho de la más ancha y el mismo texto); la
 * principal destaca por su filo y su resplandor, no por su tamaño. Con un
 * mando conectado cada una enseña su botón (A, X, Y): el hueco del glifo se
 * abre y se cierra con una animación corta y las tres siguen iguales. Con la
 * letra grande el rótulo baja a una segunda línea y los tres botones crecen
 * a la vez (nunca se corta).
 */
@Composable
internal fun MeridianActionRow(actions: MeridianActions, ink: MeridianInk, padGlyphs: Boolean, compact: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val k by animateFloatAsState(PadGlyphs.target(padGlyphs), if (reduced) snap() else tween(220, easing = Swift), label = "padGlyphs")
    val height = if (compact) ACTION_HEIGHT_COMPACT else ACTION_HEIGHT
    Row(Modifier.width(IntrinsicSize.Max).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MeridianActionButton(actions.primary, "A", primary = true, glyph = k, ink = ink, height = height, onClick = actions.onPrimary, modifier = Modifier.weight(1f))
        if (actions.details != null) {
            MeridianActionButton(actions.details, "X", primary = false, glyph = k, ink = ink, height = height, onClick = actions.onDetails, modifier = Modifier.weight(1f))
        }
        if (actions.options != null) {
            MeridianActionButton(actions.options, "Y", primary = false, glyph = k, ink = ink, height = height, onClick = actions.onOptions, modifier = Modifier.weight(1f))
        }
    }
}

/** Alto visible y ancho mínimo de las acciones; se tocan en 48 dp de alto. */
private val ACTION_HEIGHT = 44.dp
private val ACTION_HEIGHT_COMPACT = 40.dp
private val ACTION_MIN_WIDTH = 116.dp

@Composable
private fun MeridianActionButton(label: String, button: String, primary: Boolean, glyph: Float, ink: MeridianInk, height: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(height / 2)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = MinTouch)
            .fillMaxHeight()
            .widthIn(min = ACTION_MIN_WIDTH)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(vertical = (MinTouch - height) / 2)
                .heightIn(min = height)
                .pressFeedback(interaction)
                .then(
                    if (primary) {
                        Modifier.outerShadow(14.dp, shape, ambientColor = ink.primary.copy(alpha = 0.6f), spotColor = ink.primary.copy(alpha = 0.6f))
                    } else {
                        Modifier
                    },
                )
                .darkGlass(shape, minAlpha = if (primary) PLAY_GLASS else HERO_GLASS)
                .drawWithCache {
                    val stroke = 1.5.dp.toPx()
                    val r = CornerRadius(height.toPx() / 2f)
                    val rim = if (primary) Brush.linearGradient(listOf(ink.primary, ink.secondary, ink.spark), Offset.Zero, Offset(size.width, size.height)) else null
                    val glow = if (primary) Brush.horizontalGradient(listOf(ink.primary.copy(alpha = 0.34f), ink.secondary.copy(alpha = 0.16f))) else null
                    onDrawBehind {
                        if (glow != null) drawRoundRect(glow, cornerRadius = r)
                        if (rim != null) drawRoundRect(rim, topLeft = Offset(stroke / 2f, stroke / 2f), size = Size(size.width - stroke, size.height - stroke), cornerRadius = r, style = Stroke(stroke))
                    }
                }
                .indication(interaction, focusRing(shape))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (glyph > 0f) {
                Box(
                    Modifier
                        .width(GLYPH_SLOT * glyph)
                        .clipToBounds()
                        .graphicsLayer {
                            alpha = glyph
                            val s = 0.6f + 0.4f * glyph
                            scaleX = s
                            scaleY = s
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.requiredWidth(GLYPH_SLOT), contentAlignment = Alignment.CenterStart) { PadButtonGlyph(button, onDark = true) }
                }
            }
            ElyText(label, size = 12.5f, weight = FontWeight.SemiBold, color = Color.White, letterSpacing = tracking(0.06f), align = TextAlign.Center, maxLines = 2)
        }
    }
}

/** El hueco del glifo del mando: el botón (20 dp) y su aire. */
private val GLYPH_SLOT = 28.dp

/**
 * Descubre el contenido de izquierda a derecha con un borde suave y, sobre
 * el frente, un brillo inclinado del destello de la paleta (el barrido del
 * rótulo de la intro). Mientras dura va en una capa aparte (la máscara y el
 * brillo solo tocan lo que ya está pintado); al acabar, la capa se quita.
 * Con "reducir movimiento", aparece sin más.
 */
@Composable
internal fun Modifier.wipeReveal(key: Any?, sheen: Color, reduced: Boolean): Modifier {
    val p = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key, reduced) {
        if (reduced) p.snapTo(1f) else p.animateTo(1f, tween(REVEAL_MS, easing = Swift))
    }
    return this
        .graphicsLayer { compositingStrategy = if (p.value < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
        .drawWithContent {
            val v = p.value
            drawContent()
            if (v >= 1f) return@drawWithContent
            val w = size.width
            val edge = (w * 0.22f).coerceAtLeast(24.dp.toPx())
            val front = -edge + v * (w + edge * 2f)
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = front - edge, endX = front),
                blendMode = BlendMode.DstIn,
            )
            val band = edge * 0.9f
            rotate(18f, Offset(front - edge * 0.5f, size.height / 2f)) {
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, sheen.copy(alpha = 0.95f), Color.Transparent),
                        startX = front - edge * 0.5f - band,
                        endX = front - edge * 0.5f + band,
                    ),
                    topLeft = Offset(front - edge * 0.5f - band, -size.height),
                    size = Size(band * 2f, size.height * 3f),
                    blendMode = BlendMode.SrcAtop,
                )
            }
        }
}

private const val REVEAL_MS = 620

/** Ficha del hero: cristal oscuro, versalitas; la primera lleva un punto de la paleta. Con la letra grande crece en alto, no se corta. */
@Composable
internal fun MeridianChip(label: String, lead: Boolean, ink: MeridianInk) {
    Row(
        Modifier
            .heightIn(min = 24.dp)
            .darkGlass(RoundedCornerShape(12.dp), minAlpha = HERO_GLASS)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lead) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .width(6.dp)
                    .height(6.dp)
                    .drawBehind {
                        drawCircle(Brush.linearGradient(listOf(ink.primary, ink.secondary)))
                    },
            )
        }
        ElyText(label, size = 8.5f, weight = FontWeight.SemiBold, color = Color.White, letterSpacing = tracking(0.14f), uppercase = true, maxLines = 2)
    }
}

/** Fichas del bloque del hero en una ventana baja (una línea). */
private const val COMPACT_CHIPS = 2

/** Las piezas del hero llevan el cristal casi opaco: tienen texto y pueden caer sobre cualquier arte. */
private const val HERO_GLASS = 0.6f

/** El cristal de la acción principal: lleva el texto de la acción sobre cualquier arte. */
private const val PLAY_GLASS = 0.62f

/**
 * El botón del mando junto a una pieza (la B de "Volver"): solo con un mando
 * conectado, y entra y sale con un fundido y una escala cortos.
 */
@Composable
internal fun PadGlyphBadge(button: String, gamepadPresent: Boolean) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = PadGlyphs.visible(gamepadPresent),
        enter = if (reduced) fadeIn(snap()) else fadeIn(Springs.fade()) + scaleIn(Springs.snappy(), initialScale = 0.6f) + expandHorizontally(Springs.snappy()),
        exit = if (reduced) fadeOut(snap()) else fadeOut(Springs.exit()) + scaleOut(Springs.exit(), targetScale = 0.6f) + shrinkHorizontally(Springs.exit()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(6.dp))
            PadButtonGlyph(button, onDark = P.isDark)
        }
    }
}

