package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.metadata.ArtCandidate
import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.ui.ArtPickerState
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.ScrimLayer
import com.elyndra.launcher.ui.components.consumeClicks
import com.elyndra.launcher.ui.label
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.animRiseSheet
import com.elyndra.launcher.ui.theme.drawArcSpinner
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.spinAngle

/** Hoja con las imágenes que ofrece un servicio para la carátula, el fondo o el icono. */
@Composable
fun ArtPickerSheet(vm: ElyndraViewModel, state: ArtPickerState) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    val sheetKey = Triple(state.key, state.kind, state.service)
    val (minCell, ratio) = when (state.kind) {
        ArtKind.Cover -> 96.dp to 2f / 3f
        ArtKind.Background -> 180.dp to 16f / 9f
        ArtKind.Icon -> 80.dp to 1f
    }

    ScrimLayer(onDismiss = vm::closeArtPicker, alignment = Alignment.BottomCenter, key = sheetKey) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(10.dp)
                .animRiseSheet(key = sheetKey)
                .glass(RoundedCornerShape(24.dp), solid = true)
                .consumeClicks()
                .padding(top = 16.dp, bottom = 12.dp),
        ) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                ElyText(stringResource(state.kind.label()), size = 14.5f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                ElyText(
                    serviceName(state.service) + " · " + state.title,
                    size = 9.5f,
                    color = P.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            when {
                state.loading -> Message(stringResource(R.string.art_searching), spinner = true)
                state.candidates.isEmpty() -> Message(
                    stringResource(if (state.failed) R.string.art_search_failed else R.string.art_none_found),
                    spinner = false,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minCell),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.candidates, key = { it.url }) { c ->
                        CandidateCell(
                            candidate = c,
                            ratio = ratio,
                            fit = state.kind == ArtKind.Icon,
                            applying = state.applying == c.url,
                            dimmed = state.applying != null && state.applying != c.url,
                        ) { vm.applyArt(c) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, spinner: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spinner) {
            Spinner()
            Spacer(Modifier.height(12.dp))
        }
        ElyText(text, size = 11f, color = P.ink2, align = TextAlign.Center, lineHeightRatio = 1.5f)
    }
}

@Composable
private fun Spinner() {
    val skin = LocalSkin.current
    val angle = spinAngle(1000)
    Box(Modifier.size(26.dp).rotate(angle).drawBehind { drawArcSpinner(skin.a1, 2.dp, 45f) })
}

@Composable
private fun CandidateCell(
    candidate: ArtCandidate,
    ratio: Float,
    fit: Boolean,
    applying: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.alpha(if (dimmed) 0.45f else 1f)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(shape)
                .background(P.ink.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.6f), shape)
                .clickable(enabled = !dimmed && !applying, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = candidate.thumb,
                contentDescription = null,
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(if (fit) 8.dp else 0.dp),
            )
            if (applying) {
                Box(Modifier.fillMaxSize().background(P.ink.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) { Spinner() }
            }
        }
        Spacer(Modifier.height(4.dp))
        ElyText(
            candidate.label,
            size = 8.5f,
            color = P.ink2,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
