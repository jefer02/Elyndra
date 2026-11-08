package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.selection.SelectionFx
import kotlin.math.floor

/**
 * El Clásico (carrusel horizontal) de Biblioteca y Carpeta, sin Compose: todo
 * en dp. Sustituye, para este modo, al reparto de `metrics()` + [ShelfLayout].
 *
 * **Por qué.** Antes el hero tenía un alto fijo (un porcentaje del alto libre)
 * y su bloque de título iba pegado abajo sin saber nada de la barra de arriba.
 * En un móvil apaisado (360 dp de alto) el bloque —fichas, logo, pista y,
 * en Carpeta, la ficha "4 ROMs"— medía más que el hueco bajo la barra y se
 * subía encima de volver / "Abrir" / el emulador. Y como el hero se quedaba
 * con el 60 % del alto, la carátula no pasaba de ~110 dp (un 30 % de la
 * pantalla).
 *
 * **Ahora el orden es el contrario:**
 * 1. La carátula seleccionada manda: una fracción del alto de la ventana
 *    ([coverFraction]: ~48 % en un móvil apaisado, menos en ventanas altas),
 *    con topes por ancho y uno absoluto para tableta y TV ([COVER_MAX]).
 * 2. El estante mide exactamente lo que necesita la fila (halo, subida,
 *    carátula, nombre, margen y pistas del mando) y el hero se queda el resto,
 *    pero nunca menos de lo que pide su bloque mínimo ([heroMin]).
 * 3. El bloque del hero se coloca **debajo** de la barra y, si no cabe, se
 *    compacta por pasos ([heroPlan]): fuera la pista táctil, logo más
 *    pequeño, fichas en una sola línea y, por último, la ficha de la carpeta
 *    sube a la barra, junto al sistema (como en Meridian).
 *
 * Las vecinas se ven algo más pequeñas que la seleccionada ([NEIGHBOR_SCALE])
 * pero todas ocupan la misma caja: los nombres van en una sola línea base
 * ([Frame.labelTop]) y la escala se aplica desde el pie de la carátula, así
 * que ni la escala ni la subida mueven el nombre ni tapan a las vecinas.
 */
object ClassicLayout {

    /* ── Barra de arriba ─────────────────────────────────────── */

    /** Margen sobre la barra (apaisado / vertical), el mismo de siempre. */
    const val BAR_TOP_LANDSCAPE = 8f
    const val BAR_TOP_PORTRAIT = 16f

    /** Alto de la barra de Carpeta (píldoras) y de la Biblioteca (el emblema de Masha es algo mayor). */
    const val BAR_HEIGHT = TopBarLayout.BAR_HEIGHT
    const val BAR_HEIGHT_MASHA = TopBarLayout.MASHA

    /** Aire mínimo entre la barra y el bloque del hero. */
    const val BAR_GAP = 6f

    /** Margen bajo el bloque del hero (el de siempre). */
    const val HERO_BOTTOM_LANDSCAPE = 8f
    const val HERO_BOTTOM_PORTRAIT = 18f

    /** Dónde acaba la barra, desde arriba del hero. */
    fun barBottom(landscape: Boolean, barHeight: Float = BAR_HEIGHT): Float =
        (if (landscape) BAR_TOP_LANDSCAPE else BAR_TOP_PORTRAIT) + barHeight

    fun heroBottomPad(landscape: Boolean): Float = if (landscape) HERO_BOTTOM_LANDSCAPE else HERO_BOTTOM_PORTRAIT

    /**
     * Aire mínimo entre el bloque del hero y el dock que sube desde la costura
     * (pestañas y orden, en una ventana estrecha). Con el margen de siempre
     * (18 dp) y el dock subiendo 12 quedaban 6 dp: la sinopsis se pegaba a
     * las pestañas en un móvil pequeño.
     */
    const val SEAM_CLEAR = 10f

    /** El margen inferior del bloque cuando el dock sube [seamRise] dp dentro del hero. */
    fun heroBottomPad(landscape: Boolean, seamRise: Float): Float =
        maxOf(heroBottomPad(landscape), seamRise + SEAM_CLEAR)

