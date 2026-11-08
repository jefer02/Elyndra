package com.elyndra.launcher.ui.screens

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.metadata.ArtCandidate
import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.ui.ArtPickerState
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.PreviewTheme
import com.elyndra.launcher.ui.selection.rememberSelectionLook

/*
 * El selector de arte en los dos aparatos de prueba —un móvil apaisado (como
 * el Tecno Spark 30C, ~853 × 384 dp) y una tableta (como la Legion Y700,
 * ~1280 × 800 dp)— en claro y en oscuro, con una imagen señalada por el mando
 * y otra marcada como "En uso". Sin red las miniaturas no cargan: cada celda
 * enseña el color del juego, que es lo que se ve mientras llegan.
 */

@Preview(name = "Móvil apaisado · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 853, heightDp = 384)
@Preview(name = "Móvil apaisado · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 853, heightDp = 384)
@Preview(name = "Tableta · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 1280, heightDp = 800)
@Preview(name = "Tableta · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 1280, heightDp = 800)
annotation class ArtPickerPreviews

private fun candidates(n: Int) = List(n) { ArtCandidate(url = "https://example.com/$it.png", thumb = "", label = "SteamGridDB · ${it + 1}") }

/** [focus]: lo que señala el mando; la tercera imagen va "En uso". */
@Composable
private fun PickerPreview(kind: ArtKind, count: Int = 11, focus: Int = 4, loading: Boolean = false, failed: Boolean = false) = PreviewTheme {
    val list = if (loading || failed) emptyList() else candidates(count)
    val state = ArtPickerState(
        key = "r:preview",
        title = "Hollow Knight",
        kind = kind,
        service = Service.SteamGridDb,
        loading = loading,
        candidates = list,
        failed = failed,
        applied = list.getOrNull(2)?.url,
    )
    ArtPickerPanel(
        state = state,
        fallback = ArtFallback("r:preview", "Hollow Knight"),
        look = rememberSelectionLook(SettingsStore.DEFAULT_SELECTION_PARTICLE_COLOR, glow = true, particles = false),
        focus = focus,
        hintsVisible = true,
        maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp,
        holding = { false },
        onGrid = { _, _ -> },
        onPick = {},
        onRetry = {},
        onClose = {},
    )
}

@ArtPickerPreviews
@Composable
private fun HeroPickerPreview() = PickerPreview(ArtKind.Background)

@ArtPickerPreviews
@Composable
private fun CoverPickerPreview() = PickerPreview(ArtKind.Cover, count = 17, focus = 8)

@ArtPickerPreviews
@Composable
private fun LogoPickerPreview() = PickerPreview(ArtKind.Logo, count = 7, focus = 6)

@ArtPickerPreviews
@Composable
private fun IconPickerPreview() = PickerPreview(ArtKind.Icon, count = 14, focus = 0)

/** Error de red: el mando empieza en "Reintentar". */
@ArtPickerPreviews
@Composable
private fun FailedPickerPreview() = PickerPreview(ArtKind.Background, failed = true, focus = 0)
