package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.ui.TopBarLayout
import com.elyndra.launcher.ui.components.COVER_RATIO
import kotlin.math.min

/**
 * El tamaño y el sitio de las carátulas (e iconos) de la rueda según la
 * ventana, sin Compose. Todo en dp.
 *
 * Dos repartos:
 * - **Ventana baja** (móvil apaisado, [MeridianMode.compact]): una carátula
 *   enfocada grande, centrada en la rueda, y de cada vecina solo asoma la
 *   misma franja arriba y abajo ([PEEK_MIN]), con el mismo fundido en los dos
 *   cantos. Antes las vecinas medían el 60 % y seguían la curva: la de
 *   arriba quedaba en una tira apagada y la de abajo con otro tamaño, otro
 *   margen y cortada en seco por el pie. Ahora son simétricas por
 *   construcción: misma escala, misma distancia, misma opacidad y el mismo
 *   eje vertical que la enfocada (sin curva).
 * - **Tableta y tele**: como estaba (43 % de la rueda, vecinas enteras sobre
 *   el arco del dial).
 */
object MeridianCoverLayout {

    /** Aire entre la carátula enfocada y la franja de cada vecina (dp). */
    const val PEEK_GAP = 8f

    /**
     * Lo que asoma de cada vecina en un móvil (dp, antes del fundido): lo
     * justo para ver que hay un juego antes y otro después. Con 32 dp la
     * enfocada mide en 1600 × 720 el 70 % del alto de la pantalla; con menos,
     * la franja se lee como una raya sucia, no como la siguiente carátula.
     */
    const val PEEK_MIN = 32f

    /** Lo más que mide la carátula enfocada de una rueda baja respecto a la rueda (una ventana baja pero sin cabecera). */
    const val PHONE_RAIL_MAX = 0.82f

    /**
     * La escala de las vecinas en un móvil: solo se ve su canto, así que van
     * casi del ancho de la enfocada (72 %) para que la franja se lea como una
     * carátula y no como una tira estrecha.
     */
    const val PHONE_NEIGHBOR_SCALE = 0.72f

    /**
     * El hueco de una carátula, ancho/alto: 0,70 en vez de 2:3. Así las cajas
     * algo más anchas (PS2, 0,70) miden lo mismo de alto que las de 2:3 y que
     * las de PSP (0,58): en la misma lista se ven igual de grandes. Las más
     * anchas aún (SNES, GBA) encajan enteras, más bajas: recortarlas no.
     */
    const val SLOT_ASPECT = 0.70f

    /** La franja (dp) de cada canto de la rueda en la que las filas se funden a transparente. */
    const val EDGE_FADE = 28f

    /** En un móvil, qué parte de la franja de la vecina se funde (el resto se ve entero). */
    const val FADE_SHARE = 0.7f

    /** Lo menos que dura el fundido (dp): más corto se vería como un corte. */
    const val FADE_MIN = 8f

    /**
     * Del canto derecho de la carátula al de la columna en ventanas bajas
     * (dp): el dial está a 6 dp del canto y sus marcas entran ~18 dp; con 36
     * les quedan ~12 dp de aire. Antes 44 en todas.
     */
    const val TILE_TO_DIAL_COMPACT = 36f

    /**
     * El alto de la cabecera de la carpeta (dp) para estimar la rueda antes de
     * medirla: solo volver y "Abrir" (34) y sus márgenes, normal y en ventana
     * baja. La ficha de ROMs ya no va debajo (está arriba a la derecha, con el
     * sistema): esos ~28 dp son carátula.
     */
    const val FOLDER_HEADER = MeridianTopRow.TOP + TopBarLayout.BAR_HEIGHT + MeridianTopRow.BOTTOM
    const val FOLDER_HEADER_COMPACT = MeridianTopRow.TOP_COMPACT + TopBarLayout.BAR_HEIGHT + MeridianTopRow.BOTTOM_COMPACT

    /** La carátula enfocada de una rueda baja de [railHeight] dp: lo que deja asomar [PEEK_MIN] de cada vecina. */
    fun phoneTile(railHeight: Float): Float =
        min(railHeight - 2f * (PEEK_GAP + PEEK_MIN), railHeight * PHONE_RAIL_MAX)

    /** La proporción del hueco de una tarjeta: [SLOT_ASPECT] con carátulas, la del arte con iconos. */
    fun slotAspect(aspect: Float): Float = if (aspect < 1f) SLOT_ASPECT else aspect

    /** El ancho del hueco de una tarjeta de [tileHeight] dp con arte de proporción [aspect]. */
    fun slotWidth(tileHeight: Float, aspect: Float): Float = tileHeight * slotAspect(aspect)

