package com.elyndra.launcher.ui.screens.ports

import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.touchTarget
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elyndra.launcher.R
import com.elyndra.launcher.domain.ports.PortAction
import com.elyndra.launcher.domain.ports.PortDisplay
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.ui.StoreLayout
import com.elyndra.launcher.ui.TileStatus
import com.elyndra.launcher.ui.components.DotTabIcon
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Store

/* ─────────────────────────────────────────────────────────────
   Piezas de la tienda de ports: baldosas cuadradas con su línea de
   estado, tarjetas apaisadas de los carruseles, carteles grandes,
   botones redondos y los glifos, todo dibujado a mano.

   El foco es siempre el mismo: un marco blanco de 3 dp con una
   rendija oscura entre él y la pieza, y un pequeño crecimiento con
   muelle en las baldosas (StoreLayout.FOCUS_*).
   ───────────────────────────────────────────────────────────── */

/**
 * El icono de la tienda en el dock y en su baldosa: una bolsa con asa. Se
 * dibuja con el color que toca (ver [DotTabIcon]).
 */
val StoreGlyph: DotTabIcon = { color ->
    val w = size.width
    val h = size.height
    val stroke = w * 0.11f
    // El asa.
    drawArc(
        color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(w * 0.3f, h * 0.06f),
        size = Size(w * 0.4f, h * 0.42f),
        style = Stroke(stroke),
    )
    // La bolsa, un poco más ancha abajo.
    val bag = Path().apply {
        moveTo(w * 0.16f, h * 0.32f)
        lineTo(w * 0.84f, h * 0.32f)
        lineTo(w * 0.92f, h * 0.94f)
        lineTo(w * 0.08f, h * 0.94f)
        close()
    }
    drawPath(bag, color)
}

/** Los iconos de la fila de pestañas y de la cabecera. */
enum class StoreIcon { Search, Heart, HeartFilled, Download, More, Sort, Players, Size, Version, Calendar, Link, Gamepad }

/** [icon] en blanco (o [color]) en un cuadro de [size]. */
@Composable
fun StoreIconGlyph(icon: StoreIcon, color: Color = Store.text, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) { drawStoreIcon(icon, color) }
}

