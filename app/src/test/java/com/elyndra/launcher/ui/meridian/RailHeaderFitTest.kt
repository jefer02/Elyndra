package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.ui.DotTabsLayout
import com.elyndra.launcher.ui.TopBarLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * La cabecera de la rueda (solo el dock, en la fila de la barra) contra la
 * niebla curva y el bocadillo de Masha bajo el grupo de la derecha, en
 * las ventanas de siempre (móvil apaisado, móvil pequeño, la mínima de
 * Meridian y tableta), en inglés y en alemán (los rótulos más largos), con la
 * escala de letra del sistema a 1 y a 1,3, con el cristal claro y con el velo
 * oscuro, y con y sin la fila de pistas del mando (le quita alto a la rueda y
 * cambia la curva).
 *
 * Todo en dp (densidad 1): así [FogArc.top] vale lo mismo en px que en dp.
 */
class RailHeaderFitTest {

    private val windows = listOf(892f to 411f, 730f to 360f, 640f to 360f, 1280f to 800f)

    /**
     * Rótulos del dock (dp) en Poppins SemiBold 11 sp con 0,04 em, medidos con
     * los avances de los glifos de `res/font/poppins_semibold.ttf` (los de
     * inglés y alemán coinciden con los de MeridianLogicTest).
     */
    private val dockLabels = mapOf(
        "en" to floatArrayOf(15.3f, 47.7f, 55.1f, 85.3f),
        "de" to floatArrayOf(22.6f, 47.7f, 55.2f, 78.1f),
        "es" to floatArrayOf(29.6f, 47.7f, 55.8f, 68.6f),
    )

    private val scales = listOf(1f, 1.3f)

    /** Alto de la fila de pistas del mando (cuando hay mando). */
    private val hintsHeights = listOf(0f, 30f)

    private val eps = 1e-3f

    private class Scene(
        val width: Float,
        val height: Float,
        val compact: Boolean,
        val rail: Float,
        val padTop: Float,
        val plan: RailHeaderLayout.Plan,
        val arc: FogArc,
        val glass: Boolean,
        val hints: Float,
    ) {
        val dockTop: Float get() = padTop + plan.dockTop
        val dockBottom: Float get() = dockTop + DotTabsLayout.HEIGHT
        val dockRight: Float get() = plan.dockX + plan.dockWidth

        /** La niebla tal como se pinta (con la banda que pide la cabecera). */
        fun edge(y: Float): Float = arc.edgeAt(y, rail, glass, 1f)

        /** La curva sola, sin banda. */
        fun curve(y: Float): Float = arc.copy(reach = 0f).edgeAt(y, rail, glass, 1f)
    }

    /** La escena como la monta MeridianScene: cabecera (solo el dock), la rueda debajo (menos las pistas) y su círculo. */
    private fun scene(w: Float, h: Float, pills: FloatArray, glass: Boolean, hints: Float): Scene {
        val compact = MeridianMode.compact(h)
        val rail = MeridianGeometry.slimRailWidth(w, h, 1f, compact)
        val padTop = MeridianTopRow.top(compact)
        // El alto de la cabecera no depende de la curva: sale igual con o sin ella.
        val straight = RailHeaderLayout.plan(rail, DotTabsLayout.HEIGHT, pills, null)
        val headerH = padTop + straight.height + MeridianTopRow.bottom(compact)
        val geo = MeridianGeometry.compute(w, h - headerH - hints, 1f, compact, rail)
        val bare = FogArc(headerH, geo.focusY, geo.arcRadius)
        val plan = RailHeaderLayout.plan(rail, DotTabsLayout.HEIGHT, pills) { y -> bare.edgeAt(padTop + y, rail, glass, 1f) }
        assertEquals(straight.height, plan.height, eps)
        return Scene(w, h, compact, rail, padTop, plan, bare.copy(reach = plan.reach), glass, hints)
    }

    private fun pills(labels: FloatArray, scale: Float) = FloatArray(labels.size) { DotTabsLayout.pillWidth(labels[it] * scale) }

