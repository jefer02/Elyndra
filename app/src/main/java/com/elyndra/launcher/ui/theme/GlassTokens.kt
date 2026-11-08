package com.elyndra.launcher.ui.theme

/**
 * El aspecto del cristal de Meridian (la lámina curva detrás de la rueda, en
 * Biblioteca y Carpeta, en los dos temas), en un solo sitio. Antes cada
 * número vivía en otro objeto (`WheelGlass`, `GlassPanelLayout`, las alfas
 * sueltas del velo) y cambiar uno no cambiaba los demás.
 *
 * Tres pasos, de la versión primera a la de ahora:
 * - primera: desenfoque 20 dp (16–32), tinte 0,06, filo 0,16, halo 0,03,
 *   sin desenfoque 0,16 de tinte;
 * - la "más transparente": desenfoque 14 dp (10–22), tinte 0,03, filo 0,24,
 *   halo 0,05, sin desenfoque 0,08: apenas se veía la lámina, solo su canto;
 * - ahora, el término medio de abajo: un esmerilado que se ve (desenfoque,
 *   un velo blanco suave y el tinte del juego), más ligero que la primera.
 *
 * Todo en dp y en alfas para que las pruebas corran en la JVM.
 */
object GlassTokens {

    /** El ancho de ventana (dp) en el que se afinan el desenfoque y el halo (un móvil apaisado); crecen con él. */
    const val REFERENCE_WIDTH = 900f

    /**
     * El desenfoque del arte dentro del cristal a [REFERENCE_WIDTH] (dp). Con
     * 14 el motivo se adivinaba y la lámina parecía no estar; con 20 pesaba.
     * Con 18 el arte queda en manchas de color: se lee cristal, no reflejo.
     */
    const val BLUR_DP = 18f

    /** Lo menos y lo más que se desenfoca según la ventana (dp), con el ajuste "Desenfoque" en su valor por defecto. */
    const val BLUR_MIN = 14f
    const val BLUR_MAX = 28f

    /** Lo más que el ajuste "Desenfoque" (0–40) multiplica el del cristal: el doble. */
    const val USER_BLUR_MAX = 2f

    /** Por debajo de esto (dp) no se desenfoca: se pinta como sin RenderEffect. */
    const val BLUR_OFF = 1f

    /** Saturación del arte desenfocado (1 = la de siempre): deja pasar el color, no un gris lechoso. */
    const val SATURATION = 1.1f

    /** El tinte del color del juego encima del arte desenfocado (antes 0,06 y luego 0,03). */
    const val TINT_ALPHA = 0.10f

    /**
     * Un velo blanco por toda la lámina, lo que la hace "esmerilada" y no solo
     * desenfocada (nuevo). En oscuro, menos: el blanco ahí se ve lechoso.
     */
    const val FROST_ALPHA_LIGHT = 0.07f
    const val FROST_ALPHA_DARK = 0.035f

    /**
     * Sin desenfoque (Android 8–11, o "Desenfoque" a 0): el arte se ve nítido,
     * así que la lámina lleva más tinte y más velo para que se siga leyendo
     * como cristal y no como nada (antes 0,16 y luego 0,08, sin velo).
     */
    const val FALLBACK_TINT_ALPHA = 0.18f
    const val FALLBACK_FROST_LIGHT = 0.14f
    const val FALLBACK_FROST_DARK = 0.07f

    /** El filo de luz por la curva (1 dp) y su halo hacia dentro (antes 0,16 / 0,03, luego 0,24 / 0,05). */
    const val RIM_DP = 1f
    const val RIM_ALPHA = 0.30f
    const val GLOW_DP = 10f
    const val GLOW_ALPHA = 0.06f

    /** El halo según la ventana, como poco y como mucho (dp). */
    const val GLOW_MIN = 8f
    const val GLOW_MAX = 16f

    /**
     * La sombra fina bajo las marcas blancas del dial y la tira A–Z sobre el
     * cristal (antes 0,45): el respaldo de lo que se lee va con ello, no en
     * toda la lámina.
     */
    const val MARK_SHADOW_ALPHA = 0.55f
}