    /* ── Carrusel ────────────────────────────────────────────── */

    /** Lo que sube la seleccionada (= `SelectionLift`) y el aire para su halo. */
    const val LIFT = ShelfLayout.LIFT
    const val GLOW_TOP = ShelfLayout.GLOW_TOP

    /** Aire entre el nombre y el pie (o las pistas del mando), y las pistas con su margen. */
    const val BOTTOM_MARGIN = ShelfLayout.BOTTOM_MARGIN
    const val HINTS = ShelfLayout.HINTS

    /**
     * Las vecinas, a este tamaño de la seleccionada (escala desde el pie).
     * 0,9: se nota quién manda sin que la fila parezca de dos tamaños.
     */
    const val NEIGHBOR_SCALE = 0.9f

    /**
     * Fracción del alto de la ventana para la carátula seleccionada.
     * - Ventana baja (móvil apaisado, ≤ [SHORT_WINDOW]): 0,48, en mitad de la
     *   horquilla pedida (45–55 %). Más dejaría al hero sin sitio para el logo
     *   con la barra de pistas del mando puesta (360 dp: 173 dp de carátula,
     *   ~110 dp de hero; a 0,55 serían 198 y el hero bajaría a ~85).
     * - Ventana alta (tableta, ≥ [TALL_WINDOW]): 0,36; entre medias, curva suave.
     *   En una pantalla alta el hero tiene mucho que enseñar y la carátula ya
     *   es grande en dp; además la frena [COVER_MAX].
     * - Vertical: 0,30 como tope. El hero (titular, sinopsis) necesita el
     *   alto; en la práctica manda el ancho ([portraitTileWidth]).
     */
    const val FRACTION_SHORT = 0.48f
    const val FRACTION_TALL = 0.36f
    const val FRACTION_PORTRAIT = 0.30f
    const val SHORT_WINDOW = 480f
    const val TALL_WINDOW = 900f

    /** Ancho máximo de la carátula, en fracción del ancho de la ventana: que quepan varias. */
    const val WIDTH_LANDSCAPE = 0.22f

    /* ── Vertical: cuántas se ven y con qué aire ─────────────── */

    /** Margen lateral del carrusel (el `pad` de `metrics()`): apaisado y vertical. */
    const val PAD_LANDSCAPE = 22f
    const val PAD_PORTRAIT = 18f

    fun pad(landscape: Boolean): Float = if (landscape) PAD_LANDSCAPE else PAD_PORTRAIT

    /** El ancho de la fila sin sus márgenes. */
    fun rowWidth(width: Float, landscape: Boolean): Float = (width - 2 * pad(landscape)).coerceAtLeast(0f)

    /**
     * Las vecinas en vertical, a este tamaño de la seleccionada. Más cerca de
     * 1 que en apaisado ([NEIGHBOR_SCALE]): escalando desde el centro del pie,
     * cada vecina pierde (1 − escala) / 2 de su ancho por cada lado y ese
     * hueco se suma al aire. Al 90 % una card de 140 dp abría 7 dp por lado;
     * con un aire a la vista de 9 dp la maqueta se quedaba en 2 dp y, entre
     * dos vecinas, el hueco era el doble que junto a la seleccionada. Al 94 %
     * se sigue notando quién manda (con la subida, el halo y el filo) y los
     * huecos de la fila se parecen.
     */
    const val PORTRAIT_NEIGHBOR_SCALE = 0.94f

    fun neighborScale(landscape: Boolean): Float = if (landscape) NEIGHBOR_SCALE else PORTRAIT_NEIGHBOR_SCALE

    /** Lo que encoge una vecina por cada lado (dp): la escala va desde el centro del pie. */
    fun neighborInset(tile: Float, landscape: Boolean): Float = tile * (1f - neighborScale(landscape)) / 2f