    private inline fun forEachCase(block: (String, Scene, FloatArray) -> Unit) {
        for ((w, h) in windows) for ((lang, labels) in dockLabels) for (scale in scales) for (glass in listOf(true, false)) for (hints in hintsHeights) {
            for (n in 3..4) {
                val p = pills(labels.copyOf(n), scale)
                val name = "%.0f×%.0f %s ×%.1f %s pistas=%.0f n=%d".format(w, h, lang, scale, if (glass) "cristal" else "velo", hints, n)
                block(name, scene(w, h, p, glass, hints), p)
            }
        }
    }

    /* ── El dock de la cabecera ── */

    @Test
    fun `the header dock stays inside the curved fog edge at its own rows with a 16 dp margin`() {
        forEachCase { name, s, _ ->
            val edge = minOf(s.edge(s.dockTop), s.edge(s.dockBottom))
            assertTrue("$name: dock acaba en %.1f, niebla en %.1f".format(s.dockRight, edge), s.dockRight + RailHeaderLayout.FOG_MARGIN <= edge + eps)
            // Ni llega al bloque del hero ni sale por la izquierda.
            assertTrue(name, s.dockRight <= s.rail + MeridianGeometry.HERO_GAP - MeridianGeometry.RAIL_START + eps)
            assertTrue(name, s.plan.dockX >= RailHeaderLayout.START - eps)
        }
    }

    /** El dock y la barra de la derecha van en la misma fila: mismo tope, mismo alto, la misma línea central. */
    @Test
    fun `the header dock shares the center line with the top right bar`() {
        forEachCase { name, s, _ ->
            val bar = MeridianTopRow.top(s.compact) + TopBarLayout.BAR_HEIGHT / 2f
            assertEquals(name, MeridianTopRow.centerY(s.compact), (s.dockTop + s.dockBottom) / 2f, eps)
            assertEquals(name, bar, (s.dockTop + s.dockBottom) / 2f, eps)
        }
    }

    /**
     * Sin Masha encima del dock, la cabecera mide lo que el dock (más su
     * aire): la rueda gana los 48 dp (40 del emblema y 8 entre los dos) que
     * le quitaba la fila de Masha, y su línea de foco baja la mitad.
     */
    @Test
    fun `the wheel gets back the height of the old masha row`() {
        for ((w, h) in windows) {
            val compact = MeridianMode.compact(h)
            val rail = MeridianGeometry.slimRailWidth(w, h, 1f, compact)
            val header = MeridianTopRow.top(compact) + DotTabsLayout.HEIGHT + MeridianTopRow.bottom(compact)
            // Antes: 4/8 de tope, Masha (40), 8, el dock y 2/6 debajo.
            val before = (if (compact) 4f else 8f) + TopBarLayout.MASHA + 8f + DotTabsLayout.HEIGHT + (if (compact) 2f else 6f)
            assertTrue("%.0f×%.0f".format(w, h), before - header >= 46f - eps)
            val now = MeridianGeometry.compute(w, h - header, 1f, compact, rail)
            val old = MeridianGeometry.compute(w, h - before, 1f, compact, rail)
            assertTrue("%.0f×%.0f".format(w, h), now.focusY > old.focusY)
        }
    }

    /**
     * La niebla solo se ensancha arriba para que los rótulos se lean enteros
     * (hasta [RailHeaderLayout.LABEL_STRETCH]) o cuando el dock, ni en su
     * mínimo, cabe bajo la curva; si no, la curva se queda como está.
     */
    @Test
    fun `the fog only widens in the top band for whole labels or the minimum dock`() {
        forEachCase { name, s, p ->
            val curve = minOf(s.curve(s.dockTop), s.curve(s.dockBottom))
            if (s.dockRight + RailHeaderLayout.FOG_MARGIN > curve + eps) {
                val minimum = kotlin.math.abs(s.plan.dockWidth - DotTabsLayout.minWidth(p.size)) < 0.01f
                val forLabels = s.plan.dockWidth <= DotTabsLayout.fullWidth(p) + 0.01f
                assertTrue("$name: %.1f".format(s.plan.dockWidth), minimum || forLabels)
            }
            // La banda nunca recoge la niebla (solo la ensancha) y no toca la línea de foco ni lo de debajo.
            val focus = s.arc.focusIn(1f)
            assertEquals(name, s.curve(focus), s.edge(focus), eps)
            assertEquals(name, s.curve(s.arc.top + MeridianFog.BAND_BLEND), s.edge(s.arc.top + MeridianFog.BAND_BLEND), eps)
            for (y in listOf(0f, s.dockTop, s.dockBottom, s.arc.top, s.arc.top + 10f)) assertTrue(name, s.edge(y) >= s.curve(y) - eps)
        }
    }

