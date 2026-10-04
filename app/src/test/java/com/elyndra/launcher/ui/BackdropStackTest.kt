package com.elyndra.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropStackTest {

    /** El velo que queda al componer los de [alphas] uno encima de otro. */
    private fun composite(vararg alphas: Float): Float = 1f - alphas.fold(1f) { acc, a -> acc * (1f - a) }

    @Test
    fun `sin capas no hay desenfoque`() {
        val s = BackdropStack()
        assertFalse(s.anyOpen)
        assertFalse(s.blurs())
    }

    @Test
    fun `dos capas apiladas desenfocan una sola vez y el velo solo se oscurece un poco`() {
        val s = BackdropStack()
        s.put("details", z = 1, open = true)
        s.put("identify", z = 3, open = true)
        assertTrue(s.blurs())
        assertEquals(0, s.depthOf("details"))
        assertEquals(1, s.depthOf("identify"))
        val base = BackdropLevels.layerAlpha(0, blur = true)
        val upper = BackdropLevels.layerAlpha(1, blur = true)
        assertEquals(BackdropLevels.BASE_BLUR, base, 1e-4f)
        // El conjunto queda un escalón más hondo, no el doble.
        assertEquals(BackdropLevels.BASE_BLUR + BackdropLevels.STEP, composite(base, upper), 1e-4f)
        assertTrue(upper < base)
    }

    @Test
    fun `el orden es el de dibujo, no el de apertura`() {
        val s = BackdropStack()
        s.put("dialog", z = 4, open = true)
        s.put("details", z = 1, open = true)
        assertEquals(0, s.depthOf("details"))
        assertEquals(1, s.depthOf("dialog"))
    }

    @Test
    fun `al cerrarse la de arriba se vuelve al nivel anterior`() {
        val s = BackdropStack()
        s.put("details", 1, true)
        s.put("identify", 3, true)
        // Sale: deja de pedir desenfoque, pero el de debajo lo sigue pidiendo.
        s.put("identify", 3, false)
        assertTrue(s.blurs())
        assertEquals(1, s.depthOf("identify"))
        s.remove("identify")
        assertEquals(1, s.size)
        assertEquals(0, s.depthOf("details"))
        assertEquals(-1, s.depthOf("identify"))
    }

    @Test
    fun `la que entra mientras otra sale pone ya el velo de abajo`() {
        val s = BackdropStack()
        s.put("menu", 0, true)
        s.put("menu", 0, false) // el menú se cierra al abrir el diálogo
        assertEquals(0, s.depthOrNext("dialog", 4))
        s.put("dialog", 4, true)
        assertEquals(0, s.depthOf("dialog"))
    }

    @Test
    fun `profundidad provisional antes de apuntarse`() {
        val s = BackdropStack()
        s.put("details", 1, true)
        assertEquals(1, s.depthOrNext("identify", 3))
        assertEquals(0, s.depthOrNext("menu", 0))
    }

    @Test
    fun `apuntarse otra vez no cambia el sitio`() {
        val s = BackdropStack()
        s.put("a", 1, true)
        s.put("b", 1, true)
        s.put("a", 1, true)
        assertEquals(0, s.depthOf("a"))
        assertEquals(1, s.depthOf("b"))
        assertEquals(2, s.size)
    }

    @Test
    fun `cuando sale la de abajo la de arriba baja de nivel`() {
        val s = BackdropStack()
        s.put("details", 1, true)
        s.put("dialog", 4, true)
        s.remove("details")
        assertEquals(0, s.depthOf("dialog"))
    }

    @Test
    fun `al cerrar la ultima ya no se pide desenfoque`() {
        val s = BackdropStack()
        s.put("dialog", 4, true)
        s.put("dialog", 4, false)
        assertFalse(s.anyOpen)
        assertFalse(s.blurs())
    }

    @Test
    fun `diálogos ligeros sin desenfoque si se apaga para ellos`() {
        val s = BackdropStack()
        s.put("confirm", 4, open = true, light = true)
        assertTrue(s.blurs(lightBlurs = true))
        assertFalse(s.blurs(lightBlurs = false))
        s.put("details", 1, open = true)
        assertTrue(s.blurs(lightBlurs = false))
    }

    @Test
    fun `sin desenfoque el velo de abajo es más hondo`() {
        assertEquals(BackdropLevels.BASE_NO_BLUR, BackdropLevels.layerAlpha(0, blur = false), 1e-4f)
        assertTrue(BackdropLevels.layerAlpha(0, blur = false) > BackdropLevels.layerAlpha(0, blur = true))
    }

    @Test
    fun `muchas capas no pasan del tope`() {
        val alphas = (0 until 8).map { BackdropLevels.layerAlpha(it, blur = true) }.toFloatArray()
        assertTrue(composite(*alphas) <= BackdropLevels.MAX + 1e-4f)
        assertEquals(0f, BackdropLevels.total(0, blur = true), 0f)
        assertEquals(0f, BackdropLevels.layerAlpha(-1, blur = true), 0f)
    }
}