    /**
     * El aire **a la vista** entre la seleccionada y su vecina en vertical:
     * de filo dibujado a filo dibujado, con la vecina ya encogida. 9 dp en un
     * móvil, 12 en tableta (con una transición suave entre [PORTRAIT_PHONE_ROW]
     * y [PORTRAIT_TABLET_ROW] de fila). Antes se fijaba el aire de la maqueta
     * (16 dp) y a la vista quedaban ~22: los 16 más los ~6 dp que la vecina al
     * 90 % dejaba libres en su lado.
     */
    const val PORTRAIT_VISIBLE_GAP = 9f
    const val PORTRAIT_VISIBLE_GAP_TABLET = 12f

    /** Filas (ventana menos márgenes) de un móvil grande (420 dp) y de una tableta pequeña (600 dp). */
    const val PORTRAIT_PHONE_ROW = 384f
    const val PORTRAIT_TABLET_ROW = 564f

    /** El aire a la vista entre la seleccionada y su vecina en una fila vertical de [row] dp. */
    fun portraitVisibleGap(row: Float): Float =
        PORTRAIT_VISIBLE_GAP + (PORTRAIT_VISIBLE_GAP_TABLET - PORTRAIT_VISIBLE_GAP) * ShelfLayout.smoothstep(PORTRAIT_PHONE_ROW, PORTRAIT_TABLET_ROW, row)

    /**
     * Lo que se deja libre entre lo más lejano del halo y el filo de la
     * vecina. El halo (estela y luz en el estante) se recorta en vertical a
     * [portraitVisibleGap] menos esto: puede entrar en el aire, nunca en la
     * carátula de al lado.
     */
    const val HALO_CLEAR = 2f

    /** Lo que el halo de la seleccionada asoma de lado en vertical (dp); en apaisado, el de siempre. */
    fun portraitHaloSpread(row: Float): Float = portraitVisibleGap(row) - HALO_CLEAR

    /**
     * El ancho de la card en vertical, en fracción de la fila. En un móvil de
     * 360 dp: carátulas de ~136 dp e iconos de ~149, unas 2,1–2,3 cards a la
     * vista contando las que asoman. El icono va algo más ancho que la
     * carátula: con menos alto, se ve más ligero (su área queda en ~80 % de la
     * de la carátula). Antes salía de cuántas se querían ver (2,55 / 2,35) con
     * 16 dp de aire y la card se quedaba en ~117 / ~129 dp.
     */
    const val PORTRAIT_COVER_SHARE = 0.42f
    const val PORTRAIT_SQUARE_SHARE = 0.46f

    /** Ancho (dp) de la card en vertical en una fila de [row] dp (antes de los topes de alto). */
    fun portraitTileWidth(row: Float, square: Boolean): Float =
        (row * if (square) PORTRAIT_SQUARE_SHARE else PORTRAIT_COVER_SHARE).coerceAtLeast(0f)

    /** Cuántas cards caben (fracción) en [row] con cards de [tile] y aire [gap]. */
    fun visibleCount(row: Float, tile: Float, gap: Float): Float =
        if (tile <= 0f) 0f else (row + gap) / (tile + gap)

    /**
     * Lo que el halo de la seleccionada (`SelectionFrame`: elipse de radio
     * 0,58 del ancho, centrada 4 dp bajo el pie y de 10 dp de alto) asoma de
     * lado (fracción del ancho) y por debajo del pie (dp).
     */
    const val UNDERGLOW_SIDE = SelectionFx.UNDERGLOW_RADIUS - 0.5f
    const val UNDERGLOW_DROP = 14f

    /**
     * Tope absoluto (dp) del alto de la carátula: en tableta y TV no se vuelve
     * un póster (240 dp: ~44 % de una TV de 540 dp, ~30 % de una tableta de 800).
     */
    const val COVER_MAX = 240f

    /**
     * Las cards cuadradas (icono de app, carpeta) se ven más grandes que una
     * carátula del mismo alto (tienen más área): [SQUARE_FACTOR] de su alto y
     * su propio tope.
     */
    const val SQUARE_FACTOR = 0.85f
    const val SQUARE_MAX = 200f

    /** Por debajo de esto una card no se distingue (si el alto lo permite). */
    const val TILE_MIN_LANDSCAPE = 72f
    const val TILE_MIN_PORTRAIT = 96f

