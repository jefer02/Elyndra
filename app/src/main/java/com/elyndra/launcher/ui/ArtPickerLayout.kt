package com.elyndra.launcher.ui

import com.elyndra.launcher.metadata.ArtKind

/**
 * El reparto del selector de arte, el mismo para las cuatro clases de imagen
 * (carátula, fondo, logo, icono): solo cambian el ancho mínimo de la celda y
 * su proporción. La hoja lo usa para pintar y el [InputController] para
 * moverse; Kotlin puro (se prueba en la JVM).
 */
object ArtPickerLayout {

    /**
     * Celda de una clase de imagen: ancho mínimo (dp), proporción ancho/alto y
     * [fit]: la imagen se enseña entera en vez de recortada (logo e icono
     * llevan transparencia y recortarlos se come parte del dibujo).
     */
    data class Cell(val minWidthDp: Float, val ratio: Float, val fit: Boolean)

    private val COVER = Cell(96f, 2f / 3f, fit = false)
    private val HERO = Cell(180f, 16f / 9f, fit = false)

    // Los logos son apaisados y con transparencia: celda ancha y baja.
    private val LOGO = Cell(150f, 16f / 7f, fit = true)
    private val ICON = Cell(80f, 1f, fit = true)

    fun cell(kind: ArtKind): Cell = when (kind) {
        ArtKind.Cover -> COVER
        ArtKind.Background -> HERO
        ArtKind.Logo -> LOGO
        ArtKind.Icon -> ICON
    }

    /** Hueco entre columnas y entre filas, y el margen lateral de la rejilla (dp). */
    const val H_SPACING_DP = 10f
    const val V_SPACING_DP = 12f
    const val SIDE_PADDING_DP = 14f

    /**
     * Aire de arriba y abajo de la rejilla (dp): el marco de selección y su
     * escala asoman por fuera de la celda y no deben cortarse en la primera
     * ni en la última fila.
     */
    const val EDGE_PADDING_DP = 10f

    /** Margen con el que el foco se mantiene a la vista al desplazar (dp). */
    const val REVEAL_MARGIN_DP = 14f

    /** Botones de la hoja cuando no hay rejilla (buscando, sin resultados, error). */
    enum class Button { Retry, Close }

    /**
     * Botones del estado sin rejilla, en el orden del foco. Nunca vacío: el
     * mando siempre tiene algo señalado y una salida (B también cierra).
     * Reintentar solo tiene sentido si la búsqueda falló, no si no encontró nada.
     */
    fun buttons(state: ArtPickerState): List<Button> = when {
        state.candidates.isNotEmpty() -> emptyList()
        !state.loading && state.failed -> listOf(Button.Retry, Button.Close)
        else -> listOf(Button.Close)
    }

    /** Índice de la imagen que está puesta ahora, o −1 si no está en la lista. */
    fun appliedIndex(state: ArtPickerState): Int =
        state.applied?.let { url -> state.candidates.indexOfFirst { it.url == url } } ?: -1

    /**
     * El foco al abrir (o al llegar los resultados): la imagen puesta si está en
     * la lista; si no, la primera. Sin rejilla, el primer botón.
     */
    fun initialFocus(state: ArtPickerState): Int =
        if (state.candidates.isEmpty()) 0 else appliedIndex(state).coerceAtLeast(0)
}
