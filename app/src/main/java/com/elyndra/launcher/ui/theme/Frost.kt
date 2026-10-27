package com.elyndra.launcher.ui.theme

/* ─────────────────────────────────────────────────────────────
   El tema claro sobre el arte en Meridian (horizontal): el cristal
   curvo de la rueda, las píldoras de cristal claro y la tinta que va
   encima. Más el ajuste del titular y del logo del hero en vertical.
   Todo en ARGB y dp para que las pruebas corran en la JVM; los
   composables los leen de aquí y no llevan colores sueltos.
   ───────────────────────────────────────────────────────────── */

/** La tinta oscura de lo que va sobre el cristal claro. */
object Frost {

    /** Titulares e iconos: casi negro. */
    const val TITLE_INK = 0xFF101219.toInt()

    /** Texto secundario: negro al 68 % (entre el 60 y el 70 %). */
    const val SECONDARY_ALPHA = 0.68f
    const val SECONDARY_INK = 0xAD000000.toInt()
}

/**
 * El cristal curvo de la rueda en el tema claro (horizontal): una lámina con
 * el canto derecho siguiendo la curva de la lista, casi transparente: el arte
 * apenas desenfocado, sin blanco encima, y un filo de luz muy sutil por la
 * curva. La rueda solo lleva los iconos.
 */
object WheelGlass {
    /**
     * El canto en la línea de foco, pasado el eje: el dial, su nodo y el
     * resplandor (~6 dp pasado el eje) quedan dentro, con aire. Antes eran 84
     * para la cápsula del contador; sin ella, el cristal no llega al bloque
     * del hero (a [com.elyndra.launcher.ui.meridian.MeridianGeometry.HERO_GAP]).
     */
    const val PAST_AXIS_DP = 24f

    /** Saturación del arte desenfocado (1 = la de siempre). */
    const val SATURATION = 1.1f

    /** El desenfoque del arte dentro del cristal (dp): mínimo, el arte se ve casi tal cual. */
    const val BLUR_DP = 6f

    /** El tinte del color del juego encima: casi nada, el cristal es transparente. */
    const val TINT_ALPHA = 0.02f

    /**
     * Sin velo, las marcas del dial y las letras de la tira A–Z van blancas con
     * una sombra fina debajo: se leen sobre arte claro y sobre arte oscuro.
     */
    const val MARK_ALPHA = 0.95f
    const val MARK_SHADOW_ALPHA = 0.45f
    const val MARK_SHADOW_DP = 1.5f

    /** El filo de luz por la curva y su halo hacia dentro: apenas se notan. */
    const val RIM_DP = 1f
    const val RIM_ALPHA = 0.16f
    const val GLOW_DP = 10f
    const val GLOW_ALPHA = 0.03f

    /** Lo más a la izquierda que puede llegar el canto lejos de la línea de foco (dp). */
    const val MIN_EDGE_DP = 120f

    /** Cada cuánto se toma un punto de la curva (dp). */
    const val STEP_DP = 6f
}

/**
 * Cristal claro: las píldoras del tema claro (el dock y el orden, también en
 * la costura del hero en vertical). Blanco translúcido,
 * filo sutil y tinta oscura.
 */
object LightGlass {
    const val FILL_ALPHA = 0.62f

    /** Velo blanco extra arriba (se apaga hacia abajo). */
    const val HAZE_ALPHA = 0.35f

    /** El filo: luz arriba y una línea oscura muy suave abajo. */
    const val RIM_TOP = 0xF2FFFFFF.toInt()
    const val RIM_BOTTOM = 0x1F000000

    const val SHADOW_ALPHA = 0.14f

    /** Iconos y texto. */
    const val INK = Frost.TITLE_INK

    /** Puntos de las secciones sin elegir. */
    const val DOT = 0xB3101219.toInt()

    /** El aviso de la rueda (búsqueda sin resultados) en su tarjeta. */
    const val CARD_RADIUS_DP = 14f
}

/**
 * El hero en vertical (móvil y tableta): el título sin logo, más pequeño y
 * ajustado a dos líneas del ancho que hay, y el tope de alto del logo.
 */
object PortraitHero {

    /** Cuerpo del título sin logo: entre estos dos (sp), el mayor que quepa en dos líneas. */
    const val TITLE_MIN_SP = 22f
    const val TITLE_MAX_SP = 60f

    /** El tope de arriba sale del ancho disponible (sp por dp). */
    const val TITLE_SP_PER_DP = 0.12f
    const val TITLE_LINES = 2

    fun titleMaxSp(widthDp: Float, base: Float): Float =
        minOf(base, (widthDp * TITLE_SP_PER_DP).coerceIn(TITLE_MIN_SP + 8f, TITLE_MAX_SP))

    /** Alto máximo del logo: fracción del alto de la ventana y tope absoluto (dp). */
    const val LOGO_MAX_FRACTION = 0.10f
    const val LOGO_MAX_DP = 120f

    fun logoMaxHeight(windowHeightDp: Float, base: Float): Float =
        minOf(base, windowHeightDp * LOGO_MAX_FRACTION, LOGO_MAX_DP)
}