    /** El reparto de la pantalla en Clásico. Todo en dp, desde arriba del estante salvo [heroHeight]. */
    data class Frame(
        val heroHeight: Float,
        val shelfHeight: Float,
        /** Caja de cada card (la seleccionada a escala 1; las vecinas, a [NEIGHBOR_SCALE] dentro de la misma caja). */
        val tileHeight: Float,
        val tileWidth: Float,
        /** Canto superior de la caja de la card. */
        val tileTop: Float,
        /** Línea de los nombres: la misma para todas, la seleccionada incluida. */
        val labelTop: Float,
    )

    /** Fracción del alto para la carátula en una ventana de [width] × [height] dp. */
    fun coverFraction(width: Float, height: Float): Float {
        if (height > width) return FRACTION_PORTRAIT
        val t = ShelfLayout.smoothstep(SHORT_WINDOW, TALL_WINDOW, height)
        return FRACTION_SHORT + (FRACTION_TALL - FRACTION_SHORT) * t
    }

    /**
     * Reparte la ventana.
     *
     * @param aspect ancho / alto de la card (2/3 carátula, 1 icono).
     * @param labelBlock hueco y línea del nombre bajo la card (con la escala de letra).
     * @param topInset lo que va arriba del estante y le quita alto (el dock en la costura).
     * @param heroMin lo mínimo que necesita el hero: barra + bloque compacto ([heroMin]).
     */
    fun frame(
        width: Float,
        height: Float,
        aspect: Float,
        hintsVisible: Boolean,
        labelBlock: Float,
        topInset: Float,
        heroMin: Float,
    ): Frame {
        val portrait = height > width
        val square = aspect >= 0.99f
        val hints = if (hintsVisible) HINTS else 0f
        val fixed = topInset + GLOW_TOP + LIFT + labelBlock + BOTTOM_MARGIN + hints
        val target = height * coverFraction(width, height) * (if (square) SQUARE_FACTOR else 1f)
        // En vertical el ancho lo decide cuántas se quieren ver; en apaisado, una fracción del ancho.
        val byWidth = if (portrait) portraitTileWidth(rowWidth(width, landscape = false), square) / aspect else width * WIDTH_LANDSCAPE / aspect
        val cap = if (square) SQUARE_MAX else COVER_MAX
        // Lo que deja el hero en su mínimo: la carátula nunca se lo quita.
        val room = (height - heroMin - fixed).coerceAtLeast(0f)
        val min = minOf(if (portrait) TILE_MIN_PORTRAIT else TILE_MIN_LANDSCAPE, room)
        val tile = minOf(target, byWidth, cap, room).coerceAtLeast(min)
        val shelf = fixed + tile
        val top = topInset + GLOW_TOP + LIFT
        return Frame(
            heroHeight = height - shelf,
            shelfHeight = shelf,
            tileHeight = tile,
            tileWidth = tile * aspect,
            tileTop = top,
            // [labelBlock] es este hueco más la línea del nombre.
            labelTop = top + tile + ShelfLayout.LABEL_GAP,
        )
    }

    /**
     * Dónde queda el pie visible de una card: la caja entera para la
     * seleccionada (subida [LIFT]); las vecinas encogen desde el pie, que se
     * queda en su sitio. Ninguna pasa de la línea de los nombres.
     */
    fun coverBottom(frame: Frame, selected: Boolean): Float =
        frame.tileTop + frame.tileHeight - if (selected) LIFT else 0f

    /** Lo que mide de alto lo que se ve de una card. */
    fun visibleHeight(frame: Frame, selected: Boolean, landscape: Boolean = true): Float =
        frame.tileHeight * if (selected) 1f else neighborScale(landscape)

    /* ── Reparto a lo ancho ──────────────────────────────────── */

    /** Aire mínimo y máximo entre cards. */
    const val MIN_GAP = 14f
    const val MAX_GAP = 40f

    /** Lo que asoma la card siguiente en el borde derecho (fracción de card): se ve que la fila sigue. */
    const val PEEK = 0.5f

