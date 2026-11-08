package com.elyndra.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El Clásico en vertical: el aire **a la vista** (de filo dibujado a filo
 * dibujado, con las vecinas ya encogidas), el tamaño de las cards, que la
 * seleccionada nunca quede cortada y que su halo no pise a la vecina.
 * Píxeles del panel pasados a dp:
 * - móvil 720×1600 a 2,0 → 360×800 dp (las capturas)
 * - móvil 1080×2400 a 2,625 → 411×914 dp
 * - móvil pequeño 720×1440 a 2,0 → 360×720 dp
 * - tableta 1600×2560 a 2,0 → 800×1280 dp
 * - tableta 800×1280 a 1,33 → 601×962 dp
 * Apaisado tiene su propia regresión ([ClassicLandscapeRegressionTest]).
 */
class ClassicPortraitTest {

    private class Screen(val name: String, val pxW: Int, val pxH: Int, val density: Float) {
        val w get() = pxW / density
        val h get() = pxH / density
        val landscape get() = w > h
    }

    private val phone = Screen("móvil 720×1600", 720, 1600, 2f)
    private val phoneHd = Screen("móvil 1080×2400", 1080, 2400, 2.625f)
    private val phoneSmall = Screen("móvil pequeño 720×1440", 720, 1440, 2f)
    private val tablet = Screen("tableta 1600×2560", 1600, 2560, 2f)
    private val tabletSmall = Screen("tableta 800×1280", 800, 1280, 1.33f)
    private val phones = listOf(phone, phoneHd, phoneSmall)
    /** Los de ~360 dp de ancho, para los que se pidieron los tamaños. */
    private val narrowPhones = listOf(phone, phoneSmall)
    private val tablets = listOf(tablet, tabletSmall)
    private val shapes = listOf(true, false)

    private val eps = 0.01f
    private val label = ShelfLayout.LABEL_GAP + 9.5f * 1.45f

    private fun frame(s: Screen, square: Boolean, hints: Boolean = false): ClassicLayout.Frame {
        val l = s.landscape
        val bar = ClassicLayout.barBottom(l)
        val pad = ClassicLayout.heroBottomPad(l)
        val block = ClassicLayout.Block(ClassicLayout.chipsHeight(1f, l), ClassicLayout.mergedChipsHeight(1f, l), logoMax = ClassicLayout.LOGO_MIN)
        return ClassicLayout.frame(s.w, s.h, if (square) 1f else 2f / 3f, hints, label, 0f, ClassicLayout.heroMin(bar, pad, block))
    }

    private fun row(s: Screen) = ClassicLayout.rowWidth(s.w, s.landscape)
    private fun style(s: Screen, f: ClassicLayout.Frame) = ClassicLayout.rowStyle(row(s), f.tileWidth, s.landscape)

    /**
     * Los filos dibujados de la card [i] (la seleccionada es [selected]) con
     * la fila colocada como la `LazyRow`: la caja de maqueta y, si es vecina,
     * encogida desde el centro de su pie. Se calcula aquí a mano, sin
     * [ClassicLayout.visibleGap], para medir lo que de verdad se ve.
     */
    private fun drawn(s: Screen, f: ClassicLayout.Frame, st: ClassicLayout.RowStyle, i: Int, selected: Int, count: Int): Pair<Float, Float> {
        val (selLeft, _) = ClassicLayout.selectedSpan(selected, count, f.tileWidth, st.gap, s.w, s.landscape)
        val left = selLeft + (i - selected) * (f.tileWidth + st.gap)
        val shrink = if (i == selected) 0f else f.tileWidth * (1f - st.neighborScale) / 2f
        return left + shrink to left + f.tileWidth - shrink
    }

    /** El aire a la vista entre la seleccionada (en medio de 12) y su vecina de la derecha. */
    private fun visibleGap(s: Screen, square: Boolean): Float {
        val f = frame(s, square)
        val st = style(s, f)
        val (_, selRight) = drawn(s, f, st, 5, 5, 12)
        val (nextLeft, _) = drawn(s, f, st, 6, 5, 12)
        return nextLeft - selRight
    }

    /* ── Aire a la vista ─────────────────────────────────────── */

    @Test
    fun `en un movil vertical el aire a la vista es de 8 a 10 dp`() {
        for (s in phones) for (square in shapes) {
            val g = visibleGap(s, square)
            assertTrue("${s.name} square=$square: $g", g in 8f..10f)
        }
    }

    @Test
    fun `en una tableta vertical el aire a la vista ronda los 12 dp`() {
        for (s in tablets) for (square in shapes) {
            val g = visibleGap(s, square)
            assertTrue("${s.name} square=$square: $g", g in 11f..13f)
        }
    }

