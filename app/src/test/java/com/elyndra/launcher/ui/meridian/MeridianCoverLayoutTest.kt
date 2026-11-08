package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.ui.TopBarLayout
import com.elyndra.launcher.ui.components.COVER_RATIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MeridianCoverLayoutTest {

    private val eps = 1e-3f

    /** Una ventana (dp) con su nombre: la pantalla en px y su densidad. */
    private data class Window(val name: String, val w: Float, val h: Float)

    /** Móviles apaisados: 1600 × 720 px (a 1,75 y a 2) y 2340 × 1080 px (a 2,625, 2,75 y 3). */
    private val phones = listOf(
        Window("1600×720@1.75", 914f, 411f),
        Window("1600×720@2", 800f, 360f),
        Window("2340×1080@2.625", 891f, 411f),
        Window("2340×1080@2.75", 851f, 393f),
        Window("2340×1080@3", 780f, 360f),
    )

    /** Un móvil pequeño (1280 × 576 px a 2): ventana baja, pero por debajo del objetivo. */
    private val small = Window("1280×576@2", 640f, 288f)

    /**
     * Tabletas: 2560 × 1600 (Legion Y700 Gen 3, 8,8", a 2 y a 2,25), 1280 × 800
     * a 1, 2000 × 1200 a 2; y una tele de 1920 × 1080 a 2.
     */
    private val large = listOf(
        Window("2560×1600@2 (Y700)", 1280f, 800f),
        Window("2560×1600@2.25 (Y700)", 1138f, 711f),
        Window("1280×800@1", 1280f, 800f),
        Window("2000×1200@2", 1000f, 600f),
        Window("TV 1920×1080@2", 960f, 540f),
    )

    /** Proporciones de cajas de verdad: PSP, Switch, 2:3, PS2, GBA, cuadrada (icono de Android), SNES PAL y un banner 16:9. */
    private val aspects = listOf(0.58f, 0.62f, 2f / 3f, 0.70f, 1.08f, 1f, 1.40f, 16f / 9f)

    /** La rueda de la carpeta tal como la monta la escena: la columna estimada y la rueda bajo la cabecera de una fila. */
    private fun folder(win: Window): MeridianGeometry {
        val compact = MeridianMode.compact(win.h)
        val rail = MeridianGeometry.slimRailWidth(win.w, win.h, COVER_RATIO, compact, folderHeader = true)
        val railH = win.h - MeridianCoverLayout.headerEstimate(compact, folder = true)
        return MeridianGeometry.compute(win.w, railH, COVER_RATIO, compact, rail)
    }

    @Test
    fun `on phones the focused cover takes 65 to 75 percent of the screen height`() {
        for (win in phones) {
            val g = folder(win)
            val share = g.rowHeight / win.h
            assertTrue("${win.name}: $share", share in 0.65f..0.75f)
            assertTrue(g.rowHeight in WheelTransform.TILE_MIN..WheelTransform.TILE_MAX)
            // Sin la ficha de ROMs debajo de volver y "Abrir", la rueda empieza una fila más arriba.
            assertEquals(win.h - MeridianCoverLayout.FOLDER_HEADER_COMPACT, g.railHeight, eps)
        }
        // El de las capturas (1600 × 720 a 1,75): ~289 dp, antes ~191 y al principio ~147.
        val shot = folder(phones[0])
        assertEquals(289f, shot.rowHeight, 0.5f)
        // El móvil pequeño, en ventana baja, también lleva el reparto de móvil (más bajo, porque la franja de las vecinas no encoge).
        val s = folder(small)
        assertTrue(s.profile.maskOnly)
        assertTrue(s.rowHeight / small.h > 0.55f)
    }

    @Test
    fun `tablets and TV keep the cover size they had, with whole neighbors on the dial's arc`() {
        for (win in large) {
            val g = folder(win)
            assertEquals(win.name, WheelProfile.DEFAULT, g.profile)
            assertEquals(win.name, g.railHeight * WheelTransform.COVER_TILE, g.rowHeight, eps)
            assertTrue(g.rowHeight <= WheelTransform.TILE_MAX)
            val reach = (WheelTransform.center(1f) + WheelTransform.scale(1f) / 2f) * g.rowHeight
            assertTrue("${win.name}: $reach", reach <= g.focusY + eps && reach <= g.railHeight - g.focusY + eps)
            assertTrue(g.rowHeight / win.h < 0.65f)
        }
    }

    @Test
    fun `previous and next neighbors are mirror images - same size, distance, opacity, axis and fade`() {
        for (win in phones + small + large) {
            val g = folder(win)
            val p = g.profile
            val row = g.rowHeight
            // La enfocada, en el centro exacto de la rueda: lo que queda arriba es lo que queda abajo.
            assertEquals(win.name, g.railHeight / 2f, g.focusY, eps)
            for (d in listOf(0.4f, 1f, 1.6f, 2f)) {
                assertEquals(WheelTransform.scale(-d, p), WheelTransform.scale(d, p), 0f)
                assertEquals(-WheelTransform.center(-d, p), WheelTransform.center(d, p), eps)
                assertEquals(WheelTransform.alpha(-d), WheelTransform.alpha(d), 0f)
                assertEquals(WheelTransform.fog(-d), WheelTransform.fog(d), 0f)
                // El mismo corrimiento a la izquierda (en un móvil, ninguno: el mismo eje que la enfocada).
                val up = p.curve * WheelArc.offset(WheelTransform.center(-d, p) * row, g.arcRadius)
                val down = p.curve * WheelArc.offset(WheelTransform.center(d, p) * row, g.arcRadius)
                assertEquals(up, down, eps)
                if (p.maskOnly) assertEquals(0f, down, 0f)
            }
            // Y el mismo fundido: la máscara de arriba es la de abajo dada la vuelta.
            val band = MeridianCoverLayout.fadeBand(g)
            val s = WheelTransform.scale(1f, p)
            val above = MeridianCoverLayout.edgeMask(g.focusY + WheelTransform.center(-1f, p) * row, s, row, 0f, g.railHeight, band)
            val below = MeridianCoverLayout.edgeMask(g.focusY + WheelTransform.center(1f, p) * row, s, row, 0f, g.railHeight, band)
            if (above == null || below == null) {
                assertEquals(win.name, above, below)
            } else {
                assertEquals(win.name, row - below.bottomClear, above.topClear, 0.01f)
                assertEquals(win.name, row - below.bottomSolid, above.topSolid, 0.01f)
            }
        }
    }

    @Test
    fun `on phones each neighbor shows the same sliver, faded the same at both edges, never under the header`() {
        for (win in phones) {
            val g = folder(win)
            assertEquals(win.name, MeridianCoverLayout.PEEK_MIN, MeridianCoverLayout.peek(g), 0.01f)
            val band = MeridianCoverLayout.fadeBand(g)
            assertTrue(band in MeridianCoverLayout.FADE_MIN..MeridianCoverLayout.EDGE_FADE)
            // La enfocada no toca la franja de fundido; las vecinas sí (se funden hasta 0 en el canto de la rueda, bajo volver y "Abrir").
            assertNull(win.name, MeridianCoverLayout.edgeMask(g.focusY, 1f, g.rowHeight, 0f, g.railHeight, band))
            val s = WheelTransform.scale(1f, g.profile)
            assertNotNull(MeridianCoverLayout.edgeMask(g.focusY + WheelTransform.center(1f, g.profile) * g.rowHeight, s, g.rowHeight, 0f, g.railHeight, band))
            assertEquals(0f, MeridianCoverLayout.edgeAlpha(0f, 0f, g.railHeight, band), eps)
            assertEquals(0f, MeridianCoverLayout.edgeAlpha(g.railHeight, 0f, g.railHeight, band), eps)
            // Entre la enfocada y cada franja, el mismo aire.
            val gap = (WheelTransform.center(1f, g.profile) - 0.5f - s / 2f) * g.rowHeight
            assertEquals(MeridianCoverLayout.PEEK_GAP, gap, 0.01f)
        }
    }

    @Test
    fun `short lists on phones keep the focused cover centered, only the missing neighbor is missing`() {
        val g = folder(phones[0])
        val railTiles = g.railHeight / g.rowHeight
        val focusTiles = g.focusY / g.rowHeight
        for (count in 1..4) for (at in 0 until count) {
            assertEquals("$count@$at", 0f, ShortList.lift(at.toFloat(), count, railTiles, focusTiles, g.profile), 0f)
        }
        // En tableta sigue centrándose la pila (con vecinas enteras no deja huecos raros).
        val t = folder(large[0])
        assertTrue(abs(ShortList.lift(0f, 2, t.railHeight / t.rowHeight, t.focusY / t.rowHeight, t.profile)) > 0f)
    }

    @Test
    fun `covers are fitted whole and boxes up to PS2 width keep the same height`() {
        for (win in phones + large) {
            val g = folder(win)
            val slotH = g.rowHeight
            val slotW = MeridianCoverLayout.slotWidth(slotH, COVER_RATIO)
            for (a in aspects) {
                val (w, h) = MeridianCoverLayout.coverBox(slotW, slotH, a)
                assertEquals("${win.name} $a", a, w / h, 1e-3f)
                assertTrue(w <= slotW + eps && h <= slotH + eps)
                // Por el alto, centradas: hasta 0,70 (PSP, Switch, 2:3, PS2) todas igual de altas.
                if (a <= MeridianCoverLayout.SLOT_ASPECT + eps) assertEquals("${win.name} $a", slotH, h, eps)
            }
            // La reserva (sin proporción, ilegible o aún sin leer): la caja de una carátula 2:3 a todo el alto.
            for (none in listOf(null, Float.NaN, 0f)) {
                val (w, h) = MeridianCoverLayout.coverBox(slotW, slotH, none)
                assertEquals(slotH, h, eps)
                assertEquals(slotH * COVER_RATIO, w, eps)
            }
        }
    }

    @Test
    fun `neighbors stay inside the screen and the cover leaves no dead glass before the dial`() {
        for (win in phones + small + large) {
            val compact = MeridianMode.compact(win.h)
            val g = folder(win)
            val slotW = MeridianCoverLayout.slotWidth(g.rowHeight, COVER_RATIO)
            val pivot = g.inset + slotW / 2f
            for (d in listOf(-1f, 1f)) {
                val s = WheelTransform.scale(d, g.profile)
                val left = pivot - s * slotW / 2f + g.profile.curve * WheelArc.offset(WheelTransform.center(d, g.profile) * g.rowHeight, g.arcRadius)
                assertTrue("${win.name} $d: $left", left >= MeridianGeometry.RAIL_START - eps)
            }
            val gap = g.railWidth - g.inset - slotW
            val dial = MeridianCoverLayout.tileToDial(compact)
            assertTrue("${win.name}: $gap", gap >= dial - eps)
            if (g.railWidth > MeridianGeometry.SLIM_MIN + eps) assertEquals(win.name, dial, gap, 0.5f)
        }
    }

    @Test
    fun `the folder header estimate is the one-row header with the pill gone`() {
        assertEquals(MeridianTopRow.TOP_COMPACT + TopBarLayout.BAR_HEIGHT + MeridianTopRow.BOTTOM_COMPACT, MeridianCoverLayout.FOLDER_HEADER_COMPACT, eps)
        assertEquals(MeridianTopRow.TOP + TopBarLayout.BAR_HEIGHT + MeridianTopRow.BOTTOM, MeridianCoverLayout.FOLDER_HEADER, eps)
        assertEquals(MeridianGeometry.HEADER_ESTIMATE_COMPACT, MeridianCoverLayout.headerEstimate(compact = true, folder = false), eps)
    }

    @Test
    fun `the edge mask lands on the rail edges in the row's own coordinates`() {
        val row = 190f
        val s = 0.6f
        val center = 10f
        val m = MeridianCoverLayout.edgeMask(center, s, row, 0f, 341f)!!
        fun rail(local: Float) = center + (local - row / 2f) * s
        assertEquals(0f, rail(m.topClear), eps)
        assertEquals(MeridianCoverLayout.EDGE_FADE, rail(m.topSolid), eps)
        assertEquals(341f - MeridianCoverLayout.EDGE_FADE, rail(m.bottomSolid), eps)
        assertEquals(341f, rail(m.bottomClear), eps)
        assertNull(MeridianCoverLayout.edgeMask(170f, s, row, 0f, 341f))
        assertEquals(0.5f, MeridianCoverLayout.edgeAlpha(14f, 0f, 341f), eps)
    }

    @Test
    fun `the default profile is exactly the old wheel tables`() {
        val p = WheelProfile.DEFAULT
        for (n in WheelTransform.SCALE.indices) {
            assertEquals(WheelTransform.SCALE[n], p.scales[n], 1e-6f)
            assertEquals(WheelTransform.CENTER[n], p.centers[n], 1e-6f)
        }
    }
}
