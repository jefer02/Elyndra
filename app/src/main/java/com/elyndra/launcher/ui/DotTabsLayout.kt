package com.elyndra.launcher.ui

/**
 * Geometría del dock de secciones ("dot tabs"), sin Compose: cada sección es
 * un hueco; la elegida mide lo que su rótulo y las demás son un punto. Todo
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

    /** Ancho de la píldora para un rótulo de [labelWidth] (más un icono de [iconWidth], si lo hay). */
    fun pillWidth(labelWidth: Float, iconWidth: Float = 0f): Float =
        (labelWidth + iconWidth + 2 * LABEL_PAD).coerceIn(PILL_MIN, PILL_MAX)

    fun slotWidth(pill: Float, expansion: Float): Float = DOT_SLOT + (pill - DOT_SLOT) * expansion.coerceIn(0f, 1f)

    /** Borde izquierdo de cada hueco y el ancho total de la cápsula, en [out] (tamaño n + 1). */
    fun offsets(pills: FloatArray, expansion: FloatArray, out: FloatArray = FloatArray(pills.size + 1)): FloatArray {
        var x = INSET
        for (i in pills.indices) {
            out[i] = x
            x += slotWidth(pills[i], expansion[i])
            if (i < pills.lastIndex) x += GAP
        }
        out[pills.size] = x + INSET
        return out
    }

    /**
     * Zona táctil de cada hueco (izquierda, derecha): se reparten los huecos
     * entre pestañas y los márgenes de la cápsula, sin dejar nada muerto.
     */
    fun touchSpan(offsets: FloatArray, pills: FloatArray, expansion: FloatArray, i: Int): Pair<Float, Float> {
        val n = pills.size
        val left = if (i == 0) 0f else offsets[i] - GAP / 2f
        val right = if (i == n - 1) offsets[n] else offsets[i] + slotWidth(pills[i], expansion[i]) + GAP / 2f
        return left to right
    }

    /** Las expansiones de reposo con [selected] elegida. */
    fun rest(n: Int, selected: Int): FloatArray = FloatArray(n) { if (it == selected) 1f else 0f }

    /** Dónde acaba la píldora (izquierda, derecha) con [selected] ya asentada. */
    fun pillTarget(pills: FloatArray, selected: Int): Pair<Float, Float> {
        val o = offsets(pills, rest(pills.size, selected))
        return o[selected] to o[selected] + pills[selected]
    }

    fun totalWidth(pills: FloatArray, selected: Int): Float = offsets(pills, rest(pills.size, selected))[pills.size]

    /** Centro del hueco [i]. */
    fun slotCenter(pills: FloatArray, expansion: FloatArray, i: Int): Float {
        val o = offsets(pills, expansion)
        return o[i] + slotWidth(pills[i], expansion[i]) / 2f
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
}
