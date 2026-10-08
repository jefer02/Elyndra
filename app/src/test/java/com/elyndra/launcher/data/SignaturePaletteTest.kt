package com.elyndra.launcher.data

import com.elyndra.launcher.ui.intro.IntroColor
import com.elyndra.launcher.ui.intro.IntroPalettes
import com.elyndra.launcher.ui.selection.SelectionPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignaturePaletteTest {

    private fun assertAtLeast(what: String, ratio: Double, min: Double) =
        assertTrue("$what: ${"%.2f".format(ratio)} < $min", ratio >= min)

    private fun hueDistance(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /* ── Las paletas ── */

    @Test
    fun `the five signature palettes keep their hex values`() {
        val p = SignaturePreset.entries.associate { it.id to it.pair }
        assertEquals(ColorPair(0xFF7C5CFF.toInt(), 0xFF2BD9FF.toInt(), 0xFFFFF1C9.toInt()), p["plasma"])
        assertEquals(ColorPair(0xFFFF8A1F.toInt(), 0xFFFF3D5A.toInt(), 0xFFFFE2B8.toInt()), p["ember"])
        assertEquals(ColorPair(0xFF19E3A5.toInt(), 0xFF3AA8FF.toInt(), 0xFFE6FFF6.toInt()), p["aurora"])
        assertEquals(ColorPair(0xFFFF4FA3.toInt(), 0xFFB15CFF.toInt(), 0xFFFFE6F3.toInt()), p["neon_rose"])
        // Solar es el oro de siempre: el de la intro, el acento "oro" y su intro "gold".
        assertEquals(SignaturePalettes.SOLAR_GOLD, p["solar"]?.primary)
        assertEquals(SignaturePalettes.SOLAR_GOLD, IntroColor.Gold.base(0))
        assertEquals("oro", SignaturePreset.Solar.accentId)
        assertEquals("gold", SignaturePreset.Solar.introId)
    }

    @Test
    fun `every palette points to an existing accent and intro color`() {
        for (preset in SignaturePreset.entries) {
            assertTrue(preset.id, ACCENTS.any { it.id == preset.accentId })
            assertEquals(preset.id, preset.introId, IntroColor.resolve(preset.introId).id)
        }
    }

    @Test
    fun `defaults are plasma without touching what was stored`() {
        assertEquals(SignaturePreset.Plasma, SignaturePreset.DEFAULT)
        assertEquals(SignaturePreset.Plasma.accentId, Palettes.DEFAULT_ACCENT)
        assertEquals(SignaturePreset.Plasma.pair.primary, SettingsStore.DEFAULT_SELECTION_PARTICLE_COLOR)
        assertEquals(IntroColor.Plasma, IntroColor.resolve(null))
        // Lo guardado manda.
        assertEquals("cobalto", Palettes.accentId("cobalto", ACCENTS.map { it.id }))
        assertEquals(IntroColor.Cyan, IntroColor.resolve("cyan"))
        // La estela de Masha no cambia de color de partida.
        assertEquals(0xFF5CF2FF.toInt(), SettingsStore.DEFAULT_PARTICLE_COLOR)
    }

    @Test
    fun `a primary finds its palette and anything else does not`() {
        assertEquals(SignaturePreset.Aurora, SignaturePalettes.presetOf(0xFF19E3A5.toInt()))
        assertEquals(SignaturePreset.Plasma, SignaturePalettes.presetOf(0x007C5CFF))
        assertNull(SignaturePalettes.presetOf(0xFF5CF2FF.toInt()))
        assertEquals(SignaturePreset.Ember.pair, SignaturePalettes.pairFor(0xFFFF8A1F.toInt()))
    }

    /* ── Colores propios ── */

    @Test
    fun `a custom hue gets its secondary 35 degrees ahead and a light spark`() {
        for (hue in listOf(0f, 50f, 120f, 200f, 300f, 340f)) {
            val base = ColorMath.fromHsl(hue, 0.7f, 0.6f)
            val pair = SignaturePalettes.derive(base)
            assertEquals(base, pair.primary)
            val h2 = ColorMath.toHsl(pair.secondary)[0]
            assertEquals("tono $hue", 0f, hueDistance(h2, hue + SignaturePalettes.SECONDARY_HUE_SHIFT), 2f)
            assertTrue("destello claro en $hue", ColorMath.luminance(pair.spark) > 0.6)
        }
        // Lo que no es de firma se completa solo.
        assertEquals(SignaturePalettes.derive(0xFF4D9BFF.toInt()), SignaturePalettes.pairFor(0xFF4D9BFF.toInt()))
    }

    @Test
    fun `the accent pair is the signature one, its own secondary or a single tone`() {
        assertEquals(SignaturePreset.Plasma.pair, SignaturePalettes.accentPair("plasma", 0xFF7958FF.toInt(), 0xFF2BD9FF.toInt()))
        // El oro de siempre se queda de un solo tono ("igual que el acento" no cambia).
        val oro = ACCENTS.first { it.id == "oro" }
        val p = SignaturePalettes.accentPair("oro", oro.a.argb(), oro.c.argb())
        assertEquals(p.primary, p.secondary)
        assertEquals(p.primary, p.spark)
        val indigo = ACCENTS.first { it.id == "indigo" }
        val i = SignaturePalettes.accentPair("indigo", indigo.a.argb(), indigo.c.argb())
        assertEquals(BrandTokens.PRIMARY, i.primary)
        assertEquals(BrandTokens.SECONDARY, i.secondary)
    }

    /* ── Claro y oscuro ── */

    @Test
    fun `light theme variants are deeper and essential pieces reach 3 to 1 on pearl`() {
        val pairs = SignaturePreset.entries.map { it.pair } + listOf(0xFF4D9BFF, 0xFFFFC857, 0xFFF4F7FF).map { SignaturePalettes.derive(it.toInt()) }
        for (pair in pairs) {
            val light = SignaturePalettes.forTheme(pair, dark = false)
            for (c in listOf(light.primary, light.secondary)) {
                for (bg in SignaturePalettes.lightBackgrounds) {
                    assertAtLeast("%08X sobre %08X".format(c, bg), ColorMath.contrast(c, bg), SignaturePalettes.MIN_UI)
                }
            }
            assertTrue(ColorMath.luminance(light.primary) < ColorMath.luminance(pair.primary))
            assertTrue(ColorMath.luminance(light.secondary) < ColorMath.luminance(pair.secondary))
            // En oscuro, la luz tal cual.
            assertEquals(pair, SignaturePalettes.forTheme(pair, dark = true))
        }
    }

    @Test
    fun `palette text reaches 4,5 to 1 in both themes`() {
        for (preset in SignaturePreset.entries) for (dark in listOf(false, true)) {
            val n = if (dark) BrandTokens.DARK else BrandTokens.LIGHT
            val text = SignaturePalettes.text(preset.pair, dark)
            for (bg in listOf(n.paper, n.surface)) {
                assertAtLeast("${preset.id} texto (dark=$dark)", ColorMath.contrast(text, bg), 4.5)
            }
        }
    }

    @Test
    fun `new accents carry white text at their fill end and AA content in both themes`() {
        for (id in listOf("plasma", "ember", "aurora", "neon_rose")) {
            val accent = ACCENTS.first { it.id == id }
            assertAtLeast("$id blanco sobre b", ColorMath.contrast(0xFFFFFFFF.toInt(), accent.b.argb()), 4.5)
            assertEquals(SignaturePreset.entries.first { it.accentId == id }.pair.secondary, accent.c.argb())
        }
    }

    /* ── Intro y selección con el par ── */

    @Test
    fun `the intro uses the pair and keeps rims and particles at 3 to 1`() {
        for (preset in SignaturePreset.entries) for (dark in listOf(false, true)) {
            val p = IntroPalettes.derive(preset.pair, dark)
            for (bg in listOf(p.background, p.fogPeak)) {
                assertAtLeast("${preset.id} filo", ColorMath.contrast(p.rim, bg), IntroPalettes.MIN_CONTRAST)
                assertAtLeast("${preset.id} filo claro", ColorMath.contrast(p.rimLight, bg), IntroPalettes.MIN_CONTRAST)
                assertAtLeast("${preset.id} filo hondo", ColorMath.contrast(p.rimDeep, bg), IntroPalettes.MIN_CONTRAST)
                assertAtLeast("${preset.id} partículas", ColorMath.contrast(p.particle, bg), IntroPalettes.MIN_CONTRAST)
            }
        }
        // Primario → filo; secundario → resplandor; destello → barrido.
        val plasma = IntroPalettes.derive(SignaturePreset.Plasma.pair, dark = true)
        val mono = IntroPalettes.derive(SignaturePreset.Plasma.pair.primary, dark = true)
        assertEquals(mono.rim, plasma.rim)
        assertEquals(mono.particle, plasma.particle)
        assertNotEquals(mono.glow, plasma.glow)
        assertTrue(hueDistance(ColorMath.toHsl(plasma.glow)[0], ColorMath.toHsl(0xFF2BD9FF.toInt())[0]) < 6f)
        assertEquals(ColorMath.mix(0xFFFFF1C9.toInt(), 0xFFFFFFFF.toInt(), 0.25f), plasma.sheen)
        val lightPlasma = IntroPalettes.derive(SignaturePreset.Plasma.pair, dark = false)
        assertEquals(0xFFFFF1C9.toInt(), lightPlasma.sheen)
    }

    @Test
    fun `single tone intro colors look exactly as before`() {
        for (color in listOf(IntroColor.Gold, IntroColor.Cyan, IntroColor.Violet, IntroColor.Crimson, IntroColor.Emerald)) {
            for (dark in listOf(false, true)) {
                assertEquals(IntroPalettes.derive(color.base(0), dark), IntroPalettes.derive(color.pair(IntroPalettes.mono(0)), dark))
            }
        }
        // "Igual que el acento" toma el par del acento activo.
        val accent = ColorPair(0xFF4E56D8.toInt(), 0xFF5BC3DC.toInt(), 0xFFEFF6FF.toInt())
        assertEquals(accent, IntroColor.Accent.pair(accent))
        assertEquals(SignaturePreset.Ember.pair, IntroColor.Ember.pair(accent))
    }

    @Test
    fun `the selection halo of a signature color mixes both tones and stays legible`() {
        for (preset in SignaturePreset.entries) for (dark in listOf(false, true)) {
            val p = SelectionPalettes.derive(preset.pair.primary, dark)
            for (shelf in SelectionPalettes.shelves(dark)) {
                assertAtLeast("${preset.id} filo", ColorMath.contrast(p.rim, shelf), SelectionPalettes.MIN_CONTRAST)
                assertAtLeast("${preset.id} filo claro", ColorMath.contrast(p.rimLight, shelf), SelectionPalettes.MIN_CONTRAST)
                assertAtLeast("${preset.id} halo", ColorMath.contrast(p.bloom, shelf), SelectionPalettes.MIN_CONTRAST)
                p.particles.forEach { assertAtLeast("${preset.id} partícula", ColorMath.contrast(it, shelf), SelectionPalettes.MIN_CONTRAST) }
            }
            // Media partícula de cada tono (Solar tiene los dos casi iguales).
            if (preset != SignaturePreset.Solar) {
                val h1 = ColorMath.toHsl(p.particles[0])[0]
                val h2 = ColorMath.toHsl(p.particles[1])[0]
                assertTrue("${preset.id}: $h1 / $h2", hueDistance(h1, h2) > 20f)
            }
        }
    }
}
