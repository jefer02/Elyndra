package com.elyndra.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El Clásico en las pantallas de verdad. Los tamaños son píxeles del panel
 * pasados a dp con la densidad habitual de cada aparato:
 * - móvil apaisado 1600×720 a 2,0 (xhdpi, p. ej. Tecno Spark 30C) → 800×360 dp
 * - móvil apaisado 2340×1080 a 2,625 (420 dpi) → 891×411 dp
 * - móvil vertical 720×1600 a 2,0 → 360×800 dp
 * - tableta 2560×1600 a 2,0 (Legion Y700) → 1280×800 dp
 * - tableta 1280×800 a 1,33 (tvdpi, 8") → 962×601 dp
 * - TV 1920×1080 a 2,0 (xhdpi, la de Android TV) → 960×540 dp
 */
class ClassicLayoutTest {

    private class Screen(val name: String, val pxW: Int, val pxH: Int, val density: Float) {
        val w get() = pxW / density
        val h get() = pxH / density
        val landscape get() = w > h
    }

    private val phone = Screen("móvil 1600×720", 1600, 720, 2f)
    private val phoneTall = Screen("móvil 2340×1080", 2340, 1080, 2.625f)
    private val portrait = Screen("vertical 720×1600", 720, 1600, 2f)
    private val tablet = Screen("tableta 2560×1600", 2560, 1600, 2f)
    private val tabletSmall = Screen("tableta 1280×800", 1280, 800, 1.33f)
    private val tv = Screen("TV 1920×1080", 1920, 1080, 2f)
    private val all = listOf(phone, phoneTall, portrait, tablet, tabletSmall, tv)

    private val eps = 0.01f

    /** El bloque del nombre de la carpeta (8,5 sp) con la escala de letra [fs]. */
    private fun label(fs: Float) = ShelfLayout.LABEL_GAP + 8.5f * 1.45f * fs

    private class Case(val frame: ClassicLayout.Frame, val plan: ClassicLayout.HeroPlan, val barBottom: Float, val bottomPad: Float)

    /**
     * Lo mismo que hace la pantalla de Carpeta (o la de Biblioteca con
     * [library]): mide el bloque, reparte y planifica el hero.
     */
    private fun layout(
        s: Screen,
        library: Boolean = false,
        hints: Boolean = false,
        fs: Float = 1f,
        synopsis: Boolean = false,
        touch: Boolean = true,
    ): Case {
        val l = s.landscape
        val bar = ClassicLayout.barBottom(l, if (library) ClassicLayout.BAR_HEIGHT_MASHA else ClassicLayout.BAR_HEIGHT)
        val pad = ClassicLayout.heroBottomPad(l)
        val chips = ClassicLayout.chipsHeight(fs, l)
        val merged = ClassicLayout.mergedChipsHeight(fs, l)
        val aspect = if (library) 1f else 2f / 3f
        val frame = ClassicLayout.frame(
            s.w, s.h, aspect, hints, label(fs), topInset = 0f,
            heroMin = ClassicLayout.heroMin(bar, pad, ClassicLayout.Block(chips, merged, logoMax = ClassicLayout.LOGO_MIN)),
        )
        val showHint = ClassicLayout.showTouchHint(gamepadPresent = hints, padActive = !touch)
        val secondary = when {
            synopsis -> ClassicLayout.synopsisHeight(fs, l)
            showHint -> ClassicLayout.hintHeight(fs, l)
            else -> 0f
        }
        val logoMax = ClassicLayout.logoHeight(frame.heroHeight, l) * (if (library) 0.8f else if (l) 1.25f else 1f)
        val block = ClassicLayout.Block(chips, merged, logoMax, secondary = secondary, stats = if (library) 0f else ClassicLayout.STATS_BLOCK)
        val plan = ClassicLayout.heroPlan(ClassicLayout.heroRoom(frame.heroHeight, bar, pad), block)
        return Case(frame, plan, bar, pad)
    }

    /* ── Sin solapes ─────────────────────────────────────────── */

    @Test
    fun `el bloque del hero nunca se sube a la barra, en ninguna pantalla`() {
        for (s in all) for (library in listOf(false, true)) for (hints in listOf(false, true)) for (fs in listOf(1f, 1.3f)) for (syn in listOf(false, true)) {
            val c = layout(s, library, hints, fs, syn)
            val blockTop = c.frame.heroHeight - c.bottomPad - c.plan.height
            assertTrue(
                "${s.name} library=$library hints=$hints fs=$fs syn=$syn: bloque a $blockTop, barra hasta ${c.barBottom}",
                blockTop >= c.barBottom + ClassicLayout.BAR_GAP - eps,
            )
        }
    }

    @Test
    fun `hero y estante suman la ventana`() {
        for (s in all) for (hints in listOf(false, true)) {
            val f = layout(s, hints = hints).frame
            assertEquals(s.name, s.h, f.heroHeight + f.shelfHeight, eps)
        }
    }

    @Test
    fun `en el hero siempre cabe la barra y el bloque compacto`() {
        for (s in all) for (hints in listOf(false, true)) for (fs in listOf(1f, 1.3f)) {
            val c = layout(s, hints = hints, fs = fs)
            val compact = ClassicLayout.mergedChipsHeight(fs, s.landscape) + ClassicLayout.LOGO_MIN
            assertTrue("${s.name} hints=$hints fs=$fs", c.frame.heroHeight >= c.barBottom + ClassicLayout.BAR_GAP + c.bottomPad + compact - eps)
            assertTrue("${s.name}: el logo no desaparece", c.plan.logoHeight >= ClassicLayout.LOGO_MIN - eps)
        }
    }

    /* ── Carátulas grandes ───────────────────────────────────── */

    @Test
    fun `en un movil apaisado la caratula seleccionada ocupa entre el 45 y el 55 por ciento del alto`() {
        for (s in listOf(phone, phoneTall)) for (hints in listOf(false, true)) {
            val f = layout(s, hints = hints).frame
            val share = f.tileHeight / s.h
            assertTrue("${s.name} hints=$hints: ${f.tileHeight} dp = $share", share in 0.45f..0.55f)
        }
    }

    @Test
    fun `la caratula crece respecto a la de antes en el movil de la captura`() {
        // Antes: ~112 dp (150×225 px a 2,0) en 360 dp de alto.
        assertTrue(layout(phone).frame.tileHeight > 160f)
    }

    @Test
    fun `en tableta y TV la caratula tiene tope`() {
        for (s in listOf(tablet, tabletSmall, tv)) {
            val f = layout(s).frame
            assertTrue("${s.name}: ${f.tileHeight}", f.tileHeight <= ClassicLayout.COVER_MAX + eps)
            val icons = layout(s, library = true).frame
            assertTrue("${s.name} iconos: ${icons.tileHeight}", icons.tileHeight <= ClassicLayout.SQUARE_MAX + eps)
        }
        // La tableta alta no pasa del 36–40 % del alto: el hero tiene mucho que enseñar.
        assertTrue(layout(tablet).frame.tileHeight / tablet.h <= 0.40f)
    }

    @Test
    fun `en vertical la caratula la limita el ancho y deja sitio al hero`() {
        val f = layout(portrait).frame
        assertEquals(ClassicLayout.portraitTileWidth(portrait.w - 36f, square = false), f.tileWidth, eps)
        assertTrue("alto ${f.tileHeight}", f.tileHeight / portrait.h in 0.18f..0.30f)
        assertTrue("hero ${f.heroHeight}", f.heroHeight >= portrait.h * 0.55f)
    }

    @Test
    fun `la caratula conserva su proporcion`() {
        for (s in all) {
            val f = layout(s).frame
            assertEquals(s.name, 2f / 3f, f.tileWidth / f.tileHeight, 1e-4f)
            val icons = layout(s, library = true).frame
            assertEquals(s.name, 1f, icons.tileWidth / icons.tileHeight, 1e-4f)
        }
    }

    @Test
    fun `una ventana diminuta no deja la card en negativo`() {
        val f = ClassicLayout.frame(300f, 200f, 2f / 3f, hintsVisible = true, labelBlock = 18f, topInset = 30f, heroMin = 150f)
        assertTrue(f.tileHeight >= 0f)
        assertEquals(200f, f.heroHeight + f.shelfHeight, eps)
    }

    /* ── Nombres en una sola línea ───────────────────────────── */

    @Test
    fun `todos los nombres van en la misma linea y nada los pisa`() {
        for (s in all) {
            val f = layout(s).frame
            val labelTop = f.labelTop
            // La línea de los nombres no depende de la selección.
            assertEquals(labelTop, f.tileTop + f.tileHeight + ShelfLayout.LABEL_GAP, eps)
            // Ni la seleccionada (subida) ni las vecinas (encogidas desde el pie) pasan de ella.
            assertTrue(ClassicLayout.coverBottom(f, selected = true) <= labelTop - ShelfLayout.LABEL_GAP + eps)
            assertTrue(ClassicLayout.coverBottom(f, selected = false) <= labelTop - ShelfLayout.LABEL_GAP + eps)
            // La subida y el halo caben sobre la card.
            assertTrue(f.tileTop >= ClassicLayout.LIFT + ClassicLayout.GLOW_TOP - eps)
        }
    }

    @Test
    fun `las vecinas se ven algo mas pequenas que la seleccionada`() {
        val f = layout(phone).frame
        val sel = ClassicLayout.visibleHeight(f, selected = true)
        val other = ClassicLayout.visibleHeight(f, selected = false)
        assertTrue(other < sel)
        assertEquals(ClassicLayout.NEIGHBOR_SCALE, other / sel, 1e-4f)
        // La seleccionada a escala 1 cabe en su caja: no invade a sus vecinas de lado.
        assertEquals(f.tileWidth, f.tileHeight * 2f / 3f, eps)
    }

    /* ── Compactación ────────────────────────────────────────── */

    private val block = ClassicLayout.Block(chips = 24f, mergedChips = 17f, logoMax = 80f, secondary = 18f, stats = 30f)

    @Test
    fun `se compacta en el orden pedido`() {
        val b = block
        val full = b.chips + b.logoMax + b.secondary + b.stats
        assertEquals(ClassicLayout.Step.Full, ClassicLayout.heroPlan(full, b).step)
        // 1. Fuera la pista.
        val p1 = ClassicLayout.heroPlan(full - 1f, b)
        assertEquals(ClassicLayout.Step.NoSecondary, p1.step)
        assertFalse(p1.showSecondary)
        assertEquals(b.logoMax, p1.logoHeight, eps)
        // 2. El logo encoge, con las fichas en píldora y la ficha de la carpeta en el hero.
        val p2 = ClassicLayout.heroPlan(b.chips + 60f + b.stats, b)
        assertEquals(ClassicLayout.Step.SmallLogo, p2.step)
        assertTrue(p2.logoHeight < b.logoMax)
        assertFalse(p2.mergedChips)
        assertFalse(p2.statsInBar)
        // 3. Las fichas en una línea.
        val p3 = ClassicLayout.heroPlan(b.mergedChips + b.logoMin + b.stats, b)
        assertEquals(ClassicLayout.Step.MergedChips, p3.step)
        assertTrue(p3.mergedChips)
        assertFalse(p3.statsInBar)
        // 4. La ficha de la carpeta sube a la barra.
        val p4 = ClassicLayout.heroPlan(b.mergedChips + b.logoMin, b)
        assertEquals(ClassicLayout.Step.StatsInBar, p4.step)
        assertTrue(p4.statsInBar)
        assertTrue(p4.mergedChips)
    }

    @Test
    fun `menos sitio nunca deshace un paso de compactacion`() {
        var last = ClassicLayout.Step.Full
        var room = 200f
        while (room >= 0f) {
            val p = ClassicLayout.heroPlan(room, block)
            assertTrue("$room: ${p.step} tras $last", p.step.ordinal >= last.ordinal)
            assertTrue("$room: el bloque mide ${p.height}", p.height <= room + eps || p.logoHeight == 0f)
            last = p.step
            room -= 0.5f
        }
    }

    @Test
    fun `el logo encoge hasta su fraccion antes de fundir fichas`() {
        assertEquals(48f, ClassicLayout.logoMinFor(80f), eps)
        assertEquals(ClassicLayout.LOGO_MIN, ClassicLayout.logoMinFor(30f), eps)
        assertEquals(20f, ClassicLayout.logoMinFor(20f), eps)
    }

    @Test
    fun `en el movil de la captura la ficha de la carpeta sube a la barra y el logo se queda grande`() {
        val c = layout(phone)
        assertTrue(c.plan.statsInBar)
        assertTrue(c.plan.mergedChips)
        assertFalse("sin sitio, la pista se va", c.plan.showSecondary)
        assertTrue("logo de ${c.plan.logoHeight} dp", c.plan.logoHeight >= 48f)
    }

    @Test
    fun `en tableta no hace falta compactar nada y en TV basta con encoger algo el logo`() {
        assertEquals(ClassicLayout.Step.Full, layout(tablet).plan.step)
        assertEquals(ClassicLayout.Step.Full, layout(tabletSmall, synopsis = true).plan.step)
        // En la TV siempre hay mando (sin pista táctil) y su barra de pistas.
        val tvPlan = layout(tv, hints = true, touch = false).plan
        assertFalse(tvPlan.mergedChips)
        assertFalse(tvPlan.statsInBar)
        assertTrue(tvPlan.step.ordinal <= ClassicLayout.Step.SmallLogo.ordinal)
    }

    @Test
    fun `la biblioteca no tiene ficha de carpeta que subir`() {
        for (s in all) assertFalse(s.name, layout(s, library = true).plan.statsInBar)
    }

    @Test
    fun `la pista tactil nunca va con mando`() {
        assertTrue(ClassicLayout.showTouchHint(gamepadPresent = false, padActive = false))
        assertFalse(ClassicLayout.showTouchHint(gamepadPresent = true, padActive = false))
        assertFalse(ClassicLayout.showTouchHint(gamepadPresent = false, padActive = true))
    }

    /* ── A lo ancho ──────────────────────────────────────────── */

    @Test
    fun `la fila llena el ancho con cards enteras y media asomando`() {
        for (s in all) {
            val f = layout(s).frame
            val row = s.w - 2 * (if (s.landscape) 22f else 18f)
            val gap = ClassicLayout.spacing(row, f.tileWidth)
            assertTrue("${s.name}: $gap", gap in ClassicLayout.MIN_GAP..ClassicLayout.MAX_GAP)
            if (gap < ClassicLayout.MAX_GAP) {
                // k cards + k huecos + media card = la fila.
                val k = Math.round((row - ClassicLayout.PEEK * f.tileWidth) / (f.tileWidth + gap))
                assertEquals(s.name, row, k * (f.tileWidth + gap) + ClassicLayout.PEEK * f.tileWidth, 0.5f)
            }
        }
        // El móvil de la captura: antes 12 dp fijos y la fila en la mitad izquierda.
        val f = layout(phone).frame
        assertTrue(ClassicLayout.spacing(800f - 44f, f.tileWidth) > 12f)
    }

    @Test
    fun `aire minimo cuando no cabe ni una card`() {
        assertEquals(ClassicLayout.MIN_GAP, ClassicLayout.spacing(100f, 120f), eps)
        assertEquals(ClassicLayout.MIN_GAP, ClassicLayout.spacing(100f, 0f), eps)
    }

    @Test
    fun `la fila deja una card delante de la seleccionada`() {
        assertEquals(0, ClassicLayout.anchorIndex(0))
        assertEquals(0, ClassicLayout.anchorIndex(1))
        assertEquals(4, ClassicLayout.anchorIndex(5))
    }

    /* ── Medidas de las piezas ───────────────────────────────── */

    @Test
    fun `las piezas crecen con la escala de letra y las fichas fundidas son mas bajas`() {
        for (l in listOf(true, false)) {
            assertTrue(ClassicLayout.chipsHeight(1.3f, l) > ClassicLayout.chipsHeight(1f, l))
            assertTrue(ClassicLayout.mergedChipsHeight(1f, l) < ClassicLayout.chipsHeight(1f, l))
            assertTrue(ClassicLayout.synopsisHeight(1f, l) > ClassicLayout.hintHeight(1f, l))
        }
    }

    @Test
    fun `el titular sin logo cabe en su caja`() {
        val sp = ClassicLayout.titleSpFor(44f, 1f, maxSp = 96f)
        assertTrue(sp * ClassicLayout.TITLE_LINE <= 44f + eps)
        assertEquals(50f, ClassicLayout.titleSpFor(400f, 1f, maxSp = 50f), eps)
    }
}
