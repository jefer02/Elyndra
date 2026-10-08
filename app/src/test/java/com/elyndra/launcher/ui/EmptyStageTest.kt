package com.elyndra.launcher.ui

import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.data.SignaturePalettes
import com.elyndra.launcher.data.SignaturePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class EmptyStageTest {

    private val white = 0xFFFFFFFF.toInt()

    /** Las paletas de firma tal como llegan a la pantalla, y colores de acento extremos (amarillo, blanco, negro, gris). */
    private fun pairs(dark: Boolean) = SignaturePreset.entries.map { SignaturePalettes.forTheme(it.pair, dark) } + listOf(
        0xFFFFD400, 0xFFFFFFFF, 0xFF000000, 0xFF808080, 0xFF00FF00,
    ).map { val c = it.toInt(); SignaturePalettes.derive(c) }

    @Test
    fun `white text keeps 4,5 to 1 on the brightest point of the stage for every palette and theme`() {
        for (dark in listOf(true, false)) for (p in pairs(dark)) {
            val c = EmptyStage.colors(p.primary, p.secondary, p.spark, dark)
            val name = "%08X dark=%s".format(p.primary, dark)
            assertTrue(name, ColorMath.contrast(white, EmptyStage.brightest(c)) >= EmptyStage.MIN_TEXT)
            assertTrue(name, ColorMath.contrast(white, c.top) >= EmptyStage.MIN_TEXT)
            assertTrue(name, ColorMath.contrast(white, c.bottom) >= EmptyStage.MIN_TEXT)
            assertTrue(c.glowAlpha in 0f..EmptyStage.GLOW_MAX)
            assertEquals(c.glowAlpha * EmptyStage.HALO_RATIO, c.haloAlpha, 1e-6f)
        }
    }

    @Test
    fun `the signature palettes keep a visible glow instead of a flat background`() {
        for (dark in listOf(true, false)) for (preset in SignaturePreset.entries) {
            val p = SignaturePalettes.forTheme(preset.pair, dark)
            val c = EmptyStage.colors(p.primary, p.secondary, p.spark, dark)
            assertTrue("${preset.id} dark=$dark: ${c.glowAlpha}", c.glowAlpha >= 0.2f)
        }
    }

    @Test
    fun `the stage is tinted by the palette and goes from lighter at the top to deeper at the bottom`() {
        for (dark in listOf(true, false)) for (preset in SignaturePreset.entries) {
            val p = SignaturePalettes.forTheme(preset.pair, dark)
            val c = EmptyStage.colors(p.primary, p.secondary, p.spark, dark)
            val hue = ColorMath.toHsl(ColorMath.opaque(p.primary))[0]
            val top = ColorMath.toHsl(c.top)
            val d = abs(hue - top[0]).let { min(it, 360f - it) }
            assertTrue("${preset.id}: tono $hue → ${top[0]}", d < 12f)
            assertTrue(top[1] <= EmptyStage.MAX_SAT + 0.02f)
            assertTrue(ColorMath.luminance(c.top) > ColorMath.luminance(c.bottom))
        }
    }

    @Test
    fun `the day stage is a lighter dusk than the night stage`() {
        val p = SignaturePreset.Plasma.pair
        val night = EmptyStage.colors(p.primary, p.secondary, p.spark, dark = true)
        val day = EmptyStage.colors(p.primary, p.secondary, p.spark, dark = false)
        assertTrue(ColorMath.luminance(day.top) > ColorMath.luminance(night.top))
        assertTrue(ColorMath.luminance(day.bottom) > ColorMath.luminance(night.bottom))
    }

    @Test
    fun `a gray accent gives a neutral stage`() {
        val c = EmptyStage.colors(0xFF808080.toInt(), 0xFF808080.toInt(), white, dark = true)
        assertTrue(ColorMath.toHsl(c.top)[1] < 0.02f)
    }

    @Test
    fun `stage lights fall inside or just past the canvas`() {
        for (s in listOf(EmptyStage.MERIDIAN, EmptyStage.CLASSIC)) {
            assertTrue(s.glowX in 0f..1f && s.glowY in 0f..1f)
            assertTrue(s.haloX in 0f..1f && s.haloY in 0f..1.2f)
            assertTrue(s.glowRadius > 0f && s.haloRadius > 0f)
        }
    }

    /* ── El titular ── */

    @Test
    fun `title fit returns the largest size that fits`() {
        val limit = 47.3f
        val got = TitleFit.largest(22f, 88f) { it <= limit }!!
        assertTrue(got <= limit)
        assertTrue("$got", limit - got < (88f - 22f) / 512f)
    }

    @Test
    fun `title fit takes the maximum when everything fits and null when nothing does`() {
        assertEquals(88f, TitleFit.largest(22f, 88f) { true }!!, 0f)
        assertNull(TitleFit.largest(22f, 88f) { false })
        assertEquals(2, TitleFit.lines(30f))
        assertEquals(3, TitleFit.lines(null))
    }

    @Test
    fun `title fit with an inverted range still answers`() {
        assertEquals(18f, TitleFit.largest(18f, 10f) { true }!!, 0f)
        assertNull(TitleFit.largest(18f, 10f) { false })
    }

    @Test
    fun `accented titles get room between lines so the accent does not touch the line above`() {
        assertEquals(TitleFit.LINE_HEIGHT_ACCENTED, TitleFit.lineHeight("Aún no hay nada aquí"), 0f)
        assertEquals(TitleFit.LINE_HEIGHT_ACCENTED, TitleFit.lineHeight("Añadir juegos o ROMs"), 0f)
        assertEquals(TitleFit.LINE_HEIGHT_ACCENTED, TitleFit.lineHeight("Bibliothèque vide"), 0f)
        assertEquals(TitleFit.LINE_HEIGHT, TitleFit.lineHeight("Nothing here yet"), 0f)
        assertEquals(TitleFit.LINE_HEIGHT, TitleFit.lineHeight("Biblioteca vazia"), 0f)
        assertTrue(TitleFit.LINE_HEIGHT_ACCENTED >= 1f)
    }

    @Test
    fun `the node moves off the top bar on a very wide classic hero`() {
        val c = EmptyStage.CLASSIC
        assertEquals(c.nodeAngle, c.nodeAngleFor(1250f, 1500f), 0f)
        assertEquals(c.nodeAngleWide, c.nodeAngleFor(2560f, 1000f), 0f)
        // Abajo a la derecha (seno > 0): lejos de la barra de arriba.
        assertTrue(kotlin.math.sin(Math.toRadians(c.nodeAngleWide.toDouble())) > 0)
        assertEquals(EmptyStage.MERIDIAN.nodeAngle, EmptyStage.MERIDIAN.nodeAngleFor(2560f, 1000f), 0f)
    }
}
