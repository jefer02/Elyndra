package com.elyndra.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val modes = listOf(false to BrandTokens.LIGHT, true to BrandTokens.DARK)

    private fun assertAtLeast(what: String, ratio: Double, min: Double) =
        assertTrue("$what: ${"%.2f".format(ratio)} < $min", ratio >= min)

    /* ── ColorMath ── */

    @Test
    fun contrastMatchesWcagReferenceValues() {
        assertEquals(21.0, ColorMath.contrast(white, black), 0.01)
        assertEquals(1.0, ColorMath.contrast(white, white), 0.0001)
        // #767676 sobre blanco es el gris AA de referencia: 4,54:1.
        assertEquals(4.54, ColorMath.contrast(0xFF767676.toInt(), white), 0.01)
        // Es simétrico.
        assertEquals(ColorMath.contrast(BrandTokens.PRIMARY, white), ColorMath.contrast(white, BrandTokens.PRIMARY), 1e-9)
    }

    @Test
    fun hslRoundTripKeepsTheColor() {
        for (c in listOf(BrandTokens.PRIMARY, BrandTokens.SECONDARY, 0xFFC02C48.toInt(), 0xFF808080.toInt())) {
            val hsl = ColorMath.toHsl(c)
            val back = ColorMath.fromHsl(hsl[0], hsl[1], hsl[2])
            assertTrue(Math.abs(ColorMath.red(back) - ColorMath.red(c)) <= 1)
            assertTrue(Math.abs(ColorMath.green(back) - ColorMath.green(c)) <= 1)
            assertTrue(Math.abs(ColorMath.blue(back) - ColorMath.blue(c)) <= 1)
        }
    }

    @Test
    fun ensureContrastDarkensOnLightAndLightensOnDarkKeepingHue() {
        val cyan = BrandTokens.SECONDARY
        val onLight = ColorMath.ensureContrast(cyan, BrandTokens.LIGHT.paper, 4.5)
        assertAtLeast("cian sobre papel claro", ColorMath.contrast(onLight, BrandTokens.LIGHT.paper), 4.5)
        assertTrue(ColorMath.luminance(onLight) < ColorMath.luminance(cyan))
        assertEquals(ColorMath.toHsl(cyan)[0], ColorMath.toHsl(onLight)[0], 3f)

        val deep = BrandTokens.PRIMARY_DEEP
        val onDark = ColorMath.ensureContrast(deep, BrandTokens.DARK.paper, 4.5)
        assertAtLeast("índigo profundo sobre papel oscuro", ColorMath.contrast(onDark, BrandTokens.DARK.paper), 4.5)
        assertTrue(ColorMath.luminance(onDark) > ColorMath.luminance(deep))
    }

    @Test
    fun ensureContrastLeavesPassingColorsUntouched() {
        assertEquals(BrandTokens.LIGHT.ink, ColorMath.ensureContrast(BrandTokens.LIGHT.ink, BrandTokens.LIGHT.paper, 4.5))
    }

    @Test
    fun overCompositesAlphaOnOpaque() {
        assertEquals(white, ColorMath.over(0x00000000, white))
        assertEquals(black, ColorMath.over(black, white))
        val half = ColorMath.over(0x80000000.toInt(), white)
        assertEquals(127.0, ColorMath.red(half).toDouble(), 1.0)
    }

    /* ── Neutros y estados ── */

    @Test
    fun neutralTextAndStatesAreAaInBothThemes() {
        for ((dark, n) in modes) {
            val m = if (dark) "oscuro" else "claro"
            for (bg in listOf(n.paper, n.surface)) {
                assertAtLeast("ink ($m)", ColorMath.contrast(n.ink, bg), 4.5)
                assertAtLeast("ink2 ($m)", ColorMath.contrast(n.ink2, bg), 4.5)
                assertAtLeast("éxito ($m)", ColorMath.contrast(n.success, bg), 4.5)
                assertAtLeast("aviso ($m)", ColorMath.contrast(n.warning, bg), 4.5)
                assertAtLeast("error ($m)", ColorMath.contrast(n.error, bg), 4.5)
            }
            // Texto sobre una píldora de cristal (chip encima del papel).
            val chip = ColorMath.over(n.chip, n.paper)
            assertAtLeast("ink sobre chip ($m)", ColorMath.contrast(n.ink, chip), 4.5)
            assertAtLeast("ink2 sobre chip ($m)", ColorMath.contrast(n.ink2, chip), 4.5)
        }
    }

    @Test
    fun whiteReadsOnTheShade() {
        assertAtLeast("blanco sobre sombra", ColorMath.contrast(white, BrandTokens.SHADE), 4.5)
        assertAtLeast("blanco sobre widget", ColorMath.contrast(white, ColorMath.over(BrandTokens.WIDGET_BG, black)), 4.5)
    }

    /* ── Acentos ── */

    @Test
    fun everyAccentContentIsAaTextAndFocusRingInBothThemes() {
        for (accent in ACCENTS) for ((dark, n) in modes) {
            val c = accent.content(dark).argb()
            for (bg in listOf(n.paper, n.surface)) {
                assertAtLeast("${accent.id} texto (dark=$dark)", ColorMath.contrast(c, bg), 4.5)
                // Aro de foco y estados seleccionados: pieza de interfaz, 3:1.
                assertAtLeast("${accent.id} foco (dark=$dark)", ColorMath.contrast(c, bg), 3.0)
            }
            // Sobre cristal translúcido (tinte de partida al 58 % encima del papel).
            val glass = ColorMath.over(ColorMath.withAlpha(TINTS.first { it.id == Palettes.DEFAULT_TINT }.color.argb(), 0.58f), n.paper)
            if (!dark) assertAtLeast("${accent.id} sobre cristal", ColorMath.contrast(c, glass), 4.5)
        }
    }

    @Test
    fun brandAccentsCarryWhiteTextAcrossTheWholeFill() {
        for (id in listOf("indigo", "abismo", "medianoche")) {
            val accent = ACCENTS.first { it.id == id }
            assertAtLeast("$id blanco sobre a", ColorMath.contrast(white, accent.a.argb()), 4.5)
            assertAtLeast("$id blanco sobre b", ColorMath.contrast(white, accent.b.argb()), 4.5)
        }
    }

    @Test
    fun fillForAlwaysGivesWhiteTextAa() {
        for (accent in ACCENTS) {
            val fill = Palettes.fillFor(accent.a.argb())
            assertAtLeast("${accent.id} relleno", ColorMath.contrast(white, fill), 4.5)
        }
        // Un color que ya cumple no se toca.
        assertEquals(BrandTokens.PRIMARY, Palettes.fillFor(BrandTokens.PRIMARY))
    }

    @Test
    fun contentForDerivesAaFromAnyColor() {
        for (c in listOf(0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF101010.toInt(), 0xFFF59659.toInt())) {
            for ((dark, n) in modes) {
                val content = Palettes.contentFor(c, dark)
                assertAtLeast("contenido de ${Integer.toHexString(c)}", ColorMath.contrast(content, n.paper), 4.5)
                assertAtLeast("contenido de ${Integer.toHexString(c)}", ColorMath.contrast(content, n.surface), 4.5)
            }
        }
    }

    @Test
    fun accentIdsAreUniqueAndBrandPalettesComeFirst() {
        assertEquals(ACCENTS.size, ACCENTS.map { it.id }.toSet().size)
        assertEquals(TINTS.size, TINTS.map { it.id }.toSet().size)
        assertEquals(listOf("indigo", "abismo", "medianoche"), ACCENTS.take(3).map { it.id })
        // Los acentos de siempre siguen ahí para quien los tenga guardados.
        val legacy = listOf("mandarina", "fuego", "menta", "cobalto", "lila", "coral", "turquesa", "oro", "chicle", "grafito")
        assertTrue(ACCENTS.map { it.id }.containsAll(legacy))
    }

    /* ── Selección de partida y migración ── */

    @Test
    fun nothingStoredPicksTheNewDefaults() {
        val accents = ACCENTS.map { it.id }
        val tints = TINTS.map { it.id }
        assertEquals("indigo", Palettes.accentId(null, accents))
        assertEquals("niebla", Palettes.tintId(null, tints))
        assertTrue(Palettes.DEFAULT_ACCENT in accents)
        assertTrue(Palettes.DEFAULT_TINT in tints)
        // Claro por defecto.
        assertEquals(false, Palettes.DEFAULT_DARK)
    }

    @Test
    fun storedChoiceIsKept() {
        val accents = ACCENTS.map { it.id }
        val tints = TINTS.map { it.id }
        assertEquals("mandarina", Palettes.accentId("mandarina", accents))
        assertEquals("grafito", Palettes.accentId("grafito", accents))
        assertEquals("papel", Palettes.tintId("papel", tints))
        assertEquals("humo", Palettes.tintId("humo", tints))
    }

    @Test
    fun unknownStoredValueFallsBackToDefault() {
        assertEquals("indigo", Palettes.accentId("neon-que-ya-no-existe", ACCENTS.map { it.id }))
        assertEquals("niebla", Palettes.tintId("", TINTS.map { it.id }))
    }
}
