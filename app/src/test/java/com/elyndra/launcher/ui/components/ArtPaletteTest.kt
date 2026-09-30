package com.elyndra.launcher.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtPaletteTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun hueOf(argb: Int): Float {
        val hsl = FloatArray(3)
        ArtPalette.rgbToHsl((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, hsl)
        return hsl[0]
    }

    private fun lightnessOf(argb: Int): Float {
        val hsl = FloatArray(3)
        ArtPalette.rgbToHsl((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, hsl)
        return hsl[2]
    }

    @Test
    fun emptyOrTransparentHasNoAccent() {
        assertNull(ArtPalette.accentOf(IntArray(0)))
        assertNull(ArtPalette.accentOf(IntArray(64) { 0x00FF0000 }))
    }

    @Test
    fun greyArtFallsBackToTheme() {
        val grey = IntArray(256) { i -> val v = (i * 7) % 256; rgb(v, v, v) }
        assertNull(ArtPalette.accentOf(grey))
    }

    @Test
    fun picksTheDominantVividHueNotTheAverage() {
        // 60 % azul vivo, 25 % rojo vivo, 15 % gris: la media sería un morado apagado.
        val px = IntArray(100) { i ->
            when {
                i < 60 -> rgb(30, 90, 230)
                i < 85 -> rgb(220, 40, 40)
                else -> rgb(128, 128, 128)
            }
        }
        val accent = ArtPalette.accentOf(px)
        assertNotNull(accent)
        assertEquals(222f, hueOf(accent!!), 12f)
    }

    @Test
    fun accentIsKeptReadable() {
        // Un arte casi negro con algo de verde oscuro: el acento sube a media luz.
        val px = IntArray(100) { i -> if (i < 40) rgb(10, 60, 20) else rgb(5, 5, 5) }
        val accent = ArtPalette.accentOf(px)!!
        val l = lightnessOf(accent)
        assertTrue("luminosidad $l", l in 0.44f..0.63f)
    }

    @Test
    fun deeperKeepsTheToneDarker() {
        val a = rgb(80, 140, 250)
        val d = ArtPalette.deeper(a)
        assertTrue(lightnessOf(d) < lightnessOf(a))
    }

    @Test
    fun hslRoundTrip() {
        val c = rgb(200, 120, 40)
        val hsl = FloatArray(3)
        ArtPalette.rgbToHsl(200, 120, 40, hsl)
        val back = ArtPalette.hslToArgb(hsl[0], hsl[1], hsl[2])
        assertEquals((c shr 16) and 0xFF, (back shr 16) and 0xFF, 2)
        assertEquals((c shr 8) and 0xFF, (back shr 8) and 0xFF, 2)
        assertEquals(c and 0xFF, back and 0xFF, 2)
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) =
        assertTrue("esperado $expected ± $delta, fue $actual", kotlin.math.abs(expected - actual) <= delta)
}
