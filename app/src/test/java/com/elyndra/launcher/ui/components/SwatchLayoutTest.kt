package com.elyndra.launcher.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwatchLayoutTest {

    @Test
    fun `ten intro colors fit one line in the landscape pane`() {
        // Panel de la Legion apaisada: ~1280 − márgenes − raíl − relleno ≈ 900 dp.
        assertEquals(10, SwatchLayout.perRow(900f, 10))
        assertEquals(1, SwatchLayout.rows(900f, 10))
    }

    @Test
    fun `exact fit counts gaps only between swatches`() {
        val tight = 10 * SwatchLayout.SLOT_DP + 9 * SwatchLayout.GAP_DP
        assertEquals(10, SwatchLayout.perRow(tight, 10))
        // Un dp menos y ya no caben las diez: dos filas parejas.
        assertEquals(5, SwatchLayout.perRow(tight - 1f, 10))
    }

    @Test
    fun `wrapped rows are balanced`() {
        // En un móvil (~310 dp) caben 5: 10 muestras → 5 + 5.
        assertEquals(5, SwatchLayout.perRow(310f, 10))
        // Donde caben 7, 10 muestras van 5 + 5 y no 7 + 3.
        val seven = 7 * SwatchLayout.SLOT_DP + 6 * SwatchLayout.GAP_DP
        assertEquals(5, SwatchLayout.perRow(seven, 10))
        // 14 (colores de partículas) donde caben 8 → 7 + 7.
        val eight = 8 * SwatchLayout.SLOT_DP + 7 * SwatchLayout.GAP_DP
        assertEquals(7, SwatchLayout.perRow(eight, 14))
        assertEquals(2, SwatchLayout.rows(eight, 14))
    }

    @Test
    fun `degenerate widths still place one per row`() {
        assertEquals(1, SwatchLayout.perRow(10f, 4))
        assertEquals(0, SwatchLayout.perRow(500f, 0))
    }

    @Test
    fun `touch slot meets the 48dp minimum`() {
        assertTrue(SwatchLayout.SLOT_DP >= 48f)
        assertTrue(SwatchLayout.VISUAL_DP < SwatchLayout.SLOT_DP)
    }
}