    /**
     * El aire entre cards para que la fila llene el ancho [row] con cards de
     * [tile]: k cards enteras y media más asomando, sin bajar de [MIN_GAP] ni
     * pasar de [MAX_GAP]. Antes era un 12 dp fijo y en un móvil apaisado la
     * fila se quedaba en la mitad izquierda.
     */
    fun spacing(row: Float, tile: Float, landscape: Boolean = true): Float {
        // En vertical manda el aire a la vista (ver PORTRAIT_VISIBLE_GAP): la
        // maqueta pone lo que falta tras restar lo que la vecina encoge por su
        // lado. Estirarlo para "llenar la fila" solo separaba las cards.
        if (!landscape) return (portraitVisibleGap(row) - neighborInset(tile, landscape = false)).coerceAtLeast(0f)
        if (tile <= 0f || row <= tile) return MIN_GAP
        val k = floor((row - PEEK * tile + MIN_GAP) / (tile + MIN_GAP)).toInt().coerceAtLeast(1)
        return ((row - (k + PEEK) * tile) / k).coerceIn(MIN_GAP, MAX_GAP)
    }

    /**
     * El aire que se ve entre los filos dibujados de dos cards vecinas de la
     * fila (dp): el de la maqueta más lo que encoge cada una por ese lado.
     * [selectedSide] = una de las dos es la seleccionada (a escala 1).
     */
    fun visibleGap(gap: Float, tile: Float, landscape: Boolean, selectedSide: Boolean = true): Float =
        gap + neighborInset(tile, landscape) * if (selectedSide) 1f else 2f

    /**
     * Cómo se dibuja la fila, ya decidido para la pantalla: aire de la
     * maqueta, escala de las vecinas, hasta dónde llega el halo de la
     * seleccionada de lado ([haloSpread] dp; null = el de siempre, el de
     * apaisado) y cuánto se recogen los nombres por cada lado ([labelInset]).
     */
    data class RowStyle(val gap: Float, val neighborScale: Float, val haloSpread: Float?, val labelInset: Float)

    /**
     * El [RowStyle] de una fila de [row] dp con cards de [tile] dp de ancho.
     * En apaisado es exactamente el de siempre (aire para llenar la fila,
     * vecinas al 90 %, halo y nombres sin tocar).
     *
     * En vertical los nombres se recogen lo que encoge la vecina: van del
     * ancho de lo que se ve de la card y, entre dos nombres recortados, queda
     * al menos el aire a la vista (con 5 dp de maqueta se rozaban).
     */
    fun rowStyle(row: Float, tile: Float, landscape: Boolean): RowStyle =
        if (landscape) {
            RowStyle(spacing(row, tile, landscape = true), NEIGHBOR_SCALE, haloSpread = null, labelInset = 0f)
        } else {
            RowStyle(spacing(row, tile, landscape = false), PORTRAIT_NEIGHBOR_SCALE, portraitHaloSpread(row), neighborInset(tile, landscape = false))
        }

    /**
     * Lo que el halo de la seleccionada asoma de lado (dp) con [spread]
     * (el de [RowStyle.haloSpread]): la estela por fuera del contorno o la
     * luz del estante, la que más llegue. Sin recorte, las de siempre.
     */
    fun haloSide(tile: Float, spread: Float?): Float =
        spread ?: maxOf(SelectionFx.HALO_DP, tile * UNDERGLOW_SIDE)

    /**
     * Cuántas cards se dejan a la izquierda de la seleccionada al desplazar la
     * fila: una, para que se vea de dónde se viene (antes la seleccionada se
     * pegaba al borde y la anterior desaparecía).
     */
    const val LEAD = 1

    /** El índice al que se desplaza la fila para enseñar [index] con [LEAD] cards delante. */
    fun anchorIndex(index: Int): Int = (index - LEAD).coerceAtLeast(0)

    /** Lo que asoma de la card anterior en vertical, a la izquierda de la seleccionada (dp). */
    const val PORTRAIT_PEEK = 24f

    /**
     * A dónde se desplaza la fila: [index] del primer elemento visible y
     * cuánto de él queda fuera por la izquierda ([offset], dp, como el
     * `scrollOffset` de `LazyListState`).
     */
    data class Anchor(val index: Int, val offset: Float)

