package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class ArtWashTest {

    private val n = ArtWash.SAMPLE

    private val yellow = 0xFFFFE600.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFE60012.toInt()
    private val blue = 0xFF1E5BFF.toInt()
    private val navy = 0xFF0B1A3A.toInt()
    private val gray = 0xFF808080.toInt()
    private val plasma = 0xFF7C5CFF.toInt()

    private val representative = listOf(yellow, white, black, red, blue, navy, gray)

    private fun solid(c: Int) = IntArray(n * n) { c }

    /** El arte [c] con manchas blancas y negras en la franja de la rueda (los peores casos para el texto). */
    private fun spotted(c: Int) = IntArray(n * n) { i ->
        val x = i % n
        val y = i / n
        when {
            x < 3 && y < 3 -> white
            x < 3 && y > n - 4 -> black
            else -> c
        }
    }

    private fun hue(c: Int) = ColorMath.toHsl(c)[0]

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return min(d, 360f - d)
    }

    @Test
    fun `the dominant colour is the art's own hue, not a muddy average`() {
        val switch = ArtWash.extract(solid(red), n, n)!!
        assertNotNull(switch.dominant)
        assertTrue(hueDistance(hue(switch.dominant!!), hue(red)) < 12f)
        // Mitad roja, mitad azul y un poco de gris: gana un tono de verdad y el otro es el secundario.
        val px = IntArray(n * n) { i -> if (i % n < n * 0.6f) red else if (i % n < n * 0.9f) blue else gray }
        val two = ArtWash.extract(px, n, n)!!
        assertTrue(hueDistance(hue(two.dominant!!), hue(red)) < 12f)
        assertTrue(hueDistance(hue(two.secondary!!), hue(blue)) < 15f)
        // Gris: sin dominante (el fondo usa la paleta de firma).
        val g = ArtWash.extract(solid(gray), n, n)!!
        assertNull(g.dominant)
        assertNull(ArtWash.extract(IntArray(n * n), n, n))
        assertNull(ArtWash.extract(IntArray(3), n, n))
    }

    @Test
    fun `edge samples, worst left pixels and the top right come from their own regions`() {
        val px = IntArray(n * n) { i ->
            val x = i % n
            val y = i / n
            when {
                x < 4 -> if (y < n / 2) red else blue
                y < 3 && x in 10..16 -> white
                else -> navy
            }
        }
        val c = ArtWash.extract(px, n, n)!!
        assertEquals(ArtWash.EDGE_SAMPLES, c.edges.size)
        assertTrue(hueDistance(hue(c.edges.first()), hue(red)) < 30f)
        assertTrue(hueDistance(hue(c.edges.last()), hue(blue)) < 30f)
        assertEquals(white, c.topLight)
        val spots = ArtWash.extract(spotted(navy), n, n)!!
        assertEquals(white, spots.leftLight)
        assertEquals(black, spots.leftDark)
    }

    @Test
    fun `the wash tone is deep ink in dark and a tinted pastel in light, never near white`() {
        for (c in representative + listOf(plasma, 0xFF19E3A5.toInt(), 0xFFF4E6C8.toInt())) {
            val (_, sd, ld) = ColorMath.toHsl(ArtWash.tone(c, dark = true, hueFrom = plasma))
            assertTrue("oscuro %08X: l=$ld".format(c), ld in 0.075f..0.165f)
            assertTrue("oscuro %08X: s=$sd".format(c), sd in 0.19f..0.63f)
            val (_, sl, ll) = ColorMath.toHsl(ArtWash.tone(c, dark = false, hueFrom = plasma))
            assertTrue("claro %08X: l=$ll".format(c), ll in 0.795f..0.885f)
            assertTrue("claro %08X: s=$sl".format(c), sl in 0.29f..0.46f)
        }
        // El tono sigue al arte.
        assertTrue(hueDistance(hue(ArtWash.tone(red, dark = true)), hue(red)) < 6f)
        assertTrue(hueDistance(hue(ArtWash.tone(blue, dark = false)), hue(blue)) < 6f)
    }

    @Test
    fun `row titles keep 4,5 to 1 and dial ticks 3 to 1 over representative art in both themes`() {
        for (dark in listOf(false, true)) for (c in representative) for (px in listOf(solid(c), spotted(c))) {
            val colors = ArtWash.extract(px, n, n)
            val plan = ArtWash.plan(colors, dark, plasma)
            val name = "%08X dark=%s adaptive=%s".format(c, dark, plan.adaptive)
            val worst = if (dark) colors?.leftLight ?: white else colors?.leftDark ?: black
            for (tone in plan.edges.toList() + plan.tone) {
                val bg = ArtWash.behindText(plan, worst, tone)
                assertTrue("vecina $name", ArtWash.textContrast(bg, dark, ArtWash.NEIGHBOR_TEXT_ALPHA) >= 4.5)
                assertTrue("enfocada $name", ArtWash.textContrast(MeridianScrims.plated(bg, dark, tone), dark) >= 4.5)
                assertTrue("marcas $name", ArtWash.tickContrast(bg, dark) >= 3.0)
            }
            assertTrue(plan.coverage in ArtWash.MIN_COVERAGE..ArtWash.MAX_COVERAGE)
        }
    }

    @Test
    fun `colourful art gets an adaptive wash and the neutral scrim stays for the toggle`() {
        for (dark in listOf(false, true)) {
            val plan = ArtWash.plan(ArtWash.extract(solid(red), n, n), dark, plasma)
            assertTrue(plan.adaptive)
            assertTrue(hueDistance(hue(plan.tone), hue(red)) < 12f)
            val off = ArtWash.plan(ArtWash.extract(solid(red), n, n), dark, plasma, enabled = false)
            assertFalse(off.adaptive)
            assertEquals(MeridianScrims.tint(dark), off.tone)
            assertEquals(1f, off.coverage, 0f)
        }
    }

    @Test
    fun `no art or grey art falls back to the signature palette`() {
        for (dark in listOf(false, true)) {
            val none = ArtWash.plan(null, dark, plasma)
            assertTrue(none.adaptive)
            assertTrue(hueDistance(hue(none.tone), hue(plasma)) < 6f)
            val grey = ArtWash.plan(ArtWash.extract(solid(gray), n, n), dark, plasma)
            assertTrue(hueDistance(hue(grey.tone), hue(plasma)) < 6f)
        }
    }

    @Test
    fun `a tint that cannot reach the contrast is reported so the plan falls back to the neutral scrim`() {
        // Gris medio con el texto claro del tema oscuro: ni cubriendo del todo llega.
        assertTrue(ArtWash.coverageFor(gray, white, dark = true) > 1f)
        // La tinta del tema sí, cubriendo lo justo.
        assertTrue(ArtWash.coverageFor(MeridianScrims.tint(true), white, dark = true) <= 1f)
        assertTrue(ArtWash.coverageFor(MeridianScrims.tint(false), black, dark = false) <= 1f)
    }

    @Test
    fun `white bar text keeps 4,5 to 1 under the top scrim over very bright art`() {
        for (c in representative) {
            val colors = ArtWash.extract(spotted(c), n, n)!!
            val plan = ArtWash.plan(colors, dark = false, fallback = plasma)
            // Sobre el píxel más claro de la zona de la barra (en el arte blanco, blanco puro).
            val bg = ColorMath.over(ColorMath.withAlpha(plan.top, plan.topAlpha), colors.topLight)
            val path = ColorMath.over(ColorMath.withAlpha(white, ArtWash.TOP_TEXT_ALPHA), bg)
            assertTrue("%08X".format(c), ColorMath.contrast(path, bg) >= 4.5)
            assertTrue(plan.topAlpha in ArtWash.TOP_MIN_ALPHA..ArtWash.TOP_MAX_ALPHA)
            assertTrue(ColorMath.toHsl(plan.top)[2] in 0.09f..0.11f)
        }
        // Sin saber nada del arte, se cuenta con lo peor (blanco).
        val blind = ArtWash.plan(null, dark = true, fallback = plasma)
        val bg = ColorMath.over(ColorMath.withAlpha(blind.top, blind.topAlpha), white)
        assertTrue(ColorMath.contrast(white, bg) >= 4.5)
    }

    @Test
    fun `light tiles get a stronger hairline and contact shadow when their edge matches the wash`() {
        val pastel = ColorMath.luminance(ArtWash.tone(yellow, dark = false))
        // Un icono blanco sobre el pastel: casi al máximo.
        assertTrue(ArtWash.tileEdgeStrength(ColorMath.luminance(white), pastel) > 0.85f)
        // Un icono oscuro: la base.
        assertEquals(ArtWash.TILE_EDGE_BASE, ArtWash.tileEdgeStrength(ColorMath.luminance(navy), pastel), 1e-4f)
        assertEquals(1f, ArtWash.tileEdgeStrength(pastel, pastel), 1e-4f)
        assertEquals(0.6f, ArtWash.tileEdgeStrength(null, pastel), 0f)
        // Cuanto más se parecen, más fuerte.
        var last = 2f
        for (l in listOf(pastel, pastel * 0.8, pastel * 0.6, pastel * 0.4, pastel * 0.2, 0.01)) {
            val k = ArtWash.tileEdgeStrength(l, pastel)
            assertTrue(k <= last + 1e-6f)
            last = k
        }
    }

    @Test
    fun `the edge luminance reads only the opaque ring of the tile`() {
        val s = 16
        val px = IntArray(s * s) { i ->
            val x = i % s
            val y = i / s
            if (x < 2 || y < 2 || x >= s - 2 || y >= s - 2) white else black
        }
        assertEquals(1.0, ArtWash.edgeLuminance(px, s, s)!!, 1e-6)
        assertNull(ArtWash.edgeLuminance(IntArray(s * s), s, s))
    }

    @Test
    fun `the adaptive background colour is on by default`() {
        assertTrue(SettingsStore.DEFAULT_MERIDIAN_ADAPTIVE_COLOR)
    }
}
