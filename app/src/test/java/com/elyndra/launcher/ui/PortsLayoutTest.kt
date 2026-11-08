package com.elyndra.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortsLayoutTest {

    /** Móvil vertical, estrecho, apaisado (Tecno Spark 30C), tableta (Legion Y700) en los dos sentidos. */
    private val screens = listOf(412f to 892f, 360f to 780f, 853f to 384f, 1280f to 800f, 800f to 1280f)

    @Test fun `capsules stay within bounds and the next one always peeks`() {
        for ((w, _) in screens) {
            val c = PortsLayout.capsuleWidth(w)
            assertTrue("$w: $c", c in PortsLayout.CAPSULE_MIN..PortsLayout.CAPSULE_MAX)
            val room = w - PortsLayout.pad(w)
            val shown = room / (c + PortsLayout.ROW_GAP)
            // Nunca un número exacto de cápsulas: siempre asoma la siguiente.
            assertTrue("$w: $shown", shown > 1.5f)
            assertTrue("$w: $shown", shown % 1f > 0.1f)
        }
    }

    @Test fun `capsule height follows the header aspect plus the band`() {
        val c = 214f
        assertEquals(100f, PortsLayout.capsuleImageHeight(c), 0.01f)
        assertEquals(144f, PortsLayout.capsuleHeight(c), 0.01f)
    }

    @Test fun `the hero leaves room for the first row in landscape`() {
        val (w, h) = 853f to 384f
        val hero = PortsLayout.heroHeight(w, h)
        assertTrue(hero + PortsLayout.capsuleHeight(PortsLayout.capsuleWidth(w)) * 0.5f < h)
        for ((sw, sh) in screens) assertTrue(PortsLayout.heroHeight(sw, sh) in 150f..340f)
    }

    @Test fun `detail goes two columns only in roomy landscape`() {
        assertTrue(PortsLayout.detailTwoColumns(853f, 384f))
        assertTrue(PortsLayout.detailTwoColumns(1280f, 800f))
        assertFalse(PortsLayout.detailTwoColumns(412f, 892f))
        assertFalse(PortsLayout.detailTwoColumns(800f, 1280f))
        assertFalse(PortsLayout.heroSideInfo(412f))
        assertTrue(PortsLayout.heroSideInfo(853f))
    }
}
