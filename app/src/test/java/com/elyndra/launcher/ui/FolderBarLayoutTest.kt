package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.FolderBarLayout.Fit
import com.elyndra.launcher.ui.meridian.MeridianGeometry
import com.elyndra.launcher.ui.meridian.MeridianMode
import com.elyndra.launcher.ui.meridian.MeridianTopRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderBarLayoutTest {

    private val eps = 1e-3f

    /** Rótulos (dp) aproximados en Poppins SemiBold 10 sp: "Eden Nightly" y uno larguísimo. */
    private val eden = 62f
    private val long = 260f

    /** La píldora de hora y batería entera (ver TopBarLayout.statusWidth). */
    private val status = TopBarLayout.statusWidth(62f, 28f, withPercent = true)

    /** El grupo de la derecha de Meridian en una ventana de [w] × [h] con carátulas. */
    private fun meridian(w: Float, h: Float): Float {
        val rail = MeridianGeometry.slimRailWidth(w, h, 2f / 3f, MeridianMode.compact(h))
        return MeridianTopRow.barMaxWidth(w, rail)
    }

    /** El hueco tras volver y "Abrir" en el Clásico (vertical): márgenes de 18, volver de 34 y "Abrir" con su rótulo. */
    private fun classic(w: Float): Float = w - 2 * 18f - TopBarLayout.BAR_HEIGHT - 8f - TopBarLayout.openWidth(30f, withLabel = true) - 8f

    @Test
    fun `on tablets and TV the folder header keeps the full emulator name`() {
        // 2560 × 1600 y 1280 × 800 (1280 × 800 dp), 2000 × 1200 (1000 × 600), tele 1920 × 1080 (960 × 540) y un móvil apaisado.
        for ((w, h) in listOf(1280f to 800f, 1000f to 600f, 960f to 540f, 915f to 412f)) {
            val available = meridian(w, h)
            val plan = FolderBarLayout.plan(available, eden, status)
            assertEquals("w=$w", Fit.Full, plan.fit)
            assertEquals(FolderBarLayout.chipWidth(eden), plan.chipWidth, eps)
            // Al nombre del sistema le queda al menos su mínimo.
            assertTrue("w=$w", FolderBarLayout.titleRoom(available, plan, status) >= FolderBarLayout.TITLE_MIN)
        }
    }

    @Test
    fun `a very long emulator name is capped instead of eating the system name`() {
        val available = meridian(1280f, 800f)
        val plan = FolderBarLayout.plan(available, long, status)
        assertEquals(Fit.Full, plan.fit)
        assertEquals(FolderBarLayout.CHIP_MAX, plan.chipWidth, eps)
    }

    @Test
    fun `when it gets tight the label shortens first and then becomes the icon`() {
        val full = FolderBarLayout.CHIP_MAX + FolderBarLayout.GAP + FolderBarLayout.TITLE_MIN
        assertEquals(Fit.Full, FolderBarLayout.plan(full, long, 0f).fit)
        val short = FolderBarLayout.plan(full - 40f, long, 0f)
        assertEquals(Fit.ShortLabel, short.fit)
        assertEquals(FolderBarLayout.CHIP_MAX - 40f, short.chipWidth, eps)
        val least = FolderBarLayout.chipWidth(FolderBarLayout.LABEL_MIN) + FolderBarLayout.GAP + FolderBarLayout.TITLE_MIN
        assertEquals(Fit.IconEmulator, FolderBarLayout.plan(least - 1f, eden, 0f).fit)
        assertEquals(FolderBarLayout.ICON_WIDTH, FolderBarLayout.plan(least - 1f, eden, 0f).chipWidth, eps)
        // Más ancho nunca compacta más.
        var prev = Fit.IconEmulator
        var a = 100f
        while (a < 600f) {
            val fit = FolderBarLayout.plan(a, long, status).fit
            assertTrue("a=$a", fit.ordinal <= prev.ordinal)
            prev = fit
            a += 4f
        }
    }

    @Test
    fun `in portrait the status pill stays whole and the emulator gives way`() {
        // Móvil en vertical (412 dp): sin la píldora, el nombre entero; con ella, el emulador en su icono.
        val available = classic(412f)
        assertEquals(Fit.Full, FolderBarLayout.plan(available, eden, 0f).fit)
        val tight = FolderBarLayout.plan(available, eden, status)
        assertEquals(Fit.IconEmulator, tight.fit)
        // Tableta en vertical (800 dp): cabe todo.
        assertEquals(Fit.Full, FolderBarLayout.plan(classic(800f), eden, status).fit)
    }

    @Test
    fun `a short system name keeps only what it measures`() {
        val plan = FolderBarLayout.plan(200f, eden, 0f, titleWidth = 40f)
        assertEquals(Fit.Full, plan.fit)
        assertTrue(FolderBarLayout.titleRoom(200f, plan, 0f) >= 40f)
    }

    /** La ficha de la carpeta medida en Poppins SemiBold 8,5 sp con su aire: entera ("12 ROMS · 0H 16M") y corta ("12 ROMS"). */
    private val pill = 118f
    private val pillShort = 70f

    @Test
    fun `the folder pill fits in the right cluster on phones, tablets and TV`() {
        // 1600 × 720 (a 1,75 y a 2), 2340 × 1080 (a 2,75), 2560 × 1600 (Legion Y700 Gen 3), 2000 × 1200 y una tele.
        for ((w, h) in listOf(914f to 411f, 800f to 360f, 851f to 393f, 1280f to 800f, 1000f to 600f, 960f to 540f)) {
            val available = meridian(w, h)
            val plan = FolderBarLayout.plan(available, eden, status, pillWidth = pill, pillShortWidth = pillShort)
            assertTrue("w=$w", plan.pill != FolderBarLayout.Pill.Hidden)
            // Todo junto cabe en el grupo de la derecha y al nombre del sistema le queda su mínimo.
            assertTrue("w=$w", FolderBarLayout.titleRoom(available, plan, status) >= FolderBarLayout.TITLE_MIN - eps)
        }
        // En una tableta, todo entero.
        val tablet = FolderBarLayout.plan(meridian(1280f, 800f), eden, status, pillWidth = pill, pillShortWidth = pillShort)
        assertEquals(Fit.Full, tablet.fit)
        assertEquals(FolderBarLayout.Pill.Full, tablet.pill)
    }

    @Test
    fun `when it gets tight the path goes first, then the pill drops its time, then the emulator shortens`() {
        val title = FolderBarLayout.TITLE_MIN + FolderBarLayout.GAP
        val full = FolderBarLayout.chipWidth(eden) + pill + FolderBarLayout.GAP + title
        // Con el sitio justo para todo entero: el nombre y la ruta se quedan en su mínimo (la ruta, recortada por el centro).
        val a = FolderBarLayout.plan(full, eden, 0f, pillWidth = pill, pillShortWidth = pillShort)
        assertEquals(Fit.Full, a.fit)
        assertEquals(FolderBarLayout.Pill.Full, a.pill)
        // Un poco menos: la ficha pierde el tiempo antes que el emulador su nombre.
        val b = FolderBarLayout.plan(full - 20f, eden, 0f, pillWidth = pill, pillShortWidth = pillShort)
        assertEquals(Fit.Full, b.fit)
        assertEquals(FolderBarLayout.Pill.Short, b.pill)
        assertEquals(pillShort, b.pillWidth, eps)
        // Aún menos: el emulador se queda en su icono, con la ficha corta.
        val c = FolderBarLayout.plan(pillShort + FolderBarLayout.GAP + FolderBarLayout.ICON_WIDTH + title, eden, 0f, pillWidth = pill, pillShortWidth = pillShort)
        assertEquals(Fit.IconEmulator, c.fit)
        assertEquals(FolderBarLayout.Pill.Short, c.pill)
        // Sin sitio ni para eso: la ficha se quita (el nombre del sistema nunca baja de su mínimo).
        val d = FolderBarLayout.plan(FolderBarLayout.ICON_WIDTH + title, eden, 0f, pillWidth = pill, pillShortWidth = pillShort)
        assertEquals(FolderBarLayout.Pill.Hidden, d.pill)
        // Más ancho nunca compacta más.
        var prev = 99
        var room = 100f
        while (room < 700f) {
            val p = FolderBarLayout.plan(room, long, status, pillWidth = pill, pillShortWidth = pillShort)
            val level = p.fit.ordinal * 3 + p.pill.ordinal
            assertTrue("room=$room", level <= prev)
            prev = level
            room += 4f
        }
    }

    @Test
    fun `without a pill the plan is the one it always was`() {
        for (a in listOf(150f, 220f, 300f, 600f)) {
            val p = FolderBarLayout.plan(a, eden, status)
            assertEquals(0f, p.pillWidth, 0f)
            assertEquals(FolderBarLayout.Pill.Full, p.pill)
        }
    }
}
