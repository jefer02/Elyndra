package com.elyndra.launcher.ui.selection

import com.elyndra.launcher.data.ACCENTS
import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.data.argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SelectionFxTest {

    /* ── Valores de partida ── */

    @Test
    fun glowAndParticlesAreOnByDefault() {
        assertTrue(SettingsStore.DEFAULT_SELECTION_GLOW)
        assertTrue(SettingsStore.DEFAULT_SELECTION_PARTICLES)
    }

    /* ── Barrido ── */

    @Test
    fun sheenTakesAboutFourSecondsOnATypicalTile() {
        assertEquals(4f, SelectionFx.sheenPeriodSeconds(SelectionFx.REF_PERIMETER_DP), 1e-4f)
    }

    @Test
    fun sheenMovesAtTheSameSpeedOnSmallAndLargeTiles() {
        // Un icono de ~95 dp en horizontal y una carátula grande de tablet.
        for (perimeter in listOf(380f, 450f, 520f, 640f, 780f)) {
            val speed = perimeter / SelectionFx.sheenPeriodSeconds(perimeter)
            assertEquals("perímetro $perimeter", SelectionFx.SHEEN_SPEED_DP, speed, 0.01f)
        }
    }

    @Test
    fun sheenPeriodIsClampedForTinyAndHugeOutlines() {
        assertEquals(SelectionFx.SHEEN_MIN_SECONDS, SelectionFx.sheenPeriodSeconds(40f), 1e-4f)
        assertEquals(SelectionFx.SHEEN_MAX_SECONDS, SelectionFx.sheenPeriodSeconds(5_000f), 1e-4f)
    }

    @Test
    fun sheenFractionWrapsOncePerTurn() {
        val p = SelectionFx.REF_PERIMETER_DP
        assertEquals(0f, SelectionFx.sheenFraction(0f, p), 1e-5f)
        assertEquals(0.5f, SelectionFx.sheenFraction(2f, p), 1e-4f)
        assertEquals(0.25f, SelectionFx.sheenFraction(5f, p), 1e-4f)
        for (t in 0 until 400) {
            val f = SelectionFx.sheenFraction(t * 0.037f, p)
            assertTrue(f in 0f..1f)
        }
    }

    @Test
    fun sheenTailNeverWrapsAroundTheWholeOutline() {
        for (perimeter in listOf(60f, 200f, 520f, 1_200f)) {
            val len = SelectionFx.sheenLengthDp(perimeter)
            assertTrue(len <= perimeter * 0.2f + 1e-4f)
            assertTrue(len <= 64f)
        }
    }

    /* ── Número de partículas ── */

    @Test
    fun typicalTilesGetTwentyTwoToThirtyOnHighAndElevenToFifteenOnLite() {
        for (perimeter in listOf(380f, 450f, 520f, 600f, 640f)) {
            val high = SelectionFx.particleCount(perimeter, lite = false)
            val lite = SelectionFx.particleCount(perimeter, lite = true)
            assertTrue("alta $perimeter → $high", high in 22..30)
            assertTrue("ligera $perimeter → $lite", lite in 11..15)
        }
    }

    @Test
    fun particleCountGrowsMildlyAndHasAHardCap() {
        var prevHigh = 0
        var prevLite = 0
        for (step in 1..200) {
            val perimeter = step * 40f
            val high = SelectionFx.particleCount(perimeter, lite = false)
            val lite = SelectionFx.particleCount(perimeter, lite = true)
            assertTrue(high >= prevHigh && lite >= prevLite)
            assertTrue(high <= SelectionFx.MAX_PARTICLES)
            assertTrue(lite <= 16)
            prevHigh = high
            prevLite = lite
        }
        // Doblar el contorno no dobla las partículas.
        val base = SelectionFx.particleCount(300f, lite = false)
        assertTrue(SelectionFx.particleCount(600f, lite = false) < base * 2)
    }

    /* ── Fases ── */

    @Test
    fun breathingIsTenPercentOverThreeSeconds() {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0..3_000) {
            val b = SelectionFx.breath(i / 1_000f)
            lo = minOf(lo, b)
            hi = maxOf(hi, b)
            assertEquals(b, SelectionFx.breath(i / 1_000f + SelectionFx.BREATH_SECONDS), 1e-4f)
        }
        assertEquals(0.9f, lo, 1e-3f)
        assertEquals(1.1f, hi, 1e-3f)
    }

    @Test
    fun igniteDrawsTheRimFirstAndTheBloomFollows() {
        assertEquals(0f, SelectionFx.igniteRim(0f), 0f)
        assertEquals(1f, SelectionFx.igniteRim(1f), 0f)
        assertEquals(1f, SelectionFx.igniteRim(1.4f), 0f)
        assertEquals(0f, SelectionFx.igniteBloom(0f), 0f)
        assertEquals(0f, SelectionFx.igniteBloom(0.3f), 0f)
        assertEquals(1f, SelectionFx.igniteBloom(1f), 1e-6f)
        var prev = 0f
        for (i in 0..100) {
            val p = i / 100f
            val b = SelectionFx.igniteBloom(p)
            assertTrue(b >= prev)
            assertTrue(b <= SelectionFx.igniteRim(p) + 1e-6f)
            prev = b
        }
        assertTrue(SelectionFx.IGNITE_MS in 240..320)
        assertTrue(SelectionFx.FADE_OUT_MS < SelectionFx.IGNITE_MS)
    }

    @Test
    fun particleEnvelopesFadeInAndOut() {
        assertEquals(0f, SelectionFx.envelope(0f), 0f)
        assertEquals(0f, SelectionFx.envelope(1f), 1e-6f)
        assertEquals(1f, SelectionFx.envelope(0.4f), 1e-6f)
        assertEquals(0f, SelectionFx.glintEnvelope(0f), 1e-6f)
        assertEquals(1f, SelectionFx.glintEnvelope(0.5f), 1e-6f)
        assertEquals(0f, SelectionFx.glintEnvelope(1f), 1e-5f)
    }

    /* ── Polvo estelar ── */

    /** Un rectángulo recorrido en sentido horario, con su normal hacia fuera. */
    private class Rect(val w: Float, val h: Float) : PerimeterSampler {
        val length = 2f * (w + h)
        override fun sample(fraction: Float, out: FloatArray) {
            var d = fraction * length
            when {
                d < w -> { out[0] = d; out[1] = 0f; out[2] = 0f; out[3] = -1f }
                d < w + h -> { d -= w; out[0] = w; out[1] = d; out[2] = 1f; out[3] = 0f }
                d < 2f * w + h -> { d -= w + h; out[0] = w - d; out[1] = h; out[2] = 0f; out[3] = 1f }
                else -> { d -= 2f * w + h; out[0] = 0f; out[1] = h - d; out[2] = -1f; out[3] = 0f }
            }
        }
    }

    @Test
    fun spawnedParticlesStayWithinTheirLifeAndSizeRanges() {
        val density = 2.75f
        val rect = Rect(140f * density, 140f * density)
        val dust = Stardust()
        val random = Random(7)
        dust.reset(SelectionFx.particleCount(560f, lite = false), rect, density, random)
        repeat(2_000) {
            dust.step(1f / 60f, rect, density, random)
            for (i in 0 until dust.count) {
                assertTrue(dust.life(i) in SelectionFx.MIN_LIFE..SelectionFx.MAX_LIFE)
                assertTrue(dust.sizeDp(i) in SelectionFx.MIN_SIZE_DP..SelectionFx.MAX_SIZE_DP)
                assertTrue(dust.variant(i) in 0 until SelectionFx.VARIANTS)
                assertTrue(dust.alpha(i) in 0f..1f)
                // Nunca se alejan más de ~40 dp del contorno: polvo, no lluvia.
                val margin = 40f * density
                assertTrue(dust.x(i) in -margin..rect.w + margin)
                assertTrue(dust.y(i) in -margin..rect.h + margin)
            }
        }
    }

    @Test
    fun countIsCappedByCapacity() {
        val dust = Stardust()
        dust.reset(500, Rect(100f, 100f), 1f, Random(1))
        assertEquals(SelectionFx.MAX_PARTICLES, dust.count)
    }

    @Test
    fun particlesTrickleInAfterIgnitionInsteadOfPopping() {
        val dust = Stardust()
        dust.reset(20, Rect(300f, 300f), 2f, Random(3))
        for (i in 0 until dust.count) assertEquals(0f, dust.alpha(i), 0f)
    }

    @Test
    fun warmResetShowsParticlesRightAway() {
        val dust = Stardust()
        dust.reset(20, Rect(300f, 300f), 2f, Random(3), warm = true)
        assertTrue((0 until dust.count).count { dust.alpha(i = it) > 0f } > 10)
    }

    @Test
    fun particlesBornOnTheRimDriftOutwardAndUp() {
        val density = 3f
        val w = 120f * density
        val h = 180f * density
        val rect = Rect(w, h)
        val dust = Stardust()
        val random = Random(11)
        dust.reset(24, rect, density, random, warm = true)
        var outside = 0
        var total = 0
        repeat(1_200) {
            dust.step(1f / 60f, rect, density, random)
            for (i in 0 until dust.count) {
                if (dust.age(i) < 0.6f) continue
                total++
                val x = dust.x(i)
                val y = dust.y(i)
                if (x < 0f || x > w || y < 0f || y > h) outside++
            }
        }
        // Salen hacia fuera salvo parte de las del borde de abajo, que suben hacia la card.
        assertTrue("$outside de $total", outside > total * 0.6f)
    }

    /* ── Paleta ── */

    private val presets = listOf(
        0xFF5CF2FF, 0xFF6CFF8E, 0xFFFFC857, 0xFFFF5CD6, 0xFFA78BFF, 0xFFFF5C6C, 0xFF4D9BFF, 0xFFF4F7FF,
    ).map { it.toInt() }

    /** Colores de partida, la rueda de tono del deslizador, los acentos y casos extremos. */
    private val bases: List<Int> = presets +
        (0 until 24).map { ColorMath.fromHsl(it * 15f, 0.68f * 1.0f, 0.66f) } +
        ACCENTS.map { it.a.argb() } +
        listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF101828.toInt())

    private fun assertAtLeast(what: String, ratio: Double) =
        assertTrue("$what: ${"%.2f".format(ratio)} < 3", ratio >= SelectionPalettes.MIN_CONTRAST)

    @Test
    fun rimBloomAndParticlesKeepThreeToOneAgainstTheShelfInBothThemes() {
        for (dark in listOf(false, true)) {
            for (base in bases) {
                val p = SelectionPalettes.derive(base, dark)
                val name = "%08X %s".format(base, if (dark) "oscuro" else "claro")
                for (shelf in SelectionPalettes.shelves(dark)) {
                    assertAtLeast("$name filo", ColorMath.contrast(p.rim, shelf))
                    assertAtLeast("$name filo claro", ColorMath.contrast(p.rimLight, shelf))
                    assertAtLeast("$name halo", ColorMath.contrast(p.bloom, shelf))
                    p.particles.forEachIndexed { i, c -> assertAtLeast("$name partícula $i", ColorMath.contrast(c, shelf)) }
                }
            }
        }
    }

    @Test
    fun lightThemeUsesADeeperMoreSaturatedTone() {
        for (base in presets.dropLast(1)) {
            val light = SelectionPalettes.derive(base, dark = false)
            assertTrue(ColorMath.luminance(light.rim) < ColorMath.luminance(base))
            assertTrue(ColorMath.toHsl(light.rim)[1] >= ColorMath.toHsl(base)[1] - 0.02f)
            assertFalse(light.dark)
        }
    }

    @Test
    fun nearWhiteStaysNeutralInsteadOfTurningBlue() {
        val rim = SelectionPalettes.derive(0xFFF4F7FF.toInt(), dark = false).rim
        val chroma = maxOf(ColorMath.red(rim), ColorMath.green(rim), ColorMath.blue(rim)) -
            minOf(ColorMath.red(rim), ColorMath.green(rim), ColorMath.blue(rim))
        assertTrue("croma $chroma", chroma < 40)
    }

    @Test
    fun rimReadsOnAnyArtworkThanksToTheContrastingKeyline() {
        // Oscuro: filo vivo + filo interior negro. Claro: filo hondo + filo interior blanco.
        val dark = SelectionPalettes.derive(presets[0], dark = true)
        val light = SelectionPalettes.derive(presets[0], dark = false)
        assertEquals(0xFF000000.toInt(), dark.keyline)
        assertEquals(0xFFFFFFFF.toInt(), light.keyline)
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        // Sobre una carátula blanca o negra, alguno de los dos filos contrasta 3:1.
        for (p in listOf(dark, light)) {
            for (art in listOf(white, black)) {
                val best = maxOf(ColorMath.contrast(p.rim, art), ColorMath.contrast(ColorMath.opaque(p.keyline), art))
                assertTrue(best >= 3.0)
            }
        }
    }

    @Test
    fun everyParticleVariantExists() {
        for (dark in listOf(false, true)) {
            val p = SelectionPalettes.derive(presets[3], dark)
            assertEquals(SelectionFx.VARIANTS, p.particles.size)
            assertEquals(3, p.bloomAlphas.size)
            assertTrue(p.bloomAlphas.toList().zipWithNext().all { (a, b) -> a > b })
        }
    }
}
