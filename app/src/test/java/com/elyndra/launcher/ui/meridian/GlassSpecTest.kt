package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.data.Palettes
import com.elyndra.launcher.ui.theme.GlassTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El cristal que toca en cada dispositivo ([GlassPanelLayout.resolve]): el mismo aspecto en cualquier móvil con el mismo Android. */
class GlassSpecTest {

    private val eps = 1e-3f

    /** Tecno Spark 30C (1600 × 720 a ~1,75, Android 14) y Legion Y700 Gen 3 (2560 × 1600 a 2, Android 15). */
    private val tecno = 914f
    private val legion = 1280f

    @Test
    fun `android 12 and later always blur, whatever the RAM or the device`() {
        for (sdk in 31..35) for (w in listOf(tecno, legion)) for (dark in listOf(false, true)) {
            val s = GlassPanelLayout.resolve(w, sdk, glass = true, dark = dark, userBlur = Palettes.DEFAULT_BLUR)
            assertEquals("$sdk $w", GlassPanelLayout.Path.Blur, s.path)
            assertEquals(GlassPanelLayout.blurDp(w), s.blurDp, eps)
            assertEquals(GlassTokens.TINT_ALPHA, s.tint, 0f)
            assertEquals(if (dark) GlassTokens.FROST_ALPHA_DARK else GlassTokens.FROST_ALPHA_LIGHT, s.frost, 0f)
            assertEquals(GlassTokens.RIM_ALPHA, s.rim, 0f)
        }
        // Los dos móviles de las pruebas: el mismo cristal (el desenfoque crece con el ancho, nada más).
        val a = GlassPanelLayout.resolve(tecno, 34, true, false, Palettes.DEFAULT_BLUR)
        val b = GlassPanelLayout.resolve(legion, 35, true, false, Palettes.DEFAULT_BLUR)
        assertEquals(a.copy(blurDp = 0f, glow = 0f, reason = ""), b.copy(blurDp = 0f, glow = 0f, reason = ""))
    }

    @Test
    fun `android 8 to 11 get the clear glass, never an opaque one`() {
        for (sdk in 26..30) {
            val s = GlassPanelLayout.resolve(tecno, sdk, glass = true, dark = false, userBlur = Palettes.DEFAULT_BLUR)
            assertEquals(GlassPanelLayout.Path.Clear, s.path)
            assertEquals(0f, s.blurDp, 0f)
            // Más tinte y velo que con desenfoque, para que se lea como cristal; aun así, lejos de tapar el arte.
            assertTrue(s.tint > GlassTokens.TINT_ALPHA && s.tint + s.frost < 0.4f)
        }
    }

    @Test
    fun `the blur setting scales the glass, at 0 it stops blurring, and the wash is only what Settings picked`() {
        assertEquals(GlassPanelLayout.Path.Clear, GlassPanelLayout.resolve(tecno, 34, true, false, 0).path)
        assertEquals(GlassPanelLayout.blurDp(tecno) * 2f, GlassPanelLayout.resolve(tecno, 34, true, false, 40).blurDp, eps)
        assertEquals(GlassPanelLayout.Path.Wash, GlassPanelLayout.resolve(tecno, 34, false, true, Palettes.DEFAULT_BLUR).path)
    }

    @Test
    fun `the new glass sits between the first version and the too-transparent one`() {
        // Primera: desenfoque 20 a 900 dp, tinte 0,06, filo 0,16. Demasiado transparente: 14, 0,03, 0,24, sin velo.
        assertTrue(GlassTokens.BLUR_DP in 14f..20f)
        assertTrue(GlassTokens.TINT_ALPHA + GlassTokens.FROST_ALPHA_LIGHT > 0.06f)
        assertTrue(GlassTokens.RIM_ALPHA > 0.24f)
    }
}