private fun DrawScope.drawStoreIcon(icon: StoreIcon, color: Color) {
    val w = size.width
    val h = size.height
    val t = w * 0.09f
    val stroke = Stroke(t, cap = StrokeCap.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(w * x1, h * y1), Offset(w * x2, h * y2), t, StrokeCap.Round)
    fun heart(): Path = Path().apply {
        moveTo(w * 0.5f, h * 0.88f)
        cubicTo(w * 0.05f, h * 0.6f, w * 0.02f, h * 0.3f, w * 0.22f, h * 0.18f)
        cubicTo(w * 0.36f, h * 0.1f, w * 0.48f, h * 0.2f, w * 0.5f, h * 0.3f)
        cubicTo(w * 0.52f, h * 0.2f, w * 0.64f, h * 0.1f, w * 0.78f, h * 0.18f)
        cubicTo(w * 0.98f, h * 0.3f, w * 0.95f, h * 0.6f, w * 0.5f, h * 0.88f)
        close()
    }
    when (icon) {
        StoreIcon.Search -> {
            drawCircle(color, w * 0.28f, Offset(w * 0.42f, h * 0.42f), style = stroke)
            line(0.63f, 0.63f, 0.88f, 0.88f)
        }
        StoreIcon.Heart -> drawPath(heart(), color, style = stroke)
        StoreIcon.HeartFilled -> drawPath(heart(), color)
        StoreIcon.Download -> {
            line(0.5f, 0.12f, 0.5f, 0.64f)
            line(0.3f, 0.45f, 0.5f, 0.65f)
            line(0.7f, 0.45f, 0.5f, 0.65f)
            line(0.16f, 0.86f, 0.84f, 0.86f)
        }
        StoreIcon.More -> for (x in listOf(0.2f, 0.5f, 0.8f)) drawCircle(color, w * 0.08f, Offset(w * x, h * 0.5f))
        StoreIcon.Sort -> {
            // Tres renglones que menguan y una flecha hacia abajo: "ordenar".
            line(0.42f, 0.24f, 0.88f, 0.24f)
            line(0.42f, 0.5f, 0.76f, 0.5f)
            line(0.42f, 0.76f, 0.64f, 0.76f)
            line(0.2f, 0.16f, 0.2f, 0.84f)
            line(0.08f, 0.7f, 0.2f, 0.84f)
            line(0.32f, 0.7f, 0.2f, 0.84f)
        }
        StoreIcon.Players -> {
            drawCircle(color, w * 0.18f, Offset(w * 0.5f, h * 0.3f), style = stroke)
            drawArc(color, 200f, 140f, false, Offset(w * 0.14f, h * 0.56f), Size(w * 0.72f, h * 0.6f), style = stroke)
        }
        StoreIcon.Size -> {
            drawRoundRect(color, Offset(w * 0.18f, h * 0.12f), Size(w * 0.64f, h * 0.76f), CornerRadius(w * 0.08f), style = stroke)
            line(0.32f, 0.38f, 0.68f, 0.38f)
            line(0.32f, 0.56f, 0.6f, 0.56f)
        }
        StoreIcon.Version -> {
            val tag = Path().apply {
                moveTo(w * 0.12f, h * 0.2f)
                lineTo(w * 0.55f, h * 0.2f)
                lineTo(w * 0.88f, h * 0.5f)
                lineTo(w * 0.55f, h * 0.8f)
                lineTo(w * 0.12f, h * 0.8f)
                close()
            }
            drawPath(tag, color, style = stroke)
            drawCircle(color, w * 0.06f, Offset(w * 0.3f, h * 0.5f))
        }
        StoreIcon.Calendar -> {
            drawRoundRect(color, Offset(w * 0.14f, h * 0.2f), Size(w * 0.72f, h * 0.66f), CornerRadius(w * 0.08f), style = stroke)
            line(0.14f, 0.4f, 0.86f, 0.4f)
            line(0.34f, 0.1f, 0.34f, 0.28f)
            line(0.66f, 0.1f, 0.66f, 0.28f)
        }
        StoreIcon.Link -> {
            line(0.42f, 0.58f, 0.86f, 0.14f)
            line(0.6f, 0.14f, 0.86f, 0.14f)
            line(0.86f, 0.14f, 0.86f, 0.4f)
            drawRoundRect(color, Offset(w * 0.14f, h * 0.24f), Size(w * 0.6f, h * 0.62f), CornerRadius(w * 0.06f), style = stroke)
        }
        StoreIcon.Gamepad -> {
            drawRoundRect(color, Offset(w * 0.08f, h * 0.28f), Size(w * 0.84f, h * 0.48f), CornerRadius(w * 0.22f), style = stroke)
            line(0.26f, 0.52f, 0.42f, 0.52f)
            line(0.34f, 0.44f, 0.34f, 0.6f)
            drawCircle(color, w * 0.05f, Offset(w * 0.64f, h * 0.48f))
            drawCircle(color, w * 0.05f, Offset(w * 0.74f, h * 0.58f))
        }
    }
}

/** El nombre corto de la plataforma original ("N64", "XBOX 360"). */
fun platformLabel(platform: String): String = PortDisplay.platformBadge(platform)

/** La línea secundaria de un port: "Port: Ship of Harkinian · por HarbourMasters" (nunca un identificador). */
@Composable
fun portLineText(port: PortEntry): String {
    val line = PortDisplay.portLine(port)
    return when {
        line.portName != null && line.developer != null -> stringResource(R.string.ports_port_line, line.portName, line.developer)
        line.portName != null -> stringResource(R.string.ports_port_line_short, line.portName)
        line.developer != null -> stringResource(R.string.ports_port_badge_named, line.developer)
        else -> stringResource(R.string.ports_port_badge)
    }
}