    @Test
    fun `el aire a la vista es el mismo a los dos lados de la seleccionada`() {
        for (s in phones + tablets) for (square in shapes) {
            val f = frame(s, square)
            val st = style(s, f)
            val (selLeft, selRight) = drawn(s, f, st, 5, 5, 12)
            val (_, prevRight) = drawn(s, f, st, 4, 5, 12)
            val (nextLeft, _) = drawn(s, f, st, 6, 5, 12)
            assertEquals("${s.name} square=$square", selLeft - prevRight, nextLeft - selRight, eps)
            // La función del reparto dice lo mismo que los filos medidos.
            assertEquals(nextLeft - selRight, ClassicLayout.visibleGap(st.gap, f.tileWidth, landscape = false), eps)
        }
    }

    @Test
    fun `entre dos vecinas el aire no se dispara`() {
        // Las dos encogen por ese lado: algo más de aire que junto a la
        // seleccionada, pero no el doble (con 90 % y 16 dp eran ~28 dp).
        for (s in phones + tablets) for (square in shapes) {
            val f = frame(s, square)
            val st = style(s, f)
            val (_, aRight) = drawn(s, f, st, 6, 5, 12)
            val (bLeft, _) = drawn(s, f, st, 7, 5, 12)
            val between = bLeft - aRight
            val beside = visibleGap(s, square)
            assertTrue("${s.name} square=$square: $between vs $beside", between <= beside * 1.6f)
            assertTrue("${s.name} square=$square: $between", between <= 18f + eps)
        }
    }

    @Test
    fun `la causa de las capturas era sumar el aire de maqueta y lo que encoge la vecina`() {
        // La versión anterior: 16 dp de maqueta, vecinas al 90 % y carátulas
        // de ~117 dp (2,55 por pantalla) → ~22 dp a la vista, lo medido.
        val oldTile = (row(phone) + 16f) / 2.55f - 16f
        val oldVisible = 16f + oldTile * (1f - ClassicLayout.NEIGHBOR_SCALE) / 2f
        assertEquals(21.9f, oldVisible, 0.2f)
        // Y los iconos (~129 dp, 2,35 por pantalla) → ~22–24 dp.
        val oldIcon = (row(phone) + 16f) / 2.35f - 16f
        assertTrue(16f + oldIcon * (1f - ClassicLayout.NEIGHBOR_SCALE) / 2f > 22f)
    }

    /* ── Tamaños ─────────────────────────────────────────────── */

    @Test
    fun `en un movil de 360 dp las caratulas miden 135-145 dp y los iconos 140-150`() {
        for (s in narrowPhones) {
            val cover = frame(s, square = false)
            val icon = frame(s, square = true)
            assertTrue("${s.name} carátula ${cover.tileWidth}", cover.tileWidth in 135f..145f)
            assertTrue("${s.name} icono ${icon.tileWidth}", icon.tileWidth in 140f..150f)
            // Más grandes que la versión anterior (~117 / ~129) y menores que la de 190 dp.
            assertTrue(cover.tileWidth > 120f && cover.tileWidth < 190f)
        }
    }

    @Test
    fun `en un movil vertical se ven entre 2,1 y 2,35 cards contando las que asoman`() {
        for (s in phones) for (square in shapes) {
            val f = frame(s, square)
            val n = ClassicLayout.visibleCount(row(s), f.tileWidth, style(s, f).gap)
            assertTrue("${s.name} square=$square: $n", n in 2.1f..2.35f)
        }
    }

    @Test
    fun `un movil mas ancho crece en proporcion`() {
        for (square in shapes) {
            val small = frame(phone, square).tileWidth / row(phone)
            val wide = frame(phoneHd, square).tileWidth / row(phoneHd)
            assertEquals("square=$square", small, wide, 0.005f)
        }
    }

    @Test
    fun `en una tableta vertical crecen hasta los topes de siempre`() {
        for (s in tablets) for (square in shapes) {
            val f = frame(s, square)
            assertTrue("${s.name} square=$square: ${f.tileHeight}", f.tileHeight <= (if (square) ClassicLayout.SQUARE_MAX else ClassicLayout.COVER_MAX) + eps)
            assertTrue("${s.name} square=$square: ${f.tileWidth}", f.tileWidth >= frame(phone, square).tileWidth - eps)
            assertTrue(ClassicLayout.visibleCount(row(s), f.tileWidth, style(s, f).gap) >= 2.3f)
        }
    }

