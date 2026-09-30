package com.elyndra.launcher.ui.masha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MashaFramingTest {

    private val tan = MashaFraming.tanHalfFov(38.0)
    private val eyes = MashaFraming.REST_EYES[1]

    /** Una pantalla con su hueco libre (px): lo que no tapan barra, tarjetas, sugerencias y entrada. */
    private class Screen(val w: Float, val h: Float, val l: Float, val t: Float, val r: Float, val b: Float) {
        val bandH get() = b - t
        val centerX get() = (l + r) / 2f
    }

    // Móvil 1080×2400: tarjetas hasta y=330, sugerencias desde y=1850.
    private val phonePortrait = Screen(1080f, 2400f, 0f, 330f, 1080f, 1850f)
    // El mismo móvil en horizontal: ella en la mitad izquierda, bajo la barra de arriba.
    private val phoneLandscape = Screen(2400f, 1080f, 0f, 150f, 1200f, 1080f)
    // Tableta 1600×2560 en vertical y 2560×1600 en horizontal.
    private val tabletPortrait = Screen(1600f, 2560f, 0f, 300f, 1600f, 2150f)
    private val tabletLandscape = Screen(2560f, 1600f, 0f, 140f, 1280f, 1600f)

    private val all = listOf(phonePortrait, phoneLandscape, tabletPortrait, tabletLandscape)

    private fun solve(s: Screen, shot: MashaShot, eyeY: Float = eyes): FloatArray =
        FloatArray(3).also { MashaFraming.solve(s.w, s.h, s.l, s.t, s.r, s.b, tan, shot, eyeY, it) }

    private fun y(s: Screen, out: FloatArray, worldY: Float) = MashaFraming.screenY(worldY, s.h, tan, out)
    private fun x(s: Screen, out: FloatArray) = MashaFraming.screenX(s.w, s.h, tan, out)

    @Test
    fun entersFullBodyInBothOrientationsAndZoomsToCloseUp() {
        for (landscape in listOf(false, true)) {
            assertEquals(MashaShot.FullBody, MashaFraming.shotFor(landscape, alternate = false))
            assertEquals(MashaShot.CloseUp, MashaFraming.shotFor(landscape, alternate = true))
        }
    }

    @Test
    fun alwaysHorizontallyCenteredInTheFreeArea() {
        for (s in all) for (shot in MashaShot.entries) {
            val out = solve(s, shot)
            assertEquals("$shot en ${s.w}x${s.h}", s.centerX, x(s, out), 0.5f)
        }
    }

    @Test
    fun portraitCentersOnTheScreenWithoutSideOffset() {
        for (s in listOf(phonePortrait, tabletPortrait)) for (shot in MashaShot.entries) {
            assertEquals(0f, solve(s, shot)[2], 1e-6f)
        }
    }

    @Test
    fun fullBodyFitsHeadToFeetInsideTheFreeArea() {
        for (s in all) {
            val out = solve(s, MashaShot.FullBody)
            val headTop = y(s, out, MashaFraming.spanTop(eyes))
            val floor = y(s, out, MashaFraming.FLOOR)
            assertTrue("cabeza $headTop bajo ${s.t}", headTop >= s.t)
            assertTrue("pies $floor sobre ${s.b}", floor <= s.b)
            // Centrado vertical: el mismo aire arriba y abajo.
            assertEquals(headTop - s.t, s.b - floor, 2f)
            // Toda la base del holotanque cabe a lo ancho.
            val ppm = s.h / (out[0] * 2f * tan)
            assertTrue(2f * 0.55f * ppm <= (s.r - s.l) + 1f)
        }
    }

    @Test
    fun closeUpIsHeadNeckAndChestWithTheFaceInTheFreeArea() {
        for (s in all) {
            val out = solve(s, MashaShot.CloseUp)
            val headTop = y(s, out, MashaFraming.spanTop(eyes))
            val eyeY = y(s, out, eyes)
            val chest = y(s, out, MashaFraming.spanBottom(MashaShot.CloseUp, eyes))
            assertEquals(s.t + 0.07f * s.bandH, headTop, 2f)
            assertTrue("ojos $eyeY", eyeY > s.t && eyeY < s.t + s.bandH * 0.45f)
            assertTrue("pecho $chest", chest <= s.b + 1f)
        }
    }

    @Test
    fun portraitCloseUpReachesTheChipsOnAPhone() {
        val s = phonePortrait
        val out = solve(s, MashaShot.CloseUp)
        assertEquals(s.b, y(s, out, MashaFraming.spanBottom(MashaShot.CloseUp, eyes)), 2f)
    }

    @Test
    fun zoomingOutMovesTheCameraAway() {
        for (s in all) {
            val close = solve(s, MashaShot.CloseUp)
            val full = solve(s, MashaShot.FullBody)
            assertTrue(close[0] < full[0])
            for (out in listOf(close, full)) {
                val eyeY = y(s, out, eyes)
                assertTrue("ojos $eyeY fuera del hueco ${s.t}..${s.b}", eyeY > s.t && eyeY < s.b)
                assertTrue(out[0] in MashaFraming.MIN_DISTANCE..MashaFraming.MAX_DISTANCE)
            }
        }
    }

    @Test
    fun framingFollowsTheAnimatedEyes() {
        // Si ella baja 5 cm (una pose), la cámara baja lo mismo y el encuadre no cambia.
        val s = phonePortrait
        val a = solve(s, MashaShot.CloseUp)
        val b = solve(s, MashaShot.CloseUp, eyes - 0.05f)
        assertEquals(a[0], b[0], 1e-5f)
        assertEquals(a[1] - 0.05f, b[1], 1e-5f)
        assertEquals(a[2], b[2], 1e-6f)
    }

    @Test
    fun unmeasuredBandFallsBackToTheWholeView() {
        val out = FloatArray(3)
        MashaFraming.solve(1080f, 2400f, 0f, 0f, 0f, 0f, tan, MashaShot.FullBody, eyes, out)
        assertEquals(0f, out[2], 1e-6f)
        val headTop = MashaFraming.screenY(MashaFraming.spanTop(eyes), 2400f, tan, out)
        val floor = MashaFraming.screenY(MashaFraming.FLOOR, 2400f, tan, out)
        assertTrue(headTop >= 0f && floor <= 2400f)
    }

    @Test
    fun lensFovIsVerticalOn24mmSensor() {
        assertEquals(12f / 38f, tan, 1e-6f)
    }
}