/** Las insignias de confianza de un port: lo que dice si se instala primero. */
fun trustBadges(port: PortEntry): List<Int> = buildList {
    if (port.vrOnly) add(R.string.ports_badge_vr_only) else if (port.notOnAndroid) add(R.string.ports_action_pc_only)
    if (port.trust.debugBuild) add(R.string.ports_badge_debug)
    if (!port.verified) add(R.string.ports_unverified)
    if (port.trust.prerelease) add(R.string.ports_badge_prerelease)
    if (port.trust.checkRequirements) add(R.string.ports_badge_check_requirements)
}

@StringRes
fun kindLabel(kind: String): Int = when (kind) {
    "recomp" -> R.string.ports_kind_recomp
    "reimpl" -> R.string.ports_kind_reimpl
    else -> R.string.ports_kind_decomp
}

@StringRes
fun actionLabel(action: PortAction): Int = when (action) {
    PortAction.Free -> R.string.ports_action_free
    PortAction.Install -> R.string.ports_action_install
    PortAction.Update -> R.string.ports_action_update
    PortAction.Installed -> R.string.ports_action_installed
    PortAction.NotOnAndroid -> R.string.ports_action_pc_only
    PortAction.VrOnly -> R.string.ports_action_vr_only
    PortAction.Unavailable -> R.string.ports_action_unavailable
    PortAction.SignatureChanged -> R.string.ports_action_signature_changed
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1_048_576 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}

/**
 * El marco del foco: blanco de [StoreLayout.FOCUS_BORDER] dp por fuera de la
 * pieza, separado de ella por una rendija oscura. Se pinta fuera de los
 * límites (la pieza no lo recorta) y aparece con un fundido corto.
 */
@Composable
fun Modifier.storeFocusRing(focused: Boolean, radius: Dp, circle: Boolean = false): Modifier {
    val reduced = LocalReducedMotion.current
    val alpha by animateFloatAsState(if (focused) 1f else 0f, if (reduced) tween(0) else tween(StoreLayout.FOCUS_MS), label = "ring")
    return drawBehind {
        if (alpha <= 0f) return@drawBehind
        val gap = StoreLayout.FOCUS_GAP.dp.toPx()
        val border = StoreLayout.FOCUS_BORDER.dp.toPx()
        val out = gap + border / 2f
        if (circle) {
            drawCircle(Color.Black.copy(alpha = 0.85f * alpha), size.minDimension / 2f + gap / 2f, style = Stroke(gap))
            drawCircle(Store.focus.copy(alpha = alpha), size.minDimension / 2f + out, style = Stroke(border))
        } else {
            val r = radius.toPx()
            drawRoundRect(
                Color.Black.copy(alpha = 0.85f * alpha),
                Offset(-gap / 2f, -gap / 2f),
                Size(size.width + gap, size.height + gap),
                CornerRadius(r + gap / 2f),
                style = Stroke(gap),
            )
            drawRoundRect(
                Store.focus.copy(alpha = alpha),
                Offset(-out, -out),
                Size(size.width + 2 * out, size.height + 2 * out),
                CornerRadius(r + out),
                style = Stroke(border),
            )
        }
    }
}

/**
 * El `clickable` de la tienda: sin el realce de Elyndra por dentro (el foco
 * es el marco blanco de [storeFocusRing]); va después del recorte de la pieza.
 */
@Composable
fun Modifier.storeClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/** Fondo de la tienda: casi negro, con un brillo suave arriba a la izquierda y viñeta (ver [Store.background]). */
fun Modifier.storeBackground(): Modifier = drawBehind {
    drawRect(Store.bg)
    Store.background(size.width, size.height).forEach { drawRect(it) }
}