    @Test
    fun `iconos y caratulas mantienen su proporcion y un peso parecido`() {
        for (s in phones + tablets) {
            val cover = frame(s, square = false)
            val icon = frame(s, square = true)
            assertEquals(2f / 3f, cover.tileWidth / cover.tileHeight, 1e-4f)
            assertEquals(1f, icon.tileWidth / icon.tileHeight, 1e-4f)
        }
        for (s in phones) {
            val cover = frame(s, square = false)
            val icon = frame(s, square = true)
            // Área parecida (±25 %): el icono no se ve ni enano ni gigante al lado de una carátula.
            val ratio = (icon.tileWidth * icon.tileHeight) / (cover.tileWidth * cover.tileHeight)
            assertTrue("${s.name}: $ratio", ratio in 0.75f..1.25f)
        }
    }

    /* ── La seleccionada siempre entera ──────────────────────── */

    @Test
    fun `la seleccionada se ve entera al principio, en medio y al final`() {
        for (s in phones + tablets) for (square in shapes) for (count in listOf(1, 2, 3, 4, 12, 40)) {
            val f = frame(s, square)
            val st = style(s, f)
            for (index in listOf(0, 1, count / 2, count - 2, count - 1).filter { it in 0 until count }.distinct()) {
                val (left, right) = drawn(s, f, st, index, index, count)
                val where = "${s.name} square=$square n=$count i=$index"
                assertTrue("$where: izquierda $left", left >= ClassicLayout.PAD_PORTRAIT - eps)
                assertTrue("$where: derecha $right de ${s.w}", right <= s.w - ClassicLayout.PAD_PORTRAIT + eps)
                // Al desplazar (no en la primera), la anterior asoma por la izquierda.
                if (index > 0 && count > 2 && index < count - 1) {
                    val (_, prevRight) = drawn(s, f, st, index - 1, index, count)
                    assertTrue("$where: la anterior no asoma ($prevRight)", prevRight > 0f)
                }
            }
        }
    }

    @Test
    fun `la primera va pegada al margen y la ultima al margen derecho`() {
        for (s in phones + tablets) for (square in shapes) {
            val f = frame(s, square)
            val g = style(s, f).gap
            val (first, _) = ClassicLayout.selectedSpan(0, 12, f.tileWidth, g, s.w, landscape = false)
            assertEquals(ClassicLayout.PAD_PORTRAIT, first, eps)
            val (_, last) = ClassicLayout.selectedSpan(11, 12, f.tileWidth, g, s.w, landscape = false)
            assertEquals(s.w - ClassicLayout.PAD_PORTRAIT, last, eps)
        }
    }

    @Test
    fun `en medio la seleccionada va en el primer hueco con la anterior asomando`() {
        val s = phone
        val f = frame(s, square = true)
        val g = style(s, f).gap
        val (left, _) = ClassicLayout.selectedSpan(5, 12, f.tileWidth, g, s.w, landscape = false)
        assertEquals(ClassicLayout.PAD_PORTRAIT + ClassicLayout.PORTRAIT_PEEK, left, eps)
    }

    /* ── Nombres y halo ──────────────────────────────────────── */

    @Test
    fun `el halo de la seleccionada no pisa el arte ni el nombre de la vecina`() {
        for (s in phones + tablets) for (square in shapes) {
            val f = frame(s, square)
            val st = style(s, f)
            val where = "${s.name} square=$square"
            val (_, selRight) = drawn(s, f, st, 5, 5, 12)
            val (nextLeft, _) = drawn(s, f, st, 6, 5, 12)
            val halo = ClassicLayout.haloSide(f.tileWidth, st.haloSpread)
            // De lado: lo más lejano del halo (estela o luz del estante) queda antes del arte de la vecina.
            assertTrue("$where: halo hasta ${selRight + halo}, vecina desde $nextLeft", selRight + halo + ClassicLayout.HALO_CLEAR <= nextLeft + eps)
            // Ni de su nombre (que empieza recogido lo mismo que su arte).
            val nextLabelLeft = selRight + st.gap + st.labelInset
            assertTrue("$where: halo hasta ${selRight + halo}, nombre desde $nextLabelLeft", selRight + halo < nextLabelLeft)
            // Por abajo: ni la estela ni la luz del estante (bajo el pie subido) llegan a la línea de los nombres.
            val selBottom = ClassicLayout.coverBottom(f, selected = true)
            assertTrue("$where: luz ${selBottom + ClassicLayout.UNDERGLOW_DROP} vs ${f.labelTop}", selBottom + ClassicLayout.UNDERGLOW_DROP <= f.labelTop + eps)
            assertTrue("$where: estela ${selBottom + halo} vs ${f.labelTop}", selBottom + halo <= f.labelTop + eps)
        }
    }

