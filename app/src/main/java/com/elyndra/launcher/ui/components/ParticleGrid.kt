package com.elyndra.launcher.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Dp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/* ─────────────────────────────────────────────────────────────
   La rejilla de partículas, común a los dos efectos.

   Deshacer algo en polvo (Disintegration) y montarlo desde el
   polvo (Materialization) parten del mismo sitio: un fotograma
   del contenido troceado en celdas, cada una con su color medio
   y su opacidad. Lo que cambia es a dónde va cada mota y cuándo,
   no de dónde sale, así que ese trabajo vive aquí una sola vez.
   ───────────────────────────────────────────────────────────── */

/** El contenido ya troceado: [pixels] tiene un ARGB por celda, en filas. */
internal class CellGrid(
    val cols: Int,
    val rows: Int,
    /** Tamaño real de la celda en px del elemento. */
    val cellW: Float,
    val cellH: Float,
    /**
     * Lado con el que se pinta cada mota. Lleva un pelo de solape sobre la
     * celda: sin él, con todas las partículas quietas en su sitio, se verían
     * las juntas de la rejilla como una cuadrícula de pelos.
     */
    val side: Float,
    val pixels: IntArray,
)

/**
 * Trocea el fotograma capturado en celdas.
 *
 * La rejilla se elige con dos topes: la celda nunca baja de [cell] (más fino
 * no se aprecia y solo cuesta) y el total nunca pasa de [maxParticles] (lo
 * que se puede dibujar de sobra dentro de un fotograma). El color de cada
 * celda sale de reducir el bitmap al tamaño de la rejilla: el escalador
 * nativo ya hace la media de cada celda, mucho más rápido que recorrer a mano
 * el millón de píxeles del original.
 *
 * Pensado para llamarse fuera del hilo principal; no toca estado de Compose.
 */
internal fun ImageBitmap.toCellGrid(cell: Dp, maxParticles: Int, density: Float): CellGrid? {
    val source = asAndroidBitmap()
    // Un bitmap HARDWARE no deja leer sus píxeles: hay que traerlo a memoria.
    val readable = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        source
    }
    val width = readable.width
    val height = readable.height
    if (width <= 0 || height <= 0) return null

    val minCell = (cell.value * density).coerceAtLeast(1f)
    val byBudget = sqrt(width.toFloat() * height / maxParticles)
    val side = max(minCell, byBudget)
    val cols = floor(width / side).toInt().coerceAtLeast(1)
    val rows = floor(height / side).toInt().coerceAtLeast(1)

    val scaled = Bitmap.createScaledBitmap(readable, cols, rows, true)
    val pixels = IntArray(cols * rows)
    scaled.getPixels(pixels, 0, cols, 0, 0, cols, rows)
    // Los temporales se sueltan aquí mismo; el original es del ImageBitmap.
    if (scaled !== readable) scaled.recycle()
    if (readable !== source) readable.recycle()

    val cellW = width.toFloat() / cols
    val cellH = height.toFloat() / rows
    return CellGrid(cols, rows, cellW, cellH, max(cellW, cellH) + OVERLAP_PX, pixels)
}

/**
 * Cuánto "hay" en el fotograma: fracción de celdas con algo pintado.
 *
 * Sirve para saber si merece la pena animar (un contenido transparente no da
 * ninguna partícula) y, al montar, para esperar a que la imagen haya llegado
 * de verdad antes de capturarla.
 */
internal fun CellGrid.coverage(): Float {
    if (pixels.isEmpty()) return 0f
    var painted = 0
    for (argb in pixels) if ((argb ushr 24 and 0xFF) / 255f > ALPHA_FLOOR) painted++
    return painted.toFloat() / pixels.size
}

/**
 * Firma barata del fotograma, para comparar dos capturas seguidas.
 *
 * Al montar hay que capturar el contenido *ya terminado*, y una imagen que
 * todavía está descodificando (o una entrada a medio animar) cambia de un
 * fotograma a otro: si las dos capturas dan la misma firma, lo que se ve ya
 * no se mueve y se puede trocear.
 */
internal fun CellGrid.signature(): Int {
    var hash = cols * 31 + rows
    for (argb in pixels) hash = hash * 31 + argb
    return hash
}

/** Por debajo de esta opacidad la celda se considera hueco y no emite partícula. */
internal const val ALPHA_FLOOR = 0.02f

/** Color opaco: la transparencia de cada mota se guarda aparte. */
internal const val OPAQUE = 0xFF000000.toInt()

private const val OVERLAP_PX = 0.75f
