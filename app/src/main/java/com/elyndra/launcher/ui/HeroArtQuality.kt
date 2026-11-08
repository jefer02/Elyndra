package com.elyndra.launcher.ui

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * El arte del hero a pantalla completa, sin Compose: a qué tamaño se pide y
 * qué hacer cuando la imagen que hay es demasiado pequeña.
 *
 * **Por qué.** El hero se veía en píxeles en un móvil modesto por dos cosas:
 * - En la calidad ligera Coil decodifica en RGB_565 (la mitad de memoria):
 *   bien para carátulas, pero en un fondo grande con degradados se ven
 *   escalones y tramado. El hero se pide siempre en ARGB_8888.
 * - Muchas fuentes solo tienen capturas de 320–640 px. Recortadas para cubrir
 *   1600 px se amplían 3–5 veces y se ven los píxeles. Si cada píxel de la
 *   imagen ocupa más de [LOW_RES_DP_PER_PIXEL] dp en pantalla, el arte se
 *   suaviza (desenfoque leve con RenderEffect y un velo algo más oscuro), que
 *   se lee como un fondo intencionado.
 *
 * Se mide en dp y no en píxeles de pantalla: en una tableta de 3040 px un
 * fanart de 1920 px se amplía más de 2 veces, pero cada píxel sigue siendo
 * menos de 1 dp y se ve nítido. Medido en píxeles se desenfocaba ahí (y no en
 * un móvil de 1600 px) arte que antes se veía bien.
 */
object HeroArtQuality {

    /** Más dp de pantalla por píxel de la imagen que esto (en el lado que más se estira al recortar) y se ven los píxeles. */
    const val LOW_RES_DP_PER_PIXEL = 2f

    /** Desenfoque del arte de baja resolución (dp): de [SOFT_BLUR_MIN] a [SOFT_BLUR_MAX] según cuánto se amplía. */
    const val SOFT_BLUR_MIN = 2f
    const val SOFT_BLUR_MAX = 8f

    /** Velo extra sobre el arte de baja resolución: baja el contraste de los bordes de píxel. */
    const val LOW_RES_DIM = 0.18f

    /** Cuánto se amplía una imagen de [srcW]×[srcH] para cubrir (recorte) una caja de [boxW]×[boxH]. */
    fun cropScale(srcW: Int, srcH: Int, boxW: Int, boxH: Int): Float {
        if (srcW <= 0 || srcH <= 0 || boxW <= 0 || boxH <= 0) return 1f
        return max(boxW.toFloat() / srcW, boxH.toFloat() / srcH)
    }

    /** Cuántos dp ocupa en pantalla cada píxel de la imagen al cubrir la caja (con [density] px por dp). */
    fun dpPerPixel(srcW: Int, srcH: Int, boxW: Int, boxH: Int, density: Float): Float =
        if (density <= 0f) cropScale(srcW, srcH, boxW, boxH) else cropScale(srcW, srcH, boxW, boxH) / density

    /** ¿Se verían los píxeles? */
    fun isLowRes(srcW: Int, srcH: Int, boxW: Int, boxH: Int, density: Float): Boolean =
        dpPerPixel(srcW, srcH, boxW, boxH, density) > LOW_RES_DP_PER_PIXEL

    /** El desenfoque (dp) cuando cada píxel ocupa [dpPerPixel] dp: más cuanto más se estira. */
    fun softBlurDp(dpPerPixel: Float): Float =
        ((dpPerPixel - LOW_RES_DP_PER_PIXEL) * 3f + SOFT_BLUR_MIN).coerceIn(SOFT_BLUR_MIN, SOFT_BLUR_MAX)

    /**
     * El tamaño (px) al que se pide el arte para una caja de [boxW]×[boxH]:
     * el de la caja por [scale] (1 en Calidad, menos en Rendimiento), nunca
     * menos de 1. Nunca el de una miniatura: eso es lo que luego se amplía.
     */
    fun requestSize(boxW: Int, boxH: Int, scale: Float): Pair<Int, Int> =
        (boxW * scale).roundToInt().coerceAtLeast(1) to (boxH * scale).roundToInt().coerceAtLeast(1)
}