    @Test
    fun `the header dock has the same width whichever section is selected`() {
        forEachCase { name, s, p ->
            val fit = DotTabsLayout.fit(p, s.plan.dockWidth)
            val pill = DotTabsLayout.uniformPill(p, fit)
            val uniform = FloatArray(p.size) { pill }
            val widths = p.indices.map { DotTabsLayout.totalWidth(uniform, it, fit.dot) }
            for (wd in widths) assertEquals(name, widths[0], wd, eps)
            assertEquals(name, s.plan.dockWidth, widths[0], 0.01f)
            // Y sigue cabiendo con cualquier sección elegida: la píldora se mueve, el dock no crece.
            for (sel in p.indices) {
                val (_, r) = DotTabsLayout.pillTarget(uniform, sel, fit.dot)
                assertTrue(name, s.plan.dockX + r + DotTabsLayout.INSET <= s.dockRight + eps)
            }
        }
    }

    /** El fallo de la captura: con "Consolas" elegida la cápsula crecía y su canto derecho pasaba la niebla. */
    @Test
    fun `before the fix the dock grew with the selected label`() {
        val p = pills(floatArrayOf(22.6f, 47.7f, 55.2f), 1f)
        val fit = DotTabsLayout.fit(p, 200f)
        val natural = FloatArray(p.size) { minOf(p[it], fit.pillCap) }
        assertTrue(DotTabsLayout.totalWidth(natural, 0, fit.dot) < DotTabsLayout.totalWidth(natural, 2, fit.dot))
        assertEquals(DotTabsLayout.uniformWidth(p, 200f), DotTabsLayout.totalWidth(FloatArray(p.size) { DotTabsLayout.uniformPill(p, fit) }, 0, fit.dot), eps)
    }

    @Test
    fun `the visible edge of the dark veil is where it has faded to half`() {
        val axis = 200f
        val a = MeridianScrims.alphaAt(axis + MeridianFog.WASH_REACH, axis, 900f)
        assertEquals(MeridianScrims.AXIS_ALPHA * MeridianFog.VISIBLE, a, 1e-3f)
        // Bastante antes de donde se vuelve transparente: medir contra los 88 dp decía "cabe" de más.
        assertTrue(MeridianFog.WASH_REACH < MeridianScrims.FADE_DP / 2f)
        // Sin curva, el canto es recto: el eje más lo que llega cada niebla.
        assertEquals(axis + MeridianFog.WASH_REACH, MeridianFog.visibleEdge(10f, axis, 300f, 0f, glass = false), eps)
        assertEquals(axis + com.elyndra.launcher.ui.theme.WheelGlass.PAST_AXIS_DP, MeridianFog.visibleEdge(300f, axis, 300f, 0f, glass = true), eps)
    }

    @Test
    fun `the top band keeps the fog continuous below the header`() {
        val band = MeridianFog.Band(bottom = 80f, maxShift = 5f)
        var prev = MeridianFog.shift(80f, 300f, 500f, band)
        assertEquals(5f, prev, eps)
        var y = 80f
        while (y < 80f + MeridianFog.BAND_BLEND + 10f) {
            y += 1f
            val s = MeridianFog.shift(y, 300f, 500f, band)
            assertTrue("salto en y=$y", kotlin.math.abs(s - prev) < 3f)
            prev = s
        }
        assertEquals(MeridianFog.shift(y, 300f, 500f), prev, eps)
        assertEquals(MeridianFog.Band.NONE, MeridianFog.band(80f, 0f, 200f, glass = true))
    }

