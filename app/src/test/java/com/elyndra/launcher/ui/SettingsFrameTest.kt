package com.elyndra.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFrameTest {

    @Test
    fun `rail is about a quarter of a landscape tablet`() {
        val w = SettingsFrame.railWidth(1280f)
        assertEquals(272f, w, 0.01f)
        assertTrue(w / 1280f in 0.18f..0.26f)
    }

    @Test
    fun `rail never takes the larger share, even at the wide threshold`() {
        for (width in listOf(600f, 640f, 700f, 800f, 960f)) {
            val rail = SettingsFrame.railWidth(width)
            assertTrue("$width → $rail", rail <= width * SettingsFrame.RAIL_MAX_SHARE + 0.01f)
            assertTrue("$width → $rail", width - rail > width / 2f)
        }
    }

    @Test
    fun `rail keeps its minimum on a small wide window`() {
        assertEquals(196f, SettingsFrame.railWidth(600f), 0.01f)
    }

    @Test
    fun `rail rows keep the touch target and grow with font scale`() {
        assertEquals(50f, SettingsFrame.railItemHeight(1f), 0.01f)
        assertTrue(SettingsFrame.railItemHeight(0.85f) >= SettingsFrame.MIN_TOUCH_DP)
        assertTrue(SettingsFrame.railItemHeight(1.5f) > SettingsFrame.railItemHeight(1f))
    }

    @Test
    fun `eight rail rows fit a landscape tablet with room to spare`() {
        val h = SettingsFrame.railItemHeight(1f)
        val total = SettingsFrame.highlightTop(8f, h)
        // Legion apaisada: ~800 dp de alto; título arriba (~60) y pistas abajo (~40).
        assertTrue(total < 800f - 100f)
    }

    @Test
    fun `highlight follows rows and gaps`() {
        assertEquals(0f, SettingsFrame.highlightTop(0f, 50f, 2f), 0.001f)
        assertEquals(156f, SettingsFrame.highlightTop(3f, 50f, 2f), 0.001f)
        assertEquals(26f, SettingsFrame.highlightTop(0.5f, 50f, 2f), 0.001f)
    }

    @Test
    fun `dense rows only in wide landscape`() {
        assertTrue(SettingsFrame.dense(1280f, 800f))
        assertFalse(SettingsFrame.dense(800f, 1280f))
        assertFalse(SettingsFrame.dense(560f, 360f))
        assertFalse(SettingsFrame.dense(360f, 780f))
    }
}
