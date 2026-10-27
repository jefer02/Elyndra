package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.theme.PortraitHero
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitHeroTest {

    @Test
    fun `the portrait title shrinks from the landscape size and the logo gets a height cap`() {
        // Móvil (376 dp de ancho útil): menos que el titular de siempre (56–112 sp).
        val phone = PortraitHero.titleMaxSp(376f, base = 96f)
        assertTrue(phone < 56f)
        assertTrue(phone >= PortraitHero.TITLE_MIN_SP)
        // Tableta: más, sin pasar del tope.
        assertTrue(PortraitHero.titleMaxSp(760f, base = 112f) <= PortraitHero.TITLE_MAX_SP)
        // Nunca más que el de siempre.
        assertEquals(40f, PortraitHero.titleMaxSp(760f, base = 40f), 0f)
        // Logo: como mucho el 10 % del alto (y 120 dp).
        assertEquals(892f * PortraitHero.LOGO_MAX_FRACTION, PortraitHero.logoMaxHeight(892f, base = 179f), 1e-3f)
        assertEquals(PortraitHero.LOGO_MAX_DP, PortraitHero.logoMaxHeight(1400f, base = 220f), 0f)
        assertEquals(70f, PortraitHero.logoMaxHeight(892f, base = 70f), 0f)
    }
}
