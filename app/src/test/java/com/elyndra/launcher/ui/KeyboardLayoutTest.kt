package com.elyndra.launcher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutTest {

    @Test
    fun `sin teclado nunca es compacta ni se pega arriba`() {
        assertFalse(KeyboardLayout.compact(imeVisible = false, landscape = true, availableHeightDp = 200f))
        assertFalse(KeyboardLayout.dockTop(imeVisible = false))
    }

    @Test
    fun `teclado abierto en horizontal es compacta`() {
        // Legion Y700 en horizontal: el teclado deja ~330 dp, pero aunque dejase más.
        assertTrue(KeyboardLayout.compact(imeVisible = true, landscape = true, availableHeightDp = 330f))
        assertTrue(KeyboardLayout.compact(imeVisible = true, landscape = true, availableHeightDp = 900f))
        assertTrue(KeyboardLayout.dockTop(imeVisible = true))
    }

    @Test
    fun `en vertical solo si queda poco alto`() {
        assertFalse(KeyboardLayout.compact(imeVisible = true, landscape = false, availableHeightDp = 600f))
        assertTrue(KeyboardLayout.compact(imeVisible = true, landscape = false, availableHeightDp = 380f))
    }
}