    /**
     * Los rótulos se leen enteros si para ello basta con que el dock pase
     * hasta 12 dp de la curva (la niebla lo sigue en la banda de arriba); más
     * allá, se recortan. La niebla nunca se ensancha más de eso, salvo cuando
     * ni el dock mínimo cabe (la regla de antes).
     */
    @Test
    fun `the dock stretches at most 12 dp past the curve so its labels read whole`() {
        forEachCase { name, s, p ->
            val curve = minOf(s.curve(s.dockTop), s.curve(s.dockBottom))
            val past = s.dockRight + RailHeaderLayout.FOG_MARGIN - curve
            if (s.plan.dockWidth > DotTabsLayout.minWidth(p.size) + 0.01f) {
                assertTrue("$name: pasa %.1f dp de la curva".format(past), past <= RailHeaderLayout.LABEL_STRETCH + eps)
            }
            // Solo pasa de la curva lo que hace falta: nunca más que con todos los rótulos enteros (o el mínimo).
            if (past > eps) assertTrue(name, s.plan.dockWidth <= maxOf(DotTabsLayout.fullWidth(p), DotTabsLayout.minWidth(p.size)) + 0.01f)
        }
    }

    /** Lo que se lee entero con el dock que da la cabecera: los rótulos que caben en su píldora. */
    private fun whole(s: Scene, labels: FloatArray, scale: Float): List<Boolean> {
        val p = pills(labels, scale)
        val fit = DotTabsLayout.fit(p, s.plan.dockWidth)
        val pill = DotTabsLayout.uniformPill(p, fit)
        return labels.map { DotTabsLayout.labelRoom(pill) >= it * scale - 0.01f }
    }

    @Test
    fun `with three sections every label reads whole in light and dark`() {
        for ((w, h) in windows) for ((lang, labels) in dockLabels) for (glass in listOf(true, false)) for (hints in hintsHeights) {
            val l = labels.copyOf(3)
            val s = scene(w, h, pills(l, 1f), glass, hints)
            assertTrue("%.0f×%.0f %s %s".format(w, h, lang, if (glass) "cristal" else "velo"), whole(s, l, 1f).all { it })
        }
    }

    /**
     * Con cuatro secciones, en la tableta solo se recorta el rótulo más largo.
     * En los móviles (columna de 200–209 dp) no: el dock ya va en su mínimo
     * bajo la curva y 12 dp más no dan para "Android" ni "Consolas" (harían
     * falta ~193 dp; hay ~167).
     */
    @Test
    fun `with four sections the tablet only shortens the longest label`() {
        for ((lang, labels) in dockLabels) for (glass in listOf(true, false)) {
            val s = scene(1280f, 800f, pills(labels, 1f), glass, 0f)
            val ok = whole(s, labels, 1f)
            val longest = labels.indices.maxBy { labels[it] }
            assertTrue("$lang: $ok", labels.indices.filter { it != longest }.all { ok[it] })
        }
    }

    /**
     * El primer fotograma: antes de que la rueda se mida, la cabecera calcula
     * la curva con lo mismo que la rueda ([FogArc.forRail], con el alto que le
     * dejan la cabecera y las pistas). Da el mismo dock que la curva medida:
     * nada cambia de ancho al arrancar.
     */
    @Test
    fun `the header computes the same curve the wheel measures, so the dock is final on the first frame`() {
        forEachCase { name, s, p ->
            val padBottom = MeridianTopRow.bottom(s.compact)
            val headerH = s.padTop + s.plan.height + padBottom
            // Lo que calcula la cabecera sola, con el hueco que le queda a la rueda entre ella y las pistas.
            val computed = FogArc.forRail(s.width, s.height - headerH - s.hints, headerH, 1f, s.compact, s.rail)
            assertEquals(name, s.arc.copy(reach = 0f), computed)
            val first = RailHeaderLayout.plan(s.rail, DotTabsLayout.HEIGHT, p) { y ->
                computed.edgeAt(s.padTop + y, s.rail, s.glass, 1f)
            }
            assertEquals(name, s.plan, first)
        }
    }

    /* ── El bocadillo de Masha ── */

