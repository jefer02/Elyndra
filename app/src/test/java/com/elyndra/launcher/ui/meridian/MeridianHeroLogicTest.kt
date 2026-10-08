package com.elyndra.launcher.ui.meridian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeridianHeroLogicTest {

    /** Una zona del hero de 800 dp en una ventana de 600 dp: caja de 480 × 156, como poco 84 de alto. */
    private val box = LogoFit.box(heroWidth = 800f, contentWidth = 800f, screenHeight = 600f)

    @Test
    fun `the logo box follows the hero zone, the block and the window height`() {
        assertEquals(480f, box.maxWidth, 1e-3f)
        assertEquals(156f, box.maxHeight, 1e-3f)
        assertEquals(84f, box.minHeight, 1e-3f)
        // Nunca más ancha que el bloque.
        assertEquals(300f, LogoFit.box(800f, 300f, 600f).maxWidth, 1e-3f)
        // En una zona muy ancha el tope es 640 dp (en px, con la densidad).
        assertEquals(640f * 2.5f, LogoFit.box(8000f, 8000f, 600f, dp = 2.5f).maxWidth, 1e-3f)
        // Ventana baja: como mucho el 20 % del alto.
        val compact = LogoFit.box(800f, 800f, 360f, compact = true)
        assertEquals(72f, compact.maxHeight, 1e-3f)
        assertTrue(compact.minHeight < compact.maxHeight)
    }

    @Test
    fun `wide wordmarks reach 420 to 470 px on the 1280 x 800 tablet of the screenshots`() {
        // 1280 × 800 px a densidad 1,5: ventana de 853 × 533 dp; la rueda, su hueco y el margen.
        val width = 853f
        val rail = MeridianGeometry.railWidthFor(width)
        val content = width - rail - MeridianGeometry.HERO_GAP - 22f
        val px = 1.5f
        val b = LogoFit.box(width - rail, content, 533f, dp = 1f, density = px)
        val boxPx = b.copy(maxWidth = b.maxWidth * px, maxHeight = b.maxHeight * px, minHeight = b.minHeight * px)
        val (w, _) = LogoFit.fit(boxPx, 1600f, 260f)
        assertTrue("$w", w in 420f..470f)
        // Antes (46 % del bloque y 24 % del alto) se quedaba en ~290 px.
        assertTrue(w > 290f * 1.4f)
    }

    @Test
    fun `wide square and tall logos fit the box without distortion`() {
        val (ww, wh) = LogoFit.fit(box, 2000f, 300f)
        assertEquals(box.maxWidth, ww, 1e-3f)
        assertEquals(2000f / 300f, ww / wh, 1e-3f)
        val (sw, sh) = LogoFit.fit(box, 512f, 512f)
        assertEquals(box.maxHeight, sh, 1e-3f)
        assertEquals(sw, sh, 1e-3f)
        val (tw, th) = LogoFit.fit(box, 300f, 900f)
        assertEquals(box.maxHeight, th, 1e-3f)
        assertEquals(300f / 900f, tw / th, 1e-3f)
        for ((w, h) in listOf(2000f to 300f, 512f to 512f, 300f to 900f, 64f to 20f)) {
            val (fw, fh) = LogoFit.fit(box, w, h)
            assertTrue(fw <= box.maxWidth + 1e-3f)
            assertTrue(fh <= box.maxHeight + 1e-3f)
        }
    }

    @Test
    fun `small logos grow toward the minimum height but never beyond their upscale cap`() {
        // 100 × 50: se amplía al doble (200 × 100), que cabe en la caja; nunca más.
        val (w, h) = LogoFit.fit(box, 100f, 50f)
        assertEquals(200f, w, 1e-3f)
        assertEquals(100f, h, 1e-3f)
        // 40 × 16: el doble (80 × 32) no llega a la mínima, y se queda en el doble.
        val (tw, th) = LogoFit.fit(box, 40f, 16f)
        assertEquals(80f, tw, 1e-3f)
        assertEquals(32f, th, 1e-3f)
        // Muy ancho y bajo: el ancho manda aunque quede por debajo de la mínima.
        val (vw, vh) = LogoFit.fit(box, 3000f, 100f)
        assertEquals(box.maxWidth, vw, 1e-3f)
        assertTrue(vh < box.minHeight)
        assertEquals(0f to 0f, LogoFit.fit(box, 0f, 10f))
    }

    @Test
    fun `the upscale cap rises to 2,5 only on dense screens where it stays sharp`() {
        assertEquals(2f, LogoFit.maxUpscale(1f), 0f)
        assertEquals(2f, LogoFit.maxUpscale(1.5f), 0f)
        assertEquals(2.5f, LogoFit.maxUpscale(2f), 0f)
        assertEquals(2.5f, LogoFit.maxUpscale(3.5f), 0f)
        // Cada píxel del logo no ocupa más de 1,25 dp cuando pasa del doble.
        for (d in listOf(1f, 1.5f, 1.75f, 2f, 2.625f, 3f)) {
            val s = LogoFit.maxUpscale(d)
            assertTrue(s <= LogoFit.MAX_UPSCALE || s / d <= LogoFit.MAX_DP_PER_PIXEL + 1e-4f)
        }
        val dense = LogoFit.box(800f, 800f, 600f, density = 2.75f)
        val (w, _) = LogoFit.fit(dense, 40f, 16f)
        assertEquals(100f, w, 1e-3f)
    }

    @Test
    fun `long paths keep their start and end around a middle ellipsis`() {
        val path = "/storage/emulated/0/Roms/Nintendo/Switch"
        assertEquals(path, MiddleEllipsis.shorten(path, 80))
        val short = MiddleEllipsis.shorten(path, 21)
        assertEquals(21, short.length)
        assertTrue(short.startsWith("/storage/"))
        assertTrue(short.endsWith("Switch"))
        assertTrue("…" in short)
        assertEquals("…", MiddleEllipsis.shorten(path, 1))
    }

    @Test
    fun `pad glyphs show only with a gamepad connected`() {
        assertTrue(PadGlyphs.visible(gamepadPresent = true))
        assertFalse(PadGlyphs.visible(gamepadPresent = false))
        assertEquals(1f, PadGlyphs.target(true), 0f)
        assertEquals(0f, PadGlyphs.target(false), 0f)
    }

    @Test
    fun `the hero art is widened so its subject sits in the clear right side`() {
        assertEquals(1350f, MeridianArtFrame.width(1000f), 1e-3f)
        // El centro del arte cae a la derecha del velo (que se apaga hacia el 58 %).
        assertTrue(MeridianArtFrame.subjectAt() > 0.6f)
    }
}