    /**
     * Cómo se desplaza la fila para enseñar la seleccionada [index].
     * - Apaisado: una card entera delante ([LEAD]); caben de sobra dos.
     * - Vertical: la seleccionada en el primer hueco, con [PORTRAIT_PEEK] dp
     *   de la anterior asomando por la izquierda. Antes se dejaba una card
     *   entera delante también aquí y, con una card y media por pantalla, la
     *   seleccionada quedaba cortada por el borde derecho.
     * La primera va pegada al margen; al final la lista ya no se desplaza más
     * (la última queda en el margen derecho, ver [selectedSpan]).
     */
    fun anchor(index: Int, tileWidth: Float, gap: Float, landscape: Boolean): Anchor = when {
        index <= 0 -> Anchor(0, 0f)
        landscape -> Anchor(anchorIndex(index), 0f)
        else -> Anchor(index - 1, (tileWidth + gap - PORTRAIT_PEEK).coerceAtLeast(0f))
    }

    /**
     * Dónde queda la seleccionada en pantalla (dp, borde izquierdo y derecho)
     * con [count] cards en una ventana de [width], tal como la coloca una
     * `LazyRow` con el margen [pad] a los dos lados: el desplazamiento de
     * [anchor] limitado a lo que la lista puede moverse.
     */
    fun selectedSpan(index: Int, count: Int, tileWidth: Float, gap: Float, width: Float, landscape: Boolean): Pair<Float, Float> {
        val pad = pad(landscape)
        val stride = tileWidth + gap
        val a = anchor(index, tileWidth, gap, landscape)
        val content = 2 * pad + count * tileWidth + (count - 1).coerceAtLeast(0) * gap
        val maxScroll = (content - width).coerceAtLeast(0f)
        val scroll = (a.index * stride + a.offset).coerceIn(0f, maxScroll)
        val x = pad + index * stride - scroll
        return x to x + tileWidth
    }

    /* ── Bloque del hero ─────────────────────────────────────── */

    /** El logo (o el titular) no baja de esto; por debajo se ve como un sello. */
    const val LOGO_MIN = 28f

    /**
     * En el paso "logo más pequeño" el logo encoge hasta esta fracción de su
     * alto (la misma que deja `HeroLogo`), no hasta [LOGO_MIN]: un logo de
     * 30 dp con las fichas en píldora y la ficha de la carpeta debajo se veía
     * peor que fundir las fichas y subir la ficha a la barra, que es lo que
     * pasa a continuación y le devuelve el alto.
     */
    const val LOGO_SHRINK = 0.6f

    /** Lo menos que encoge un logo de [logoMax] en el paso [Step.SmallLogo]. */
    fun logoMinFor(logoMax: Float): Float = maxOf(LOGO_MIN, logoMax * LOGO_SHRINK).coerceAtMost(logoMax)

    /**
     * Lo que mide cada pieza del bloque del hero (dp, con la escala de letra
     * ya aplicada); 0 = no hay. [logoMax] es el alto que el diseño le daría con
     * sitio de sobra.
     */
    data class Block(
        /** Fichas ("ROM · PSP", logros) y su línea de datos, en su fila de píldoras. */
        val chips: Float,
        /** Las mismas, fundidas en una sola línea de texto (sin píldora): más baja. */
        val mergedChips: Float,
        val logoMax: Float,
        val logoMin: Float = logoMinFor(logoMax),
        /** Lo de bajo el logo con su aire: la pista táctil o la sinopsis (entera). */
        val secondary: Float = 0f,
        /** Sus líneas (la sinopsis, 2–3; la pista, 1) y lo que mide cada una: la sinopsis pierde líneas antes de irse. */
        val secondaryLines: Int = 1,
        val secondaryLine: Float = 0f,
        /** La ficha de la carpeta ("4 ROMs · 0h 0m") con su aire; 0 en la biblioteca. */
        val stats: Float = 0f,
    )

    /** Los pasos de compactación, en el orden en que se aplican. */
    enum class Step { Full, FewerLines, NoSecondary, SmallLogo, MergedChips, StatsInBar }

