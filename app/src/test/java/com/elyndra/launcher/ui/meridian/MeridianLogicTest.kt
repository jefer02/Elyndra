package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.data.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MeridianLogicTest {

    /* ── Modo ── */

    @Test
    fun `meridian only with a landscape window at least 640 dp wide`() {
        // Móviles en horizontal, tableta en horizontal, ventana libre ancha.
        assertTrue(MeridianMode.active(915f, 412f, LayoutStyle.Meridian))
        assertTrue(MeridianMode.active(800f, 360f, LayoutStyle.Meridian))
        assertTrue(MeridianMode.active(1280f, 800f, LayoutStyle.Meridian))
        assertTrue(MeridianMode.active(MeridianMode.MIN_WIDTH_DP, 360f, LayoutStyle.Meridian))
        assertEquals(640f, MeridianMode.MIN_WIDTH_DP, 0f)
        // Vertical: el carrusel, también en tableta.
        assertFalse(MeridianMode.active(412f, 914f, LayoutStyle.Meridian))
        assertFalse(MeridianMode.active(800f, 1280f, LayoutStyle.Meridian))
        // Cuadrada o estrecha (pantalla partida): el carrusel.
        assertFalse(MeridianMode.active(700f, 700f, LayoutStyle.Meridian))
        assertFalse(MeridianMode.active(MeridianMode.MIN_WIDTH_DP - 1f, 300f, LayoutStyle.Meridian))
        // Con el carrusel elegido, nunca.
        assertFalse(MeridianMode.active(1280f, 800f, LayoutStyle.Classic))
    }

    @Test
    fun `windows lower than 480 dp use the compact layout`() {
        assertTrue(MeridianMode.compact(360f))
        assertTrue(MeridianMode.compact(412f))
        assertTrue(MeridianMode.compact(479f))
        assertFalse(MeridianMode.compact(480f))
        assertFalse(MeridianMode.compact(800f))
        // Rueda al 40 % entre 280 y 520 dp en ventana baja.
        assertEquals(320f, MeridianGeometry.railWidthFor(800f, compact = true), 1e-3f)
        assertEquals(366f, MeridianGeometry.railWidthFor(915f, compact = true), 1e-3f)
        assertEquals(MeridianGeometry.COMPACT_RAIL_MIN, MeridianGeometry.railWidthFor(640f, compact = true), 0f)
        assertEquals(MeridianGeometry.COMPACT_RAIL_MAX, MeridianGeometry.railWidthFor(1800f, compact = true), 0f)
        // La tarjeta enfocada nunca baja de 88 dp ni el texto de 12 sp.
        val g = MeridianGeometry.compute(640f, 250f, 1f, compact = true)
        assertTrue(g.rowHeight >= 88f)
        assertTrue(WheelTransform.NEIGHBOR_SP_COMPACT * WheelTransform.scale(1f) >= 12f)
        val title = WheelTransform.titleSp(g.rowHeight, focused = true, compact = true)
        assertTrue("$title", title in 18f..20f)
    }

    @Test
    fun `without a stored style meridian is the default and stored choices are kept`() {
        assertEquals(LayoutStyle.Meridian, LayoutStyle.byId(null))
        assertEquals(LayoutStyle.Meridian, LayoutStyle.byId("algo-que-ya-no-existe"))
        assertEquals(LayoutStyle.Classic, LayoutStyle.byId("classic"))
        assertEquals(LayoutStyle.Meridian, LayoutStyle.byId("meridian"))
        assertEquals(LayoutStyle.Meridian, LayoutStyle.DEFAULT)
    }

    /* ── Forma de la rueda ── */

    @Test
    fun `scale alpha and rotation follow the tuned tables by distance`() {
        val scale = floatArrayOf(1f, 0.64f, 0.46f, 0.32f, 0.24f)
        val alpha = floatArrayOf(1f, 0.85f, 0.5f, 0.25f, 0f)
        val rot = floatArrayOf(0f, 6f, 12f, 18f)
        for (d in 0..4) {
            assertEquals(scale[d], WheelTransform.scale(d.toFloat()), 1e-6f)
            assertEquals(scale[d], WheelTransform.scale(-d.toFloat()), 1e-6f)
            assertEquals(alpha[d], WheelTransform.alpha(d.toFloat()), 1e-6f)
        }
        for (d in 0..3) assertEquals(-rot[d], WheelTransform.rotationY(d.toFloat(), flat = false), 1e-6f)
        assertEquals(0f, WheelTransform.alpha(7f), 0f)
        assertEquals(0.24f, WheelTransform.scale(9f), 1e-6f)
        assertEquals((0.64f + 0.46f) / 2f, WheelTransform.scale(1.5f), 1e-6f)
        // El nombre: en la enfocada y sus vecinas; desde ±2, solo el arte.
        assertEquals(1f, WheelTransform.detail(1f), 0f)
        assertEquals(0f, WheelTransform.detail(2f), 0f)
    }

    @Test
    fun `everything shrinks fades turns curves and fogs monotonically and symmetrically`() {
        var last = WheelTransform.pose(0f, reduced = false)
        assertEquals(1f, last.scale, 0f)
        assertEquals(0f, last.fog, 0f)
        assertEquals(0f, last.shift, 0f)
        assertEquals(0f, last.arc, 0f)
        var d = 0.1f
        while (d <= 5f) {
            val p = WheelTransform.pose(d, reduced = false)
            val q = WheelTransform.pose(-d, reduced = false)
            assertEquals(p.scale, q.scale, 1e-6f)
            assertEquals(p.alpha, q.alpha, 1e-6f)
            assertEquals(p.arc, q.arc, 1e-5f)
            assertEquals(p.rotationY, q.rotationY, 1e-6f)
            assertEquals(-p.shift, q.shift, 1e-5f)
            assertTrue("escala en $d", p.scale <= last.scale + 1e-6f)
            assertTrue("opacidad en $d", p.alpha <= last.alpha + 1e-6f)
            assertTrue("curva en $d", p.arc <= last.arc + 1e-6f && p.arc <= 0f)
            assertTrue("giro en $d", abs(p.rotationY) >= abs(last.rotationY) - 1e-6f)
            assertTrue("niebla en $d", p.fog >= last.fog - 1e-6f)
            assertTrue("texto en $d", p.detail <= last.detail + 1e-6f)
            last = p
            d += 0.1f
        }
    }

    @Test
    fun `visual gaps stay even, neighbours of the focus breathe and far tiles tuck behind`() {
        for (n in 0 until WheelTransform.GAP.size) {
            val a = WheelTransform.center(n.toFloat())
            val b = WheelTransform.center(n + 1f)
            val gap = (b - WheelTransform.scale(n + 1f) / 2f) - (a + WheelTransform.scale(n.toFloat()) / 2f)
            assertEquals("hueco $n→${n + 1}", WheelTransform.GAP[n], gap, 1e-5f)
        }
        assertTrue(WheelTransform.GAP[0] >= 0f)
        for (n in 1 until WheelTransform.GAP.size) assertTrue(WheelTransform.GAP[n] in -0.10f..-0.06f)
        assertTrue(WheelTransform.zOrder(5, 5) > WheelTransform.zOrder(6, 5))
        assertTrue(WheelTransform.zOrder(6, 5) > WheelTransform.zOrder(8, 5))
        assertEquals(WheelTransform.zOrder(3, 5), WheelTransform.zOrder(7, 5), 0f)
    }

    @Test
    fun `translation compensation moves each row from its list slot to its visual centre`() {
        var d = -6f
        while (d <= 6f) {
            assertEquals("en $d", WheelTransform.center(d), d + WheelTransform.shift(d), 1e-5f)
            d += 0.25f
        }
        var prev = WheelTransform.center(-6f)
        d = -5.99f
        while (d <= 6f) {
            val c = WheelTransform.center(d)
            assertTrue("en orden en $d", c > prev)
            assertTrue("sin saltos en $d", c - prev < 0.02f)
            prev = c
            d += 0.01f
        }
    }

    /** Filas que asoman en la rueda (alguna parte dentro) con opacidad ≥ [min] y por debajo. */
    private fun rows(tileFraction: Float): Pair<Int, Int> {
        val half = 1f / tileFraction / 2f
        var clear = 0
        var faint = 0
        for (d in -6..6) {
            val f = d.toFloat()
            if (abs(WheelTransform.center(f)) - WheelTransform.scale(f) / 2f >= half) continue
            if (WheelTransform.alpha(f) >= 0.8f) clear++ else if (WheelTransform.alpha(f) > 0f) faint++
        }
        return clear to faint
    }

    @Test
    fun `about three rows read clearly plus two faint ones with icons, and covers keep three`() {
        assertEquals(3 to 2, rows(WheelTransform.ICON_TILE))
        // Carátulas al 48 %: la enfocada y sus vecinas (que pueden asomar recortadas en los cantos).
        val (clear, _) = rows(WheelTransform.COVER_TILE)
        assertEquals(3, clear)
    }

    @Test
    fun `focused tiles are big, sized from the rail height and capped by the window and the rail`() {
        // Iconos: 38 % del alto; carátulas: 48 % (entre el 46 y el 52 % pedido).
        assertEquals(600f * 0.38f, WheelTransform.tileHeight(600f, 1600f, 1f), 1e-3f)
        assertEquals(600f * 0.48f, WheelTransform.tileHeight(600f, 1600f, 2f / 3f), 1e-3f)
        assertTrue(WheelTransform.COVER_TILE in 0.46f..0.52f)
        assertTrue(WheelTransform.ICON_TILE in 0.36f..0.40f)
        // Ventana estrecha: el ancho de la carátula no pasa del 24 % de la ventana.
        val tall = WheelTransform.tileHeight(900f, 600f, 2f / 3f)
        assertEquals(600f * 0.24f, tall * 2f / 3f, 1e-3f)
        // Ni del 42 % de la rueda (al nombre le queda sitio).
        assertEquals(300f * 0.42f, WheelTransform.tileHeight(600f, 2000f, 1f, railWidth = 300f), 1e-3f)
        // Topes absolutos.
        assertEquals(WheelTransform.TILE_MIN, WheelTransform.tileHeight(120f, 1600f, 1f), 0f)
        assertEquals(WheelTransform.TILE_MAX, WheelTransform.tileHeight(3000f, 4000f, 1f), 0f)
        // La tableta de las capturas (853 × 533 dp, rueda de 457 dp): la carátula enfocada ~48 % de la rueda.
        val g = MeridianGeometry.compute(853f, 457f, 2f / 3f)
        assertTrue("${g.rowHeight / 457f}", g.rowHeight / 457f in 0.46f..0.52f)
        assertTrue(g.rowHeight * 2f / 3f <= 853f * 0.24f + 1e-3f)
    }

    @Test
    fun `the focused title is large and neighbours stay readable`() {
        for (t in listOf(88f, 120f, 180f, 260f, 380f)) {
            val focus = WheelTransform.titleSp(t, focused = true)
            assertTrue("$t: $focus", focus in 20f..24f)
            val neighbour = WheelTransform.titleSp(t, focused = false) * WheelTransform.scale(1f)
            assertTrue("$t: $neighbour", neighbour >= 12f)
        }
    }

    @Test
    fun `reduced motion drops rotation but keeps the shared curve, scale, fades and fog`() {
        for (d in listOf(-3f, -1.5f, -0.4f, 0.7f, 2f, 3.5f)) {
            val p = WheelTransform.pose(d, reduced = true)
            assertEquals(0f, p.rotationY, 0f)
            assertEquals(WheelTransform.pose(d, reduced = false).arc, p.arc, 0f)
            assertEquals(WheelTransform.scale(d), p.scale, 0f)
            assertEquals(WheelTransform.alpha(d), p.alpha, 0f)
            assertEquals(WheelTransform.fog(d), p.fog, 0f)
        }
    }

    @Test
    fun `the light tier shows only two rows each way, flat and without fog`() {
        for (d in listOf(-4f, -3f, 3f, 3.5f, 5f)) assertEquals(0f, WheelTransform.alpha(d, lite = true), 0f)
        assertEquals(WheelTransform.alpha(2f), WheelTransform.alpha(2f, lite = true), 0f)
        assertEquals(WheelTransform.alpha(1f), WheelTransform.alpha(1f, lite = true), 0f)
        for (d in listOf(-2f, 1f, 2.5f)) {
            val p = WheelTransform.pose(d, reduced = false, lite = true)
            assertEquals(0f, p.rotationY, 0f)
            assertEquals(0f, p.fog, 0f)
        }
    }

    /* ── El círculo compartido ── */

    @Test
    fun `the arc is a circle centred off screen to the left at the focus line`() {
        val r = 500f
        assertEquals(0f, WheelArc.offset(0f, r), 0f)
        assertEquals(-r, WheelArc.offset(r, r), 1e-3f)
        assertEquals(-r, WheelArc.offset(3 * r, r), 1e-3f)
        var prev = 0f
        var dy = 1f
        while (dy <= r) {
            val x = WheelArc.offset(dy, r)
            assertEquals(x, WheelArc.offset(-dy, r), 0f)
            assertTrue(x <= prev)
            // Es un círculo: el punto está a r del centro (−r, 0).
            assertEquals(r, kotlin.math.hypot(x + r, dy), 1e-2f)
            prev = x
            dy += 7f
        }
        // La normal apunta al centro y es unitaria; en la línea de foco, horizontal.
        val n = FloatArray(2)
        WheelArc.inward(0f, r, n)
        assertEquals(-1f, n[0], 1e-6f)
        assertEquals(0f, n[1], 1e-6f)
        WheelArc.inward(200f, r, n)
        assertEquals(1f, kotlin.math.hypot(n[0], n[1]), 1e-5f)
        assertTrue(n[1] < 0f)
    }

    @Test
    fun `rows and dial follow the very same curve`() {
        val rail = 457f
        val tile = rail * WheelTransform.COVER_TILE
        val radius = WheelArc.radius(rail)
        for (d in listOf(-2.5f, -1f, -0.3f, 0f, 0.6f, 1f, 2f)) {
            // Lo que se mueve la fila (en altos de tarjeta, ya en px) = el dial a la altura de su centro.
            val row = WheelTransform.arc(d, radius / tile) * tile
            val dial = WheelArc.offset(WheelTransform.center(d) * tile, radius)
            assertEquals("en $d", dial, row, 1e-3f)
        }
    }

    /* ── Listas cortas ── */

    @Test
    fun `short lists centre the whole stack and long ones keep the focus line`() {
        val railTiles = 1f / WheelTransform.COVER_TILE
        val focus = railTiles / 2f
        assertEquals(1f, ShortList.weight(1), 0f)
        assertEquals(1f, ShortList.weight(4), 0f)
        assertEquals(0.5f, ShortList.weight(5), 0f)
        assertEquals(0f, ShortList.weight(6), 0f)
        assertEquals(0f, ShortList.weight(0), 0f)
        for (count in 1..3) for (at in 0 until count) {
            val p = at.toFloat()
            val lift = ShortList.lift(p, count, railTiles, focus)
            val top = focus + lift + WheelTransform.center(-p) - WheelTransform.scale(-p) / 2f
            val last = count - 1 - p
            val bottom = focus + lift + WheelTransform.center(last) + WheelTransform.scale(last) / 2f
            // Centrada (salvo que la enfocada se saliera) y sin hueco grande arriba.
            val f = focus + lift
            assertTrue("$count@$at: la enfocada dentro", f - 0.5f >= -1e-4f && f + 0.5f <= railTiles + 1e-4f)
            if (f > 0.5f + 1e-4f && f < railTiles - 0.5f - 1e-4f) assertEquals("$count@$at", railTiles / 2f, (top + bottom) / 2f, 1e-3f)
            assertTrue("$count@$at hueco ${top}", top <= railTiles * 0.3f)
        }
        // Desde 6 filas, nada se mueve.
        assertEquals(0f, ShortList.lift(0f, 40, railTiles, focus), 0f)
        // Continuo al girar (sin saltos).
        var prev = ShortList.lift(0f, 3, railTiles, focus)
        var p = 0.01f
        while (p <= 2f) {
            val l = ShortList.lift(p, 3, railTiles, focus)
            assertTrue(abs(l - prev) < 0.03f)
            prev = l
            p += 0.01f
        }
    }

    @Test
    fun `the node sits on the exact centre of the focused tile when the wheel rests`() {
        val focusY = 228f
        val row = 219f
        for (lift in listOf(0f, -40f, 37.5f)) for (f in listOf(0, 3, 41)) {
            val node = focusY + lift
            assertEquals(node, WheelMath.visualCenter(f, f.toFloat(), focusY, lift, row), 1e-3f)
        }
        // A mitad de camino el nodo sigue en la muesca; la tarjeta llega a él al pararse.
        val moving = WheelMath.visualCenter(4, 3.5f, focusY, 0f, row)
        assertTrue(moving > focusY)
    }

    @Test
    fun `rows fade before reaching the masha bubble or the rail edges`() {
        val h = 140f
        // Lejos de los cantos, entera.
        assertEquals(1f, RailEdge.fade(300f, h, top = 0f, bottom = 600f), 0f)
        // Con su centro por encima del canto de arriba (el bocadillo), apagada.
        assertEquals(0f, RailEdge.fade(80f - h * 0.3f, h, top = 80f, bottom = 600f), 0f)
        // Igual abajo.
        assertEquals(0f, RailEdge.fade(600f + h * 0.3f, h, top = 0f, bottom = 600f), 0f)
        // Una vecina algo recortada arriba, sin bocadillo, apenas se apaga.
        assertTrue(RailEdge.fade(h * 0.4f, h, top = 0f, bottom = 600f) > 0.9f)
    }

    /* ── Bocadillo de Masha ── */

    @Test
    fun `the masha bubble goes below its button, never over the dock, and inside the rail column`() {
        val column = BubbleSlot.Area(0f, 0f, 340f, 520f)
        val masha = BubbleSlot.Area(22f, 8f, 62f, 48f)
        val (x, y) = BubbleSlot.place(masha, 250f, 90f, column, emptyList(), gap = 8f, margin = 8f)
        assertEquals(22f, x, 0f)
        assertEquals(56f, y, 0f)
        // El dock bajó a una segunda línea bajo Masha: el bocadillo no lo pisa.
        val dock = BubbleSlot.Area(60f, 52f, 330f, 86f)
        val (dx, dy) = BubbleSlot.place(masha, 250f, 90f, column, listOf(dock), gap = 8f, margin = 8f)
        val box = BubbleSlot.Area(dx, dy, dx + 250f, dy + 90f)
        assertFalse(box.overlaps(dock))
        assertTrue(box.left >= column.left && box.right <= column.right)
        // Nunca más ancho que la columna.
        assertEquals(324f, BubbleSlot.maxWidth(340f, 8f, 400f), 0f)
        assertEquals(250f, BubbleSlot.maxWidth(600f, 8f, 250f), 0f)
        // Un botón arrastrado al canto: el bocadillo se queda dentro.
        val dragged = BubbleSlot.Area(320f, 480f, 360f, 520f)
        val (cx, cy) = BubbleSlot.place(dragged, 250f, 90f, column, emptyList(), gap = 8f, margin = 8f)
        assertTrue(cx + 250f <= column.right - 8f + 1e-3f && cy + 90f <= column.bottom - 8f + 1e-3f)
        // La rueda le deja sitio: hasta su canto de abajo y un poco más; sin bocadillo, nada.
        assertEquals(158f, BubbleSlot.reserve(bubbleBottom = 300f, railTop = 150f, gap = 8f), 0f)
        assertEquals(0f, BubbleSlot.reserve(bubbleBottom = 100f, railTop = 150f, gap = 8f), 0f)
    }

    /* ── Posición, snap y muelle ── */

    @Test
    fun `position counts rows from the logical scroll`() {
        assertEquals(0f, WheelMath.position(0, 0, 120f), 0f)
        assertEquals(3.5f, WheelMath.position(3, 60, 120f), 1e-6f)
        assertEquals(2f, WheelMath.distance(5, 3f), 0f)
        assertEquals(-1.25f, WheelMath.distance(2, 3.25f), 1e-6f)
    }

    @Test
    fun `releasing slowly snaps to the nearest row and flinging to the next one`() {
        val min = 400f
        assertEquals(3, WheelMath.snapTarget(3.4f, 0f, min, 10))
        assertEquals(4, WheelMath.snapTarget(3.6f, -100f, min, 10))
        assertEquals(4, WheelMath.snapTarget(3.1f, 2_000f, min, 10))
        assertEquals(3, WheelMath.snapTarget(3.9f, -2_000f, min, 10))
        // Ya en una fila, un gesto rápido no se salta ninguna de más.
        assertEquals(3, WheelMath.snapTarget(3f, 2_000f, min, 10))
        assertEquals(3, WheelMath.snapTarget(3f, -2_000f, min, 10))
        // Dentro de la lista, siempre.
        assertEquals(0, WheelMath.snapTarget(-0.4f, -2_000f, min, 10))
        assertEquals(9, WheelMath.snapTarget(9.6f, 2_000f, min, 10))
        assertEquals(0, WheelMath.snapTarget(2f, 0f, min, 0))
    }

    @Test
    fun `the fling approach stops one row short and keeps its direction`() {
        assertEquals(380f, WheelMath.approachOffset(500f, 120f), 1e-4f)
        assertEquals(-380f, WheelMath.approachOffset(-500f, 120f), 1e-4f)
        assertEquals(0f, WheelMath.approachOffset(80f, 120f), 0f)
    }

    @Test
    fun `the spring reaches its row without overshooting and keeps velocity when retargeted`() {
        val out = FloatArray(2)
        var x = 0f
        var v = 0f
        var t = 0f
        while (t < 1.2f) {
            WheelMath.springStep(x, v, 4f, 1f / 60f, out)
            x = out[0]
            v = out[1]
            assertTrue("sin pasarse: $x", x <= 4f + 1e-3f)
            t += 1f / 60f
        }
        assertTrue(WheelMath.settled(x, v, 4f))
        // Un paso grande da lo mismo que muchos pequeños (solución exacta, estable a cualquier ritmo).
        WheelMath.springStep(0f, 0f, 4f, 0.05f, out)
        val big = out[0]
        var xs = 0f
        var vs = 0f
        repeat(5) {
            WheelMath.springStep(xs, vs, 4f, 0.01f, out)
            xs = out[0]
            vs = out[1]
        }
        assertEquals(big, xs, 1e-3f)
        // Cambiar de destino a mitad no frena la rueda: sigue con su velocidad.
        WheelMath.springStep(0f, 0f, 4f, 0.08f, out)
        val vMid = out[1]
        WheelMath.springStep(out[0], vMid, 6f, 1f / 120f, out)
        assertTrue("sigue acelerada: ${out[1]} vs $vMid", out[1] > vMid * 0.9f)
    }

    /* ── Estado al girar o cambiar de modo ── */

    @Test
    fun `the wheel starts on the selected row so rotating or resizing loses nothing`() {
        val keys = listOf("a:1", "a:2", "r:3", "a:4")
        assertEquals(2, WheelMath.restoreIndex(keys, "r:3", addFocused = false, hasAdd = true))
        // "Añadir" señalada con el mando: la última fila.
        assertEquals(4, WheelMath.restoreIndex(keys, "r:3", addFocused = true, hasAdd = true))
        assertEquals(2, WheelMath.restoreIndex(keys, "r:3", addFocused = true, hasAdd = false))
        // Sin selección (o una que ya no está en la sección): la primera, la que enseña el hero.
        assertEquals(0, WheelMath.restoreIndex(keys, null, addFocused = false, hasAdd = true))
        assertEquals(0, WheelMath.restoreIndex(keys, "a:99", addFocused = false, hasAdd = true))
        assertEquals(0, WheelMath.restoreIndex(emptyList(), null, addFocused = false, hasAdd = true))
        // Ida y vuelta entre modos con la misma selección: la misma fila.
        val there = WheelMath.restoreIndex(keys, "a:4", false, true)
        val back = WheelMath.restoreIndex(keys, keys[there], false, true)
        assertEquals(there, back)
    }

    /* ── Mando ── */

    @Test
    fun `holding the dpad accelerates the wheel step by step`() {
        assertEquals(1, MeridianInput.stepFor(0))
        assertEquals(1, MeridianInput.stepFor(5))
        assertEquals(2, MeridianInput.stepFor(6))
        assertEquals(3, MeridianInput.stepFor(12))
        assertEquals(5, MeridianInput.stepFor(20))
        assertEquals(5, MeridianInput.stepFor(500))
        var last = 0
        for (r in 0..60) {
            val s = MeridianInput.stepFor(r)
            assertTrue(s >= last)
            last = s
        }
    }

    /* ── Tira A–Z ── */

    @Test
    fun `the index strip shows only when sorted by name and the list is long`() {
        assertFalse(IndexStrip.visible(sortedByName = true, count = 12))
        assertTrue(IndexStrip.visible(sortedByName = true, count = 13))
        assertFalse(IndexStrip.visible(sortedByName = false, count = 300))
    }

    @Test
    fun `letters fold accents and group the rest under a hash`() {
        assertEquals("O", IndexStrip.letterOf("Ödland"))
        assertEquals("E", IndexStrip.letterOf("  échec"))
        assertEquals("Z", IndexStrip.letterOf("zelda"))
        assertEquals("#", IndexStrip.letterOf("007 GoldenEye"))
        assertEquals("#", IndexStrip.letterOf("ファイナルファンタジー"))
        assertEquals("#", IndexStrip.letterOf(""))
    }

    @Test
    fun `each letter jumps to its first game and the finger maps to an entry`() {
        val names = listOf("1942", "Alien", "Asteroids", "Bomberman", "Castlevania", "Contra", "Échec")
        val entries = IndexStrip.entries(names)
        assertEquals(listOf("#", "A", "B", "C", "E"), entries.map { it.label })
        assertEquals(listOf(0, 1, 3, 4, 6), entries.map { it.firstIndex })
        assertEquals(0, IndexStrip.entryAt(0f, entries.size))
        assertEquals(4, IndexStrip.entryAt(1f, entries.size))
        assertEquals(2, IndexStrip.entryAt(0.5f, entries.size))
        assertEquals(0, IndexStrip.entryAt(-0.2f, entries.size))
        assertEquals(-1, IndexStrip.entryAt(0.5f, 0))
    }

    /* ── Entrada ── */

    @Test
    fun `the line ignites first and only the first eight rows wait their turn`() {
        assertEquals(0f, MeridianMotion.ignite(0f), 0f)
        assertEquals(1f, MeridianMotion.ignite(MeridianMotion.IGNITE_MS), 1e-6f)
        assertEquals(0f, MeridianMotion.cascade(0f, 0), 0f)
        // Cada fila arranca después de la anterior…
        val t = 120f
        for (i in 1 until MeridianMotion.CASCADE_ROWS) {
            assertTrue(MeridianMotion.cascade(t, i) <= MeridianMotion.cascade(t, i - 1))
        }
        // …y de la octava en adelante entran a la vez.
        assertEquals(MeridianMotion.cascade(t, 7), MeridianMotion.cascade(t, 30), 0f)
        assertEquals(1f, MeridianMotion.cascade(MeridianMotion.TOTAL_MS, 50), 1e-6f)
        assertEquals(1f, MeridianMotion.heroFade(MeridianMotion.TOTAL_MS), 1e-6f)
    }

    /* ── Reparto ── */

    @Test
    fun `the rail takes about 42 percent and the focus sits at the exact centre`() {
        assertEquals(383.88f, MeridianGeometry.railWidthFor(914f), 0.1f)
        assertEquals(MeridianGeometry.RAIL_MAX, MeridianGeometry.railWidthFor(2_000f), 0f)
        assertEquals(MeridianGeometry.RAIL_MIN, MeridianGeometry.railWidthFor(700f), 0f)
        assertEquals(MeridianGeometry.RAIL_MIN, MeridianGeometry.railWidthFor(640f), 0f)
        // Nunca más de la mitad.
        assertEquals(280f, MeridianGeometry.railWidthFor(560f), 0f)
        val g = MeridianGeometry.compute(1280f, 600f)
        assertEquals(300f, g.focusY, 1e-3f)
        assertEquals(g.focusY, g.padTop + g.rowHeight / 2f, 1e-3f)
        assertEquals(g.railHeight, g.padTop + g.rowHeight + g.padBottom, 1e-3f)
        assertTrue(g.heroLeft > g.axisX)
        // Las vecinas (±1) no se salen por la izquierda con la curva.
        assertTrue(g.inset + WheelArc.offset(WheelTransform.center(1f) * g.rowHeight, g.arcRadius) >= MeridianGeometry.RAIL_START - 1e-3f)
        // Al nombre de la enfocada le queda sitio (más de un tercio de la rueda) en tableta, móvil y el umbral.
        for ((w, h, compact) in listOf(Triple(853f, 457f, false), Triple(1280f, 720f, false), Triple(915f, 340f, true), Triple(800f, 300f, true), Triple(640f, 300f, true))) {
            for (aspect in listOf(1f, 2f / 3f)) {
                val m = MeridianGeometry.compute(w, h, aspect, compact)
                val text = m.railWidth - m.inset - m.rowHeight * aspect - 14f - 34f
                assertTrue("$w×$h $aspect: $text", text >= 90f)
            }
        }
    }

    /* ── Velo neutro (ajuste apagado) ── */

    private val arts = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFD400, 0xFF7FD3FF, 0xFFFF2D55, 0xFF808080, 0xFF1E3A8A, 0xFFF4E6C8).map { it.toInt() }
    private val widths = listOf(640f, 900f, 1280f, 1600f)

    @Test
    fun `the feathered scrim has no edge and is clear by about 62 percent of the width`() {
        for (w in widths) {
            val ax = MeridianGeometry.railWidthFor(w)
            assertEquals(MeridianScrims.EDGE_ALPHA, MeridianScrims.alphaAt(0f, ax, w), 1e-6f)
            assertEquals(MeridianScrims.AXIS_ALPHA, MeridianScrims.alphaAt(ax, ax, w), 1e-6f)
            val end = MeridianScrims.clearAt(ax, w)
            assertTrue("$w: $end", end <= w * 0.66f)
            assertEquals(0f, MeridianScrims.alphaAt(end, ax, w), 1e-6f)
            var x = 0f
            var prev = MeridianScrims.alphaAt(0f, ax, w)
            while (x < w) {
                val a = MeridianScrims.alphaAt(x, ax, w)
                assertTrue("salto en $x", abs(a - prev) < 0.02f)
                prev = a
                x += 1f
            }
            val stops = MeridianScrims.stops(ax, w)
            assertTrue(stops.size >= 6)
            stops.zipWithNext().forEach { (p, q) ->
                assertTrue(q.first >= p.first)
                assertTrue(q.second <= p.second + 1e-6f)
            }
        }
    }

    @Test
    fun `with the neutral scrim row titles keep 4,5 to 1 over very bright and very dark art in both themes`() {
        for (dark in listOf(false, true)) for (w in widths) {
            val ax = MeridianGeometry.railWidthFor(w)
            var x = w * 0.08f
            while (x <= ax - 34f) {
                for (art in arts) {
                    val bg = MeridianScrims.background(art, x, ax, w, dark)
                    val name = "%08X dark=%s w=%.0f x=%.0f".format(art, dark, w, x)
                    assertTrue("enfocada $name", MeridianScrims.textContrast(MeridianScrims.plated(bg, dark), dark) >= MeridianScrims.MIN_TEXT)
                    assertTrue("vecina $name", MeridianScrims.textContrast(bg, dark, WheelTransform.alpha(1f)) >= MeridianScrims.MIN_TEXT)
                }
                x += 8f
            }
        }
    }

    @Test
    fun `white hero text reads over the dark oval on any art`() {
        val white = 0xFFFFFFFF.toInt()
        val t = MeridianScrims.titleAlpha()
        for (art in arts) {
            val bg = ColorMath.over(ColorMath.withAlpha(0xFF000000.toInt(), t), art)
            assertTrue(ColorMath.contrast(white, bg) >= 4.5)
        }
        assertTrue("el óvalo no tapa el arte entero: $t", t < 0.7f)
    }

    @Test
    fun `the light theme neutral tint is a warm pearl, never a flat gray`() {
        val (h, s, l) = ColorMath.toHsl(MeridianScrims.tint(dark = false))
        assertTrue("tono $h", h in 20f..50f)
        assertTrue("saturación $s", s > 0.3f)
        assertTrue(l > 0.9f)
        assertEquals(BrandTokens.DARK.paper, MeridianScrims.tint(dark = true))
    }

    /* ── Píldoras ── */

    @Test
    fun `meridian pills keep white text at 4,5 and icons at 3 over any background`() {
        val white = 0xFFFFFFFF.toInt()
        val backgrounds = arts + listOf(0xFFE9E1F0.toInt(), 0xFFF2E3B4.toInt(), 0xFFFFC2C8.toInt())
        for (bg in backgrounds) {
            val pill = MeridianGlass.over(bg)
            // Con el reflejo de arriba del cristal (blanco a ~5 %), el peor caso.
            val hazed = ColorMath.over(ColorMath.withAlpha(white, 0.05f), pill)
            assertTrue("%08X".format(bg), ColorMath.contrast(white, hazed) >= 4.5)
            assertTrue(ColorMath.contrast(ColorMath.over(ColorMath.withAlpha(white, 0.8f), hazed), hazed) >= 3.0)
        }
    }

    @Test
    fun `an empty list has no index entries`() {
        assertTrue(IndexStrip.entries(emptyList()).isEmpty())
    }
}
