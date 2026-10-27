package com.elyndra.launcher.ui

/**
 * Geometría del dock de secciones ("dot tabs"), sin Compose: cada sección es
 * un hueco; la elegida se abre en la píldora (la misma para todas, la del
 * rótulo más ancho: ver [DotTabsLayout.uniformPill]) y las demás son un punto. Todo
 * en dp (o en px, si se pasa todo en px: no hay constantes de densidad).
 *
 * [expansion] de cada hueco va de 0 (punto) a 1 (píldora): durante el cambio
 * de sección las dos que cambian están a medias, y así el dock entero se
 * deduce de esos valores en cualquier fotograma.
 */
object DotTabsLayout {

    /** Alto del dock: el de las píldoras de la barra del hero. */
    const val HEIGHT = 34f

    /** Margen entre el canto de la cápsula y la píldora (radios concéntricos). */
    const val INSET = 3f

    /**
     * Ancho del hueco de un punto. Con [GAP] da 48 dp de zona táctil: cada
     * pestaña cubre su hueco y la mitad de los huecos de al lado, y de alto
     * [TOUCH] (sobresale de la cápsula por arriba y por abajo).
     */
    const val DOT_SLOT = 44f

    /** Diámetro del punto. */
    const val DOT = 8f

    const val GAP = 4f

    /** Alto de la zona táctil de cada pestaña. */
    const val TOUCH = 48f

    /** Aire del rótulo dentro de la píldora, a cada lado. */
    const val LABEL_PAD = 12f

    const val PILL_MIN = 48f

    /** Por encima, el rótulo se corta con puntos suspensivos. */
    const val PILL_MAX = 152f

    val LABEL_MAX = PILL_MAX - 2 * LABEL_PAD

    /**
     * Píldora que aún se lee de un vistazo (~8 letras a 11 sp, "Consolas"):
     * cuando falta sitio, el rótulo se recorta hasta aquí antes de apretar los
     * puntos, y los puntos se aprietan antes de bajar de aquí.
     */
    const val PILL_FIT = 88f

    /**
     * Hueco más estrecho de un punto. Con [GAP], 36 dp de zona táctil a lo
     * ancho (48 de alto): por debajo, el dedo ya no acierta el punto vecino.
     */
    const val DOT_SLOT_MIN = 32f

    /**
     * Cómo cabe el dock en [Fit]: ancho del hueco de cada punto ([dot]) y
     * tope de la píldora ([pillCap]; [PILL_MAX] = sin recorte extra).
     */
    data class Fit(val dot: Float, val pillCap: Float)

    /**
     * El dock con las píldoras naturales [pills] dentro de [maxWidth]: primero
     * entero; si no cabe, el rótulo se recorta hasta [PILL_FIT]; después los
     * puntos se juntan hasta [DOT_SLOT_MIN]; por último la píldora baja hasta
     * [PILL_MIN]. Se mide con la píldora más ancha, que es la que abren todas
     * ([uniformPill]): el dock no cambia de tamaño al cambiar de sección.
     *
     * Por debajo de [minWidth] no hay forma de que quepa: lo tiene que
     * resolver quien lo coloca (la cabecera de la rueda le da más sitio).
     */
    fun fit(pills: FloatArray, maxWidth: Float): Fit {
        val n = pills.size
        val widest = pills.maxOrNull() ?: return Fit(DOT_SLOT, PILL_MAX)
        if (!maxWidth.isFinite()) return Fit(DOT_SLOT, PILL_MAX)
        if (n == 1) return Fit(DOT_SLOT, (maxWidth - 2 * INSET).coerceIn(PILL_MIN, PILL_MAX))
        fun capFor(dot: Float) = maxWidth - 2 * INSET - (n - 1) * (dot + GAP)
        if (capFor(DOT_SLOT) >= widest) return Fit(DOT_SLOT, PILL_MAX)
        val wanted = minOf(widest, PILL_FIT).coerceAtLeast(PILL_MIN)
        if (capFor(DOT_SLOT) >= wanted) return Fit(DOT_SLOT, capFor(DOT_SLOT))
        val dot = ((maxWidth - 2 * INSET - wanted) / (n - 1) - GAP).coerceIn(DOT_SLOT_MIN, DOT_SLOT)
        return Fit(dot, capFor(dot).coerceIn(PILL_MIN, wanted))
    }

    /**
     * La píldora única del dock: la del rótulo más ancho de [pills], con el
     * tope de [fit]. Todas las secciones abren esa misma píldora (el rótulo va
     * centrado): si cada una midiera su rótulo, al pasar de "Todo" a "Consolas"
     * la cápsula crecía, empujaba al vecino y en la cabecera de la rueda se
     * salía de la niebla. Así cambiar de sección solo mueve la píldora.
     */
    fun uniformPill(pills: FloatArray, fit: Fit): Float = minOf(pills.maxOrNull() ?: PILL_MIN, fit.pillCap)

