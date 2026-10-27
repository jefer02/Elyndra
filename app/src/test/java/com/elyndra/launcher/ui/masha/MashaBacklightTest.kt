package com.elyndra.launcher.ui.masha

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.data.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class MashaBacklightTest {

    private val tan = MashaFraming.tanHalfFov(MashaFraming.FOCAL_LENGTH_MM)
    private val eyes = MashaFraming.REST_EYES[1]
    private val eps = 0.5f

    /** Una ventana (dp) con el hueco libre para ella, como lo mide MashaScreen. */
    private class Screen(val name: String, val w: Float, val h: Float, val l: Float, val t: Float, val r: Float, val b: Float, val landscape: Boolean)

    /** En horizontal, la columna de la izquierda bajo la barra (54 dp); en vertical, entre las tarjetas y las sugerencias. */
    private fun landscape(w: Float, h: Float) = Screen("${w.toInt()}×${h.toInt()}", w, h, 0f, 54f, w / 2f, h, landscape = true)
    private fun portrait(w: Float, h: Float) = Screen("${w.toInt()}×${h.toInt()}", w, h, 0f, 118f, w, h - 150f, landscape = false)

    private val screens = listOf(
        landscape(892f, 411f), landscape(730f, 360f), landscape(1280f, 800f),
        portrait(411f, 892f), portrait(360f, 730f), portrait(800f, 1280f),
    )

    private fun halo(s: Screen, shot: MashaShot) =
        checkNotNull(MashaBacklight.place(s.w, s.h, s.l, s.t, s.r, s.b, shot, s.landscape)) { "${s.name} $shot sin halo" }

    private fun framing(s: Screen, shot: MashaShot) =
        FloatArray(3).also { MashaFraming.solve(s.w, s.h, s.l, s.t, s.r, s.b, tan, shot, eyes, it, s.landscape) }

    @Test
    fun `the halo sits on her, centered in her column, between her head and her feet`() {
        for (s in screens) for (shot in MashaShot.entries) {
            val h = halo(s, shot)
            val out = framing(s, shot)
            assertEquals("${s.name} $shot", MashaFraming.screenX(s.w, s.h, tan, out), h.cx, eps)
            assertEquals("${s.name} $shot centrado en el hueco", (s.l + s.r) / 2f, h.cx, eps)
            val headTop = MashaFraming.screenY(MashaFraming.spanTop(eyes), s.h, tan, out)
            val bottom = MashaFraming.screenY(MashaFraming.spanBottom(shot, eyes, s.landscape), s.h, tan, out)
            assertTrue("${s.name} $shot: ${h.cy} fuera de $headTop..$bottom", h.cy > headTop && h.cy < bottom)
        }
    }

    @Test
    fun `in landscape the halo never spills onto the conversation`() {
        for (s in screens.filter { it.landscape }) for (shot in MashaShot.entries) {
            val h = halo(s, shot)
            assertTrue("${s.name} $shot: ${h.cx + h.rx} > ${s.r}", h.cx + h.rx <= s.r + eps)
            assertTrue("${s.name} $shot", h.cx - h.rx >= s.l - eps)
        }
    }

    @Test
    fun `the ring leaves her torso almost clear so she keeps her own contrast`() {
        for (s in screens) {
            val h = halo(s, MashaShot.FullBody)
            val out = framing(s, MashaShot.FullBody)
            val ppm = s.h / (out[0] * 2f * tan)
            val clear = h.inner * h.rx
            // El hueco limpio cubre los hombros (salvo que el tope del anillo lo impida).
            val shoulders = MashaBacklight.SHOULDER_HALF_WIDTH * ppm
            assertTrue("${s.name}: $clear < $shoulders", clear >= min(shoulders, MashaBacklight.MAX_INNER * h.rx) - eps)
            assertTrue(h.inner in MashaBacklight.MIN_INNER..MashaBacklight.MAX_INNER)
            // Una elipse a lo alto, como su silueta.
            assertTrue(h.ry >= h.rx)
        }
    }

    @Test
    fun `the halo is a soft low-saturation version of the mood color`() {
        for (mood in MashaMood.entries) {
            val glow = Color(mood.glow)
            val tint = MashaBacklight.tint(glow)
            assertTrue("$mood", saturation(tint) < saturation(glow) + 1e-4f)
            // Aun en su pico, el fondo que enciende sigue siendo fondo: el texto de la interfaz encima se lee igual (≥ 4,5:1).
            val lit = ColorMath.over(ColorMath.withAlpha(tint.toArgb(), MashaBacklight.peak(1f, lite = false, reduced = false)), BrandTokens.HOLO_BG)
            assertTrue("$mood", ColorMath.contrast(BrandTokens.HOLO_TEXT, lit) >= 4.5)
            assertTrue("$mood", ColorMath.contrast(BrandTokens.HOLO_DIM, lit) >= 4.5)
            // Y aclara de verdad el fondo de detrás de ella (si no, no la recorta).
            assertTrue("$mood", ColorMath.luminance(lit) > ColorMath.luminance(BrandTokens.HOLO_BG) * 1.5)
        }
    }

    @Test
    fun `lite and reduced motion get a fixed halo`() {
        for (energy in listOf(0f, 0.5f, 1f)) {
            assertEquals(MashaBacklight.PEAK_ALPHA, MashaBacklight.peak(energy, lite = true, reduced = false), 0f)
            assertEquals(MashaBacklight.PEAK_ALPHA, MashaBacklight.peak(energy, lite = false, reduced = true), 0f)
        }
        assertTrue(MashaBacklight.peak(1f, lite = false, reduced = false) > MashaBacklight.peak(0f, lite = false, reduced = false))
        assertTrue(MashaBacklight.peak(1f, lite = false, reduced = false) <= MashaBacklight.PEAK_ALPHA * 1.15f + 1e-6f)
    }

    @Test
    fun `without measures there is no halo`() {
        assertEquals(null, MashaBacklight.place(0f, 0f, 0f, 0f, 0f, 0f, MashaShot.FullBody, landscape = true))
    }

    private fun saturation(c: Color): Float {
        val hi = max(c.red, max(c.green, c.blue))
        val lo = min(c.red, min(c.green, c.blue))
        return if (hi <= 0f) 0f else (hi - lo) / hi
    }
}
