package com.elyndra.launcher.ui

/**
 * Cómo se reparte una capa con un campo de texto cuando el teclado ocupa
 * media pantalla (lógica pura: se prueba en la JVM).
 */
object KeyboardLayout {

    /** Por debajo de este alto libre (dp), aun en vertical, la capa pasa a compacta. */
    const val COMPACT_BELOW_DP = 420f

    /**
     * Compacta: el campo arriba del todo y en una sola fila con sus botones,
     * y los resultados en una tira corta debajo. Con el teclado abierto en
     * horizontal (en la Legion tapa más de la mitad) o si queda poco alto.
     */
    fun compact(imeVisible: Boolean, landscape: Boolean, availableHeightDp: Float): Boolean =
        imeVisible && (landscape || availableHeightDp < COMPACT_BELOW_DP)

    /**
     * Con el teclado abierto la capa se pega arriba del hueco que queda (el
     * campo nunca acaba detrás del teclado ni de los resultados); sin
     * teclado, abajo, como el resto de hojas.
     */
    fun dockTop(imeVisible: Boolean): Boolean = imeVisible
}
