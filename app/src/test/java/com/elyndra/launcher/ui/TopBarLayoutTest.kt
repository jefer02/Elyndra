package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.TopBarLayout.Fit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.elyndra.launcher.ui.meridian.MeridianGeometry
import com.elyndra.launcher.ui.meridian.MeridianMode
import com.elyndra.launcher.ui.meridian.MeridianTopRow

class TopBarLayoutTest {

    private val eps = 1e-3f

    /** Rótulos (dp) aproximados en Poppins SemiBold 11 sp: "Abrir", "12:58 p. m." y "100%". */
    private val open = 30f
    private val time = 62f
    private val percent = 28f

    /** El rótulo de la tienda más largo de los seis idiomas: "Boutique" (fr), Poppins SemiBold 11 sp. */
    private val store = 48f

    /** Margen de la barra a cada lado en vertical (Metrics.pad). */
    private val pad = 18f

    private val closed = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = false, landscape = false))

    private fun fitFor(window: Float, fixed: Float = closed, openLabel: Float? = open, t: Float? = time, p: Float? = percent, s: Float? = store): Fit =
        TopBarLayout.fit(window - 2 * pad, fixed, openLabel, t, p, s)

    @Test
    fun `las piezas miden lo que dibujan sus componentes`() {
        assertEquals(8f + 18f + 6f + open + 12f, TopBarLayout.openWidth(open, withLabel = true), eps)
        assertEquals(TopBarLayout.BAR_HEIGHT, TopBarLayout.openWidth(open, withLabel = false), eps)
        // 11 + hora + 8 + punto + 8 + pila + 8 + % + 11.
        assertEquals(22f + time + 3f + 20f + percent + 3 * 8f, TopBarLayout.statusWidth(time, percent, withPercent = true), eps)
        assertEquals(22f + time + 3f + 20f + 2 * 8f, TopBarLayout.statusWidth(time, percent, withPercent = false), eps)
        assertEquals(22f + time, TopBarLayout.statusWidth(time, null, withPercent = true), eps)
        assertEquals(0f, TopBarLayout.statusWidth(null, null, withPercent = true), eps)
        assertEquals(34f + 11f + 96f, TopBarLayout.searchWidth(open = true, landscape = false), eps)
        assertEquals(8f + 18f + 6f + store + 12f, TopBarLayout.storeWidth(store, withLabel = true), eps)
        assertEquals(TopBarLayout.BAR_HEIGHT, TopBarLayout.storeWidth(store, withLabel = false), eps)
        // El icono de la tienda va en lo fijo, con su aire.
        assertEquals(
            TopBarLayout.BAR_HEIGHT + TopBarLayout.GAP,
            TopBarLayout.fixedWidth(34f) - TopBarLayout.fixedWidth(34f, store = false),
            eps,
        )
    }

    /**
     * La entrada a la tienda en los tres anchos de verdad: la tableta en
     * apaisado (1280), el móvil en apaisado (800) y en vertical (360). La
     * tienda y Ajustes caben siempre; el rótulo de la tienda se ve mientras
     * haya sitio.
     */
    @Test
    fun `la tienda y Ajustes caben siempre en los tres anchos`() {
        // En apaisado el dock va en la barra (lo menos que mide con cuatro secciones) y el buscador abre ancho.
        val dock = DotTabsLayout.minWidth(4)
        val wide = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = false, landscape = true), extra = dock)
        val landscapePad = 24f
        for (window in listOf(1280f, 800f)) {
            val available = window - 2 * landscapePad
            val fit = TopBarLayout.fit(available, wide, open, time, percent, store)
            assertTrue("$window: $fit", fit.storeLabel)
            assertTrue("$window", TopBarLayout.width(fit, wide, open, time, percent, store) <= available + eps)
        }
        assertEquals(Fit.Full, TopBarLayout.fit(1280f - 2 * landscapePad, wide, open, time, percent, store))
        // Móvil en vertical: la tienda se queda en su icono, pero está.
        val phone = fitFor(360f)
        assertTrue(!phone.storeLabel)
        assertTrue(TopBarLayout.width(phone, closed, open, time, percent, store) <= 360f - 2 * pad)
        // Buscando en vertical (sin "Abrir" ni píldora), la tienda y Ajustes siguen dentro.
        val searching = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = true, landscape = false))
        val s = TopBarLayout.fit(360f - 2 * pad, searching, null, null, null, store)
        assertTrue(TopBarLayout.width(s, searching, null, null, null, store) <= 360f - 2 * pad)
    }

    @Test
    fun `el rotulo de la tienda aguanta mas que el de Abrir`() {
        assertTrue(Fit.IconOpen.storeLabel)
        assertTrue(!Fit.IconOpen.openLabel)
        assertTrue(Fit.entries.indexOf(Fit.IconStore) > Fit.entries.indexOf(Fit.IconOpen))
    }

    /** El fallo: en un móvil normal (393 dp) la barra entera pedía más de lo que hay y Ajustes se cortaba. */
    @Test
    fun `movil vertical normal se compacta y Ajustes cabe`() {
        val full = TopBarLayout.width(Fit.Full, closed, open, time, percent, store)
        assertTrue("el caso del fallo: la barra entera no cabe", full > 393f - 2 * pad)
        val fit = fitFor(393f)
        assertEquals(Fit.SmallStatus, fit)
        assertTrue(TopBarLayout.width(fit, closed, open, time, percent, store) <= 393f - 2 * pad)
        // Sin la tienda (como antes), solo "Abrir" se quedaba en su icono.
        val noStore = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = false, landscape = false), store = false)
        assertEquals(Fit.IconOpen, fitFor(393f, fixed = noStore, s = null))
    }

    @Test
    fun `movil vertical estrecho la pildora se va antes que la tienda o Ajustes`() {
        assertEquals(Fit.NoStatus, fitFor(360f))
        assertTrue(TopBarLayout.width(Fit.NoStatus, closed, open, time, percent, store) <= 360f - 2 * pad)
    }

    @Test
    fun `en lo mas justo la pildora se va`() {
        assertEquals(Fit.NoStatus, fitFor(300f))
    }

    @Test
    fun `tableta en vertical y ventanas anchas no cambian`() {
        assertEquals(Fit.Full, fitFor(800f))
        assertEquals(Fit.Full, fitFor(600f))
    }

    @Test
    fun `cada paso compacta mas que el anterior`() {
        val widths = Fit.entries.map { TopBarLayout.width(it, closed, open, time, percent, store) }
        widths.zipWithNext().forEach { (a, b) -> assertTrue(b < a) }
    }

    @Test
    fun `buscando no hay Abrir ni pildora y el campo cabe`() {
        val searching = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = true, landscape = false))
        // Buscando, la barra deja fuera "Abrir" y la píldora (ver LibraryScreen).
        assertTrue(TopBarLayout.width(fitFor(360f, searching, null, null, null), searching, null, null, null) <= 360f - 2 * pad)
    }

    @Test
    fun `en Meridian Masha y el orden van en la barra y cuentan en ella`() {
        val plain = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = false, landscape = true), masha = false)
        val withSort = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = false, landscape = true), masha = true, sort = true)
        assertEquals(plain + TopBarLayout.MASHA + SectionDockLayout.SORT_WIDTH + 2 * TopBarLayout.GAP, withSort, eps)
        // "12:58" y "Open" medidos en Poppins SemiBold 11 sp (inglés).
        val openEn = 30.2f
        val timeEn = 27.3f
        // La barra de Meridian tiene lo que le deja la fila (ver MeridianTopRow.barMaxWidth): con Masha sigue entera en los móviles medidos.
        for ((window, height) in listOf(892f to 411f, 730f to 360f, 640f to 360f, 1280f to 800f)) {
            val compact = MeridianMode.compact(height)
            val rail = MeridianGeometry.slimRailWidth(window, height, 1f, compact)
            val available = MeridianTopRow.barMaxWidth(window, rail)
            // Sin la tienda, entera; con ella (y su rótulo más largo) se compacta, y en los móviles de verdad
            // (730 dp o más) la tienda conserva su rótulo. En la ventana mínima (640) se queda en su icono.
            val noStore = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = false, landscape = true), masha = true, sort = true, store = false)
            assertEquals("$window", Fit.Full, TopBarLayout.fit(available, noStore, openEn, timeEn, percent))
            val withStore = TopBarLayout.fit(available, withSort, openEn, timeEn, percent, store)
            if (window >= 730f) assertTrue("$window: $withStore", withStore.storeLabel)
            assertTrue("$window", TopBarLayout.width(withStore, withSort, openEn, timeEn, percent, store) <= available + eps)
            // Con la letra a 1,3 se compacta antes "Abrir" o la píldora, nunca Masha ni Ajustes (van en lo fijo).
            val big = TopBarLayout.fit(available, withSort, openEn * 1.3f, timeEn * 1.3f, percent * 1.3f)
            assertTrue("$window", TopBarLayout.width(big, withSort, openEn * 1.3f, timeEn * 1.3f, percent * 1.3f) <= available + eps)
            // Sin píldora (la de serie): entera, y con la letra a 1,3 también cabe.
            assertEquals("$window sin píldora", Fit.Full, TopBarLayout.fit(available, withSort, openEn, null, null))
            val bigBare = TopBarLayout.fit(available, withSort, openEn * 1.3f, null, null)
            assertTrue("$window sin píldora", TopBarLayout.width(bigBare, withSort, openEn * 1.3f, null, null) <= available + eps)
            // Buscando (sin "Abrir", píldora ni tienda), el campo ancho también cabe.
            val searching = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = true, landscape = true), masha = true, sort = true, store = false)
            assertTrue("$window", searching <= available + eps)
        }
        // Y si la ventana aprieta, se compacta paso a paso sin cortar Ajustes: lo fijo (Masha y orden incluidos) siempre cabe.
        // (La barra de Meridian nunca baja de ~394 dp: la ventana mínima es de 640.)
        var last = Fit.Full
        for (available in listOf(400f, 300f, 260f, 230f)) {
            val fit = TopBarLayout.fit(available, withSort, openEn, timeEn, percent)
            assertTrue("$available", fit.ordinal >= last.ordinal)
            if (fit != Fit.NoStatus) assertTrue("$available", TopBarLayout.width(fit, withSort, openEn, timeEn, percent) <= available)
            last = fit
        }
        // En lo más justo queda lo fijo (con la tienda en su icono) más "Abrir" en el suyo.
        assertEquals(withSort + TopBarLayout.BAR_HEIGHT + TopBarLayout.GAP, TopBarLayout.width(Fit.NoStatus, withSort, openEn, timeEn, percent), eps)
        assertTrue(withSort + TopBarLayout.BAR_HEIGHT + TopBarLayout.GAP < 394f)
    }

    @Test
    fun `sin pildora (apagada en Ajustes) solo se compactan los rotulos si hace falta`() {
        assertEquals(Fit.Full, fitFor(393f, t = null, p = null))
    }

    @Test
    fun `desde 320 dp todo lo que se ve cabe, con pildora y sin ella`() {
        for (status in listOf(false, true)) {
            val t = time.takeIf { status }
            val p = percent.takeIf { status }
            var w = 320f
            while (w <= 1_000f) {
                val fit = fitFor(w, t = t, p = p)
                assertTrue("$w dp pildora=$status", TopBarLayout.width(fit, closed, open, t, p, store) <= w - 2 * pad)
                w += 1f
            }
        }
    }

    /** Masha va en lo fijo: ningún paso de la compactación la quita (solo "Abrir" y la píldora encogen). */
    @Test
    fun `Masha nunca se compacta`() {
        val meridian = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = false, landscape = true), masha = true, sort = true)
        val noMasha = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = false, landscape = true), masha = false, sort = true)
        assertEquals(TopBarLayout.MASHA + TopBarLayout.GAP, meridian - noMasha, eps)
        for (fit in Fit.entries) assertTrue(TopBarLayout.width(fit, meridian, open, time, percent) >= meridian)
        // En lo más justo queda lo fijo entero: Masha, orden, buscar y Ajustes.
        assertEquals(meridian, TopBarLayout.width(Fit.NoStatus, meridian, null, time, percent), eps)
    }
}