/**
 * Una imagen remota o local con un respaldo oscuro y el nombre mientras
 * carga o si falla (sin red las imágenes no llegan).
 */
@Composable
fun StoreImage(model: Any?, fallback: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var failed by remember(model) { mutableStateOf(model == null) }
    Box(modifier.background(Brush.linearGradient(listOf(Store.tileHigh, Store.tile)))) {
        if (!failed) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
            )
        } else if (fallback.isNotEmpty()) {
            ElyText(
                fallback,
                Modifier
                    .align(Alignment.Center)
                    .padding(10.dp),
                size = 13f,
                weight = FontWeight.Light,
                color = Store.textDim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** La caja blanca con letra negra (NUEVO, ACTUALIZADO, v1.3), como la de un descuento. */
@Composable
fun StoreBadge(text: String, modifier: Modifier = Modifier) {
    ElyText(
        text,
        modifier
            .background(Store.badge, RoundedCornerShape(2.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        size = 9.5f,
        weight = FontWeight.Bold,
        color = Store.badgeText,
        maxLines = 1,
        uppercase = true,
    )
}

/** El texto de [status] (sin la caja ni lo tachado). */
@Composable
fun tileStatusText(status: TileStatus): String = when (status.text) {
    TileStatus.Text.Free -> stringResource(R.string.ports_action_free)
    TileStatus.Text.Size -> formatSize(status.size)
    TileStatus.Text.Installed -> stringResource(R.string.ports_action_installed)
    TileStatus.Text.Update -> stringResource(R.string.ports_action_update)
    TileStatus.Text.Downloading -> "${status.percent} %"
    TileStatus.Text.NotOnAndroid -> stringResource(R.string.ports_action_pc_only)
    TileStatus.Text.VrOnly -> stringResource(R.string.ports_action_vr_only)
    TileStatus.Text.Unavailable -> stringResource(R.string.ports_action_unavailable)
}

/** La línea de estado de una baldosa: [caja] texto ~~tachado~~. */
@Composable
fun TileStatusLine(status: TileStatus, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        status.badge?.let { b ->
            StoreBadge(
                when (b) {
                    TileStatus.Badge.New -> stringResource(R.string.store_badge_new)
                    TileStatus.Badge.Updated -> stringResource(R.string.store_badge_updated)
                    is TileStatus.Badge.Version -> b.tag
                },
            )
            Spacer(Modifier.width(6.dp))
        }
        ElyText(tileStatusText(status), size = 11.5f, weight = FontWeight.Medium, color = Store.text, maxLines = 1)
        status.struck?.let {
            Spacer(Modifier.width(6.dp))
            ElyText(it, size = 11f, color = Store.textDim, maxLines = 1, decoration = TextDecoration.LineThrough)
        }
    }
}

/**
 * Una baldosa cuadrada de la rejilla: el arte recortado a sangre y, abajo a
 * la izquierda sobre un degradado oscuro, su línea de estado. Con el foco
 * crece un poco con un muelle, lleva el marco blanco y enseña el nombre del
 * juego encima de la línea de estado; sin él, se apaga un poco.
 */
@Composable
fun StoreTile(
    port: PortEntry,
    status: TileStatus,
    art: Any?,
    side: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
) {
    val shape = RoundedCornerShape(StoreLayout.TILE_RADIUS.dp)
    val reduced = LocalReducedMotion.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) StoreLayout.FOCUS_SCALE else 1f,
        if (reduced) tween(0) else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "tileScale",
    )
    val dim by animateFloatAsState(if (focused) 0f else 0.14f, if (reduced) tween(0) else tween(StoreLayout.FOCUS_MS), label = "tileDim")
    val title = PortDisplay.title(port)
    Box(
        modifier
            .size(side)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .storeFocusRing(focused, StoreLayout.TILE_RADIUS.dp)
            .semantics { contentDescription = title }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clip(shape)
            .storeClickable(onClick = onClick),
    ) {
        StoreImage(art ?: port.image, title, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Store.scrim)),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 9.dp)) {
            if (focused) {
                ElyText(title, size = 12.5f, weight = FontWeight.Normal, color = Store.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
            }
            TileStatusLine(status)
        }
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}

