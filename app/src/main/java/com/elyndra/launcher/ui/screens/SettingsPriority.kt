package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.PriorityOrder
import com.elyndra.launcher.ui.ServiceState
import com.elyndra.launcher.ui.SettingsController
import com.elyndra.launcher.ui.components.AccordionHeader
import com.elyndra.launcher.ui.components.ConsoleGlyph
import com.elyndra.launcher.ui.components.ConsoleGlyphIcon
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.Expandable
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.IconAction
import com.elyndra.launcher.ui.components.SegmentedControl
import com.elyndra.launcher.ui.components.SettingsGroup
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Space
import com.elyndra.launcher.ui.theme.TypeScale
import com.elyndra.launcher.ui.theme.shapeClickable

/* ─────────────────────────────────────────────────────────────
   Prioridad de fuentes de metadatos.

   Un panel plegable: cerrado, una línea dice qué manda en textos y en
   imágenes. Abierto, las dos listas —lado a lado si hay ancho; si no,
   con un segmentado para elegir cuál—, cada fuente en una fila corta
   con su número, su estado de conexión (las que no están configuradas
   se ven apagadas) y un asa.

   Se reordena de tres formas: arrastrando por el asa; tocando la fila
   (o A con el mando) para "cogerla" y moviéndola con sus flechas o con
   LB/RB; y soltándola con otro toque, B o al cerrar el panel. Lo que se
   guarda es lo de siempre (MetadataPriorityStore).
   ───────────────────────────────────────────────────────────── */

@Composable
internal fun MetadataPriorityPanel(vm: ElyndraViewModel, wide: Boolean) {
    val s = vm.settings
    var open by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val summary = stringResource(
        R.string.priority_summary,
        PriorityOrder.summary(s.priority.text, ::serviceName),
        PriorityOrder.summary(s.priority.art, ::serviceName),
    )

    SettingsGroup(padding = 0.dp) {
        AccordionHeader(
            title = stringResource(R.string.settings_priority),
            expanded = open,
            onToggle = {
                open = !open
                if (!open) s.releaseGrab()
            },
            summary = summary,
        )
        Expandable(open) {
            ElyText(
                stringResource(R.string.settings_priority_desc) + " " + stringResource(R.string.priority_hint),
                size = TypeScale.Caption,
                color = P.ink2,
                lineHeightRatio = 1.45f,
            )
            Spacer(Modifier.height(Space.s))
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    PriorityList(stringResource(R.string.settings_priority_text), art = false, s.priority.text, s, Modifier.weight(1f))
                    PriorityList(stringResource(R.string.settings_priority_art), art = true, s.priority.art, s, Modifier.weight(1f))
                }
            } else {
                SegmentedControl(
                    options = listOf(stringResource(R.string.priority_tab_text), stringResource(R.string.priority_tab_art)),
                    selected = tab,
                    onSelect = {
                        tab = it
                        s.releaseGrab()
                    },
                )
                Spacer(Modifier.height(Space.s))
                if (tab == 0) {
                    PriorityList(stringResource(R.string.settings_priority_text), art = false, s.priority.text, s, Modifier.fillMaxWidth())
                } else {
                    PriorityList(stringResource(R.string.settings_priority_art), art = true, s.priority.art, s, Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(Space.s))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GhostButton(stringResource(R.string.settings_priority_reset), s::resetPriority)
            }
            Spacer(Modifier.height(Space.s))
        }
    }
}

/**
 * Una de las dos listas. El estado del arrastre vive aquí y cada fila va con
 * su `key`, así que al cambiar el orden la fila (y su gesto) siguen siendo la
 * misma: no se corta el arrastre a mitad.
 */
