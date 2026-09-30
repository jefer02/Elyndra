package com.elyndra.launcher.ui.masha

import kotlin.math.min

/** Los planos de la cámara de Masha. */
enum class MashaShot {
    /** Cabeza entera, cuello y pecho: el plano de conversación. */
    CloseUp,

    /** De la cabeza a la cadera (el plano "abierto" en vertical). */
    UpperBody,

    /** De la cabeza a los pies, con la base del holotanque. */
    FullBody,
}

/**
 * El encuadre de cada plano, definido por **proporciones**: qué tramo del
 * cuerpo tiene que caber, en qué parte del hueco libre de la interfaz y cuánto
 * aire deja. De ahí salen la distancia de la cámara y el desplazamiento de la
 * imagen para cualquier pantalla (móvil o tableta, en vertical u horizontal).
 * Kotlin puro (se prueba en la JVM).
 *
 * El hueco libre es la parte de la vista que no tapan la barra de arriba, las
 * tarjetas, las sugerencias ni la barra de escribir. La cámara mira de frente
 * y en horizontal (sin girar): para que ella caiga en el sitio que le toca
 * dentro del hueco, la cámara entera se desplaza en paralelo (arriba/abajo y a
 * los lados). Es exacto para lo que está a su profundidad y no depende de
 * `Camera.setShift`. Siempre centrada en horizontal en el hueco.
 *
 * El tramo se mide desde sus ojos (el punto que da el rig en cada pose), con
 * las medidas del modelo, iguales en `masha.glb` y `masha_lite.glb`: ojos a
 * 1,659 m, coronilla con pelo a 1,78 m, pecho (Spine2) a 1,34 m, cadera a
 * 1,00 m, pies a 0 m (base del holotanque hasta −0,07 m).
 */
internal object MashaFraming {

    /** Coronilla y pelo por encima de los ojos (0,121 m) y un poco de aire. */
    const val HEAD_TOP_ABOVE_EYES = 0.135f

    /** Línea del pecho por debajo de los ojos (≈1,30 m). */
    const val CHEST_BELOW_EYES = 0.36f

    /** Cadera por debajo de los ojos (≈1,00 m). */
    const val HIPS_BELOW_EYES = 0.66f

    /**
     * Abajo del cuerpo entero, en el mundo: la planta de los pies (el suelo del
     * holotanque, a 0). Su base, que baja hasta −0,07, queda fuera para acercar el plano.
     */
    const val FLOOR = 0f

    /** Ojos en reposo, hasta que llega la primera pose animada. */
    val REST_EYES = floatArrayOf(0f, 1.659f, 0.117f)

    const val MIN_DISTANCE = 0.45f
    const val MAX_DISTANCE = 8f

    /**
     * Un plano: el aire arriba y abajo (fracciones del alto del hueco), la
     * media anchura que tiene que caber, qué parte del ancho del hueco puede
     * ocupar y si se ancla arriba (la cabeza siempre en el mismo sitio aunque
     * el ancho obligue a alejarse) o se centra.
     */
    class Spec(
        val topMargin: Float,
        val bottomMargin: Float,
        val halfWidth: Float,
        val widthFill: Float,
        val anchorTop: Boolean,
    )

    /**
     * - Primer plano: la coronilla al 7 % del hueco y el pecho justo en su
     *   borde de abajo; a lo ancho, cuello y arranque de los hombros (±0,18 m):
     *   en un móvil en vertical los hombros rozan el borde, como en un retrato.
     * - Medio cuerpo: igual, hasta la cadera, con los brazos (±0,30 m).
     * - Cuerpo entero: lo más cerca posible sin cortarla: la coronilla y la
     *   planta de los pies tocan los bordes del hueco (1 % de aire); a lo
     *   ancho, el holotanque (±0,55 m) en todo el ancho del hueco.
     */
    fun spec(shot: MashaShot): Spec = when (shot) {
        MashaShot.CloseUp -> Spec(topMargin = 0.07f, bottomMargin = 0f, halfWidth = 0.18f, widthFill = 1f, anchorTop = true)
        MashaShot.UpperBody -> Spec(topMargin = 0.06f, bottomMargin = 0f, halfWidth = 0.30f, widthFill = 0.95f, anchorTop = true)
        MashaShot.FullBody -> Spec(topMargin = 0.01f, bottomMargin = 0.01f, halfWidth = 0.55f, widthFill = 1f, anchorTop = false)
    }