    /** El reparto de la rueda: el de móvil en una ventana baja ([compact]) para una tarjeta de [tile] dp, o el de siempre. */
    fun profile(compact: Boolean, tile: Float): WheelProfile =
        if (!compact || tile <= 0f) {
            WheelProfile.DEFAULT
        } else {
            WheelProfile(neighborScale = PHONE_NEIGHBOR_SCALE, focusGap = PEEK_GAP / tile, curve = 0f, centerShortLists = false, maskOnly = true)
        }

    /** Del canto derecho de la carátula al de la columna (dp). */
    fun tileToDial(compact: Boolean): Float = if (compact) TILE_TO_DIAL_COMPACT else MeridianGeometry.TILE_TO_DIAL

    /** Lo que se cuenta para la cabecera de la rueda: la de la carpeta ([folder]) o la de la biblioteca. */
    fun headerEstimate(compact: Boolean, folder: Boolean): Float = when {
        folder && compact -> FOLDER_HEADER_COMPACT
        folder -> FOLDER_HEADER
        compact -> MeridianGeometry.HEADER_ESTIMATE_COMPACT
        else -> MeridianGeometry.HEADER_ESTIMATE
    }

    /** Lo que asoma de cada vecina (±1) en la rueda [geo], antes del fundido (dp; 0 si no asoma). */
    fun peek(geo: MeridianGeometry): Float {
        val p = geo.profile
        val inner = (WheelTransform.center(1f, p) - WheelTransform.scale(1f, p) / 2f) * geo.rowHeight
        return (geo.railHeight - geo.focusY - inner).coerceIn(0f, WheelTransform.scale(1f, p) * geo.rowHeight)
    }

    /** La franja de fundido de los cantos en la rueda [geo] (dp): [EDGE_FADE], o en un móvil la parte de la franja que asoma. */
    fun fadeBand(geo: MeridianGeometry): Float =
        if (geo.profile.maskOnly) (peek(geo) * FADE_SHARE).coerceIn(FADE_MIN, EDGE_FADE) else EDGE_FADE

    /**
     * La caja de una carátula de proporción [aspect] (ancho/alto de su imagen)
     * dentro del hueco de [slotWidth] × [slotHeight]: entera, sin recortar ni
     * deformar, lo más grande que quepa (como `ContentScale.Fit`), centrada.
     * El marco, la sombra, el filo y el brillo de la selección van con lo que
     * mide el arte. Sin proporción conocida (aún sin leer, sin imagen o
     * ilegible) la caja es la de una carátula de reserva: 2:3 a todo el alto,
     * la misma que una carátula de verdad, nunca una tarjeta de otro tamaño.
     */
    fun coverBox(slotWidth: Float, slotHeight: Float, aspect: Float?): Pair<Float, Float> =
        fit(slotWidth, slotHeight, aspect?.takeIf { it.isFinite() && it > 0f } ?: COVER_RATIO)

    /** [aspect] encajado entero en [slotWidth] × [slotHeight]. */
    fun fit(slotWidth: Float, slotHeight: Float, aspect: Float): Pair<Float, Float> {
        if (!aspect.isFinite() || aspect <= 0f || slotWidth <= 0f || slotHeight <= 0f) return slotWidth to slotHeight
        val k = min(slotWidth / aspect, slotHeight)
        return k * aspect to k
    }

    /**
     * Lo que deja ver el canto de la rueda (de [top] a [bottom]) a la altura
     * [y]: 0 en el canto, 1 a [band] de él. Es la máscara con la que se funde
     * una fila que asoma, igual arriba que abajo.
     */
    fun edgeAlpha(y: Float, top: Float, bottom: Float, band: Float = EDGE_FADE): Float {
        val b = band.coerceAtLeast(1e-3f)
        return min(((y - top) / b).coerceIn(0f, 1f), ((bottom - y) / b).coerceIn(0f, 1f))
    }

    /**
     * Dónde cae la franja de fundido dentro de una fila de [rowHeight] (en sus
     * coordenadas, sin escalar) con su centro visual en [center] y escalada
     * [scale] desde su centro vertical; null si la fila no la toca (entonces
     * no hace falta capa aparte ni máscara).
     */
    fun edgeMask(center: Float, scale: Float, rowHeight: Float, top: Float, bottom: Float, band: Float = EDGE_FADE): EdgeMask? {
        val s = scale.coerceAtLeast(1e-3f)
        val half = rowHeight * s / 2f
        if (center - half >= top + band && center + half <= bottom - band) return null
        fun local(y: Float) = rowHeight / 2f + (y - center) / s
        return EdgeMask(local(top), local(top + band), local(bottom - band), local(bottom))
    }

    /** La franja de fundido en coordenadas de la fila: transparente antes de [topClear] y después de [bottomClear]. */
    data class EdgeMask(val topClear: Float, val topSolid: Float, val bottomSolid: Float, val bottomClear: Float)
}
