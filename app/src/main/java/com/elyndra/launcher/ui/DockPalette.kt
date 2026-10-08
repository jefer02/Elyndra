package com.elyndra.launcher.ui

import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.data.Palettes

/**
 * Colores (ARGB) del dock de secciones, del botón de orden y de su menú, en
 * claro (perla y champán, acento más hondo) y en oscuro (tinta honda con filo
 * de luz). La cápsula es translúcida y va sobre el arte del juego, así que el
 * contraste se mide contra el peor fondo posible: negro y blanco puros
 * (ver DockPaletteTest).
 */
object DockPalettes {

    /*
     * El dock y el botón de orden llevan el cristal oscuro de la barra del hero
     * (`darkGlass`), en los dos temas, como la hora, "Abrir" y buscar.
     */

    /** Punto de una sección sin elegir sobre el cristal oscuro. */
    const val GLASS_DOT = 0xE6FFFFFF.toInt()

    /** Tinta física del cristal oscuro (`P.shade`). */
    const val SHADE = BrandTokens.SHADE

    /** Ficha del rótulo que asoma bajo el dock. */
    const val GLASS_PEEK_ALPHA = 0.88f

    /** Velo del acento sobre el botón de orden con el menú abierto. */
    const val SORT_OPEN_VEIL = 0.22f

    /** Lo menos que oscurece la barra del hero (velo de arriba con "Intensidad del hero" de serie). */
    const val HERO_TOP_SCRIM = 0.62f * 0.55f

    /** Suelo de la tinta del cristal en la costura con el estante (puede caer sobre la perla). */
    const val SEAM_GLASS_MIN = 0.6f

    /**
     * Lo que se ve detrás de una pieza en cristal oscuro de tinta [glass] puesto
     * sobre [under], con el velo negro [scrim] del hero entre medias.
     */
    fun onDarkGlass(under: Int, glass: Float, scrim: Float): Int {
        val veiled = ColorMath.over(ColorMath.withAlpha(BLACK, scrim), ColorMath.opaque(under))
        return ColorMath.over(ColorMath.withAlpha(SHADE, glass), veiled)
    }

    /** El peor contraste de [fg] (con su alfa) sobre el cristal oscuro, con arte negro o blanco detrás. */
    fun worstOnDarkGlass(fg: Int, glass: Float, scrim: Float, unders: List<Int> = listOf(BLACK, WHITE)): Double =
        unders.minOf { under ->
            val bg = onDarkGlass(under, glass, scrim)
            ColorMath.contrast(ColorMath.over(fg, bg), bg)
        }

    /**
     * El acento sobre el cristal oscuro (aro de foco, flecha del orden): el
     * tono para fondo oscuro, aclarado hacia el blanco lo justo para el 3:1
     * aunque detrás haya arte muy claro.
     */
    fun glassAccent(content: Int): Int {
        var t = 0f
        while (t <= 1f) {
            val c = ColorMath.mix(ColorMath.opaque(content), WHITE, t)
            if (worstOnDarkGlass(c, DARK_GLASS_FLOOR, HERO_TOP_SCRIM) >= MIN_UI) return c
            t += 0.05f
        }
        return WHITE
    }

    /** La tinta del cristal oscuro con la transparencia al mínimo (`DARK_GLASS_MIN`). */
    const val DARK_GLASS_FLOOR = 0.25f

    /** Piezas de interfaz esenciales (los puntos, el filo de la píldora). */
    const val MIN_UI = 3.0

    /** Texto. */
    const val MIN_TEXT = 4.5

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** Perla cálida de la intro en claro; tinta honda, algo más clara que el papel, en oscuro. */
    private const val PEARL = BrandTokens.INTRO_PEARL
    private const val INK = 0xFF12141D.toInt()

    fun surfaceAlpha(dark: Boolean): Float = 0.9f

    /** Velo blanco de la lámina en su canto de arriba (se apaga hacia abajo). */
    fun hazeTop(dark: Boolean): Float = if (dark) 0.04f else 0.4f

    /** El menú lleva texto de varias líneas: va casi opaco. */
    fun popoverAlpha(dark: Boolean): Float = if (dark) 0.95f else 0.96f

    fun surface(dark: Boolean, alpha: Float = surfaceAlpha(dark)): Int = ColorMath.withAlpha(if (dark) INK else PEARL, alpha)

    /** Punto de una sección sin elegir: el gris de texto secundario del tema. */
    fun dot(dark: Boolean): Int = (if (dark) BrandTokens.DARK else BrandTokens.LIGHT).ink2

    fun ink(dark: Boolean): Int = (if (dark) BrandTokens.DARK else BrandTokens.LIGHT).ink

    fun ink2(dark: Boolean): Int = (if (dark) BrandTokens.DARK else BrandTokens.LIGHT).ink2

    /** Relleno de la píldora: el degradado del acento, hondo lo justo para el blanco encima. */
    fun pillStart(accentA: Int): Int = Palettes.fillFor(ColorMath.opaque(accentA))

    fun pillEnd(accentB: Int): Int = Palettes.fillFor(ColorMath.opaque(accentB))

    /**
     * Filo de luz de la píldora. En oscuro el relleno se parece en luminosidad
     * a la tinta, así que lo que la separa de la cápsula es este filo (claro,
     * con el tono del acento); en claro basta un blanco sobre el relleno.
     */
    fun pillRim(accentA: Int, dark: Boolean): Int =
        if (dark) ColorMath.mix(ColorMath.opaque(accentA), WHITE, 0.6f) else ColorMath.withAlpha(WHITE, 0.7f)

    /**
     * El peor contraste de [fg] (opaco) sobre la cápsula [translucent] puesta
     * encima de negro o de blanco, con el velo blanco de la lámina en la
     * altura [hazeAt] (1 = el canto de arriba, 0,5 = el centro, donde va el texto).
     */
    fun worstContrast(fg: Int, translucent: Int, dark: Boolean? = null, hazeAt: Float = 0.5f): Double {
        val haze = dark?.let { ColorMath.withAlpha(WHITE, hazeTop(it) * hazeAt) }
        fun bg(under: Int): Int {
            val c = ColorMath.over(translucent, under)
            return if (haze != null) ColorMath.over(haze, c) else c
        }
        return minOf(
            ColorMath.contrast(ColorMath.opaque(fg), bg(BLACK)),
            ColorMath.contrast(ColorMath.opaque(fg), bg(WHITE)),
        )
    }

    /** La píldora se distingue de la cápsula por su relleno o por su filo. */
    fun pillStandsOut(accentA: Int, dark: Boolean): Boolean {
        val surface = surface(dark)
        return worstContrast(pillStart(accentA), surface, dark) >= MIN_UI ||
            (dark && worstContrast(pillRim(accentA, true), surface, dark) >= MIN_UI)
    }
}
