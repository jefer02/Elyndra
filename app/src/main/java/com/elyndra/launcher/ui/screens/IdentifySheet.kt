package com.elyndra.launcher.ui.screens

import com.elyndra.launcher.ui.IdentifyState
import com.elyndra.launcher.ui.components.overlayEmerge
import com.elyndra.launcher.ui.components.Backdrop
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import androidx.compose.runtime.State
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.IdentifyController
import com.elyndra.launcher.ui.KeyboardLayout
import com.elyndra.launcher.ui.NameMatch
import com.elyndra.launcher.ui.components.AccentButton
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlassTextField
import com.elyndra.launcher.ui.components.PadFocusGroup
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.ScrimLayer
import com.elyndra.launcher.ui.components.consumeClicks
import com.elyndra.launcher.ui.components.padInitialFocus
import com.elyndra.launcher.ui.components.rememberImeVisible
import com.elyndra.launcher.ui.theme.LocalLandscape
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.shapeClickable

/**
 * "Editar nombre / Identificar juego": un campo con la propuesta de nombre y,
 * debajo, lo que encuentran las fuentes mientras se escribe. Se elige un
 * resultado o se usa el nombre tal cual ("Hecho" en el teclado hace lo mismo).
 *
 * El teclado nunca tapa el campo: la capa acaba encima de él (ScrimLayer) y,
 * con el teclado abierto, se pega arriba del hueco. En horizontal (o si
 * queda poco alto) pasa a compacta: campo y botones en una fila y los
 * resultados en una tira horizontal debajo (ver [KeyboardLayout]).
 *
 * Con mando: el foco empieza en el campo (A abre el teclado; B lo cierra y,
 * otra vez, cierra la capa), luego los botones y los resultados.
 */
@Composable
fun IdentifySheet(vm: ElyndraViewModel, c: IdentifyController, state: IdentifyState, open: Boolean, progress: State<Float>) {
    val reduced = LocalReducedMotion.current
    val field = remember { FocusRequester() }
    LaunchedEffect(state.key) { runCatching { field.requestFocus() } }
    val ime = rememberImeVisible()
    val landscape = LocalLandscape.current

    ScrimLayer(
        onDismiss = c::close,
        alignment = if (KeyboardLayout.dockTop(ime)) Alignment.TopCenter else Alignment.BottomCenter,
        open = open,
        progress = progress,
        z = Backdrop.Z_IDENTIFY,
    ) {
        // El alto que queda de verdad: sin barras, sin muesca y sin teclado.
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = if (KeyboardLayout.dockTop(ime)) Alignment.TopCenter else Alignment.BottomCenter) {
            val compact = KeyboardLayout.compact(ime, landscape, maxHeight.value)
            PadFocusGroup(
                Modifier
                    .widthIn(max = if (compact) 880.dp else 640.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .padding(if (compact) 6.dp else 10.dp)
                    .overlayEmerge(progress, reduced)
                    .glass(RoundedCornerShape(24.dp), solid = true)
                    .consumeClicks()
                    .animateContentSize(),
                modal = true,
                padFocus = open,
            ) {
                IdentifyContent(vm, c, state, field, compact)
            }
        }
    }
}

/**
 * El contenido, en sus dos repartos:
 *  - Normal (sin teclado o con sitio de sobra): título, campo, botones y la
 *    lista de resultados, que se desplaza bajo el campo.
 *  - Compacto (teclado abierto en horizontal): el campo en una fila con
 *    "Usar este nombre" y "Cancelar", y los resultados en una tira corta.
 *
 * El campo es **el mismo** en los dos: está siempre en el mismo sitio de la
 * composición (lo que cambia son los bloques condicionales de alrededor). Si
 * cada reparto tuviese su propio campo, al abrirse el teclado se pasaría al
 * compacto, el campo con el foco desaparecería, Compose cerraría el teclado
 * y la hoja volvería abajo: subía y bajaba sola.
 */
@Composable
private fun IdentifyContent(vm: ElyndraViewModel, c: IdentifyController, state: IdentifyState, field: FocusRequester, compact: Boolean) {
    Column(Modifier.padding(horizontal = if (compact) 14.dp else 16.dp, vertical = if (compact) 10.dp else 16.dp)) {
        if (!compact) {
            ElyText(stringResource(R.string.identify_title), size = 16f, weight = FontWeight.SemiBold, color = P.ink)
            Spacer(Modifier.height(4.dp))
            ElyText(stringResource(R.string.identify_raw, state.rawName), size = 10f, color = P.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NameField(c, field, Modifier.weight(1f))
            if (compact) {
                AccentButton(stringResource(R.string.identify_use_name), c::useTypedName)
                if (state.locked) GhostButton(stringResource(R.string.identify_reset), c::reset)
                GhostButton(stringResource(R.string.cancel), c::close)
            }
        }
        if (!compact) {
            Spacer(Modifier.height(12.dp))
            Buttons(c, state.locked)
        }
        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
        ResultsLabel(c)
        Spacer(Modifier.height(6.dp))
        if (!c.searching && c.results.isEmpty()) {
            ElyText(stringResource(R.string.identify_no_results), size = 10.5f, color = P.ink2)
        } else if (compact) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 4.dp),
            ) {
                items(c.results, key = { "${it.source.id}:${it.id}" }) { r -> ResultCard(r) { c.pick(r) } }
            }
        } else {
            // Los resultados se desplazan; el campo y los botones se quedan arriba.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                c.results.forEach { r -> ResultRow(r) { c.pick(r) } }
            }
        }
        if (!compact) {
            PadHints(hints = IDENTIFY_HINTS, visible = vm.input.gamepadPresent, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun NameField(c: IdentifyController, field: FocusRequester, modifier: Modifier) {
    GlassTextField(
        value = c.query,
        onValueChange = c::updateQuery,
        modifier = modifier.focusRequester(field).padInitialFocus(),
        label = stringResource(R.string.identify_field),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        // "Hecho" guarda el nombre escrito.
        keyboardActions = KeyboardActions(onDone = { c.useTypedName() }),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(c: IdentifyController, locked: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AccentButton(stringResource(R.string.identify_use_name), c::useTypedName)
        if (locked) GhostButton(stringResource(R.string.identify_reset), c::reset)
        GhostButton(stringResource(R.string.cancel), c::close)
    }
}

@Composable
private fun ResultsLabel(c: IdentifyController) {
    ElyText(
        stringResource(if (c.searching) R.string.identify_searching else R.string.identify_results),
        size = 9.5f,
        weight = FontWeight.SemiBold,
        color = P.ink2,
        uppercase = true,
    )
}

private val IDENTIFY_HINTS = listOf(PadHint("A", R.string.hint_select), PadHint("B", R.string.hint_back))

@Composable
private fun ResultRow(r: NameMatch, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(P.chip)
            .shapeClickable(shape, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(r)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            ElyText(r.title, size = 11.5f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ElyText(
                listOfNotNull(r.year, r.platform, serviceName(r.source)).joinToString(" · "),
                size = 9.5f,
                color = P.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Un resultado en la tira horizontal: miniatura y dos líneas. */
@Composable
private fun ResultCard(r: NameMatch, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .width(230.dp)
            .clip(shape)
            .background(P.chip)
            .shapeClickable(shape, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(r)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            ElyText(r.title, size = 11f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ElyText(
                listOfNotNull(r.year, r.platform, serviceName(r.source)).joinToString(" · "),
                size = 9f,
                color = P.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Thumb(r: NameMatch) {
    Box(Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(6.dp)).background(P.ink.copy(alpha = 0.08f))) {
        r.thumb?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp, 48.dp)) }
    }
}
