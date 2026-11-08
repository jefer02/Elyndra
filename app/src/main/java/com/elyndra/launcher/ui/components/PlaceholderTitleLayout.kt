package com.elyndra.launcher.ui.components

/**
 * El título de una carátula de reserva (sin arte): cuánto mide según su caja,
 * sin Compose. Todo en dp y sp.
 *
 * Antes iba a un cuerpo fijo por ancho (12,5 % del ancho, 9–26 sp) y se
 * cortaba en cuatro líneas: un nombre largo ("Shin Megami Tensei: Devil
 * Summoner") llenaba media carátula y, al lado de una carátula de verdad, se
 * leía como otra tarjeta. Ahora se busca el cuerpo más grande, hasta ese
 * mismo tope, con el que el nombre cabe entero en sus líneas y en la parte de
 * abajo de la caja ([heightBudget]); si ni con [MIN_SP] cabe, se corta con
 * puntos suspensivos.
 */
object PlaceholderTitleLayout {

    /** Lo menos y lo más que mide el título (sp), y su tope respecto al ancho de la caja (con icono, más pequeño). */
    const val MIN_SP = 9f
    const val MAX_SP = 26f
    const val WIDTH_SHARE = 0.125f
    const val WIDTH_SHARE_ICON = 0.095f

    /** Márgenes del título en fracción del ancho de la caja (los de siempre). */
    const val PAD_X = 0.09f
    const val PAD_Y = 0.08f

    /** Qué alto de la caja puede ocupar: sin icono el título es la carátula; con icono, la franja de abajo. */
    const val HEIGHT_SHARE = 0.62f
    const val HEIGHT_SHARE_ICON = 0.30f

    /** Líneas como mucho. */
    fun maxLines(icon: Boolean): Int = if (icon) 3 else 4

    /** El cuerpo más grande que se prueba para una caja de [boxWidth] dp. */
    fun maxSp(boxWidth: Float, icon: Boolean): Float =
        (boxWidth * if (icon) WIDTH_SHARE_ICON else WIDTH_SHARE).coerceIn(MIN_SP, MAX_SP)

    /** El ancho (dp) en que se reparte el título. */
    fun textWidth(boxWidth: Float): Float = (boxWidth * (1f - 2f * PAD_X)).coerceAtLeast(0f)

    /** El alto (dp) que puede ocupar en una caja de [boxWidth] × [boxHeight]. */
    fun heightBudget(boxWidth: Float, boxHeight: Float, icon: Boolean): Float =
        ((boxHeight - 2f * boxWidth * PAD_Y) * if (icon) HEIGHT_SHARE_ICON else HEIGHT_SHARE).coerceAtLeast(0f)
}