    /**
     * La línea de la captura ("6 juegos que nunca has abierto. La elección de
     * hoy: Hollow Knight.") medida con los avances de los glifos de Poppins
     * Regular a 11,5 sp. Aquí el alemán es la más corta y el español la más larga.
     */
    private val insight = mapOf("en" to 335.7f, "de" to 294.0f, "es" to 380.0f)

    /** "Abrir" en la barra (dp, SemiBold 11 sp) en cada idioma; la hora ("12:58") y "100%" miden 27,3 en los tres. */
    private val barLabels = mapOf("en" to 30.2f, "de" to 37.6f, "es" to 27.3f)

    /** La X del bocadillo de una línea (dp), en el sitio de "Ahora no". */
    private val close = 22f

    /** El bocadillo de una línea, entero: relleno 13 + texto + 8 + la X + 6 (ver InsightLine). */
    private fun oneLineNatural(textW: Float) = 13f + textW + 8f + close + 6f

    /** Las líneas del texto con el bocadillo de [width] (fuera del relleno, 13 + 6). */
    private fun lines(textW: Float, width: Float): Float = if (width >= oneLineNatural(textW) - eps) 1f else ceil(textW / (width - 19f).coerceAtLeast(1f)).coerceIn(1f, 3f)

    /** Su alto: en una fila con la X; partido, el texto y "Ahora no" debajo (ver InsightLine). */
    private fun bubbleHeight(textW: Float, width: Float, scale: Float): Float {
        val line = 17f * scale
        val n = lines(textW, width)
        return if (n == 1f) 12f + maxOf(line, close) else 12f + line * n + 4f + 21f * scale
    }

