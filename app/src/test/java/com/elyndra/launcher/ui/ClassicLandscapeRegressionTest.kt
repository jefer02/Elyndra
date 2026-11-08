package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.selection.SelectionFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El Clásico en apaisado, clavado al build de antes del ajuste del aire en
 * vertical: tamaño de la card, aire, ancla y dónde queda la seleccionada.
 * Los valores se sacaron de `ClassicLayout` antes de tocarlo; si este test
 * falla, apaisado ha cambiado y no debía.
 * - móvil 1600×720 a 2,0 → 800×360 dp
 * - tableta 2560×1600 a 2,0 → 1280×800 dp
 * - TV 1920×1080 a 2,0 → 960×540 dp
 */
class ClassicLandscapeRegressionTest {

    private val eps = 0.001f
    private val label = ShelfLayout.LABEL_GAP + 9.5f * 1.45f

    private class Pinned(
        val name: String,
        val w: Float,
        val h: Float,
        val square: Boolean,
        val tile: Float,
        val tileHeight: Float,
        val labelTop: Float,
        val hero: Float,
        val heroWithHints: Float,
        val gap: Float,
        val selLeft: Float,
    )

    private val pinned = listOf(
        Pinned("móvil carátulas", 800f, 360f, false, 115.200005f, 172.8f, 198.8f, 139.42499f, 107.42499f, 24.479992f, 161.68f),
        Pinned("móvil iconos", 800f, 360f, true, 146.88f, 146.88f, 172.88f, 165.345f, 133.345f, 23.759995f, 192.64001f),
        Pinned("tableta carátulas", 1280f, 800f, false, 160f, 240f, 266f, 512.225f, 480.225f, 32.666668f, 214.66669f),
        Pinned("tableta iconos", 1280f, 800f, true, 200f, 200f, 226f, 552.225f, 520.225f, 27.2f, 249.20001f),
        Pinned("TV carátulas", 960f, 540f, false, 160f, 240f, 266f, 252.225f, 220.225f, 40f, 222f),
        Pinned("TV iconos", 960f, 540f, true, 200f, 200f, 226f, 292.225f, 260.225f, 40f, 262f),
    )

    private fun frame(p: Pinned, hints: Boolean): ClassicLayout.Frame {
        val bar = ClassicLayout.barBottom(true)
        val pad = ClassicLayout.heroBottomPad(true)
        val block = ClassicLayout.Block(ClassicLayout.chipsHeight(1f, true), ClassicLayout.mergedChipsHeight(1f, true), logoMax = ClassicLayout.LOGO_MIN)
        return ClassicLayout.frame(p.w, p.h, if (p.square) 1f else 2f / 3f, hints, label, 0f, ClassicLayout.heroMin(bar, pad, block))
    }

    @Test
    fun `el tamano de la card y el reparto no cambian`() {
        for (p in pinned) for (hints in listOf(false, true)) {
            val f = frame(p, hints)
            val where = "${p.name} hints=$hints"
            assertEquals(where, p.tile, f.tileWidth, eps)
            assertEquals(where, p.tileHeight, f.tileHeight, eps)
            assertEquals(where, 20f, f.tileTop, eps)
            assertEquals(where, p.labelTop, f.labelTop, eps)
            assertEquals(where, if (hints) p.heroWithHints else p.hero, f.heroHeight, eps)
        }
    }

    @Test
    fun `el aire, la escala, el halo y los nombres no cambian`() {
        for (p in pinned) {
            val f = frame(p, hints = false)
            val row = ClassicLayout.rowWidth(p.w, landscape = true)
            val st = ClassicLayout.rowStyle(row, f.tileWidth, landscape = true)
            assertEquals(p.name, p.gap, st.gap, eps)
            assertEquals(p.name, p.gap, ClassicLayout.spacing(row, f.tileWidth), eps)
            assertEquals(p.name, 0.9f, st.neighborScale, 0f)
            assertNull(p.name, st.haloSpread)
            assertEquals(p.name, 0f, st.labelInset, 0f)
        }
        // La geometría del halo de siempre (SelectionFrame sin recorte).
        assertEquals(11f, SelectionFx.HALO_DP, 0f)
        assertEquals(0.58f, SelectionFx.UNDERGLOW_RADIUS, 0f)
        assertEquals(0.9f, ClassicLayout.NEIGHBOR_SCALE, 0f)
    }

    @Test
    fun `el ancla y la seleccionada en pantalla no cambian`() {
        for (p in pinned) {
            val f = frame(p, hints = false)
            val gap = ClassicLayout.rowStyle(ClassicLayout.rowWidth(p.w, true), f.tileWidth, true).gap
            val a = ClassicLayout.anchor(5, f.tileWidth, gap, landscape = true)
            assertEquals(p.name, 4, a.index)
            assertEquals(p.name, 0f, a.offset, 0f)
            assertEquals(p.name, ClassicLayout.Anchor(0, 0f), ClassicLayout.anchor(0, f.tileWidth, gap, landscape = true))
            val (left, right) = ClassicLayout.selectedSpan(5, 12, f.tileWidth, gap, p.w, landscape = true)
            assertEquals(p.name, p.selLeft, left, eps)
            assertEquals(p.name, p.selLeft + p.tile, right, eps)
        }
    }
}
