package com.elyndra.launcher.ui.intro

import com.elyndra.launcher.data.ACCENTS
import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.data.argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroPaletteTest {

    /** Los colores fijos y cualquier acento (lo que puede dar "igual que el acento"). */
    private val bases = IntroColor.entries.filter { it != IntroColor.Accent }.map { it.base(0) } + ACCENTS.map { it.a.argb() }

    private fun assertAtLeast(what: String, ratio: Double, min: Double) =
        assertTrue("$what: ${"%.2f".format(ratio)} < $min", ratio >= min)

    @Test
    fun rimsParticlesAndSubtitleKeepThreeToOneInBothThemes() {
        for (dark in listOf(false, true)) {
            for (base in bases) {
                val p = IntroPalettes.derive(base, dark)
                val name = "%08X %s".format(base, if (dark) "oscuro" else "claro")
                for (bg in listOf(p.background, p.fogPeak)) {
                    assertAtLeast("$name filo", ColorMath.contrast(p.rim, bg), IntroPalettes.MIN_CONTRAST)
                    assertAtLeast("$name filo claro", ColorMath.contrast(p.rimLight, bg), IntroPalettes.MIN_CONTRAST)
                    assertAtLeast("$name filo hondo", ColorMath.contrast(p.rimDeep, bg), IntroPalettes.MIN_CONTRAST)
                    assertAtLeast("$name partículas", ColorMath.contrast(p.particle, bg), IntroPalettes.MIN_CONTRAST)
                    assertAtLeast("$name subtítulo", ColorMath.contrast(p.subtitle, bg), IntroPalettes.MIN_CONTRAST)
                }
            }
        }
    }

    @Test
    fun backgroundsMatchTheSystemSplashAndNeverFlash() {
        for (base in bases) {
            assertEquals(BrandTokens.INTRO_PEARL, IntroPalettes.derive(base, dark = false).background)
            assertEquals(BrandTokens.INTRO_SMOKE, IntroPalettes.derive(base, dark = true).background)
        }
        // Perla: claro de verdad, no gris. Ámbar ahumado: casi negro.
        assertTrue(ColorMath.luminance(BrandTokens.INTRO_PEARL) > 0.85)
        assertTrue(ColorMath.luminance(BrandTokens.INTRO_SMOKE) < 0.01)
        val (h) = ColorMath.toHsl(BrandTokens.INTRO_PEARL)
        assertTrue("perla cálido, tono $h", h in 20f..60f)
    }

    @Test
    fun lightVariantDarkensAndSaturatesTheBase() {
        for (color in IntroColor.entries.filter { it != IntroColor.Accent }) {
            val base = color.base(0)
            val p = IntroPalettes.derive(base, dark = false)
            assertTrue("${color.id} más oscuro", ColorMath.luminance(p.rim) < ColorMath.luminance(base))
            val b = ColorMath.toHsl(base)
            val r = ColorMath.toHsl(p.rim)
            assertTrue("${color.id} saturación ${r[1]} < ${b[1]}", r[1] >= b[1] - 0.02f)
            assertTrue("${color.id} mismo tono", hueDistance(r[0], b[0]) < 6f)
            assertTrue("${color.id} cuerpo oscuro", ColorMath.luminance(p.bodyTop) < 0.06)
        }
    }

    @Test
    fun darkVariantKeepsTheBaseColorAsTheGlow() {
        val gold = IntroColor.Gold.base(0)
        val p = IntroPalettes.derive(gold, dark = true)
        assertEquals(gold, p.glow)
        assertTrue(ColorMath.luminance(p.bodyBottom) < 0.01)
    }

    @Test
    fun matchAccentUsesTheAccentAndUnknownIdsFallBackToGold() {
        val accent = 0xFF4E56D8.toInt()
        assertEquals(accent, IntroColor.Accent.base(accent))
        assertEquals(IntroColor.Gold, IntroColor.byId(null))
        assertEquals(IntroColor.Gold, IntroColor.byId("nope"))
        assertEquals(IntroColor.Emerald, IntroColor.byId("emerald"))
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}
