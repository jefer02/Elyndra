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
}