    data class HeroPlan(
        val step: Step,
        val showSecondary: Boolean,
        /** Líneas de la sinopsis que caben (0 sin sinopsis o sin sitio). */
        val secondaryLines: Int = 0,
        val logoHeight: Float,
        val mergedChips: Boolean,
        val statsInBar: Boolean,
        /** Lo que mide el bloque con este plan. */
        val height: Float,
    )

    /** El hueco del bloque del hero: bajo la barra (con su aire) y sobre su margen inferior. */
    fun heroRoom(heroHeight: Float, barBottom: Float, bottomPad: Float): Float =
        (heroHeight - barBottom - BAR_GAP - bottomPad).coerceAtLeast(0f)

    /** Lo mínimo que necesita el hero: la barra y el bloque en su forma más compacta. */
    fun heroMin(barBottom: Float, bottomPad: Float, block: Block): Float =
        barBottom + BAR_GAP + bottomPad + compactHeight(block)

    private fun compactHeight(b: Block): Float = (if (b.chips > 0f) b.mergedChips else 0f) + minOf(b.logoMin, LOGO_MIN)

    /**
     * El primer paso con el que el bloque cabe en [room], en este orden:
     * 1. Sin la línea secundaria (pista táctil o sinopsis).
     * 2. El logo encoge (hasta [Block.logoMin]).
     * 3. Las fichas en una sola línea.
     * 4. La ficha de la carpeta sube a la barra.
     * Cada paso conserva los anteriores. En el último el logo toma todo lo
     * que queda (puede volver a crecer: ya no hay ficha ni píldoras) y, si ni
     * así cabe, baja de su mínimo hasta 0 (la pantalla lo quita): el bloque
     * nunca se sube a la barra.
     */
    fun heroPlan(room: Float, block: Block): HeroPlan {
        val chips = block.chips
        val merged = if (chips > 0f) block.mergedChips else 0f
        val maxLines = if (block.secondary > 0f) block.secondaryLines.coerceAtLeast(1) else 0
        // Lo que mide lo de bajo el logo con [lines] líneas (0 = fuera).
        fun secondary(lines: Int): Float = when {
            lines <= 0 || maxLines == 0 -> 0f
            else -> block.secondary - (maxLines - lines) * block.secondaryLine
        }
        fun h(lines: Int, logo: Float, mergedChips: Boolean, statsInBar: Boolean) =
            (if (mergedChips) merged else chips) + logo + secondary(lines) + (if (statsInBar) 0f else block.stats)
        fun plan(step: Step, lines: Int, logo: Float, mergedChips: Boolean, statsInBar: Boolean) = HeroPlan(
            step = step,
            showSecondary = lines > 0,
            secondaryLines = lines,
            logoHeight = logo,
            mergedChips = mergedChips,
            statsInBar = statsInBar,
            height = h(lines, logo, mergedChips, statsInBar),
        )

        if (h(maxLines, block.logoMax, false, false) <= room) return plan(Step.Full, maxLines, block.logoMax, false, false)

        // 1a. La sinopsis pierde líneas (3 → 1) antes de irse: se sigue leyendo de qué va.
        if (block.secondaryLine > 0f) {
            for (lines in maxLines - 1 downTo 1) {
                if (h(lines, block.logoMax, false, false) <= room) return plan(Step.FewerLines, lines, block.logoMax, false, false)
            }
        }

        // 1b. Fuera la pista (o la sinopsis).
        if (h(0, block.logoMax, false, false) <= room) return plan(Step.NoSecondary, 0, block.logoMax, false, false)

        // 2. El logo encoge lo justo para que quepa el resto, sin bajar de su mínimo.
        val restSmall = h(0, 0f, false, false)
        val logoMin = minOf(block.logoMin, block.logoMax)
        if (restSmall + logoMin <= room) {
            return plan(Step.SmallLogo, 0, (room - restSmall).coerceIn(logoMin, block.logoMax), false, false)
        }

        // 3. Las fichas en una línea.
        val restMerged = h(0, 0f, true, false)
        if (restMerged + logoMin <= room) {
            return plan(Step.MergedChips, 0, (room - restMerged).coerceIn(logoMin, block.logoMax), true, false)
        }

        // 4. La ficha de la carpeta sube a la barra; el logo toma lo que queda.
        val restBar = h(0, 0f, true, true)
        return plan(Step.StatsInBar, 0, (room - restBar).coerceIn(0f, block.logoMax), true, block.stats > 0f)
    }