    @Test
    fun `en vertical el halo se recorta y en apaisado sigue el de siempre`() {
        for (s in phones + tablets) {
            val st = style(s, frame(s, square = true))
            val spread = st.haloSpread
            assertTrue("${s.name}", spread != null && spread < com.elyndra.launcher.ui.selection.SelectionFx.HALO_DP)
        }
        assertEquals(null, ClassicLayout.rowStyle(756f, 115f, landscape = true).haloSpread)
    }

    @Test
    fun `los nombres van del ancho de lo que se ve y nunca se rozan`() {
        for (s in phones + tablets) for (square in shapes) {
            val f = frame(s, square)
            val st = style(s, f)
            // Recogidos lo que encoge la vecina: el nombre mide lo que su arte.
            assertEquals(f.tileWidth * (1f - st.neighborScale) / 2f, st.labelInset, eps)
            // Entre dos nombres recortados queda al menos el aire a la vista.
            val labelGap = st.gap + 2 * st.labelInset
            assertTrue("${s.name} square=$square: $labelGap", labelGap >= visibleGap(s, square) - eps)
        }
    }

    @Test
    fun `las vecinas en vertical siguen viendose algo mas pequenas`() {
        val f = frame(phone, square = false)
        val sel = ClassicLayout.visibleHeight(f, selected = true, landscape = false)
        val other = ClassicLayout.visibleHeight(f, selected = false, landscape = false)
        assertTrue(other < sel)
        assertEquals(ClassicLayout.PORTRAIT_NEIGHBOR_SCALE, other / sel, 1e-4f)
    }

    /* ── El hero vertical con el dock en la costura ──────────── */

    @Test
    fun `la sinopsis nunca toca el dock de la costura en un movil pequeno`() {
        for (s in phones) for (fs in listOf(1f, 1.3f)) {
            val seamRise = DockPlacement.seamRise(ClassicLayout.HERO_BOTTOM_PORTRAIT)
            val bar = ClassicLayout.barBottom(false, ClassicLayout.BAR_HEIGHT_MASHA)
            val bottomPad = ClassicLayout.heroBottomPad(false, seamRise)
            val chips = ClassicLayout.chipsHeight(fs, false)
            val merged = ClassicLayout.mergedChipsHeight(fs, false)
            val inset = DotTabsLayout.HEIGHT - seamRise + 6f
            val f = ClassicLayout.frame(
                s.w, s.h, 1f, false, label, inset,
                ClassicLayout.heroMin(bar, ClassicLayout.HERO_BOTTOM_PORTRAIT, ClassicLayout.Block(chips, merged, logoMax = ClassicLayout.LOGO_MIN)),
            )
            val logoMax = ClassicLayout.logoHeight(f.heroHeight, false)
            val plan = ClassicLayout.heroPlan(
                ClassicLayout.heroRoom(f.heroHeight, bar, bottomPad),
                ClassicLayout.Block(
                    chips, merged, logoMax,
                    secondary = ClassicLayout.synopsisHeight(fs, false),
                    secondaryLines = 3,
                    secondaryLine = ClassicLayout.synopsisLineHeight(fs),
                ),
            )
            val blockBottom = f.heroHeight - bottomPad
            val dockTop = DockPlacement.top(f.heroHeight, DotTabsLayout.HEIGHT, inHero = false, seamRise = seamRise)
            assertTrue("${s.name} fs=$fs: ${dockTop - blockBottom}", dockTop - blockBottom >= ClassicLayout.SEAM_CLEAR - eps)
            assertTrue("${s.name} fs=$fs: bloque bajo la barra", f.heroHeight - bottomPad - plan.height >= bar + ClassicLayout.BAR_GAP - eps)
        }
    }

    @Test
    fun `la sinopsis pierde lineas antes de irse`() {
        val line = 12.8f
        val b = ClassicLayout.Block(chips = 24f, mergedChips = 17f, logoMax = 80f, secondary = 3 * line + 6f, secondaryLines = 3, secondaryLine = line, stats = 0f)
        val full = 24f + 80f + 3 * line + 6f
        assertEquals(3, ClassicLayout.heroPlan(full, b).secondaryLines)
        val two = ClassicLayout.heroPlan(full - line + 0.01f, b)
        assertEquals(ClassicLayout.Step.FewerLines, two.step)
        assertEquals(2, two.secondaryLines)
        val one = ClassicLayout.heroPlan(full - 2 * line + 0.01f, b)
        assertEquals(1, one.secondaryLines)
        val none = ClassicLayout.heroPlan(full - 3 * line + 0.01f, b)
        assertEquals(ClassicLayout.Step.NoSecondary, none.step)
        assertFalse(none.showSecondary)
        assertEquals(0, none.secondaryLines)
    }
}
