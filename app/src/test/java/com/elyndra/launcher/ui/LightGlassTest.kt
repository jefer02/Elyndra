package com.elyndra.launcher.ui

import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.ui.theme.Frost
import com.elyndra.launcher.ui.theme.LightGlass
import com.elyndra.launcher.ui.theme.WheelGlass
import org.junit.Assert.assertTrue
import org.junit.Test

class LightGlassTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** La pieza de cristal claro directamente sobre [art] (sin velo detrás). */
    private fun glassOver(art: Int, fill: Float = LightGlass.FILL_ALPHA): Int = ColorMath.over(ColorMath.withAlpha(white, fill), art)

    @Test
    fun `dark ink on the light glass reads at 4,5 to 1 and the section dots at 3 to 1 over any art`() {
        for (art in listOf(black, white, 0xFF0B1A3A.toInt(), 0xFFE60012.toInt())) {
            val bg = glassOver(art)
            assertTrue(ColorMath.contrast(LightGlass.INK, bg) >= 4.5)
            assertTrue(ColorMath.contrast(ColorMath.over(LightGlass.DOT, bg), bg) >= 3.0)
        }
    }

    @Test
    fun `white dial marks with their thin shadow read over very light and very dark art`() {
        for (art in listOf(black, white, 0xFFFFE600.toInt(), 0xFF0B1A3A.toInt(), 0xFFF4B6D2.toInt())) {
            // Sobre arte claro manda la sombra; sobre oscuro, el blanco.
            val shadow = ColorMath.over(ColorMath.withAlpha(black, WheelGlass.MARK_SHADOW_ALPHA), art)
            val mark = ColorMath.over(ColorMath.withAlpha(white, WheelGlass.MARK_ALPHA), art)
            val best = maxOf(ColorMath.contrast(mark, art), ColorMath.contrast(mark, shadow))
            assertTrue("%08X".format(art), best >= 3.0)
        }
    }
}
