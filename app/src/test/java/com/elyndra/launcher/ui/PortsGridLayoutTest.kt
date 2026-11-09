package com.elyndra.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortsGridLayoutTest {

    @Test fun `columns follow the width of the real devices`() {
        // Legion Y700 en apaisado, el Tecno Spark 30C en apaisado y en vertical.
        assertEquals(5, PortsGridLayout.columns(1280f))
        assertEquals(3, PortsGridLayout.columns(800f))
        assertEquals(2, PortsGridLayout.columns(360f))
        // La tableta en vertical y un móvil grande.
        assertEquals(3, PortsGridLayout.columns(800f))
        assertEquals(2, PortsGridLayout.columns(412f))
    }

    @Test fun `never fewer than two nor more than six columns`() {
        assertEquals(2, PortsGridLayout.columns(200f))
        assertEquals(2, PortsGridLayout.columns(0f))
        assertEquals(6, PortsGridLayout.columns(4000f))
    }

    @Test fun `capsules fill the row exactly and never go below the minimum`() {
        for (w in listOf(360f, 412f, 600f, 800f, 1024f, 1280f, 1920f)) {
            val cols = PortsGridLayout.columns(w)
            val c = PortsGridLayout.capsuleWidth(w, cols)
            val used = cols * c + (cols - 1) * PortsGridLayout.GAP
            assertEquals("$w", PortsGridLayout.room(w), used, 0.01f)
            assertTrue("$w: $c", c >= PortsGridLayout.minCapsule(w) || cols == PortsGridLayout.MIN_COLUMNS)
        }
    }

    @Test fun `the grid moves in two dimensions with the shared grid navigation`() {
        val cols = PortsGridLayout.columns(1280f)
        assertEquals(GridNav.Result.Move(cols), GridNav.move(0, 12, cols, GridNav.Dir.Down))
        assertEquals(GridNav.Result.Move(1), GridNav.move(0, 12, cols, GridNav.Dir.Right))
        // La última fila está incompleta: abajo va a su último elemento.
        assertEquals(GridNav.Result.Move(11), GridNav.move(9, 12, cols, GridNav.Dir.Down))
    }
}
