package com.elyndra.launcher.ui

import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.display.FrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroArtQualityTest {

    @Test
    fun `un fanart grande no es de baja resolucion`() {
        // 1920×1080 cubriendo el hero de un móvil apaisado (1600×~500 px).
        assertFalse(HeroArtQuality.isLowRes(1920, 1080, 1600, 500, density = 2f))
        assertTrue(HeroArtQuality.cropScale(1920, 1080, 1600, 500) < 1f)
    }

    @Test
    fun `una captura de 320 px a pantalla completa si lo es`() {
        assertTrue(HeroArtQuality.isLowRes(320, 240, 1600, 500, density = 2f))
        assertTrue(HeroArtQuality.isLowRes(640, 480, 2560, 900, density = 1f))
        // En la tableta también: cada píxel ocuparía unos 5 dp.
        assertTrue(HeroArtQuality.isLowRes(320, 240, 4104, 2021, density = 2.5f))
    }

    /*
     * La regresión de la tableta (Legion Y700, 3040×1904): la capa del arte de
     * Meridian mide ~1,35 veces la ventana de ancho (MeridianArtFrame) y algo
     * más de alto (el paralaje), ~4104×2021 px. Un hero de 1920×620 o un fanart
     * de 1920×1080 se amplían ahí más de 2 veces y se desenfocaban, aunque en
     * una pantalla así cada píxel sigue siendo ~1 dp y se ve nítido.
     */
    @Test
    fun `en una tableta densa el arte de 1920 px no se desenfoca`() {
        for (density in listOf(2.25f, 2.5f)) {
            assertFalse("hero 1920×620 a $density", HeroArtQuality.isLowRes(1920, 620, 4104, 2021, density))
            assertFalse("fanart 1920×1080 a $density", HeroArtQuality.isLowRes(1920, 1080, 4104, 2021, density))
        }
        // Y en el móvil (1600×720, ~2160×802 px de capa) sigue igual de nítido.
        assertFalse(HeroArtQuality.isLowRes(1920, 620, 2160, 802, density = 2f))
    }

    @Test
    fun `la misma ampliacion se nota mas cuanto menos densa es la pantalla`() {
        // Ampliar ~2,1 veces: cada píxel son 2,1 dp a densidad 1 y menos de 1 dp a 2,5.
        assertTrue(HeroArtQuality.isLowRes(1920, 1080, 4104, 2021, density = 1f))
        assertFalse(HeroArtQuality.isLowRes(1920, 1080, 4104, 2021, density = 2.5f))
        assertEquals(1.5f, HeroArtQuality.dpPerPixel(1000, 500, 3000, 1000, density = 2f), 1e-4f)
    }

    @Test
    fun `el recorte se mide por el lado que mas se estira`() {
        // Muy apaisada en una caja alta: lo que manda es el alto.
        assertEquals(3f, HeroArtQuality.cropScale(1000, 200, 900, 600), 1e-4f)
    }

    @Test
    fun `sin medidas no hay nada que suavizar`() {
        assertFalse(HeroArtQuality.isLowRes(0, 0, 1600, 500, density = 2f))
        assertFalse(HeroArtQuality.isLowRes(320, 240, 0, 0, density = 2f))
    }

    @Test
    fun `el desenfoque crece con la ampliacion y tiene topes`() {
        val a = HeroArtQuality.softBlurDp(2.5f)
        val b = HeroArtQuality.softBlurDp(4f)
        assertTrue(b > a)
        assertEquals(HeroArtQuality.SOFT_BLUR_MIN, HeroArtQuality.softBlurDp(HeroArtQuality.LOW_RES_DP_PER_PIXEL), 1e-4f)
        assertEquals(HeroArtQuality.SOFT_BLUR_MAX, HeroArtQuality.softBlurDp(20f), 1e-4f)
    }

    @Test
    fun `el hero se pide al tamaño de su caja, nunca al de una miniatura`() {
        assertEquals(1600 to 520, HeroArtQuality.requestSize(1600, 520, 1f))
        assertEquals(1200 to 390, HeroArtQuality.requestSize(1600, 520, 0.75f))
        assertEquals(1 to 1, HeroArtQuality.requestSize(0, 0, 1f))
    }

    /* ── El pie del estante ──────────────────────────────────── */

    @Test
    fun `el pie del estante es opaco y algo mas hondo, nunca gris lavado`() {
        assertEquals(1f, ShelfLayout.shelfBottomAlpha(true), 0f)
        assertEquals(1f, ShelfLayout.shelfBottomAlpha(false), 0f)
        val accents = listOf(0xFF7958FF.toInt(), 0xFFFF8A1F.toInt(), 0xFF19E3A5.toInt(), 0xFFFF4FA3.toInt(), 0xFFE9B44C.toInt())
        for (accent in accents) for (dark in listOf(true, false)) {
            val n = if (dark) BrandTokens.DARK else BrandTokens.LIGHT
            val shelf = ShelfLayout.shelfColor(n.paper, accent, dark)
            val foot = ShelfLayout.shelfFootColor(n.paper, accent, dark)
            assertEquals("opaco", 0xFF, foot ushr 24)
            if (dark) {
                assertTrue("en oscuro el pie se ahonda", ColorMath.luminance(foot) < ColorMath.luminance(shelf))
            } else {
                assertTrue("en claro sigue siendo perla", ColorMath.luminance(foot) > 0.6)
            }
            // Las pistas del mando se leen al pie.
            assertTrue("pistas (dark=$dark, ${Integer.toHexString(accent)})", ColorMath.contrast(n.ink2, foot) >= 4.5)
        }
    }

    /* ── 60 fps en Rendimiento ───────────────────────────────── */

    @Test
    fun `en Rendimiento Auto va a 60 y un 120 elegido se respeta`() {
        assertEquals(FrameRate.HIGH, FrameRate.effective(FrameRate.AUTO, supportsHigh = true))
        assertEquals(FrameRate.STANDARD, FrameRate.effective(FrameRate.AUTO, supportsHigh = true, capped = true))
        assertEquals(FrameRate.HIGH, FrameRate.effective(FrameRate.HIGH, supportsHigh = true, capped = true))
        assertEquals(FrameRate.STANDARD, FrameRate.effective(FrameRate.HIGH, supportsHigh = false, capped = true))
    }
}