@Composable
private fun PriorityList(title: String, art: Boolean, order: List<Service>, s: SettingsController, modifier: Modifier) {
    val rowPx = with(LocalDensity.current) { (ROW_H + ROW_GAP).toPx() }
    var dragging by remember { mutableStateOf<Service?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val latest by rememberUpdatedState(order)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        ElyText(title, size = 9f, weight = FontWeight.SemiBold, color = P.ink2, letterSpacing = tracking(0.06f), uppercase = true)
        order.forEachIndexed { i, service ->
            key(service) {
                PriorityRow(
                    position = i + 1,
                    service = service,
                    status = s.state(service).status,
                    grabbed = s.priorityGrab == SettingsController.PriorityGrab(art, service),
                    dragged = dragging == service,
                    offset = { if (dragging == service) offset else 0f },
                    canUp = i > 0,
                    canDown = i < order.lastIndex,
                    onToggle = { s.toggleGrab(art, service) },
                    onMove = { delta -> s.movePriority(art, service, delta) },
                    handle = Modifier.pointerInput(service) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = service
                                offset = 0f
                            },
                            onDragEnd = {
                                dragging = null
                                offset = 0f
                            },
                            onDragCancel = {
                                dragging = null
                                offset = 0f
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                offset += delta.y
                                val from = latest.indexOf(service)
                                val to = PriorityOrder.dragTarget(from, offset, rowPx, latest.size)
                                if (to != from) {
                                    s.movePriorityTo(art, service, to)
                                    // La fila ya ha saltado de sitio: lo que se movió se descuenta y sigue bajo el dedo.
                                    offset -= (to - from) * rowPx
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PriorityRow(
    position: Int,
    service: Service,
    status: ServiceState.Status,
    grabbed: Boolean,
    dragged: Boolean,
    offset: () -> Float,
    canUp: Boolean,
    canDown: Boolean,
    onToggle: () -> Unit,
    onMove: (Int) -> Unit,
    handle: Modifier,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(10.dp)
    val configured = status != ServiceState.Status.Unconfigured
    val notConfigured = stringResource(R.string.status_not_configured)
    val lifted = grabbed || dragged
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_H)
            .zIndex(if (dragged) 1f else 0f)
            .graphicsLayer {
                translationY = offset()
                val k = if (dragged) 1.02f else 1f
                scaleX = k
                scaleY = k
            }
            .clip(shape)
            .background(if (lifted) skin.a2.copy(alpha = if (P.isDark) 0.18f else 0.10f) else P.ink.copy(alpha = if (P.isDark) 0.05f else 0.03f))
            .border(1.dp, if (lifted) skin.a2.copy(alpha = 0.7f) else Color.Transparent, shape)
            .semantics {
                selected = grabbed
                if (!configured) stateDescription = notConfigured
            }
            .shapeClickable(shape, onClick = onToggle)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ElyText("$position", size = 11f, weight = FontWeight.Bold, color = skin.a2, modifier = Modifier.width(16.dp))
        StatusDot(status)
        Spacer(Modifier.width(8.dp))
        ElyText(
            serviceName(service),
            modifier = Modifier.weight(1f),
            size = 11.5f,
            weight = FontWeight.Medium,
            color = if (configured) P.ink else P.ink2.copy(alpha = 0.6f),
            maxLines = 1,
        )
        if (grabbed) {
            IconAction(
                ConsoleGlyph.Chevron,
                stringResource(R.string.move_up),
                { onMove(-1) },
                modifier = Modifier.graphicsLayer { rotationZ = 180f },
                enabled = canUp,
            )
            IconAction(ConsoleGlyph.Chevron, stringResource(R.string.move_down), { onMove(1) }, enabled = canDown)
        }
        val dragLabel = stringResource(R.string.priority_drag)
        Box(
            Modifier
                .size(ROW_H)
                .semantics { contentDescription = dragLabel }
                .then(handle),
            contentAlignment = Alignment.Center,
        ) {
            ConsoleGlyphIcon(ConsoleGlyph.Handle, P.ink2.copy(alpha = 0.75f), size = 16.dp)
        }
    }
}

private val ROW_H = 44.dp
private val ROW_GAP = 4.dp
