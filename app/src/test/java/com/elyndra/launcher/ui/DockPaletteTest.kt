package com.elyndra.launcher.ui

import com.elyndra.launcher.data.ACCENTS
import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.data.argb
import org.junit.Assert.assertTrue
import org.junit.Test

/** El dock, el botón de orden y su menú se leen sobre cualquier arte (negro o blanco detrás), en claro y en oscuro. */
class DockPaletteTest {

    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `en el cristal oscuro los puntos y el glifo se ven sobre cualquier arte`() {
        // En la barra: el velo de arriba del hero y el cristal con la transparencia al mínimo.
        val bar = DockPalettes.worstOnDarkGlass(DockPalettes.GLASS_DOT, glass = 0.25f, scrim = DockPalettes.HERO_TOP_SCRIM)
        assertTrue("barra: $bar", bar >= DockPalettes.MIN_UI)
        // En la costura media cápsula cae sobre el estante, perla en claro, sin velo del hero.
        val pearl = com.elyndra.launcher.data.BrandTokens.LIGHT.paper
        val seam = DockPalettes.worstOnDarkGlass(DockPalettes.GLASS_DOT, glass = DockPalettes.SEAM_GLASS_MIN, scrim = 0f, unders = listOf(pearl, white))
        assertTrue("costura: $seam", seam >= DockPalettes.MIN_UI)
        // El rótulo que asoma: blanco sobre su ficha de tinta.
        val peek = DockPalettes.worstOnDarkGlass(white, glass = DockPalettes.GLASS_PEEK_ALPHA, scrim = 0f)
        assertTrue("ficha: $peek", peek >= DockPalettes.MIN_TEXT)
        // El aro de foco y la flecha del orden: el acento en su tono para fondo oscuro.
        for (accent in ACCENTS) {
            val c = DockPalettes.worstOnDarkGlass(DockPalettes.glassAccent(accent.content(true).argb()), glass = 0.25f, scrim = DockPalettes.HERO_TOP_SCRIM)
            assertTrue("${accent.id} acento: $c", c >= DockPalettes.MIN_UI)
        }
    }

    @Test
    fun `los puntos llegan al 3 a 1 sobre la capsula en los dos temas`() {
        for (dark in listOf(false, true)) {
            val c = DockPalettes.worstContrast(DockPalettes.dot(dark), DockPalettes.surface(dark), dark)
            assertTrue("puntos (dark=$dark): $c", c >= DockPalettes.MIN_UI)
        }
    }

    @Test
    fun `el texto del menu y de la ficha que asoma llega al 4,5 a 1`() {
        for (dark in listOf(false, true)) for (alpha in listOf(DockPalettes.surfaceAlpha(dark), DockPalettes.popoverAlpha(dark))) {
            val surface = DockPalettes.surface(dark, alpha)
            // Con el velo blanco de la lámina en el centro (donde va el texto) y en el canto de arriba.
            for (at in listOf(0.5f, 1f)) {
                assertTrue("tinta (dark=$dark, velo $at)", DockPalettes.worstContrast(DockPalettes.ink(dark), surface, dark, at) >= DockPalettes.MIN_TEXT)
                assertTrue("tinta secundaria (dark=$dark, velo $at)", DockPalettes.worstContrast(DockPalettes.ink2(dark), surface, dark, at) >= DockPalettes.MIN_TEXT)
            }
        }
    }

    @Test
    fun `el rotulo blanco se lee en toda la pildora con cualquier acento`() {
        for (accent in ACCENTS) {
            val start = DockPalettes.pillStart(accent.a.argb())
            val end = DockPalettes.pillEnd(accent.b.argb())
            assertTrue("${accent.id} inicio", ColorMath.contrast(white, start) >= DockPalettes.MIN_TEXT)
            assertTrue("${accent.id} final", ColorMath.contrast(white, end) >= DockPalettes.MIN_TEXT)
        }
    }

    @Test
    fun `la pildora elegida se distingue de la capsula con cualquier acento`() {
        for (accent in ACCENTS) for (dark in listOf(false, true)) {
            assertTrue("${accent.id} (dark=$dark)", DockPalettes.pillStandsOut(accent.a.argb(), dark))
        }
    }

    @Test
    fun `el glifo del boton de orden y su flecha en el acento llegan al 3 a 1`() {
        for (dark in listOf(false, true)) {
            assertTrue(DockPalettes.worstContrast(DockPalettes.ink(dark), DockPalettes.surface(dark), dark) >= DockPalettes.MIN_UI)
            for (accent in ACCENTS) {
                val arrow = accent.content(dark).argb()
                assertTrue("${accent.id} (dark=$dark)", DockPalettes.worstContrast(arrow, DockPalettes.surface(dark), dark) >= DockPalettes.MIN_UI)
                // Abierto el menú, el botón lleva encima un velo del acento.
                val veil = ColorMath.withAlpha(accent.a.argb(), if (dark) 0.22f else 0.14f)
                for (under in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
                    val open = ColorMath.over(veil, ColorMath.over(DockPalettes.surface(dark), under))
                    assertTrue("${accent.id} abierto (dark=$dark)", ColorMath.contrast(DockPalettes.ink(dark), open) >= DockPalettes.MIN_UI)
                }
            }
        }
    }
}