    /** La barra de la derecha como la monta MeridianScene: Masha la primera, compactada a lo que le deja la fila. */
    private fun topBar(s: Scene, lang: String, scale: Float, searchOpen: Boolean, status: Boolean): BubbleSlot.Area {
        val available = MeridianTopRow.barMaxWidth(s.width, s.rail)
        val fixed = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(searchOpen, landscape = true), masha = true, sort = true)
        val open = (barLabels.getValue(lang) * scale).takeIf { !searchOpen }
        val time = (27.3f * scale).takeIf { !searchOpen && status }
        val percent = (27.3f * scale).takeIf { !searchOpen && status }
        val fit = TopBarLayout.fit(available, fixed, open, time, percent)
        val width = TopBarLayout.width(fit, fixed, open, time, percent)
        assertTrue("la barra cabe entera (Ajustes no se corta)", width <= available + eps)
        val top = MeridianTopRow.top(s.compact)
        return BubbleSlot.Area(s.width - MeridianTopRow.END - width, top, s.width - MeridianTopRow.END, top + TopBarLayout.BAR_HEIGHT)
    }

    /** El emblema de 40 dp, el primero de la barra, centrado en su hueco de 34 de alto. */
    private fun mashaIn(bar: BubbleSlot.Area): BubbleSlot.Area {
        val top = bar.top + (TopBarLayout.BAR_HEIGHT - TopBarLayout.MASHA) / 2f
        return BubbleSlot.Area(bar.left, top, bar.left + TopBarLayout.MASHA, top + TopBarLayout.MASHA)
    }

    /**
     * El bloque del hero (fichas, logo o título, descripción, Jugar/Detalles/
     * Opciones) en su caso más alto, con la letra a [scale]: el logo en su
     * tope de alto ([LogoFit]) y, en la ventana normal, dos filas de fichas y
     * dos líneas de descripción. Va abajo, sobre su margen ([MeridianGeometry.HERO_BOTTOM]).
     */
    private fun heroBlock(s: Scene, scale: Float): BubbleSlot.Area {
        val chips = if (s.compact) 24f * scale else (24f * 2 + 6f) * scale
        val logo = s.height * if (s.compact) LogoFit.MAX_HEIGHT_COMPACT else LogoFit.MAX_HEIGHT
        val description = if (s.compact) 0f else 8f + 2 * 11.5f * 1.32f * scale
        val actions = (if (s.compact) 10f else 16f) + 44f * scale
        val bottom = s.height * (1f - if (s.compact) MeridianGeometry.HERO_BOTTOM_COMPACT else MeridianGeometry.HERO_BOTTOM)
        val height = chips + 8f + logo + description + actions
        return BubbleSlot.Area(s.rail + MeridianGeometry.HERO_GAP, bottom - height, s.width - MeridianTopRow.END, bottom)
    }

    private fun slot(s: Scene, masha: BubbleSlot.Area, bar: BubbleSlot.Area, obstacles: List<BubbleSlot.Area>, textW: Float, scale: Float): BubbleSlot.Slot =
        BubbleSlot.underGroup(
            anchor = masha,
            group = bar,
            natural = oneLineNatural(textW),
            maxWidth = 560f,
            heightFor = { bubbleHeight(textW, it, scale) },
            bounds = BubbleSlot.Area(s.rail, 0f, s.width, s.height),
            obstacles = obstacles,
            gap = 8f,
            margin = 8f,
            minWidth = 150f,
        )

    @Test
    fun `the masha bubble sits under masha over the art and never covers the bar, the dock or the hero`() {
        for ((w, h) in windows) for (lang in insight.keys) for (scale in scales) for (glass in listOf(true, false)) for (searchOpen in listOf(false, true)) for (status in listOf(false, true)) {
            val s = scene(w, h, pills(dockLabels.getValue(lang).copyOf(3), scale), glass, hints = 0f)
            val name = "%.0f×%.0f %s ×%.1f %s buscando=%s píldora=%s".format(w, h, lang, scale, if (glass) "cristal" else "velo", searchOpen, status)
            val bar = topBar(s, lang, scale, searchOpen, status)
            val masha = mashaIn(bar)
            val dock = BubbleSlot.Area(s.plan.dockX, s.dockTop, s.dockRight, s.dockBottom)
            val hero = heroBlock(s, scale)
            val textW = insight.getValue(lang) * scale
            val slot = slot(s, masha, bar, listOf(dock, bar, hero), textW, scale)
            val box = BubbleSlot.Area(slot.x, slot.y, slot.x + slot.width, slot.y + bubbleHeight(textW, slot.width, scale))
            assertTrue("$name: sin sitio", slot.clear)
            assertFalse("$name tapa a Masha", box.overlaps(masha))
            assertFalse("$name tapa el dock", box.overlaps(dock))
            assertFalse("$name tapa la barra", box.overlaps(bar))
            assertFalse("$name tapa el hero", box.overlaps(hero))
            // Sobre el arte, fuera de la columna de la rueda, y dentro de la ventana.
            assertTrue(name, box.left >= s.rail + 8f - eps && box.right <= w - 8f + eps && box.bottom <= h)
            // Debajo de Masha (la cubre a lo ancho) y sin pasar del canto derecho del grupo.
            assertEquals(name, masha.bottom + 8f, box.top, eps)
            assertTrue(name, box.left <= masha.left + eps && box.right >= masha.right - eps)
            assertTrue(name, box.right <= bar.right + eps)
            // El texto entero: en una fila o, como mucho, en dos.
            assertTrue("$name: %.0f líneas".format(lines(textW, slot.width)), lines(textW, slot.width) <= 2f)
            if (scale == 1f && w >= 730f) assertEquals("$name: en una fila", oneLineNatural(textW), slot.width, eps)
        }
    }

    /**
     * El caso de la captura: móvil apaisado de 892 × 411 en español, con la
     * píldora de hora y batería y sin ella (la de serie). Antes la línea iba a la derecha de Masha, en
     * la cabecera de la rueda, con 24 dp de sobra; ahora va debajo del grupo
     * de la derecha, entera en una fila, con unos 220 dp de sobra.
     */
    @Test
    fun `on a landscape phone in spanish the masha line sits under masha on one line`() {
        for (glass in listOf(true, false)) for (status in listOf(true, false)) {
            val s = scene(892f, 411f, pills(dockLabels.getValue("es").copyOf(3), 1f), glass, hints = 0f)
            val bar = topBar(s, "es", 1f, searchOpen = false, status = status)
            val masha = mashaIn(bar)
            val dock = BubbleSlot.Area(s.plan.dockX, s.dockTop, s.dockRight, s.dockBottom)
            val textW = insight.getValue("es")
            val slot = slot(s, masha, bar, listOf(dock, bar, heroBlock(s, 1f)), textW, 1f)
            assertTrue(slot.clear)
            assertEquals(oneLineNatural(textW), slot.width, eps)
            assertEquals(1f, lines(textW, slot.width), 0f)
            // Más ancho que el grupo: pegado a su canto derecho, y aun así bajo Masha.
            assertEquals(bar.right, slot.x + slot.width, eps)
            assertTrue(slot.x <= masha.left)
            assertEquals(masha.bottom + 8f, slot.y, eps)
            val room = bar.right - (s.rail + 8f)
            assertTrue("sobra %.0f".format(room - slot.width), room - slot.width > 200f)
        }
    }

    /** Ventana justa: la línea se parte en dos (no se corta) y sigue bajo Masha, sin pisar el dock que asoma. */
    @Test
    fun `on the narrowest window the line wraps to two lines under masha`() = listOf(false, true).forEach { status ->
        val s = scene(640f, 360f, pills(dockLabels.getValue("es").copyOf(3), 1.3f), glass = true, hints = 0f)
        val bar = topBar(s, "es", 1.3f, searchOpen = false, status = status)
        val masha = mashaIn(bar)
        val textW = insight.getValue("es") * 1.3f
        val dock = BubbleSlot.Area(s.plan.dockX, s.dockTop, s.dockRight, s.dockBottom)
        val slot = slot(s, masha, bar, listOf(dock, bar, heroBlock(s, 1.3f)), textW, 1.3f)
        assertTrue(slot.clear)
        assertTrue(slot.width < oneLineNatural(textW))
        assertEquals(2f, lines(textW, slot.width), 0f)
        assertTrue(slot.x <= masha.left)
    }

    /** Si ni partido cabe sin tapar el hero (letra enorme con un hero muy alto), no se enseña. */
    @Test
    fun `the masha bubble is not shown when it would cover the hero`() = listOf(false, true).forEach { status ->
        val s = scene(892f, 411f, pills(dockLabels.getValue("es").copyOf(3), 1f), glass = true, hints = 0f)
        val bar = topBar(s, "es", 1f, searchOpen = false, status = status)
        val masha = mashaIn(bar)
        val tall = BubbleSlot.Area(s.rail + MeridianGeometry.HERO_GAP, 60f, 892f - MeridianTopRow.END, 380f)
        assertFalse(slot(s, masha, bar, listOf(bar, tall), insight.getValue("es"), 1f).clear)
    }

    /**
     * Clásico en vertical: el bocadillo va debajo de Masha y, si el dock está
     * justo debajo (sección vacía, el dock dentro de un hero bajo), por debajo
     * del dock; nunca encima de Masha ni del dock.
     */
    @Test
    fun `in classic the masha bubble goes below masha and clears the dock`() {
        for ((w, h) in listOf(393f to 851f, 360f to 640f, 412f to 732f)) for (heroH in listOf(h * 0.42f, 150f, 110f)) {
            val masha = BubbleSlot.Area(18f, 13f, 58f, 53f)
            val dockW = 181f
            // Dentro del hero (vacía) o en la costura.
            for (dockTop in listOf(heroH - DotTabsLayout.HEIGHT - 16f, heroH - 6f)) {
                val dock = BubbleSlot.Area((w - dockW) / 2f, dockTop, (w + dockW) / 2f, dockTop + DotTabsLayout.HEIGHT)
                for (bh in listOf(30f, 47f, 92f)) {
                    val (x, y) = BubbleSlot.below(masha, minOf(380f, w - 16f), bh, BubbleSlot.Area(0f, 0f, w, h), listOf(dock), 8f, 8f)
                    val box = BubbleSlot.Area(x, y, x + minOf(380f, w - 16f), y + bh)
                    val name = "%.0f×%.0f hero=%.0f dock=%.0f alto=%.0f".format(w, h, heroH, dockTop, bh)
                    assertFalse(name, box.overlaps(masha))
                    assertFalse(name, box.overlaps(dock))
                    assertTrue(name, box.bottom <= h)
                }
            }
        }
    }
}
