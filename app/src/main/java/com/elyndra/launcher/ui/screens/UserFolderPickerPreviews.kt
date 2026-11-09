package com.elyndra.launcher.ui.screens

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.domain.library.UserFolderPickLogic.Candidate
import com.elyndra.launcher.domain.library.UserFolderPickLogic.Kind
import com.elyndra.launcher.ui.components.PreviewTheme

/*
 * El selector de juegos de una carpeta en un móvil apaisado y en vertical,
 * con una fila señalada por el mando y dos marcadas. Sin imágenes: cada fila
 * enseña el color del juego.
 */

@Preview(name = "Apaisado · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 853, heightDp = 384)
@Preview(name = "Vertical · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 390, heightDp = 780)
annotation class UserFolderPickerPreview

private val SAMPLE = List(12) { i ->
    val kind = when (i % 3) {
        0 -> Kind.Rom
        1 -> Kind.App
        else -> Kind.Port
    }
    Candidate("r:$i", "Game ${i + 1}", kind, "PS2".takeIf { kind == Kind.Rom }, null, null, null, "game ${i + 1}")
}

@Composable
private fun PickerPreview(query: String = "", loading: Boolean = false, results: List<Candidate> = SAMPLE) = PreviewTheme {
    UserFolderPickerContent(
        folderName = "Favoritos",
        query = query,
        loading = loading,
        results = results,
        picked = setOf("r:1", "r:3"),
        focus = 2,
        hintsVisible = true,
        sidePadding = 16.dp,
        holding = { false },
        onPageRows = {},
        onQuery = {},
        onToggle = {},
        onBack = {},
        onConfirm = {},
    )
}

@UserFolderPickerPreview
@Composable
private fun ListPreview() = PickerPreview()

@UserFolderPickerPreview
@Composable
private fun LoadingPreview() = PickerPreview(loading = true, results = emptyList())

@UserFolderPickerPreview
@Composable
private fun NoResultsPreview() = PickerPreview(query = "zzz", results = emptyList())
