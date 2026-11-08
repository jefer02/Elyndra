package com.elyndra.launcher.ui

import com.elyndra.launcher.metadata.ArtCandidate
import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.ui.ArtPickerLayout.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtPickerLayoutTest {

    private val list = List(6) { ArtCandidate("u$it", "t$it", "l$it") }

    private fun state(
        kind: ArtKind = ArtKind.Background,
        loading: Boolean = false,
        candidates: List<ArtCandidate> = list,
        failed: Boolean = false,
        applied: String? = null,
    ) = ArtPickerState("r:1", "Game", kind, Service.SteamGridDb, loading, candidates, failed, applied = applied)

    @Test
    fun initialFocusIsTheAppliedImageWhenListed() {
        assertEquals(3, ArtPickerLayout.initialFocus(state(applied = "u3")))
        assertEquals(3, ArtPickerLayout.appliedIndex(state(applied = "u3")))
    }

    @Test
    fun initialFocusFallsBackToFirstWhenAppliedIsMissing() {
        assertEquals(0, ArtPickerLayout.initialFocus(state(applied = "elsewhere")))
        assertEquals(0, ArtPickerLayout.initialFocus(state(applied = null)))
        assertEquals(-1, ArtPickerLayout.appliedIndex(state(applied = "elsewhere")))
    }

    @Test
    fun withoutGridFocusStartsOnTheFirstButton() {
        assertEquals(0, ArtPickerLayout.initialFocus(state(loading = true, candidates = emptyList())))
    }

    @Test
    fun everyStateWithoutGridHasAWayOut() {
        assertEquals(listOf(Button.Close), ArtPickerLayout.buttons(state(loading = true, candidates = emptyList())))
        assertEquals(listOf(Button.Close), ArtPickerLayout.buttons(state(candidates = emptyList())))
        assertEquals(listOf(Button.Retry, Button.Close), ArtPickerLayout.buttons(state(candidates = emptyList(), failed = true)))
        assertTrue(ArtPickerLayout.buttons(state()).isEmpty())
    }

    @Test
    fun logosAndIconsFitWhileCoversAndHeroesCrop() {
        assertTrue(ArtPickerLayout.cell(ArtKind.Logo).fit)
        assertTrue(ArtPickerLayout.cell(ArtKind.Icon).fit)
        assertFalse(ArtPickerLayout.cell(ArtKind.Cover).fit)
        assertFalse(ArtPickerLayout.cell(ArtKind.Background).fit)
    }

    @Test
    fun columnsFollowTheKindOnPhoneAndTablet() {
        // Ancho útil de la hoja (máx. 720 dp, menos márgenes) en un móvil apaisado y en una tableta.
        fun cols(kind: ArtKind, widthDp: Int): Int {
            val available = minOf(widthDp, 720) - 20 - (ArtPickerLayout.SIDE_PADDING_DP * 2).toInt()
            return GridNav.columns(available, ArtPickerLayout.cell(kind).minWidthDp.toInt(), ArtPickerLayout.H_SPACING_DP.toInt())
        }
        // Los fondos, anchos, van en menos columnas que las carátulas o los iconos.
        assertTrue(cols(ArtKind.Background, 853) < cols(ArtKind.Cover, 853))
        assertTrue(cols(ArtKind.Logo, 853) < cols(ArtKind.Icon, 853))
        assertEquals(3, cols(ArtKind.Background, 853))
        // En vertical en un móvil (~384 dp), los fondos de uno en uno.
        assertEquals(1, cols(ArtKind.Background, 384))
        assertTrue(cols(ArtKind.Icon, 384) >= 3)
    }
}
