package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.IdentifyController
import com.elyndra.launcher.ui.NameMatch
import com.elyndra.launcher.ui.components.AccentButton
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlassTextField
import com.elyndra.launcher.ui.components.ScrimLayer
import com.elyndra.launcher.ui.components.consumeClicks
import com.elyndra.launcher.ui.theme.animRiseSheet
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.shapeClickable

/**
 * "Editar nombre / Identificar juego": un campo con la propuesta de nombre y,
 * debajo, lo que encuentran las fuentes mientras se escribe. Se elige un
 * resultado o se usa el nombre tal cual. Con mando: el campo primero (abre el
 * teclado), luego los resultados y los botones en orden; B cierra.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdentifySheet(vm: ElyndraViewModel, c: IdentifyController) {
    val state = c.state ?: return
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
    val field = remember { FocusRequester() }
    LaunchedEffect(state.key) { runCatching { field.requestFocus() } }

    ScrimLayer(onDismiss = c::close, alignment = Alignment.BottomCenter, key = state.key) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(10.dp)
                .animRiseSheet(key = state.key)
                .glass(RoundedCornerShape(24.dp), solid = true)
                .consumeClicks()
                .padding(16.dp),
        ) {
            ElyText(stringResource(R.string.identify_title), size = 16f, weight = FontWeight.SemiBold, color = P.ink)
            Spacer(Modifier.height(4.dp))
            ElyText(stringResource(R.string.identify_raw, state.rawName), size = 10f, color = P.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            GlassTextField(
                value = c.query,
                onValueChange = c::updateQuery,
                modifier = Modifier.fillMaxWidth().focusRequester(field),
                label = stringResource(R.string.identify_field),
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton(stringResource(R.string.identify_use_name), c::useTypedName)
                if (state.locked) GhostButton(stringResource(R.string.identify_reset), c::reset)
                GhostButton(stringResource(R.string.cancel), c::close)
            }
            Spacer(Modifier.height(14.dp))
            ElyText(
                stringResource(if (c.searching) R.string.identify_searching else R.string.identify_results),
                size = 9.5f,
                weight = FontWeight.SemiBold,
                color = P.ink2,
                uppercase = true,
            )
            Spacer(Modifier.height(6.dp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!c.searching && c.results.isEmpty()) {
                    ElyText(stringResource(R.string.identify_no_results), size = 10.5f, color = P.ink2)
                }
                c.results.forEach { r -> ResultRow(r) { c.pick(r) } }
            }
        }
    }
}

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
        Box(Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(6.dp)).background(P.ink.copy(alpha = 0.08f))) {
            r.thumb?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp, 48.dp)) }
        }
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
