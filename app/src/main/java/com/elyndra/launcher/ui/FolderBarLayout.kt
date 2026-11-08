package com.elyndra.launcher.ui

/**
 * La cabecera de una carpeta, arriba a la derecha (Meridian) o tras volver y
 * "Abrir" (Clásico): el emulador de la carpeta, el nombre del sistema con su
 * ruta y la píldora de hora y batería. Sin Compose: qué cabe en un ancho
 * dado. Todo en dp; el rótulo del emulador llega ya medido.
 *
 * El emulador va aquí, con el sistema, y no en la cabecera de la rueda:
 * allí, junto a volver y "Abrir", obligaba al cristal curvo a abrirse arriba
 * hasta enderezarse. Es de la carpeta (no de un juego), así que se lee al
 * lado de lo que nombra la carpeta.
 *
 * En Meridian lleva además la ficha de la carpeta ("4 ROMs · 0h 16m") a la
 * izquierda del emulador: antes iba bajo volver y "Abrir" y le quitaba a la
 * rueda una fila entera de alto. Aquí va en la misma fila (no debajo de la
 * ruta: esa segunda línea bajaría el grupo hasta los chips del hero en un
 * móvil), con el emulador y el sistema, que es lo que describe.
 *
 * La píldora de hora y batería va siempre entera. Si no cabe todo, se
 * compacta como [TopBarLayout], de lo que menos se echa en falta a lo que más:
 * primero el nombre y la ruta encogen hasta [TITLE_MIN] (la ruta se recorta
 * por el centro); luego la ficha de la carpeta pierde el tiempo jugado
 * ([Pill.Short], "4 ROMs"); luego el rótulo del emulador se recorta (sin
 * bajar de [LABEL_MIN]) y después se queda en su icono (su nombre sigue en
 * la descripción para el lector de pantalla); en lo más justo, la ficha se
 * quita ([Pill.Hidden]: el número de ROMs se ve en la rueda y el dial).
 */
object FolderBarLayout {

    /** Aire a cada lado del rótulo del emulador, dentro de su píldora. */
    const val CHIP_PAD = 11f

    /** Lo más ancha que se hace la píldora del emulador con su rótulo (algunos nombres son larguísimos). */
    const val CHIP_MAX = 180f

    /** Lo menos que se deja al rótulo del emulador antes de quedarse en el icono ("Eden N…", "RetroA…"). */
    const val LABEL_MIN = 56f

    /** Aire entre el emulador, el nombre y la píldora de estado. */
    const val GAP = 12f

    /** Lo que el nombre del sistema necesita para leerse ("Nintendo S…"). */
    const val TITLE_MIN = 96f

    /** Cuánto se compacta, de menos a más. */
    enum class Fit(val emulatorLabel: Boolean) {
        Full(emulatorLabel = true),
        ShortLabel(emulatorLabel = true),
        IconEmulator(emulatorLabel = false),
    }

    /** Cómo va la ficha de la carpeta. */
    enum class Pill { Full, Short, Hidden }

    /** La cabecera colocada: cuánto se compacta, lo que mide la píldora del emulador y cómo va la ficha de la carpeta (dp). */
    data class Plan(val fit: Fit, val chipWidth: Float, val pill: Pill = Pill.Full, val pillWidth: Float = 0f)

    /** La píldora del emulador con un rótulo de [labelWidth] (sin tope). */
    fun chipWidth(labelWidth: Float): Float = 2 * CHIP_PAD + labelWidth

    /** La píldora del emulador solo con su icono: un botón cuadrado como sus vecinos. */
    const val ICON_WIDTH = TopBarLayout.BAR_HEIGHT

    /**
     * Cómo va la cabecera en [available] dp con un rótulo de emulador de
     * [labelWidth], la píldora de estado de [statusWidth] (0 = apagada) y un
     * nombre del sistema de [titleWidth] (lo que mide entero; null = sin medir,
     * se le guarda [TITLE_MIN]).
     */
    fun plan(
        available: Float,
        labelWidth: Float,
        statusWidth: Float,
        titleWidth: Float? = null,
        /** La ficha de la carpeta entera y sin el tiempo (0 = no hay ficha). */
        pillWidth: Float = 0f,
        pillShortWidth: Float = pillWidth,
    ): Plan {
        val status = if (statusWidth > 0f) statusWidth + GAP else 0f
        val title = minOf(titleWidth ?: TITLE_MIN, TITLE_MIN)
        val natural = minOf(chipWidth(labelWidth), CHIP_MAX)
        val least = chipWidth(minOf(labelWidth, LABEL_MIN))
        fun pillSpace(w: Float) = if (w > 0f) w + GAP else 0f
        // Lo que puede medir el emulador dejando al nombre su mínimo, con la ficha en [w].
        fun room(w: Float) = available - status - title - GAP - pillSpace(w)
        val hasPill = pillWidth > 0f
        if (natural <= room(pillWidth)) return Plan(Fit.Full, natural, Pill.Full, pillWidth)
        if (hasPill && natural <= room(pillShortWidth)) return Plan(Fit.Full, natural, Pill.Short, pillShortWidth)
        val short = if (hasPill) Pill.Short else Pill.Full
        val shortW = if (hasPill) pillShortWidth else 0f
        if (room(shortW) >= least) return Plan(Fit.ShortLabel, room(shortW), short, shortW)
        if (!hasPill || room(shortW) >= ICON_WIDTH) return Plan(Fit.IconEmulator, ICON_WIDTH, short, shortW)
        return Plan(Fit.IconEmulator, ICON_WIDTH, Pill.Hidden, 0f)
    }

    /** Lo que le queda al nombre y a la ruta con [plan] (nunca negativo). */
    fun titleRoom(available: Float, plan: Plan, statusWidth: Float): Float {
        val status = if (statusWidth > 0f) statusWidth + GAP else 0f
        val pill = if (plan.pillWidth > 0f) plan.pillWidth + GAP else 0f
        return (available - plan.chipWidth - GAP - status - pill).coerceAtLeast(0f)
    }
}
