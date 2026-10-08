package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.data.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MeridianFogTest {

    private val widths = listOf(640f, 900f, 1280f, 1600f)

    /** Una ventana de [w] de ancho con la rueda de 0,78·alto bajo su cabecera. */
    private fun geo(w: Float) = MeridianGeometry.compute(w, w * 0.5f)

    @Test
    fun `on the focus line the fog is the plain scrim profile`() {
        for (w in widths) {
            val g = geo(w)
            var x = 0f
            while (x < w) {
                assertEquals(MeridianScrims.alphaAt(x, g.axisX, w), MeridianFog.alphaAt(x, g.focusY, g.axisX, w, g.focusY, g.arcRadius), 1e-6f)
                x += 7f
            }
        }
    }

    @Test
    fun `every row of the wheel has behind its text the same fog as the focused row`() {
        // La fila que pasa a dy de la línea se corre WheelArc.offset(dy): la niebla se corre igual.
        for (w in widths) {
            val g = geo(w)
            var dy = -g.railHeight / 2f
            while (dy <= g.railHeight / 2f) {
                val off = WheelArc.offset(dy, g.arcRadius)
                var x = w * 0.08f
                while (x <= g.axisX - 34f) {
                    val onRow = MeridianFog.alphaAt(x + off, g.focusY + dy, g.axisX, w, g.focusY, g.arcRadius)
                    assertEquals("w=$w dy=$dy x=$x", MeridianScrims.alphaAt(x, g.axisX, w), onRow, 1e-5f)
                    x += 8f
                }
                dy += 10f
            }
        }
    }

    @Test
    fun `far from the centre of the list the fog clears earlier and follows the arc`() {
        for (w in widths) {
            val g = geo(w)
            val x = g.axisX + (MeridianScrims.clearAt(g.axisX, w) - g.axisX) * 0.3f
            val centre = MeridianFog.alphaAt(x, g.focusY, g.axisX, w, g.focusY, g.arcRadius)
            val edge = MeridianFog.alphaAt(x, g.focusY - g.railHeight / 2f, g.axisX, w, g.focusY, g.arcRadius)
            assertTrue("w=$w $edge < $centre", edge < centre)
            // Simétrica arriba y abajo.
            assertEquals(edge, MeridianFog.alphaAt(x, g.focusY + g.railHeight / 2f, g.axisX, w, g.focusY, g.arcRadius), 1e-6f)
        }
    }

    @Test
    fun `along every row the fog never grows towards the art and has no edge`() {
        for (w in widths) {
            val g = geo(w)
            for (dy in listOf(0f, g.railHeight * 0.25f, g.railHeight * 0.6f)) {
                var prev = MeridianFog.alphaAt(0f, g.focusY + dy, g.axisX, w, g.focusY, g.arcRadius)
                var x = 1f
                while (x < w) {
                    val a = MeridianFog.alphaAt(x, g.focusY + dy, g.axisX, w, g.focusY, g.arcRadius)
                    assertTrue("w=$w dy=$dy x=$x", a <= prev + 1e-6f)
                    assertTrue("salto en $x", abs(a - prev) < 0.02f)
                    prev = a
                    x += 1f
                }
            }
        }
    }

    @Test
    fun `past the axis the fog is graded, denser near the wheel and lighter than a plain fade`() {
        for (w in widths) {
            val ax = MeridianGeometry.railWidthFor(w)
            val end = MeridianScrims.clearAt(ax, w)
            val mid = MeridianScrims.alphaAt((ax + end) / 2f, ax, w)
            assertTrue("w=$w mid=$mid", mid < MeridianScrims.AXIS_ALPHA * 0.4f)
            val near = MeridianScrims.alphaAt(ax + (end - ax) * 0.1f, ax, w)
            assertTrue("w=$w near=$near", near > MeridianScrims.AXIS_ALPHA * 0.85f)
            assertTrue("w=$w end=$end", end <= w * 0.62f)
        }
    }

    @Test
    fun `without an arc the fog is the straight profile`() {
        val w = 1280f
        val ax = MeridianGeometry.railWidthFor(w)
        for (y in listOf(0f, 200f, 700f)) {
            assertEquals(MeridianScrims.alphaAt(500f, ax, w), MeridianFog.alphaAt(500f, y, ax, w, 300f, 0f), 0f)
        }
    }

    @Test
    fun `the mask samples the fog at the centre of each cell`() {
        val w = 1280f
        val g = geo(w)
        val layerW = MeridianScrims.clearAt(g.axisX, w)
        val h = g.railHeight
        val cols = MeridianFog.samples(layerW, MeridianFog.CELL_DP)
        val rows = MeridianFog.samples(h, MeridianFog.CELL_DP)
        val px = MeridianFog.mask(cols, rows, layerW, h, g.axisX, w, g.focusY, g.arcRadius)
        assertEquals(cols * rows, px.size)
        for ((i, j) in listOf(0 to 0, cols / 2 to rows / 2, cols - 1 to rows - 1, cols / 3 to 5)) {
            val x = (i + 0.5f) * layerW / cols
            val y = (j + 0.5f) * h / rows
            val expected = MeridianFog.alphaAt(x, y, g.axisX, w, g.focusY, g.arcRadius)
            val p = px[j * cols + i]
            assertEquals(0, p and 0x00FFFFFF)
            assertEquals(expected, ColorMath.alpha(p) / 255f, 1f / 255f + 1e-4f)
        }
        // La última columna (el canto de la capa) ya es transparente en la línea de foco.
        assertTrue(ColorMath.alpha(px[(rows / 2) * cols + cols - 1]) <= 2)
        assertEquals(2, MeridianFog.samples(0f, 6f))
    }
}
