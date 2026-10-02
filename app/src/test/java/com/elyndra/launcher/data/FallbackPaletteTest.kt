package com.elyndra.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPaletteTest {

    private val white = 0xFFFFFFFF.toInt()

    private fun hsl(c: Int) = ColorMath.toHsl(c)

    private fun assertReadable(t: FallbackPalette.Tones, what: String) {
        val onBase = ColorMath.contrast(white, t.base)
        assertTrue("$what: blanco sobre base ${"%.2f".format(onBase)}", onBase >= 4.5)
        val onDeep = ColorMath.contrast(white, t.deep)
        assertTrue("$what: blanco sobre profundo ${"%.2f".format(onDeep)}", onDeep >= 7.0)
    }

    private fun assertOrdered(t: FallbackPalette.Tones, what: String) {
        assertTrue("$what: el profundo es más oscuro", ColorMath.luminance(t.deep) < ColorMath.luminance(t.base))
        assertTrue("$what: el claro es más claro", ColorMath.luminance(t.light) > ColorMath.luminance(t.base))
    }

    @Test
    fun vividColorKeepsItsHueAndGetsDarkAndLightTones() {
        val red = 0xFFE53935.toInt()
        val t = FallbackPalette.of(red, "Some Game")
        assertEquals(hsl(red)[0], hsl(t.base)[0], 4f)
        assertOrdered(t, "rojo")
        assertReadable(t, "rojo")
        // El claro es análogo: el tono se desplaza, no es el mismo color aclarado.
        val shift = ((hsl(t.light)[0] - hsl(t.base)[0] + 540f) % 360f) - 180f
        assertTrue("giro análogo $shift", shift in -30f..-15f)
    }

    @Test
    fun saturationAndLightnessAreClamped() {
        // Neón puro: se baja a una saturación y luminosidad medias.
        val neon = FallbackPalette.of(0xFF00FF66.toInt(), "x")
        assertTrue(hsl(neon.base)[1] <= FallbackPalette.MAX_SATURATION + 0.02f)
        assertTrue(hsl(neon.base)[2] <= FallbackPalette.MAX_LIGHTNESS + 0.01f)
        assertReadable(neon, "neón")

        // Pastel muy claro: no da una carátula blanquecina.
        val pastel = FallbackPalette.of(0xFFFFD6E8.toInt(), "x")
        assertTrue(hsl(pastel.base)[2] <= FallbackPalette.MAX_LIGHTNESS + 0.01f)
        assertTrue(hsl(pastel.base)[1] >= FallbackPalette.MIN_SATURATION - 0.02f)
        assertReadable(pastel, "pastel")

        // Casi negro con algo de color: no da una carátula negra.
        val dark = FallbackPalette.of(0xFF1A0A30.toInt(), "x")
        assertTrue(hsl(dark.base)[2] >= FallbackPalette.MIN_LIGHTNESS - 0.01f)
        assertOrdered(dark, "oscuro")
    }

    @Test
    fun grayWhiteAndBlackBecomeCoolGraphite() {
        val brandHue = hsl(BrandTokens.PRIMARY)[0]
        for (c in listOf(0xFF808080.toInt(), white, 0xFF000000.toInt(), 0xFF7A7D80.toInt())) {
            val t = FallbackPalette.of(c, "seed")
            val h = hsl(t.base)
            assertEquals("tono de marca para ${Integer.toHexString(c)}", brandHue, h[0], 4f)
            assertEquals(FallbackPalette.NEUTRAL_SATURATION, h[1], 0.03f)
            assertEquals(FallbackPalette.NEUTRAL_LIGHTNESS, h[2], 0.02f)
            assertOrdered(t, "gris")
            assertReadable(t, "gris")
        }
        // Blanco y negro acaban en el mismo grafito.
        assertEquals(FallbackPalette.of(white, "a"), FallbackPalette.of(0xFF000000.toInt(), "b"))
    }

    @Test
    fun withoutIconUsesBrandPrimaryTurnedBySeed() {
        val brandHue = hsl(BrandTokens.PRIMARY)[0]
        val a = FallbackPalette.of(null, "Chrono Trigger")
        val b = FallbackPalette.of(null, "Metroid Prime")
        for (t in listOf(a, b)) {
            val d = ((hsl(t.base)[0] - brandHue + 540f) % 360f) - 180f
            assertTrue("dentro del abanico: $d", abs(d) <= FallbackPalette.SEED_SPREAD + 2f)
            assertReadable(t, "marca")
            assertOrdered(t, "marca")
        }
        assertNotEquals(a, b)
        // Estable: el mismo juego siempre da lo mismo.
        assertEquals(a, FallbackPalette.of(null, "Chrono Trigger"))
        assertEquals(0f, FallbackPalette.seedShift(""), 0f)
    }

    @Test
    fun extractedColorIsNotTurnedBySeed() {
        val c = 0xFF2E7D32.toInt()
        assertEquals(FallbackPalette.of(c, "uno"), FallbackPalette.of(c, "otro distinto"))
    }

    @Test
    fun unitIsStableAndInRange() {
        for (slot in 0 until 6) {
            val u = FallbackPalette.unit("Zelda", slot)
            assertTrue(u in 0f..1f)
            assertEquals(u, FallbackPalette.unit("Zelda", slot), 0f)
        }
    }

    @Test
    fun dominantPrefersVividHueThenGrayThenNothing() {
        val blue = 0xFF1E5BD8.toInt()
        val mostlyGray = IntArray(100) { if (it < 30) blue else 0xFF9A9A9A.toInt() }
        val d = FallbackPalette.dominant(mostlyGray)!!
        assertEquals(hsl(blue)[0], hsl(d)[0], 4f)

        val gray = FallbackPalette.dominant(IntArray(64) { 0xFF606060.toInt() })!!
        assertTrue(hsl(gray)[1] < FallbackPalette.GRAY_SATURATION)

        assertNull(FallbackPalette.dominant(IntArray(64) { 0x00FF0000 }))
        assertNull(FallbackPalette.dominant(IntArray(0)))
    }

    @Test
    fun blurSpreadsAndKeepsFlatAreas() {
        val w = 16
        val h = 16
        // Plano: el desenfoque no lo cambia.
        val flat = IntArray(w * h) { 0xFF336699.toInt() }
        assertTrue(PixelBlur.blur(flat.copyOf(), w, h, 3).all { it == 0xFF336699.toInt() })
        // Un punto blanco sobre negro se reparte: el centro baja y los vecinos suben.
        val dot = IntArray(w * h) { 0xFF000000.toInt() }
        dot[8 * w + 8] = white
        val out = PixelBlur.blur(dot, w, h, 2, passes = 1)
        assertTrue(ColorMath.red(out[8 * w + 8]) in 1..254)
        assertTrue(ColorMath.red(out[8 * w + 9]) > 0)
        assertEquals(0, ColorMath.red(out[0]))
    }

    private fun abs(f: Float) = kotlin.math.abs(f)
}