    /** Lo que mide el dock (el mismo con cualquier sección elegida) con las píldoras naturales [pills] en [maxWidth]. */
    fun uniformWidth(pills: FloatArray, maxWidth: Float): Float {
        if (pills.isEmpty()) return 0f
        val fit = fit(pills, maxWidth)
        return 2 * INSET + (pills.size - 1) * (fit.dot + GAP) + uniformPill(pills, fit)
    }

    /**
     * Lo menos que mide el dock con todos los rótulos de [pills] enteros (la
     * píldora única llega a la más ancha): con los puntos en su mínimo si esa
     * píldora no pasa de [PILL_FIT] (así lo encaja [fit]); si pasa, [fit] solo
     * la deja crecer con los puntos enteros.
     */
    fun fullWidth(pills: FloatArray): Float {
        val widest = pills.maxOrNull() ?: return 0f
        if (pills.size == 1) return widest + 2 * INSET
        val dot = if (widest <= PILL_FIT) DOT_SLOT_MIN else DOT_SLOT
        return 2 * INSET + (pills.size - 1) * (dot + GAP) + widest
    }

    /** Lo menos que puede medir un dock de [n] secciones (puntos y píldora en su mínimo). */
    fun minWidth(n: Int): Float =
        if (n <= 0) 0f else 2 * INSET + (n - 1) * (DOT_SLOT_MIN + GAP) + PILL_MIN

    /** Sitio para el rótulo (más su icono de [iconWidth]) en una píldora con tope [pillCap]. */
    fun labelRoom(pillCap: Float, iconWidth: Float = 0f): Float =
        (minOf(pillCap, PILL_MAX) - 2 * LABEL_PAD - iconWidth).coerceAtLeast(0f)

    /** Ancho de la píldora para un rótulo de [labelWidth] (más un icono de [iconWidth], si lo hay). */
    fun pillWidth(labelWidth: Float, iconWidth: Float = 0f): Float =
        (labelWidth + iconWidth + 2 * LABEL_PAD).coerceIn(PILL_MIN, PILL_MAX)

    /** Ancho del hueco: de [dot] (punto) a [pill] (píldora) según [expansion]. */
    fun slotWidth(pill: Float, expansion: Float, dot: Float = DOT_SLOT): Float = dot + (pill - dot) * expansion.coerceIn(0f, 1f)

    /** Borde izquierdo de cada hueco y el ancho total de la cápsula, en [out] (tamaño n + 1). */
    fun offsets(pills: FloatArray, expansion: FloatArray, out: FloatArray = FloatArray(pills.size + 1), dot: Float = DOT_SLOT): FloatArray {
        var x = INSET
        for (i in pills.indices) {
            out[i] = x
            x += slotWidth(pills[i], expansion[i], dot)
            if (i < pills.lastIndex) x += GAP
        }
        out[pills.size] = x + INSET
        return out
    }

    /**
     * Zona táctil de cada hueco (izquierda, derecha): se reparten los huecos
     * entre pestañas y los márgenes de la cápsula, sin dejar nada muerto.
     */
    fun touchSpan(offsets: FloatArray, pills: FloatArray, expansion: FloatArray, i: Int, dot: Float = DOT_SLOT): Pair<Float, Float> {
        val n = pills.size
        val left = if (i == 0) 0f else offsets[i] - GAP / 2f
        val right = if (i == n - 1) offsets[n] else offsets[i] + slotWidth(pills[i], expansion[i], dot) + GAP / 2f
        return left to right
    }

    /** Las expansiones de reposo con [selected] elegida. */
    fun rest(n: Int, selected: Int): FloatArray = FloatArray(n) { if (it == selected) 1f else 0f }

    /** Dónde acaba la píldora (izquierda, derecha) con [selected] ya asentada. */
    fun pillTarget(pills: FloatArray, selected: Int, dot: Float = DOT_SLOT): Pair<Float, Float> {
        val o = offsets(pills, rest(pills.size, selected), dot = dot)
        return o[selected] to o[selected] + pills[selected]
    }

    fun totalWidth(pills: FloatArray, selected: Int, dot: Float = DOT_SLOT): Float = offsets(pills, rest(pills.size, selected), dot = dot)[pills.size]

