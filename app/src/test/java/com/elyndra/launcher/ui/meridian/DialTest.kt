package com.elyndra.launcher.ui.meridian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialTest {

    private val density = 2f

    /** Las marcas pintadas a la vista con la rueda en [position] y el dial hasta [extent] px a cada lado. */
    private fun drawn(scale: DialScale, position: Float, extent: Float): List<Int> {
        val s = scale.spacing * density
        val out = ArrayList<Int>()
        var i = scale.first(position, extent, s)
        val last = scale.last(position, extent, s)
        while (i <= last) {
            out += i
            i += scale.stride
        }
        return out
    }

    @Test
    fun `no items hide the dial`() {
        assertNull(DialScale.of(0))
        assertNull(DialScale.of(-3))
    }

    @Test
    fun `one and three items get a clean short arc around the notch`() {
        val one = DialScale.of(1)!!
        val span = FloatArray(2)
        val s = one.spacing * density
        one.span(0f, s, 44f, 400f, 400f, span)
        assertEquals(-44f, span[0], 1e-4f)
        assertEquals(44f, span[1], 1e-4f)
        assertEquals(listOf(0), drawn(one, 0f, 400f))

        val three = DialScale.of(3)!!
        assertEquals(1, three.stride)
        three.span(1f, s, 44f, 400f, 400f, span)
        assertEquals(-(s + 44f), span[0], 1e-4f)
        assertEquals(s + 44f, span[1], 1e-4f)
        assertEquals(listOf(0, 1, 2), drawn(three, 1f, 400f))
        // En la primera, el arco solo baja; nunca pasa de los cantos que se le dan.
        three.span(0f, s, 44f, 20f, 400f, span)
        assertEquals(-20f, span[0], 1e-4f)
        assertEquals(2 * s + 44f, span[1], 1e-4f)
    }

    @Test
    fun `forty items get one tick each with majors every five`() {
        val d = DialScale.of(40)!!
        assertEquals(1, d.stride)
        assertTrue(d.isMajor(0) && d.isMajor(5) && !d.isMajor(3))
        val ticks = drawn(d, 20f, 228f * density)
        assertEquals(ticks, ticks.sorted())
        assertTrue(ticks.all { it in 0 until 40 })
        assertTrue(20 in ticks)
    }

    @Test
    fun `long lists are decimated so 500 and more items stay clean`() {
        for (count in listOf(500, 600, 601, 5_000, 50_000)) {
            val d = DialScale.of(count)!!
            assertTrue("$count: ${d.stride}", d.stride > 1)
            // Lo más juntas que llegan a verse dos marcas pintadas.
            assertTrue("$count", d.spacing * d.stride >= DialScale.MIN_DRAWN_GAP)
            for (p in listOf(0f, 1.5f, count / 2f + 0.3f, count - 1f)) {
                val extent = 230f * density
                val ticks = drawn(d, p, extent)
                assertTrue(ticks.all { it in 0 until count && it % d.stride == 0 })
                // Las que se pintan caben de sobra en el dial: unas pocas decenas, no cientos.
                assertTrue("$count@$p: ${ticks.size}", ticks.size <= (2 * extent / (d.spacing * density * d.stride)).toInt() + 2)
            }
        }
        // Y en todas las escalas, las pintadas nunca quedan más juntas que el mínimo.
        for (count in listOf(1, 3, 24, 25, 40, 120, 121)) {
            val d = DialScale.of(count)!!
            assertTrue("$count", d.spacing * d.stride >= DialScale.MIN_DRAWN_GAP)
        }
    }

    @Test
    fun `ticks roll with the list and the focus notch lights the one passing through it`() {
        val d = DialScale.of(40)!!
        val s = d.spacing * density
        // Con la rueda en 7, la marca 7 está en la muesca; a 7,5, a media marca.
        assertEquals(0f, (7 - 7f) * s, 0f)
        assertEquals(1f, DialScale.lit(0f, s), 0f)
        assertEquals(0f, DialScale.lit(s, s), 0f)
        assertTrue(DialScale.lit(s * 0.25f, s) in 0.1f..0.99f)
        assertEquals(1f, DialScale.endFade(0f), 0f)
        assertEquals(1f, DialScale.endFade(0.5f), 0f)
        assertEquals(0f, DialScale.endFade(1f), 0f)
    }

    @Test
    fun `the counter keeps the same digits for 1 to 4 digit totals`() {
        assertEquals(2, DialCounter.digits(1))
        assertEquals(2, DialCounter.digits(9))
        assertEquals(2, DialCounter.digits(99))
        assertEquals(3, DialCounter.digits(100))
        assertEquals(3, DialCounter.digits(999))
        assertEquals(4, DialCounter.digits(1000))
        assertEquals(4, DialCounter.digits(9999))
        for (total in listOf(1, 7, 42, 100, 512, 1000, 9999)) {
            val width = DialCounter.total(total).length
            for (i in listOf(0, total / 2, total - 1)) assertEquals("$i/$total", width, DialCounter.current(i, total).length)
        }
        assertEquals("07", DialCounter.current(6, 42))
        assertEquals("42", DialCounter.total(42))
        assertEquals("007", DialCounter.current(6, 120))
        assertEquals("0001", DialCounter.current(0, 2048))
        assertEquals("42", DialCounter.current(99, 42))
    }

    @Test
    fun `each digit rolls in the direction of the change, odometer style`() {
        assertEquals(1, DialCounter.digitAt(17, 2, 0))
        assertEquals(7, DialCounter.digitAt(17, 2, 1))
        assertEquals(0, DialCounter.digitAt(17, 4, 0))
        assertEquals(7, DialCounter.digitAt(1234567, 4, 3))
        // 09 → 10: las dos cifras suben; 10 → 09: bajan; 11 → 12: solo la de las unidades.
        assertEquals(1, DialCounter.direction(9, 10, 2, 0))
        assertEquals(1, DialCounter.direction(9, 10, 2, 1))
        assertEquals(-1, DialCounter.direction(10, 9, 2, 0))
        assertEquals(0, DialCounter.direction(11, 12, 2, 0))
        assertEquals(1, DialCounter.direction(11, 12, 2, 1))
        // 0999 → 1000 en cuatro cifras: todas ruedan.
        for (c in 0..3) assertEquals(1, DialCounter.direction(999, 1000, 4, c))
    }
}