    /* ── Lo que mide cada pieza (dp; los textos, por la escala de letra) ── */

    /** Alto de una línea de Poppins por sp de cuerpo (su ascendente y descendente son altos). */
    const val TEXT_LINE = 1.5f

    /** Cuerpos (sp) de las piezas del bloque, los de siempre. */
    const val CHIP_SP = 8.5f
    const val SUBLINE_SP = 9.5f
    const val HINT_SP = 9f
    const val SYNOPSIS_SP = 10f
    const val SYNOPSIS_LINE = 1.28f

    /** Relleno vertical de la ficha ("ROM · PSP") y la ficha de la carpeta con su aire encima. */
    const val CHIP_PAD_V = 4f
    const val STATS_H = 24f
    const val STATS_GAP = 6f

    private fun rowPad(landscape: Boolean) = if (landscape) 3f else 6f

    /** La fila de fichas: la píldora (o su línea, si es más alta) y su margen. */
    fun chipsHeight(fontScale: Float, landscape: Boolean): Float =
        maxOf(2 * CHIP_PAD_V + CHIP_SP * TEXT_LINE * fontScale, SUBLINE_SP * TEXT_LINE * fontScale) + rowPad(landscape)

    /** Las fichas fundidas en una línea de texto. */
    fun mergedChipsHeight(fontScale: Float, landscape: Boolean): Float =
        SUBLINE_SP * TEXT_LINE * fontScale + rowPad(landscape)

    /** La pista táctil con su aire. */
    fun hintHeight(fontScale: Float, landscape: Boolean): Float =
        HINT_SP * TEXT_LINE * fontScale + if (landscape) 4f else 7f

    /** Líneas de la sinopsis: dos en apaisado, tres en vertical. */
    fun synopsisLines(landscape: Boolean): Int = if (landscape) 2 else 3

    /** Una línea de la sinopsis. */
    fun synopsisLineHeight(fontScale: Float): Float = SYNOPSIS_SP * SYNOPSIS_LINE * fontScale

    /** La sinopsis con su aire. */
    fun synopsisHeight(fontScale: Float, landscape: Boolean): Float =
        synopsisLines(landscape) * SYNOPSIS_SP * SYNOPSIS_LINE * fontScale + if (landscape) 4f else 6f

    /** La ficha de la carpeta al pie del bloque. */
    const val STATS_BLOCK = STATS_H + STATS_GAP

    /** Cuerpo (sp) del titular sin logo para un hero de [heroHeight] dp (la regla de `metrics()`). */
    fun titleSp(heroHeight: Float, landscape: Boolean): Float =
        if (landscape) (heroHeight * 0.34f).coerceIn(50f, 96f) else (heroHeight * 0.20f).coerceIn(56f, 112f)

    /** Alto (dp) de la caja del logo con sitio de sobra (la regla de `metrics()`). */
    fun logoHeight(heroHeight: Float, landscape: Boolean): Float =
        titleSp(heroHeight, landscape) * if (landscape) 1.25f else 1.6f

    /** El cuerpo del titular sin logo que cabe en [boxHeight] dp (una línea), sin pasar de [maxSp]. */
    fun titleSpFor(boxHeight: Float, fontScale: Float, maxSp: Float): Float =
        (boxHeight / (TITLE_LINE * fontScale)).coerceAtMost(maxSp).coerceAtLeast(0f)

    /** Alto de una línea del titular por sp (va con interlineado 0,92 y versalitas). */
    const val TITLE_LINE = 1.1f

    /**
     * ¿Se enseña la pista táctil ("Toca para elegir…")? Nunca con mando (sus
     * pistas van al pie) ni mientras se maneja con él; sí al tocar.
     */
    fun showTouchHint(gamepadPresent: Boolean, padActive: Boolean): Boolean = !gamepadPresent && !padActive
}
