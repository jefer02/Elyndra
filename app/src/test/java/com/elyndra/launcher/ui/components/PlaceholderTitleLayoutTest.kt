package com.elyndra.launcher.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderTitleLayoutTest {

    private val eps = 1e-3f

    @Test
    fun `the title size grows with its box, within its limits`() {
        // La carátula enfocada de un móvil (~202 × 289 dp) y una vecina pequeña de tableta.
        assertEquals(PlaceholderTitleLayout.MAX_SP, PlaceholderTitleLayout.maxSp(260f, icon = false), eps)
        assertEquals(202f * PlaceholderTitleLayout.WIDTH_SHARE, PlaceholderTitleLayout.maxSp(202f, icon = false), eps)
        assertEquals(PlaceholderTitleLayout.MIN_SP, PlaceholderTitleLayout.maxSp(40f, icon = false), eps)
        assertTrue(PlaceholderTitleLayout.maxSp(202f, icon = true) < PlaceholderTitleLayout.maxSp(202f, icon = false))
    }

    @Test
    fun `the title stays inside its box, away from the badges at the top`() {
        for ((w, h) in listOf(202f to 289f, 133f to 200f, 100f to 150f)) for (icon in listOf(false, true)) {
            val budget = PlaceholderTitleLayout.heightBudget(w, h, icon)
            // Nunca más que la caja sin sus márgenes, y deja libre la franja de arriba (la extensión y "sin nombre").
            assertTrue(budget <= h - 2f * w * PlaceholderTitleLayout.PAD_Y)
            assertTrue("$w×$h $icon", budget <= h * 0.62f)
            assertTrue(PlaceholderTitleLayout.textWidth(w) < w)
        }
        assertEquals(4, PlaceholderTitleLayout.maxLines(icon = false))
        assertEquals(3, PlaceholderTitleLayout.maxLines(icon = true))
    }
}