    /** Centro del hueco [i]. */
    fun slotCenter(pills: FloatArray, expansion: FloatArray, i: Int, dot: Float = DOT_SLOT): Float {
        val o = offsets(pills, expansion, dot = dot)
        return o[i] + slotWidth(pills[i], expansion[i], dot) / 2f
    }

    /**
     * Muelles de los dos cantos de la píldora: el que va delante tira fuerte y
     * el de atrás llega tarde, y en medio la píldora se estira como una gota.
     * Devuelve (rigidez del canto izquierdo, del derecho).
     */
    fun edgeStiffness(from: Int, to: Int): Pair<Float, Float> = when {
        to > from -> TRAILING to LEADING
        to < from -> LEADING to TRAILING
        else -> LEADING to LEADING
    }

    const val LEADING = 900f
    const val TRAILING = 320f

    /** Alfa del rótulo de un hueco que se recoge: se apaga en la primera mitad del cierre. */
    fun fadingLabelAlpha(expansion: Float): Float = ((expansion.coerceIn(0f, 1f) - 0.5f) * 2f).coerceIn(0f, 1f)

    /** Escala del punto: crece cuanto más recogido está el hueco. */
    fun dotScale(expansion: Float): Float = (1f - expansion.coerceIn(0f, 1f) * 1.4f).coerceIn(0f, 1f)
}

/**
 * Dónde va el dock de la biblioteca según el ancho de la ventana (nunca el
 * tipo de aparato): en ventana ancha, en la barra del hero, abriendo el grupo
 * de la derecha (junto a la hora y "Abrir", a mano); en estrecha, en la
 * costura hero/estante.
 */
object DockPlacement {

    /**
     * Desde este ancho (dp) el dock y el orden caben en la barra del hero, en
     * el grupo de la derecha, sin llegar al botón de Masha (con cuatro
     * secciones en alemán y la hora visible).
     */
    const val BAR_MIN_WIDTH = 760f

    fun inBar(windowWidth: Float): Boolean = windowWidth >= BAR_MIN_WIDTH

    /** En la costura hero/estante: cuánto sube el dock dentro del hero sin tocar el bloque de título. */
    fun seamRise(heroInfoBottomPad: Float): Float = (heroInfoBottomPad - 6f).coerceIn(0f, 12f)

    /**
     * Con la sección vacía ([empty]) el dock de la costura va entero dentro
     * del hero. Con juegos, su arte se prolonga sobre el estante y la cápsula
     * a caballo se lee como una pieza; sin juegos, la costura es un corte duro
     * (escenario arriba, estante liso abajo) y el dock quedaba partido por la
     * mitad. En la barra ([inBar]) no hay costura que evitar.
     */
    fun insideHero(empty: Boolean, inBar: Boolean): Boolean = empty && !inBar

    /** Aire entre el dock y el canto de abajo del hero cuando va dentro. */
    const val HERO_GAP = 16f

    /** Lo que sube el bloque de título del hero para que el dock de dentro no lo pise. */
    fun heroLift(dockHeight: Float): Float = dockHeight + HERO_GAP

    /**
     * Borde de arriba del dock (dp, desde arriba del hero de [heroHeight]):
     * dentro del hero ([inHero]), apoyado a [HERO_GAP] de su canto; en la
     * costura, a caballo, subiendo [seamRise] sobre ella.
     */
    fun top(heroHeight: Float, dockHeight: Float, inHero: Boolean, seamRise: Float): Float =
        if (inHero) heroHeight - dockHeight - HERO_GAP else heroHeight - seamRise
}

/**
 * El dock de secciones con el botón de orden a su derecha (en la barra, en la
 * costura y en la cabecera de la rueda). El botón se reserva primero y el
 * dock se encoge en lo que queda (ver [DotTabsLayout.fit]): nunca se pisan.
 */
object SectionDockLayout {

    /** Ancho visible del botón de orden (su zona táctil sobresale hasta 48 × 48). */
    const val SORT_WIDTH = 44f

    /** Aire entre el dock y el botón de orden (= Space.s). */
    const val SORT_GAP = 8f

    /** Lo que le queda al dock dentro de [maxWidth], con el botón ya reservado. */
    fun tabsMaxWidth(maxWidth: Float): Float = (maxWidth - SORT_GAP - SORT_WIDTH).coerceAtLeast(0f)

    /** Dónde empieza el botón: tras lo que mide de verdad el dock, más el aire. */
    fun sortX(tabsWidth: Float): Float = tabsWidth + SORT_GAP

    fun width(tabsWidth: Float): Float = sortX(tabsWidth) + SORT_WIDTH

    /** Lo menos que mide el conjunto con [n] secciones. */
    fun minWidth(n: Int): Float = width(DotTabsLayout.minWidth(n))
}
