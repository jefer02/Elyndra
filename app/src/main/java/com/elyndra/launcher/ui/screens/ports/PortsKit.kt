package com.elyndra.launcher.ui.screens.ports

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elyndra.launcher.R
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.domain.ports.PortAction
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.ui.PortsLayout
import com.elyndra.launcher.ui.components.DotTabIcon
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Store
import com.elyndra.launcher.ui.theme.shapeClickable

/**
 * El icono de la tienda en el dock: una bolsa con asa. Se dibuja con el color
 * que toca (ver [DotTabIcon]) en un cuadro de 14 dp.
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

/** El nombre corto de la plataforma original ("N64", "XBOX 360"). */
fun platformLabel(platform: String): String = Systems.byId(platform)?.short ?: platform.uppercase()

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
    PortAction.PcOnly -> R.string.ports_action_pc_only
    PortAction.Unavailable -> R.string.ports_action_unavailable
}

/**
 * El área de acción de una cápsula (donde un escaparate pondría el precio):
 * verde para instalar, azul para actualizar, pizarra para lo demás. Con
 * [progress] (0..1) se convierte en la barra de la descarga.
 */
@Composable
fun PortActionPill(action: PortAction, modifier: Modifier = Modifier, progress: Float? = null, large: Boolean = false) {
    val shape = RoundedCornerShape(if (large) 6.dp else 3.dp)
    val fill: Brush = when (action) {
        PortAction.Install -> Brush.horizontalGradient(Store.install)
        PortAction.Update -> Brush.horizontalGradient(Store.update)
        PortAction.Installed -> Brush.horizontalGradient(listOf(Store.installed, Store.installed))
        PortAction.Free -> Brush.horizontalGradient(listOf(Store.free, Store.free))
        PortAction.PcOnly, PortAction.Unavailable -> Brush.horizontalGradient(listOf(Store.muted, Store.muted))
    }
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .padding(horizontal = if (large) 18.dp else 8.dp, vertical = if (large) 10.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(if (large) 3.dp else 2.dp)
                    .background(Store.ok),
            )
            ElyText("${(progress * 100).toInt()} %", size = if (large) 13f else 10f, weight = FontWeight.SemiBold, color = Store.text)
        } else {
            ElyText(
                stringResource(actionLabel(action)),
                size = if (large) 13.5f else 10f,
                weight = FontWeight.SemiBold,
                color = if (action == PortAction.PcOnly || action == PortAction.Unavailable) Store.textDim else Store.text,
                maxLines = 1,
            )
        }
    }
}

/** Un chip de etiqueta o de filtro: azul apagado, encendido si está elegido. */
@Composable
fun StoreChip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(3.dp)
    val base = modifier
        .clip(shape)
        .background(if (selected) Store.chipOn else Store.chip)
    Box(
        (if (onClick != null) base.shapeClickable(shape, onClick = onClick) else base)
            .heightIn(min = if (onClick != null) 32.dp else 0.dp)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        ElyText(text, size = 10.5f, weight = FontWeight.Medium, color = if (selected) Store.text else Store.chipText, maxLines = 1)
    }
}

/** El título de una fila de la portada: en versalitas, como las tiendas de escritorio. */
@Composable
fun StoreRowTitle(text: String, modifier: Modifier = Modifier) {
    ElyText(text, modifier, size = 11.5f, weight = FontWeight.SemiBold, color = Store.text, uppercase = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Una imagen remota o local con un respaldo de degradado y el nombre
 * mientras carga o si falla (las tarjetas OpenGraph de GitHub siempre
 * existen, pero sin red no).
 */
@Composable
fun StoreImage(model: Any?, fallback: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var failed by remember(model) { mutableStateOf(model == null) }
    Box(modifier.background(Brush.linearGradient(listOf(Store.panelHigh, Store.capsule)))) {
        if (!failed) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
            )
        } else {
            ElyText(
                fallback,
                Modifier
                    .align(Alignment.Center)
                    .padding(10.dp),
                size = 13f,
                weight = FontWeight.SemiBold,
                color = Store.textDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Una cápsula de la tienda: la imagen del port arriba y, debajo, el nombre, la
 * plataforma original y el área de acción a la derecha.
 *
 * Al recibir el foco (mando) se ilumina con el acento y avisa con [onFocus]
 * (la tienda aprovecha para pedir sus releases).
 */
@Composable
fun PortCapsule(
    port: PortEntry,
    action: PortAction,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    owned: Boolean = false,
    onFocus: () -> Unit = {},
) {
    val shape = RoundedCornerShape(4.dp)
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .width(width)
            .clip(shape)
            .background(if (focused) Store.panelHigh else Store.capsule)
            .border(1.dp, if (focused) skin.a1 else Store.line, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .shapeClickable(shape, color = skin.a1, onClick = onClick),
    ) {
        Box {
            StoreImage(
                port.image,
                port.name,
                Modifier
                    .fillMaxWidth()
                    .height(PortsLayout.capsuleImageHeight(width.value).dp),
            )
            if (!port.verified) {
                StoreChip(
                    stringResource(R.string.ports_unverified),
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp),
                )
            }
            if (owned) {
                ElyText(
                    "✓",
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .background(Store.ok.copy(alpha = 0.9f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    size = 11f,
                    weight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(PortsLayout.CAPSULE_BAND.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                ElyText(port.name, size = 11.5f, weight = FontWeight.SemiBold, color = Store.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ElyText(
                    platformLabel(port.original.platform) + " · " + port.original.title,
                    size = 9.5f,
                    color = Store.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PortActionPill(action, progress = progress)
        }
    }
}

/** El fino canto de luz que separa bloques de la ficha. */
@Composable
fun StoreDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Store.line),
    )
}

/** Fondo de la tienda (degradado azul noche) con un brillo suave arriba. */
fun Modifier.storeBackground(): Modifier = this
    .background(Store.background)
    .background(
        Brush.radialGradient(
            listOf(Color(0x332F6FA8), Color.Transparent),
            center = Offset(0f, 0f),
            radius = 900f,
        ),
    )

/** Un panel de la ficha: pizarra azul con su canto. */
fun Modifier.storePanel(radius: Dp = 4.dp): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(Store.panel)
    .border(1.dp, Store.line, RoundedCornerShape(radius))
