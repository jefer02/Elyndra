package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.ui.theme.WheelGlass
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
            // La línea de foco bajada lo justo para que ninguna muestra caiga junto al borde de arriba (ver TOP_EASE).
            val focus = g.focusY + MeridianFog.TOP_EASE
            val centre = MeridianFog.alphaAt(x, focus, g.axisX, w, focus, g.arcRadius)
            val edge = MeridianFog.alphaAt(x, focus - g.railHeight / 2f, g.axisX, w, focus, g.arcRadius)
            assertTrue("w=$w $edge < $centre", edge < centre)
            // Simétrica arriba y abajo.
            assertEquals(edge, MeridianFog.alphaAt(x, focus + g.railHeight / 2f, g.axisX, w, focus, g.arcRadius), 1e-6f)
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
            // La columna de la rueda de solo iconos (la que se usa).
            val ax = MeridianGeometry.slimRailWidth(w, w * 0.5f)
            val end = MeridianScrims.clearAt(ax, w)
            val mid = MeridianScrims.alphaAt((ax + end) / 2f, ax, w)
            assertTrue("w=$w mid=$mid", mid < MeridianScrims.AXIS_ALPHA * 0.4f)
            val near = MeridianScrims.alphaAt(ax + (end - ax) * 0.1f, ax, w)
            assertTrue("w=$w near=$near", near > MeridianScrims.AXIS_ALPHA * 0.85f)
            assertTrue("w=$w end=$end", end <= w * 0.62f)
        }
    }

    /** El arco del cristal en una ventana [w] de alto la mitad, con la cabecera y las pistas de siempre. */
    private fun glass(w: Float, reach: Float = 0f): Pair<MeridianGeometry, GlassArc> {
        val g = geo(w)
        val h = w * 0.5f
        val focus = 40f + g.focusY
        return g to MeridianScrims.glassArc(g.axisX, focus, -24f, h, g.arcRadius, reach)
    }

    @Test
    fun `the light glass edge passes the dial at the focus line`() {
        for (w in widths) {
            val (g, arc) = glass(w)
            val atFocus = arc.edgeAt(40f + g.focusY)
            // En la línea de foco: el dial con su nodo y su resplandor (~6 dp pasado el eje) dentro, con aire; el hero, fuera.
            assertTrue(atFocus >= g.axisX + WheelGlass.PAST_AXIS_DP - 1e-3f)
            assertTrue(atFocus <= g.heroLeft - 16f)
            assertTrue(MeridianScrims.glassRight(arc, w) >= arc.peak)
        }
    }

    @Test
    fun `the light glass edge is one symmetric curve from the top of the screen to the bottom`() {
        for (w in widths) {
            val (_, arc) = glass(w)
            val top = -24f
            val bottom = w * 0.5f
            val c = (top + bottom) / 2f
            // Simétrica respecto al centro de la pantalla, y se recoge siempre hacia las puntas (sin picos ni tramos rectos).
            for (d in listOf(10f, 80f, 160f, (bottom - top) / 2f)) assertEquals(arc.edgeAt(c - d), arc.edgeAt(c + d), 1e-3f)
            var prev = arc.edgeAt(c)
            var y = c
            while (y > top) {
                y -= 4f
                val x = arc.edgeAt(y)
                assertTrue("y=$y", x < prev)
                prev = x
            }
            assertTrue(arc.edgeAt(top) >= WheelGlass.MIN_EDGE_DP - 1e-3f)
        }
    }

    @Test
    fun `a wide header opens the arc but keeps it symmetric`() {
        for (w in widths) {
            val (_, bare) = glass(w)
            val reach = bare.edgeAt(0f) + minOf(30f, (bare.peak - bare.edgeAt(0f)) / 2f)
            val (_, open) = glass(w, reach)
            assertTrue(open.edgeAt(0f) >= reach - 1e-3f)
            assertTrue(open.radius > bare.radius)
            assertEquals(open.edgeAt(open.centerY - 100f), open.edgeAt(open.centerY + 100f), 1e-3f)
        }
    }

    @Test
    fun `the glass edge reaches the top of the layer flat, so its mirror under the status bar has no peak`() {
        for (w in widths) {
            val g = geo(w)
            // El velo oscuro sigue reflejándose bajo la barra de estado: su canto llega plano.
            val at = { y: Float -> MeridianFog.visibleEdge(y, g.axisX, g.focusY, g.arcRadius) }
            assertEquals(at(0f), at(1f), 0.05f)
            val y = MeridianFog.TOP_EASE
            assertEquals(g.axisX + MeridianFog.WASH_REACH + WheelArc.offset(y + 10f - g.focusY, g.arcRadius), at(y + 10f), 1e-3f)
            assertTrue(abs(at(y) - at(y - 1f)) < 1f)
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
