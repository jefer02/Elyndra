package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.GridNav.Dir
import com.elyndra.launcher.ui.GridNav.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class GridNavTest {

    private fun move(index: Int, count: Int, cols: Int, dir: Dir, header: Boolean = false, more: Boolean = false, initial: Int = 0) =
        GridNav.move(index, count, cols, dir, hasHeader = header, canLoadMore = more, initial = initial)

    /* ── columnas ─────────────────────────────────────────────── */

    @Test
    fun columnsMatchComposeAdaptiveCount() {
        // (available + spacing) / (min + spacing), como GridCells.Adaptive.
        assertEquals(3, GridNav.columns(availablePx = 600, minCellPx = 180, spacingPx = 10))
        assertEquals(3, GridNav.columns(availablePx = 560, minCellPx = 180, spacingPx = 10))
        assertEquals(2, GridNav.columns(availablePx = 559, minCellPx = 180, spacingPx = 10))
        assertEquals(6, GridNav.columns(availablePx = 1100, minCellPx = 150, spacingPx = 20))
    }

    @Test
    fun columnsNeverDropBelowOne() {
        assertEquals(1, GridNav.columns(availablePx = 50, minCellPx = 180, spacingPx = 10))
        assertEquals(1, GridNav.columns(availablePx = 0, minCellPx = 180, spacingPx = 10))
        assertEquals(1, GridNav.columns(availablePx = 500, minCellPx = 0, spacingPx = 10))
    }

    @Test
    fun pageRowsCountWholeRowsAndAtLeastOne() {
        assertEquals(3, GridNav.pageRows(viewportPx = 400, rowPx = 120, spacingPx = 12))
        assertEquals(1, GridNav.pageRows(viewportPx = 80, rowPx = 120, spacingPx = 12))
        assertEquals(1, GridNav.pageRows(viewportPx = 400, rowPx = 0, spacingPx = 12))
    }

    /* ── vacío y un solo elemento ─────────────────────────────── */

    @Test
    fun emptyGridStaysOrGoesToHeader() {
        for (dir in Dir.entries) assertEquals(Result.Stay, move(-1, 0, 3, dir))
        assertEquals(Result.ToHeader, move(-1, 0, 3, Dir.Up, header = true))
        assertEquals(Result.Stay, move(-1, 0, 3, Dir.Down, more = true))
    }

    @Test
    fun singleItemStaysEverywhere() {
        for (cols in 1..6) for (dir in Dir.entries) assertEquals("cols=$cols $dir", Result.Stay, move(0, 1, cols, dir))
    }

    @Test
    fun singleItemUpGoesToHeaderAndDownLoadsMore() {
        assertEquals(Result.ToHeader, move(0, 1, 3, Dir.Up, header = true))
        assertEquals(Result.LoadMore, move(0, 1, 3, Dir.Down, more = true))
    }

    /* ── sin nada señalado ───────────────────────────────────── */

    @Test
    fun firstPressOnlyPlacesFocusOnInitial() {
        assertEquals(Result.Move(0), move(-1, 8, 3, Dir.Right))
        assertEquals(Result.Move(5), move(-1, 8, 3, Dir.Down, initial = 5))
        // Una imagen puesta que ya no está en la lista no saca el foco de la rejilla.
        assertEquals(Result.Move(7), move(-1, 8, 3, Dir.Up, initial = 40))
        assertEquals(Result.Move(0), move(99, 8, 3, Dir.Left))
    }

    /* ── todas las columnas de 1 a 6 ──────────────────────────── */

    @Test
    fun everyColumnCountMovesByOneCellOrOneRow() {
        for (cols in 1..6) {
            val count = cols * 4
            val middle = cols * 2 + (cols - 1) / 2
            assertEquals("cols=$cols", Result.Move(middle - cols), move(middle, count, cols, Dir.Up))
            assertEquals("cols=$cols", Result.Move(middle + cols), move(middle, count, cols, Dir.Down))
            val left = if (middle % cols > 0) Result.Move(middle - 1) else Result.Stay
            val right = if (middle % cols < cols - 1) Result.Move(middle + 1) else Result.Stay
            assertEquals("cols=$cols", left, move(middle, count, cols, Dir.Left))
            assertEquals("cols=$cols", right, move(middle, count, cols, Dir.Right))
        }
    }

    @Test
    fun oneColumnIsAVerticalList() {
        assertEquals(Result.Move(1), move(0, 5, 1, Dir.Down))
        assertEquals(Result.Stay, move(2, 5, 1, Dir.Left))
        assertEquals(Result.Stay, move(2, 5, 1, Dir.Right))
        assertEquals(Result.Stay, move(4, 5, 1, Dir.Down))
    }

    /* ── cantos ───────────────────────────────────────────────── */

    @Test
    fun rowEdgesDoNotWrap() {
        // 3 columnas: 0 1 2 / 3 4 5 / 6 7 8
        assertEquals(Result.Stay, move(2, 9, 3, Dir.Right))
        assertEquals(Result.Stay, move(3, 9, 3, Dir.Left))
        assertEquals(Result.Stay, move(5, 9, 3, Dir.Right))
        assertEquals(Result.Stay, move(0, 9, 3, Dir.Left))
    }

    @Test
    fun firstRowUpStaysWithoutHeaderAndGoesToHeaderWithOne() {
        for (i in 0 until 3) {
            assertEquals(Result.Stay, move(i, 9, 3, Dir.Up))
            assertEquals(Result.ToHeader, move(i, 9, 3, Dir.Up, header = true))
        }
        // Desde la segunda fila, arriba es la fila de arriba aunque haya cabecera.
        assertEquals(Result.Move(1), move(4, 9, 3, Dir.Up, header = true))
    }

    @Test
    fun lastRowDownStaysOrLoadsMore() {
        for (i in 6 until 9) {
            assertEquals(Result.Stay, move(i, 9, 3, Dir.Down))
            assertEquals(Result.LoadMore, move(i, 9, 3, Dir.Down, more = true))
        }
        // Antes de la última fila no se pide más: se baja.
        assertEquals(Result.Move(7), move(4, 9, 3, Dir.Down, more = true))
    }

    /* ── última fila incompleta ───────────────────────────────── */

    @Test
    fun downIntoRaggedRowLandsOnNearestItem() {
        // 4 columnas, 10 elementos: 0 1 2 3 / 4 5 6 7 / 8 9
        assertEquals(Result.Move(8), move(4, 10, 4, Dir.Down))
        assertEquals(Result.Move(9), move(5, 10, 4, Dir.Down))
        assertEquals(Result.Move(9), move(6, 10, 4, Dir.Down))
        assertEquals(Result.Move(9), move(7, 10, 4, Dir.Down))
    }

    @Test
    fun raggedRowKeepsItsOwnEdgesAndGoesBackUpToSameColumn() {
        assertEquals(Result.Stay, move(9, 10, 4, Dir.Right))
        assertEquals(Result.Move(8), move(9, 10, 4, Dir.Left))
        assertEquals(Result.Move(5), move(9, 10, 4, Dir.Up))
        assertEquals(Result.Stay, move(9, 10, 4, Dir.Down))
        assertEquals(Result.LoadMore, move(8, 10, 4, Dir.Down, more = true))
    }

    @Test
    fun raggedRowOfOneUnderSixColumns() {
        // 6 columnas, 7 elementos: la última fila es un solo elemento.
        for (i in 1..5) assertEquals(Result.Move(6), move(i, 7, 6, Dir.Down))
        assertEquals(Result.Move(6), move(0, 7, 6, Dir.Down))
    }

    @Test
    fun fewerItemsThanColumnsIsOneRow() {
        assertEquals(Result.Move(1), move(0, 2, 5, Dir.Right))
        assertEquals(Result.Stay, move(1, 2, 5, Dir.Right))
        assertEquals(Result.Stay, move(1, 2, 5, Dir.Down))
        assertEquals(Result.LoadMore, move(1, 2, 5, Dir.Down, more = true))
    }

    /* ── páginas (LB/RB) ─────────────────────────────────────── */

    @Test
    fun pageJumpsRowsKeepingColumnAndClamps() {
        // 3 columnas, 20 elementos (7 filas, la última con 2), 2 filas por página.
        assertEquals(7, GridNav.page(1, 20, 3, rows = 2, delta = 1))
        assertEquals(1, GridNav.page(7, 20, 3, rows = 2, delta = -1))
        assertEquals(2, GridNav.page(2, 20, 3, rows = 50, delta = -1))
        // Más allá de la última fila: a ella; en su hueco vacío, al último elemento.
        assertEquals(19, GridNav.page(16, 20, 3, rows = 50, delta = 1))
        assertEquals(19, GridNav.page(17, 20, 3, rows = 2, delta = 1))
    }

    @Test
    fun pageWithoutFocusOrItems() {
        assertEquals(0, GridNav.page(-1, 20, 3, rows = 2, delta = 1))
        assertEquals(-1, GridNav.page(0, 0, 3, rows = 2, delta = 1))
    }

    /* ── desplazar para ver el foco ──────────────────────────── */

    @Test
    fun revealDeltaKeepsMargin() {
        // Hueco 0..500, margen 20.
        assertEquals(0, GridNav.revealDelta(top = 40, bottom = 200, start = 0, end = 500, margin = 20))
        assertEquals(-30, GridNav.revealDelta(top = -10, bottom = 150, start = 0, end = 500, margin = 20))
        assertEquals(70, GridNav.revealDelta(top = 400, bottom = 550, start = 0, end = 500, margin = 20))
    }

    @Test
    fun revealDeltaPrefersTopWhenItemIsTallerThanViewport() {
        assertEquals(80, GridNav.revealDelta(top = 100, bottom = 900, start = 0, end = 500, margin = 20))
    }

    @Test
    fun revealDeltaHonoursNegativeStartFromContentPadding() {
        // Con padding de contenido el hueco empieza en negativo (viewportStartOffset).
        assertEquals(0, GridNav.revealDelta(top = 0, bottom = 100, start = -30, end = 470, margin = 20))
        assertEquals(-10, GridNav.revealDelta(top = -20, bottom = 100, start = -30, end = 470, margin = 20))
    }
}
