package com.elyndra.launcher.ui

import com.elyndra.launcher.data.ACCENTS
import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.ui.ShelfLayout.BOTTOM_MARGIN
import com.elyndra.launcher.ui.ShelfLayout.GLOW_TOP
import com.elyndra.launcher.ui.ShelfLayout.HINTS
import com.elyndra.launcher.ui.ShelfLayout.LIFT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfLayoutTest {

    private val eps = 1e-2f
    private val label = 6f + 9.5f * 1.45f

    private fun compute(available: Float, hints: Boolean = false, base: Float = 128f, inset: Float = 0f, aspect: Float = 1f) =
        ShelfLayout.compute(available, hints, aspect, base, inset, label, minTile = 72f, maxTile = 185f)

    /** Lo que la seleccionada necesita por encima de la card en reposo. */
    private fun headroom(tile: Float) = LIFT + GLOW_TOP + tile * ShelfLayout.SCALE_GROW / 2f

    @Test
    fun `con sitio la card crece como mucho un 10 por ciento`() {
        val r = compute(available = 260f)
        assertEquals(128f * ShelfLayout.GROW, r.tileHeight, eps)
        assertTrue(r.tileHeight <= 128f * 1.12f)
    }

    @Test
    fun `el tope absoluto manda en tableta`() {
        val r = compute(available = 400f, base = 180f)
        assertEquals(185f, r.tileHeight, eps)
    }

    @Test
    fun `en una ventana baja la card encoge para no recortarse`() {
        for (available in listOf(150f, 170f, 190f)) for (hints in listOf(false, true)) {
            val r = compute(available, hints)
            val h = if (hints) HINTS else 0f
            if (r.tileHeight > 72f + eps) {
                assertTrue("halo arriba ($available)", r.tileTop >= headroom(r.tileHeight) - eps)
                assertTrue("nombre sobre las pistas ($available)", r.labelTop + (label - 6f) <= available - h - BOTTOM_MARGIN + eps)
            }
            assertTrue(r.tileHeight >= 72f - eps)
        }
    }

    @Test
    fun `la card se centra entre el hero y las pistas`() {
        for ((available, inset) in listOf(240f to 26f, 248f to 0f, 416f to 36f)) {
            val r = compute(available, inset = inset)
            val above = r.tileTop - inset
            val below = available - (r.tileTop + r.tileHeight)
            assertEquals("centrada ($available)", above, below, 0.5f)
        }
    }

    @Test
    fun `con las pistas la fila sube y no cambia de tamano`() {
        // metrics() cede al estante el alto de las pistas (el hero encoge 30 dp).
        val without = compute(available = 240f)
        val with = compute(available = 270f, hints = true)
        assertEquals(without.tileHeight, with.tileHeight, eps)
        assertTrue(with.labelTop + (label - 6f) <= 270f - HINTS - BOTTOM_MARGIN + eps)
    }

    @Test
    fun `la proporcion de la card se respeta`() {
        val cover = compute(available = 416f, base = 304f, aspect = 2f / 3f, inset = 36f)
        assertEquals(cover.tileHeight * 2f / 3f, cover.tileWidth, eps)
        val square = compute(available = 240f)
        assertEquals(square.tileHeight, square.tileWidth, eps)
    }

    @Test
    fun `la extension del arte encoge en ventanas bajas y nunca llega a los nombres`() {
        val tall = ShelfLayout.artExtension(240f, 900f, labelTop = 200f)
        val short = ShelfLayout.artExtension(240f, 412f, labelTop = 200f)
        assertEquals(240f * ShelfLayout.EXTENSION_TALL, tall, eps)
        assertEquals(240f * ShelfLayout.EXTENSION_SHORT, short, eps)
        var last = 0f
        var h = 300f
        while (h <= 1400f) {
            val e = ShelfLayout.artExtension(240f, h, labelTop = 500f)
            assertTrue("monótona ($h)", e >= last - eps)
            last = e
            h += 50f
        }
        assertEquals(94f, ShelfLayout.artExtension(240f, 900f, labelTop = 100f), eps)
        assertEquals(0f, ShelfLayout.artExtension(240f, 900f, labelTop = 2f), eps)
    }

    @Test
    fun `las rampas del velo empiezan y acaban donde toca y no dan saltos atras`() {
        assertEquals(0f, ShelfLayout.ramp(ShelfLayout.SHELF_RAMP, 0f), eps)
        assertEquals(1f, ShelfLayout.ramp(ShelfLayout.SHELF_RAMP, 1f), eps)
        assertEquals(1f, ShelfLayout.ramp(ShelfLayout.TITLE_BAND, 0f), eps)
        assertEquals(0f, ShelfLayout.ramp(ShelfLayout.TITLE_BAND, 1f), eps)
        var up = 0f
        var down = 1f
        var x = 0f
        while (x <= 1f) {
            val a = ShelfLayout.ramp(ShelfLayout.SHELF_RAMP, x)
            val b = ShelfLayout.ramp(ShelfLayout.TITLE_BAND, x)
            assertTrue(a >= up - eps)
            assertTrue(b <= down + eps)
            up = a
            down = b
            x += 0.02f
        }
    }

    @Test
    fun `los nombres y las pistas se leen sobre el estante en los dos temas`() {
        for (accent in ACCENTS) for (dark in listOf(false, true)) {
            val n = if (dark) BrandTokens.DARK else BrandTokens.LIGHT
            val shelf = ShelfLayout.shelfColor(n.paper, accent.a.argb(), dark)
            assertTrue("${accent.id} nombre (dark=$dark)", ColorMath.contrast(n.ink, shelf) >= 4.5)
            assertTrue("${accent.id} nombre secundario y pistas (dark=$dark)", ColorMath.contrast(n.ink2, shelf) >= 4.5)
        }
    }

    @Test
    fun `al pie las pistas se leen aunque el estante deje pasar el fondo`() {
        for (accent in ACCENTS) for (dark in listOf(false, true)) {
            val n = if (dark) BrandTokens.DARK else BrandTokens.LIGHT
            val shelf = ColorMath.withAlpha(ShelfLayout.shelfColor(n.paper, accent.a.argb(), dark), ShelfLayout.shelfBottomAlpha(dark))
            for (under in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
                val seen = ColorMath.over(shelf, under)
                assertTrue("${accent.id} pistas (dark=$dark)", ColorMath.contrast(n.ink2, seen) >= 4.5)
            }
        }
    }

    @Test
    fun `el estante es tinta honda en oscuro y perla en claro, nunca negro ni gris plano`() {
        val accent = ACCENTS.first().a.argb()
        val dark = ShelfLayout.shelfColor(BrandTokens.DARK.paper, accent, true)
        val light = ShelfLayout.shelfColor(BrandTokens.LIGHT.paper, accent, false)
        assertTrue(dark != 0xFF000000.toInt())
        assertTrue(ColorMath.luminance(dark) < 0.03)
        assertTrue(ColorMath.luminance(light) > 0.8)
        // Con matiz: el azul manda un poco (no es un gris de r = g = b).
        assertTrue(ColorMath.blue(light) > ColorMath.red(light))
        assertTrue(ColorMath.blue(dark) > ColorMath.red(dark))
    }

    @Test
    fun `por debajo de la extension el estante es liso, sea cual sea el arte`() {
        // Con el velo del estante a 1, el color es el del estante tal cual: ni el
        // arte más claro ni el más oscuro asoman por debajo de los nombres.
        val n = BrandTokens.LIGHT
        val shelf = ShelfLayout.shelfColor(n.paper, ACCENTS.first().a.argb(), false)
        val alpha = ShelfLayout.ramp(ShelfLayout.SHELF_RAMP, 1f)
        for (art in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFE066.toInt())) {
            val seen = ColorMath.over(ColorMath.withAlpha(shelf, alpha), art)
            assertEquals(shelf, seen)
        }
    }
}