/** Una tarjeta apaisada (16:9) de un carrusel: el arte y, abajo, el nombre y su estado. */
@Composable
fun StoreCard(
    port: PortEntry,
    status: TileStatus,
    art: Any?,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
) {
    val shape = RoundedCornerShape(StoreLayout.TILE_RADIUS.dp)
    val reduced = LocalReducedMotion.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) StoreLayout.FOCUS_SCALE else 1f,
        if (reduced) tween(0) else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "cardScale",
    )
    val title = PortDisplay.title(port)
    Box(
        modifier
            .width(width)
            .height(StoreLayout.cardHeight(width.value).dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .storeFocusRing(focused, StoreLayout.TILE_RADIUS.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clip(shape)
            .storeClickable(onClick = onClick),
    ) {
        StoreImage(art ?: port.image, title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Store.scrim)))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp)) {
            ElyText(title, size = 12f, color = Store.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            TileStatusLine(status)
        }
    }
}

/**
 * Un cartel grande de la portada (≈ 4:3): el arte a sangre y una llamada en
 * letra gruesa sobre una caja del acento ("PORTS NUEVOS", "RECIÉN ACTUALIZADO").
 */
@Composable
fun StoreHeroBanner(
    callout: String,
    title: String,
    art: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
) {
    val shape = RoundedCornerShape(8.dp)
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .storeFocusRing(focused, 8.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clip(shape)
            .storeClickable(onClick = onClick),
    ) {
        StoreImage(art, title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            ElyText(
                callout,
                Modifier
                    .background(skin.a1, RoundedCornerShape(3.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                size = 20f,
                weight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                uppercase = true,
            )
            Spacer(Modifier.height(8.dp))
            ElyText(title, size = 15f, weight = FontWeight.Light, color = Store.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Una colección: el nombre de la familia, cuántos juegos y hasta tres carátulas en abanico. */
@Composable
fun StoreCollectionCard(name: String, count: String, arts: List<Any?>, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(StoreLayout.TILE_RADIUS.dp)
    var focused by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val bg by animateColorAsState(if (focused) Store.tileHigh else Store.tile, if (reduced) tween(0) else tween(StoreLayout.FOCUS_MS), label = "collection")
    Row(
        modifier
            .width(width)
            .height(StoreLayout.cardHeight(width.value).dp)
            .storeFocusRing(focused, StoreLayout.TILE_RADIUS.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(bg)
            .storeClickable(onClick = onClick),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(14.dp), verticalArrangement = Arrangement.Bottom) {
            ElyText(name, size = 18f, weight = FontWeight.Light, color = Store.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ElyText(count, size = 11f, color = Store.textDim, maxLines = 1)
        }
        Row(Modifier.fillMaxHeight().padding(vertical = 12.dp).padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy((-18).dp)) {
            arts.take(3).forEach { a ->
                StoreImage(
                    a,
                    "",
                    Modifier
                        .fillMaxHeight()
                        .width(StoreLayout.cardHeight(width.value).dp * 0.5f)
                        .clip(RoundedCornerShape(3.dp))
                        .border(1.dp, Store.line, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

/**
 * Un botón redondo de icono (Buscar, Lista de deseos, Descargas, Más, orden):
 * sin fondo hasta que tiene el foco, que lo rellena y le pone el aro.
 */
@Composable
fun StoreIconButton(
    icon: StoreIcon,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = StoreLayout.ICON_BUTTON.dp,
    filled: Boolean = false,
    badge: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val bg by animateColorAsState(
        when {
            focused -> Store.tileHigh
            filled -> Store.tile
            else -> Color.Transparent
        },
        if (reduced) tween(0) else tween(StoreLayout.FOCUS_MS),
        label = "iconBg",
    )
    Box(
        modifier
            .size(size)
            .storeFocusRing(focused, size / 2, circle = true)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .background(bg)
            .storeClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StoreIconGlyph(icon, if (focused || filled) Store.text else Store.text.copy(alpha = 0.85f), size * 0.45f)
        if (badge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(size * 0.16f)
                    .size(9.dp)
                    .background(LocalSkin.current.a1, CircleShape)
                    .border(1.5.dp, Store.bg, CircleShape),
            )
        }
    }
}

/**
 * Volver a la biblioteca: el botón redondeado de la cabecera, el mismo que el
 * de Masha (38 dp, cristal oscuro con un canto claro, el galón blanco) y que
 * se toca en 48 dp. Con el mando lleva el marco blanco de la tienda.
 */
@Composable
fun StoreBackButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .touchTarget(Modifier.semantics { contentDescription = description }.storeClickable(onClick = onClick))
            .size(38.dp)
            .storeFocusRing(focused, 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Store.tileHigh else Store.tile.copy(alpha = 0.6f))
            .border(1.dp, Store.pillBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(16.dp)) { BackChevron(color = Color.White) }
    }
}

/** Una baldosa cuadrada redondeada de la cabecera (la de la tienda y la de descargas). */
@Composable
fun StoreHeaderTile(size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, description: String? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(size * 0.22f)
    var focused by remember { mutableStateOf(false) }
    val base = modifier
        .size(size)
        .storeFocusRing(focused, size * 0.22f)
        .onFocusChanged { focused = it.isFocused }
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .clip(shape)
        .background(if (focused) Store.tileHigh else Store.tile)
    Box(if (onClick != null) base.storeClickable(onClick = onClick) else base, contentAlignment = Alignment.Center) {
        content()
    }
}

/** Un chip con borde (la consola original, "Android"), como las insignias de plataforma. */
@Composable
fun StoreOutlinedChip(text: String, modifier: Modifier = Modifier) {
    ElyText(
        text,
        modifier
            .border(1.dp, Store.text.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        size = 9.5f,
        weight = FontWeight.SemiBold,
        color = Store.text,
        maxLines = 1,
        uppercase = true,
    )
}

/**
 * El botón principal de la ficha (Instalar / Actualizar / Abrir), en el
 * acento de Elyndra y bien redondeado. Mientras baja, es la barra de progreso.
 */
@Composable
fun StorePrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    progress: Float? = null,
    busy: Boolean = false,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    val accent = if (enabled) skin.a1 else Store.tileHigh
    Box(
        modifier
            .heightIn(min = 46.dp)
            .storeFocusRing(focused, 23.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (progress != null) Store.tileHigh else accent)
            .storeClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(accent),
            )
        }
        ElyText(
            when {
                progress != null -> "${(progress * 100).toInt()} %"
                else -> label
            },
            Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            size = 14f,
            weight = FontWeight.SemiBold,
            color = if (enabled || progress != null) Color.White else Store.textDim,
            maxLines = 1,
        )
        if (busy) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)) {
                com.elyndra.launcher.ui.components.ArcSpinner(size = 16.dp, color = Color.White)
            }
        }
    }
}

/** El título de una sección ("Todos los ports", "Recién actualizados"): pequeño y en blanco. */
@Composable
fun StoreSectionTitle(text: String, modifier: Modifier = Modifier) {
    ElyText(text, modifier, size = 14f, weight = FontWeight.Normal, color = Store.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** El fino canto que separa bloques de la ficha. */
@Composable
fun StoreDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Store.line),
    )
}

/** Un panel oscuro redondeado (la ficha, los avisos). */
fun Modifier.storePanel(radius: Dp = 8.dp, shape: Shape = RoundedCornerShape(radius)): Modifier = this
    .clip(shape)
    .background(Store.panel)
