package com.elyndra.launcher.ui

/**
 * La barra de arriba de la biblioteca (Masha, píldora de hora y batería,
 * "Abrir", en Meridian el orden, buscar, la tienda y Ajustes), sin Compose: qué cabe en un ancho dado. Todo en
 * dp; los rótulos llegan ya medidos (con la fuente, el idioma y la escala de
 * letra de verdad) y aquí solo se suman con lo que mide cada pieza.
 *
 * En un móvil en vertical no cabe todo y la fila recortaba lo último, que es
 * Ajustes. En vez de eso se compacta, de lo que menos se echa en falta a lo
 * que más ([Fit]): "Abrir" se queda en su icono, luego la tienda, la píldora
 * pierde el tanto por ciento (la pila sigue) y, en lo más justo, la píldora
 * se va. El botón de la tienda no se va nunca (es la entrada a la tienda de
 * ports): como mucho se queda en su icono, igual que "Abrir".
 */
object TopBarLayout {

    /** Alto de las píldoras de la barra (y lado de los botones de icono). */
    const val BAR_HEIGHT = 34f

    /** Aire entre piezas (= Space.s). */
    const val GAP = 8f

    /** Emblema de Masha: algo mayor que las píldoras, como un avatar. */
    const val MASHA = 40f

    /* "Abrir": icono y rótulo. */
    const val OPEN_PAD_START = 8f
    const val OPEN_PAD_END = 12f
    const val OPEN_GLYPH = 18f
    const val OPEN_GLYPH_GAP = 6f

    /* Píldora de estado: hora · pila y tanto por ciento. */
    const val STATUS_PAD = 11f
    const val STATUS_ITEM_GAP = 8f
    const val STATUS_DOT = 3f
    const val BATTERY_GLYPH = 20f

    /* Buscador abierto: el campo y su margen; más ancho en apaisado. */
    const val SEARCH_FIELD = 96f
    const val SEARCH_FIELD_WIDE = 190f
    const val SEARCH_PAD = 11f

    /* La tienda: el mismo molde que "Abrir" (icono de bolsa y rótulo). */
    const val STORE_PAD_START = OPEN_PAD_START
    const val STORE_PAD_END = OPEN_PAD_END
    const val STORE_GLYPH = OPEN_GLYPH
    const val STORE_GLYPH_GAP = OPEN_GLYPH_GAP

    /** La tienda con su rótulo de [labelWidth], o solo el icono. */
    fun storeWidth(labelWidth: Float, withLabel: Boolean): Float =
        if (withLabel) STORE_PAD_START + STORE_GLYPH + STORE_GLYPH_GAP + labelWidth + STORE_PAD_END else BAR_HEIGHT

    /** "Abrir" con su rótulo de [labelWidth], o solo el icono (un botón cuadrado como sus vecinos). */
    fun openWidth(labelWidth: Float, withLabel: Boolean): Float =
        if (withLabel) OPEN_PAD_START + OPEN_GLYPH + OPEN_GLYPH_GAP + labelWidth + OPEN_PAD_END else BAR_HEIGHT

    /**
     * La píldora de estado: [timeWidth] null = sin hora, [percentWidth] null =
     * sin batería; sin [withPercent], la pila sin el número. 0 = no se ve.
     */
    fun statusWidth(timeWidth: Float?, percentWidth: Float?, withPercent: Boolean): Float {
        var items = 0
        var sum = 0f
        fun add(w: Float) {
            sum += w
            items++
        }
        timeWidth?.let(::add)
        if (percentWidth != null) {
            if (timeWidth != null) add(STATUS_DOT)
            add(BATTERY_GLYPH)
            if (withPercent) add(percentWidth)
        }
        if (items == 0) return 0f
        return 2 * STATUS_PAD + sum + STATUS_ITEM_GAP * (items - 1)
    }

    /** El buscador: cerrado, su botón; abierto, además el campo. */
    fun searchWidth(open: Boolean, landscape: Boolean): Float =
        if (open) BAR_HEIGHT + SEARCH_PAD + if (landscape) SEARCH_FIELD_WIDE else SEARCH_FIELD else BAR_HEIGHT

    /**
     * Lo que siempre está: Masha (si va en esta fila, [masha]), el buscador
     * ([searchWidth]), la tienda en su icono ([store]), Ajustes y el aire
     * entre ellos, más [extra] (el dock, cuando va en la barra) y el botón de
     * orden si va suelto delante del buscador ([sort]: en Meridian, que lo
     * saca de la cabecera de la rueda). El rótulo de la tienda no va aquí:
     * es de lo que se compacta ([Fit.storeLabel]).
     */
    fun fixedWidth(searchWidth: Float, extra: Float = 0f, masha: Boolean = true, sort: Boolean = false, store: Boolean = true): Float =
        (if (masha) MASHA + GAP else 0f) + (if (extra > 0f) extra + GAP else 0f) +
            (if (sort) SectionDockLayout.SORT_WIDTH + GAP else 0f) + searchWidth + GAP +
            (if (store) BAR_HEIGHT + GAP else 0f) + BAR_HEIGHT

    /** Cuánto se compacta la barra, de menos a más. */
    enum class Fit(val openLabel: Boolean, val storeLabel: Boolean, val statusPercent: Boolean, val status: Boolean) {
        Full(openLabel = true, storeLabel = true, statusPercent = true, status = true),
        IconOpen(openLabel = false, storeLabel = true, statusPercent = true, status = true),
        IconStore(openLabel = false, storeLabel = false, statusPercent = true, status = true),
        SmallStatus(openLabel = false, storeLabel = false, statusPercent = false, status = true),
        NoStatus(openLabel = false, storeLabel = false, statusPercent = false, status = false),
    }

    /**
     * El primer [Fit] con el que todo cabe en [available] dp. [openLabel]
     * null = "Abrir" no se ve (buscando); [time] y [percent] como en
     * [statusWidth] (los dos null = la píldora está apagada en Ajustes);
     * [storeLabel], el rótulo de la tienda (null = sin rótulo posible: la
     * barra no lleva tienda, o solo cabe el icono que ya cuenta [fixed]).
     * Si ni así cabe, [Fit.NoStatus]: lo más compacto que hay.
     */
    fun fit(available: Float, fixed: Float, openLabel: Float?, time: Float?, percent: Float?, storeLabel: Float? = null): Fit =
        Fit.entries.firstOrNull { width(it, fixed, openLabel, time, percent, storeLabel) <= available } ?: Fit.NoStatus

    /** Lo que mide la barra con [fit]. */
    fun width(fit: Fit, fixed: Float, openLabel: Float?, time: Float?, percent: Float?, storeLabel: Float? = null): Float {
        var w = fixed
        // [fixed] ya cuenta el icono de la tienda: el rótulo es lo que se suma.
        if (storeLabel != null && fit.storeLabel) w += storeWidth(storeLabel, true) - BAR_HEIGHT
        if (openLabel != null) w += openWidth(openLabel, fit.openLabel) + GAP
        if (fit.status) {
            val s = statusWidth(time, percent, fit.statusPercent)
            if (s > 0f) w += s + GAP
        }
        return w
    }
}
