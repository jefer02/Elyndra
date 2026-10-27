package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.DotTabsLayout.DOT_SLOT
import com.elyndra.launcher.ui.DotTabsLayout.GAP
import com.elyndra.launcher.ui.DotTabsLayout.INSET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotTabsLayoutTest {

    private val eps = 1e-3f

    /** Anchos de rótulo (dp) aproximados en Poppins SemiBold 11 sp, del más corto al más largo de los seis idiomas. */
    private val labels = mapOf(
        "es" to floatArrayOf(30f, 46f, 54f, 62f),
        "en" to floatArrayOf(16f, 46f, 52f, 78f),
        "pt" to floatArrayOf(28f, 46f, 52f, 56f),
        "fr" to floatArrayOf(26f, 46f, 52f, 54f),
        "de" to floatArrayOf(24f, 46f, 58f, 70f),
        "ja" to floatArrayOf(36f, 46f, 36f, 48f),
    )

    private fun pills(widths: FloatArray) = FloatArray(widths.size) { DotTabsLayout.pillWidth(widths[it]) }

    @Test
    fun `la pildora cabe el rotulo entero hasta su tope y se corta solo por encima`() {
        var w = 0f
        while (w <= DotTabsLayout.LABEL_MAX) {
            val pill = DotTabsLayout.pillWidth(w)
            assertTrue("rótulo $w cabe", pill - 2 * DotTabsLayout.LABEL_PAD >= w - eps)
            w += 1f
        }
        assertEquals(DotTabsLayout.PILL_MAX, DotTabsLayout.pillWidth(400f), eps)
        assertEquals(DotTabsLayout.PILL_MIN, DotTabsLayout.pillWidth(2f), eps)
        // El icono cuenta.
        assertEquals(DotTabsLayout.pillWidth(40f) + 20f, DotTabsLayout.pillWidth(40f, iconWidth = 20f), eps)
    }

    @Test
    fun `en reposo los huecos van seguidos y la cápsula mide lo justo`() {
        val p = floatArrayOf(60f, 80f, 100f)
        val o = DotTabsLayout.offsets(p, DotTabsLayout.rest(3, 1))
        assertEquals(INSET, o[0], eps)
        assertEquals(INSET + DOT_SLOT + GAP, o[1], eps)
        assertEquals(o[1] + 80f + GAP, o[2], eps)
        assertEquals(o[2] + DOT_SLOT + INSET, o[3], eps)
        assertEquals(o[3], DotTabsLayout.totalWidth(p, 1), eps)
    }

    @Test
    fun `para cualquier seleccion y cualquier idioma la pildora cae en su hueco y nada se pisa`() {
        for ((lang, widths) in labels) for (n in 3..4) {
            val p = pills(widths.copyOf(n))
            for (sel in 0 until n) {
                val e = DotTabsLayout.rest(n, sel)
                val o = DotTabsLayout.offsets(p, e)
                val (l, r) = DotTabsLayout.pillTarget(p, sel)
                assertEquals("$lang n=$n sel=$sel izquierda", o[sel], l, eps)
                assertEquals("$lang n=$n sel=$sel ancho", p[sel], r - l, eps)
                for (i in 0 until n - 1) {
                    val width = DotTabsLayout.slotWidth(p[i], e[i])
                    assertEquals("$lang hueco $i seguido", o[i] + width + GAP, o[i + 1], eps)
                }
                val expected = 2 * INSET + (n - 1) * (DOT_SLOT + GAP) + p[sel]
                assertEquals("$lang n=$n sel=$sel total", expected, o[n], eps)
                assertTrue("$lang centro del punto dentro de su hueco", DotTabsLayout.slotCenter(p, e, (sel + 1) % n) > 0f)
            }
        }
    }

    @Test
    fun `cada pestana tiene 48 dp de ancho tactil y entre todas cubren la capsula`() {
        for ((lang, widths) in labels) for (n in 3..4) for (sel in 0 until n) {
            val p = pills(widths.copyOf(n))
            val e = DotTabsLayout.rest(n, sel)
            val o = DotTabsLayout.offsets(p, e)
            var last = 0f
            for (i in 0 until n) {
                val (l, r) = DotTabsLayout.touchSpan(o, p, e, i)
                assertEquals("$lang seguidas", last, l, eps)
                assertTrue("$lang pestaña $i: ${r - l} dp", r - l >= DotTabsLayout.TOUCH - eps)
                last = r
            }
            assertEquals("$lang hasta el final", o[n], last, eps)
        }
    }

    @Test
    fun `a mitad del cambio la capsula mide entre los dos extremos`() {
        val p = pills(labels.getValue("de"))
        val a = DotTabsLayout.totalWidth(p, 0)
        val b = DotTabsLayout.totalWidth(p, 3)
        val e = floatArrayOf(0.5f, 0f, 0f, 0.5f)
        val mid = DotTabsLayout.offsets(p, e)[4]
        assertTrue(mid >= minOf(a, b) - eps && mid <= maxOf(a, b) + eps)
    }

    @Test
    fun `el canto que va delante tira mas fuerte`() {
        val (lRight, rRight) = DotTabsLayout.edgeStiffness(0, 2)
        assertTrue("hacia la derecha manda el canto derecho", rRight > lRight)
        val (lLeft, rLeft) = DotTabsLayout.edgeStiffness(2, 0)
        assertTrue("hacia la izquierda manda el izquierdo", lLeft > rLeft)
    }

    @Test
    fun `el punto y el rotulo viejo se reparten el cierre`() {
        assertEquals(1f, DotTabsLayout.dotScale(0f), eps)
        assertEquals(0f, DotTabsLayout.dotScale(1f), eps)
        assertEquals(1f, DotTabsLayout.fadingLabelAlpha(1f), eps)
        assertEquals(0f, DotTabsLayout.fadingLabelAlpha(0.5f), eps)
        assertEquals(0f, DotTabsLayout.fadingLabelAlpha(0f), eps)
    }

    @Test
    fun `en la barra el dock con cuatro secciones en aleman cabe junto al grupo sin llegar a Masha`() {
        // Ancho mínimo de la barra: márgenes (2 × 22), el botón de Masha (58 + 12)
        // y el grupo de la derecha: hora (~92), "Öffnen" (~86), buscar y Ajustes
        // (34 + 34) y sus huecos (4 × 8), más el dock, su hueco y el orden.
        val dock = DotTabsLayout.totalWidth(pills(labels.getValue("de")), 3)
        val needed = 44f + 70f + 92f + 86f + 34f + 34f + 4 * 8f + dock + 8f + 44f
        assertTrue("hacen falta $needed dp", needed <= DockPlacement.BAR_MIN_WIDTH)
    }

    @Test
    fun `el dock va en la barra solo en ventanas anchas`() {
        assertTrue(DockPlacement.inBar(892f))
        assertTrue(DockPlacement.inBar(1280f))
        assertTrue(!DockPlacement.inBar(412f))
        assertTrue(!DockPlacement.inBar(600f))
        assertTrue(!DockPlacement.inBar(700f))
        assertTrue(DockPlacement.inBar(800f))
    }

    @Test
    fun `en la costura el dock no llega al bloque de titulo`() {
        assertEquals(12f, DockPlacement.seamRise(18f), eps)
        assertEquals(2f, DockPlacement.seamRise(8f), eps)
        assertEquals(0f, DockPlacement.seamRise(4f), eps)
    }

    @Test
    fun `con la seccion vacia el dock va entero dentro del hero y con juegos a caballo de la costura`() {
        assertTrue(DockPlacement.insideHero(empty = true, inBar = false))
        assertTrue(!DockPlacement.insideHero(empty = false, inBar = false))
        // En la barra no hay costura: ni vacía se mueve.
        assertTrue(!DockPlacement.insideHero(empty = true, inBar = true))

        val dock = DotTabsLayout.HEIGHT
        for (hero in listOf(240f, 420f, 560f)) for (pad in listOf(8f, 18f)) {
            val rise = DockPlacement.seamRise(pad)
            val inside = DockPlacement.top(hero, dock, inHero = true, seamRise = rise)
            assertTrue("hero $hero: dentro", inside >= 0f && inside + dock <= hero + eps)
            assertEquals("hero $hero: a HERO_GAP del canto", hero - DockPlacement.HERO_GAP, inside + dock, eps)
            // El bloque de título sube lo que ocupa el dock más su aire: no se pisan.
            assertTrue(hero - DockPlacement.heroLift(dock) <= inside + eps)

            val seam = DockPlacement.top(hero, dock, inHero = false, seamRise = rise)
            assertTrue("hero $hero pad $pad: a caballo", seam < hero && seam + dock > hero)
            assertEquals(hero - rise, seam, eps)
        }
    }

    /* ── Encajar en un ancho dado (fit) ── */

    /** El dock con [fit] ya aplicado: píldoras recortadas a su tope, puntos a su hueco. */
    private fun fittedWidth(natural: FloatArray, maxWidth: Float): Float {
        val fit = DotTabsLayout.fit(natural, maxWidth)
        val capped = FloatArray(natural.size) { minOf(natural[it], fit.pillCap) }
        // Se mide con la píldora más ancha elegida: el dock no cambia de tamaño al cambiar de sección.
        return capped.indices.maxOf { DotTabsLayout.totalWidth(capped, it, fit.dot) }
    }

    @Test
    fun `con sitio de sobra el dock no cambia`() {
        val natural = pills(labels.getValue("es"))
        val fit = DotTabsLayout.fit(natural, 1_000f)
        assertEquals(DOT_SLOT, fit.dot, eps)
        assertEquals(DotTabsLayout.PILL_MAX, fit.pillCap, eps)
        assertEquals(DotTabsLayout.PILL_MAX, DotTabsLayout.fit(natural, Float.POSITIVE_INFINITY).pillCap, eps)
    }

    @Test
    fun `con poco sitio primero se recorta el rotulo y despues se juntan los puntos`() {
        val natural = floatArrayOf(60f, 140f, 140f)
        val loose = DotTabsLayout.fit(natural, 2 * INSET + 2 * (DOT_SLOT + GAP) + 100f)
        assertEquals("los puntos no se tocan mientras el rótulo pueda bajar hasta PILL_FIT", DOT_SLOT, loose.dot, eps)
        assertEquals(100f, loose.pillCap, eps)
        // Los puntos ceden (hasta 38 dp) para que la píldora conserve PILL_FIT.
        val tight = DotTabsLayout.fit(natural, 2 * INSET + 2 * (38f + GAP) + DotTabsLayout.PILL_FIT)
        assertEquals(38f, tight.dot, eps)
        assertEquals(DotTabsLayout.PILL_FIT, tight.pillCap, eps)
        // Con los puntos ya en su mínimo, lo que falte sale de la píldora.
        val tighter = DotTabsLayout.fit(natural, 2 * INSET + 2 * (DotTabsLayout.DOT_SLOT_MIN + GAP) + 60f)
        assertEquals(DotTabsLayout.DOT_SLOT_MIN, tighter.dot, eps)
        assertEquals(60f, tighter.pillCap, eps)
    }

    @Test
    fun `desde su minimo el dock nunca mide mas de lo que le dan`() {
        for ((lang, widths) in labels) {
            for (n in 2..widths.size) {
                val natural = pills(widths.copyOf(n))
                var w = DotTabsLayout.minWidth(n)
                while (w <= 400f) {
                    assertTrue("$lang n=$n en $w dp", fittedWidth(natural, w) <= w + eps)
                    val fit = DotTabsLayout.fit(natural, w)
                    assertTrue(fit.dot in DotTabsLayout.DOT_SLOT_MIN..DOT_SLOT)
                    assertTrue(fit.pillCap >= DotTabsLayout.PILL_MIN)
                    w += 1f
                }
            }
        }
    }

    @Test
    fun `el sitio del rotulo descuenta margenes e icono`() {
        assertEquals(DotTabsLayout.LABEL_MAX, DotTabsLayout.labelRoom(DotTabsLayout.PILL_MAX), eps)
        assertEquals(88f - 24f - 16f, DotTabsLayout.labelRoom(88f, 16f), eps)
        assertEquals(0f, DotTabsLayout.labelRoom(10f, 16f), eps)
    }

    /* ── Dock + botón de orden ── */

    @Test
    fun `el boton de orden va siempre tras el dock con su aire`() {
        val tabs = 150f
        assertEquals(tabs + SectionDockLayout.SORT_GAP, SectionDockLayout.sortX(tabs), eps)
        assertEquals(tabs + SectionDockLayout.SORT_GAP + SectionDockLayout.SORT_WIDTH, SectionDockLayout.width(tabs), eps)
        assertEquals(200f - SectionDockLayout.SORT_GAP - SectionDockLayout.SORT_WIDTH, SectionDockLayout.tabsMaxWidth(200f), eps)
        assertEquals(0f, SectionDockLayout.tabsMaxWidth(20f), eps)
    }

    /**
     * Móvil apaisado (~800 × 360 dp, Meridian): la columna de la rueda mide
     * 200 dp. Antes el dock solo tenía 162 dp y, sin respetarlos, el botón de
     * orden acababa encima de la píldora. El orden ya va en la barra de la
     * derecha: el dock, solo, cabe en los seis idiomas sin llegar al bloque
     * del hero ni pasar del velo.
     */
    @Test
    fun `movil apaisado el dock cabe solo en la cabecera de la rueda`() {
        val rail = com.elyndra.launcher.ui.meridian.MeridianGeometry.slimRailWidth(800f, 360f, 1f, compact = true)
        val header = com.elyndra.launcher.ui.meridian.RailHeaderLayout
        val old = rail - header.START - header.END
        assertTrue("el caso del fallo: antes no cabía", SectionDockLayout.width(DotTabsLayout.totalWidth(pills(labels.getValue("es").copyOf(3)), 2)) > old)
        val heroLeft = rail + com.elyndra.launcher.ui.meridian.MeridianGeometry.HERO_GAP
        val veilEnd = com.elyndra.launcher.ui.meridian.MeridianScrims.clearAt(rail, 800f)
        for ((lang, widths) in labels) {
            for (n in 2..widths.size) {
                val maxDock = header.dockMaxWidth(rail)
                val dock = fittedWidth(pills(widths.copyOf(n)), maxDock)
                assertTrue("$lang n=$n", dock <= maxDock + eps)
                val right = header.dockX(rail, dock) + dock
                assertTrue("$lang n=$n no llega al bloque del hero", right < heroLeft)
                assertTrue("$lang n=$n tiene velo detrás", right + 16f <= veilEnd)
            }
        }
    }

    /** Móvil en vertical (~393 dp, Clásico): el dock va en la costura con todo el ancho; entero. */
    @Test
    fun `movil vertical el dock va entero en la costura`() {
        val seam = 393f - 2 * 18f
        for ((lang, widths) in labels) {
            val natural = pills(widths)
            val fit = DotTabsLayout.fit(natural, SectionDockLayout.tabsMaxWidth(seam))
            assertEquals(lang, DOT_SLOT, fit.dot, eps)
            assertEquals(lang, DotTabsLayout.PILL_MAX, fit.pillCap, eps)
        }
    }

    /** Tableta apaisada (Legion Y700, ~1280 × 800 dp): la columna es ancha y el dock no se toca. */
    @Test
    fun `tableta apaisada el dock no cambia`() {
        val rail = com.elyndra.launcher.ui.meridian.MeridianGeometry.slimRailWidth(1280f, 800f, 1f, compact = false)
        val header = com.elyndra.launcher.ui.meridian.RailHeaderLayout
        for ((lang, widths) in labels) {
            val natural = pills(widths.copyOf(3))
            // Solo el dock: el orden va en la barra de la derecha.
            val fit = DotTabsLayout.fit(natural, header.dockMaxWidth(rail))
            assertEquals(lang, DOT_SLOT, fit.dot, eps)
            assertEquals(lang, DotTabsLayout.PILL_MAX, fit.pillCap, eps)
            // Pegado al canto de la columna, como antes (no asoma sobre el hueco del hero).
            val dock = fittedWidth(natural, Float.POSITIVE_INFINITY)
            assertTrue(lang, header.dockX(rail, dock) + dock <= rail - header.END + eps)
        }
    }
}
