package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.ui.theme.GlassTokens
import com.elyndra.launcher.ui.theme.WheelGlass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassPanelLayoutTest {

    private val eps = 1e-3f

    /**
     * Ventanas (dp) donde se ve el cristal: móvil apaisado (915 × 412), tableta
     * de 1280 × 800 (también la de 2560 × 1600 a densidad 2), la de 2000 × 1200
     * (1000 × 600) y una tele de 1920 × 1080 (960 × 540).
     */
    private val windows = listOf(915f to 412f, 1280f to 800f, 1000f to 600f, 960f to 540f)

    /** El cristal de una ventana de [w] × [h], con la cabecera y las pistas de siempre (40 dp y 30 dp) y la cabecera pidiendo [reach]. */
    private fun arc(w: Float, h: Float, reach: Float = 0f): Pair<MeridianGeometry, GlassArc> {
        val compact = MeridianMode.compact(h)
        val rail = MeridianGeometry.slimRailWidth(w, h, 2f / 3f, compact)
        val g = MeridianGeometry.compute(w, h - 70f, 2f / 3f, compact, rail)
        return g to MeridianScrims.glassArc(g.axisX, 40f + g.focusY, 0f, h, g.arcRadius, reach)
    }

    @Test
    fun `blur and glow keep the same share of the window on every size`() {
        for ((w, _) in windows) {
            val blur = GlassPanelLayout.blurDp(w)
            assertTrue("w=$w blur=$blur", blur in GlassTokens.BLUR_MIN..GlassTokens.BLUR_MAX)
            // Dentro de los topes, el desenfoque es la misma fracción del ancho que en la referencia.
            assertEquals("w=$w", GlassTokens.BLUR_DP / GlassTokens.REFERENCE_WIDTH, blur / w, 1e-4f)
            val glow = GlassPanelLayout.glowDp(w)
            assertTrue("w=$w glow=$glow", glow in GlassTokens.GLOW_MIN..GlassTokens.GLOW_MAX)
        }
        // En una tableta grande se desenfoca más dp que en un móvil (antes, los mismos 20 dp).
        assertTrue(GlassPanelLayout.blurDp(1280f) > GlassPanelLayout.blurDp(915f))
        // Los topes: ni un móvil estrecho queda nítido ni una ventana enorme lechosa.
        assertEquals(GlassTokens.BLUR_MIN, GlassPanelLayout.blurDp(640f), eps)
        assertEquals(GlassTokens.BLUR_MAX, GlassPanelLayout.blurDp(2400f), eps)
        assertEquals(GlassPanelLayout.blurDp(1280f) * GlassPanelLayout.BLUR_BLEED, GlassPanelLayout.blurBleed(1280f), eps)
    }

    @Test
    fun `the glass edge never becomes a straight line, however far the header asks`() {
        for ((w, h) in windows) {
            val (_, open) = arc(w, h, reach = w)
            val half = h / 2f
            // El fallo: con la cabecera más allá del pico, radio infinito y canto recto.
            assertTrue("w=$w r=${open.radius}", open.radius.isFinite())
            val sag = open.peak - open.edgeAt(0f)
            assertTrue("w=$w sag=$sag", sag >= GlassPanelLayout.minSag(half) - eps)
            // Sigue siendo una sola curva simétrica.
            assertEquals(open.edgeAt(open.centerY - 120f), open.edgeAt(open.centerY + 120f), eps)
        }
    }

    @Test
    fun `a normal header still opens the arc as much as it needs`() {
        for ((w, h) in windows) {
            val (_, bare) = arc(w, h)
            val reach = bare.edgeAt(0f) + 20f
            val (_, open) = arc(w, h, reach)
            assertTrue("w=$w", open.edgeAt(0f) >= reach - eps)
        }
    }

    @Test
    fun `the minimum curve scales with the panel height, within its limits`() {
        assertEquals(400f * GlassPanelLayout.MIN_SAG_FRACTION, GlassPanelLayout.minSag(400f), eps)
        assertEquals(GlassPanelLayout.MIN_SAG_MIN, GlassPanelLayout.minSag(100f), eps)
        assertEquals(GlassPanelLayout.MIN_SAG_MAX, GlassPanelLayout.minSag(2000f), eps)
        assertTrue(GlassPanelLayout.maxRadius(0f).isInfinite())
        // El radio más abierto pasa justo por minSag en el filo.
        val half = 400f
        val r = GlassPanelLayout.maxRadius(half)
        assertEquals(GlassPanelLayout.minSag(half), GlassArc.sag(r, half), 1e-2f)
    }

    @Test
    fun `the glass passes the dial and stays clear of the hero on every size`() {
        for ((w, h) in windows) {
            val (g, a) = arc(w, h)
            val atFocus = a.edgeAt(40f + g.focusY)
            assertTrue("w=$w", atFocus >= g.axisX + WheelGlass.PAST_AXIS_DP - 1f)
            assertTrue("w=$w", MeridianScrims.glassRight(a, w) <= g.heroLeft)
        }
    }
}