    /**
     * El plano de cada orientación: al entrar, cuerpo entero (en vertical y en
     * horizontal); el botón de encuadre acerca al primer plano y vuelve.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shotFor(landscape: Boolean, alternate: Boolean): MashaShot =
        if (alternate) MashaShot.CloseUp else MashaShot.FullBody

    /** Arriba y abajo del tramo del plano, en metros del mundo. */
    fun spanTop(eyeY: Float): Float = eyeY + HEAD_TOP_ABOVE_EYES

    fun spanBottom(shot: MashaShot, eyeY: Float): Float = when (shot) {
        MashaShot.CloseUp -> eyeY - CHEST_BELOW_EYES
        MashaShot.UpperBody -> eyeY - HIPS_BELOW_EYES
        MashaShot.FullBody -> FLOOR
    }

    /**
     * tan(½ FOV vertical) de `Camera.setLensProjection`: Filament usa un sensor
     * de 24 mm de alto, así que no depende de la relación de aspecto.
     */
    fun tanHalfFov(focalLengthMm: Double): Float = (12.0 / focalLengthMm).toFloat()

    /**
     * Resuelve [shot] para una vista de [viewW]×[viewH] px con el hueco libre
     * [left], [top], [right], [bottom] (px, mismas coordenadas; sin área = toda
     * la vista) y los ojos a [eyeY] m.
     *
     * Escribe en [out]: 0 distancia (m) · 1 altura de la cámara (m, mira en
     * horizontal) · 2 desplazamiento lateral de la cámara respecto a sus ojos (m).
     */
    fun solve(
        viewW: Float,
        viewH: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        tanHalf: Float,
        shot: MashaShot,
        eyeY: Float,
        out: FloatArray,
    ) {
        val spanTop = spanTop(eyeY)
        val spanBottom = spanBottom(shot, eyeY)
        if (viewW <= 0f || viewH <= 0f || tanHalf <= 0f) {
            out[0] = 2f; out[1] = (spanTop + spanBottom) / 2f; out[2] = 0f
            return
        }
        val l = left.coerceIn(0f, viewW)
        val r = right.coerceIn(0f, viewW)
        val t = top.coerceIn(0f, viewH)
        val b = bottom.coerceIn(0f, viewH)
        val ok = r - l > 1f && b - t > 1f
        val bl = if (ok) l else 0f
        val br = if (ok) r else viewW
        val bt = if (ok) t else 0f
        val bb = if (ok) b else viewH
        val bandW = br - bl
        val bandH = bb - bt

        val s = spec(shot)
        val span = spanTop - spanBottom
        // Píxeles por metro: lo que permite el alto (con su aire) y lo que permite el ancho.
        val byHeight = bandH * (1f - s.topMargin - s.bottomMargin) / span
        val byWidth = bandW * s.widthFill / (2f * s.halfWidth)
        var ppm = min(byHeight, byWidth)
        // A una distancia d se ven 2·d·tan metros en los viewH px de alto.
        var distance = viewH / (ppm * 2f * tanHalf)
        if (distance !in MIN_DISTANCE..MAX_DISTANCE) {
            distance = distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
            ppm = viewH / (distance * 2f * tanHalf)
        }
        val spanPx = span * ppm
        val topPx = if (s.anchorTop) bt + s.topMargin * bandH else bt + (bandH - spanPx) / 2f
        // El centro del tramo tiene que caer en aimPx (px desde arriba). Con la
        // cámara en horizontal, el centro de la vista es su propia altura: se
        // sube o se baja lo que falte. Igual en horizontal con el centro del hueco.
        val aimPx = topPx + spanPx / 2f
        val aimY = (spanTop + spanBottom) / 2f
        val centerX = (bl + br) / 2f

        out[0] = distance
        out[1] = aimY + (aimPx - viewH / 2f) / ppm
        out[2] = (viewW / 2f - centerX) / ppm
    }

    /**
     * Dónde cae en pantalla (px desde arriba) un punto a [y] m con el encuadre
     * [out] de [solve]. Para las pruebas y el diagnóstico.
     */
    fun screenY(y: Float, viewH: Float, tanHalf: Float, out: FloatArray): Float {
        val ppm = viewH / (out[0] * 2f * tanHalf)
        return viewH / 2f - (y - out[1]) * ppm
    }

    /**
     * Dónde cae en pantalla (px desde la izquierda) el punto de sus ojos: la
     * cámara se aparta `out[2]` m hacia un lado, así que ella se ve hacia el otro.
     */
    fun screenX(viewW: Float, viewH: Float, tanHalf: Float, out: FloatArray): Float {
        val ppm = viewH / (out[0] * 2f * tanHalf)
        return viewW / 2f - out[2] * ppm
    }
}
