package com.elyndra.launcher.ui.masha.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** El anillo del último segundo: lo más nuevo al final, sin perder el orden al dar la vuelta. */
class PcmRingTest {

    private fun shorts(vararg v: Int) = ShortArray(v.size) { v[it].toShort() }

    @Test
    fun lastReturnsTheNewestInOrder() {
        val r = PcmRing(8)
        r.write(shorts(1, 2, 3))
        r.write(shorts(4, 5))
        assertArrayEquals(shorts(3, 4, 5), r.last(3))
        assertArrayEquals(shorts(1, 2, 3, 4, 5), r.last(5))
    }

    @Test
    fun itWrapsAround() {
        val r = PcmRing(4)
        r.write(shorts(1, 2, 3))
        r.write(shorts(4, 5, 6))
        assertEquals(4, r.size)
        assertArrayEquals(shorts(3, 4, 5, 6), r.last(4))
    }

    @Test
    fun askingForMoreThanItHasGivesWhatThereIs() {
        val r = PcmRing(16)
        r.write(shorts(7, 8))
        assertArrayEquals(shorts(7, 8), r.last(100))
        assertArrayEquals(ShortArray(0), PcmRing(4).last(3))
    }

    @Test
    fun aWriteBiggerThanTheRingKeepsItsTail() {
        val r = PcmRing(3)
        r.write(shorts(1, 2, 3, 4, 5, 6, 7))
        assertArrayEquals(shorts(5, 6, 7), r.last(3))
    }

    @Test
    fun onlyTheFirstNSamplesOfTheBufferCount() {
        val r = PcmRing(4)
        r.write(shorts(1, 2, 9, 9), n = 2)
        assertArrayEquals(shorts(1, 2), r.last(4))
        r.clear()
        assertEquals(0, r.size)
    }
}
