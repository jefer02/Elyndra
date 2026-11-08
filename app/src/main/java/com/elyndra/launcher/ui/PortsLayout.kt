package com.elyndra.launcher.ui

/**
 * Geometría de la tienda de ports, sin Compose (PortsLayoutTest). Todo en dp.
 *
 * Las cápsulas tienen la proporción de las cabeceras de las tiendas de
 * escritorio (≈ 2,14:1) y debajo una banda con el nombre y el área de
 * acción. Cuántas se ven a la vez depende del ancho: siempre asoma parte de
 * la siguiente, que es lo que dice "esto se desliza".
 */
object PortsLayout {

    const val CAPSULE_ASPECT = 2.14f

    /** Banda bajo la imagen: nombre y área de acción. */
    const val CAPSULE_BAND = 44f
    const val CAPSULE_MIN = 168f
    const val CAPSULE_MAX = 300f
    const val ROW_GAP = 12f

    /** Lado de la tienda y de la ficha. */
    fun pad(width: Float): Float = if (width < 600f) 16f else 24f

    /** Cápsulas visibles a la vez (con parte de la siguiente). */
    fun visibleCapsules(width: Float): Float = when {
        width < 480f -> 2.25f
        width < 760f -> 2.6f
        width < 1100f -> 3.5f
        else -> 4.5f
    }

    fun capsuleWidth(width: Float): Float {
        val room = width - pad(width)
        return (room / visibleCapsules(width) - ROW_GAP).coerceIn(CAPSULE_MIN, CAPSULE_MAX)
    }

    fun capsuleImageHeight(capsuleWidth: Float): Float = capsuleWidth / CAPSULE_ASPECT

    fun capsuleHeight(capsuleWidth: Float): Float = capsuleImageHeight(capsuleWidth) + CAPSULE_BAND

    /**
     * El carrusel de destacados: en apaisado, una parte de la altura (que se
     * vea la primera fila debajo); en vertical, la proporción de una cabecera.
     */
    fun heroHeight(width: Float, height: Float): Float {
        val landscape = width > height
        return if (landscape) (height * 0.46f).coerceIn(150f, 340f) else ((width - 2 * pad(width)) / 1.6f).coerceIn(170f, 320f)
    }

    /** En el destacado, ¿el texto va a la derecha del arte (ancho) o encima, sobre un velo (estrecho)? */
    fun heroSideInfo(width: Float): Boolean = width >= 700f

    /** Ancho del panel de texto del destacado cuando va al lado. */
    fun heroInfoWidth(width: Float): Float = (width * 0.3f).coerceIn(240f, 360f)

    /** La ficha a dos columnas (galería | información) en apaisado con sitio; si no, apilada. */
    fun detailTwoColumns(width: Float, height: Float): Boolean = width >= 640f && width > height

    /** Parte del ancho de la ficha para la galería en dos columnas. */
    const val GALLERY_FRACTION = 0.62f

    /** Alto de la imagen grande de la galería para un ancho de columna [columnWidth]. */
    fun galleryHeight(columnWidth: Float): Float = columnWidth / 1.78f

    /** Miniaturas de la galería. */
    const val THUMB_WIDTH = 96f
}
