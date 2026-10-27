package com.elyndra.launcher.ui.masha

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import kotlin.math.max
import kotlin.math.min

/**
 * La luz de fondo de Masha: un halo suave detrás de su silueta para que el
 * holograma azul no se pierda en el azul oscuro de la sala. Kotlin puro (se
 * prueba en la JVM): sale del mismo encuadre que la cámara ([MashaFraming]).
 *
 * Por qué un anillo y no un foco lleno: la vista de Filament es opaca (el
 * cielo de la sala se dibuja detrás de ella), así que nada 2D puede ir de
 * verdad *detrás* del 3D; el halo va justo encima del escenario y debajo de la
 * atmósfera y la interfaz. Un foco lleno aclararía igual a ella que al fondo y
 * le quitaría contraste; el anillo deja casi limpio el hueco de su torso y
 * enciende el fondo alrededor de la silueta, que es lo que la recorta.
 *
 * Por qué hace falta: la luz del cielo (las columnas de la sala) está fija en
 * el centro de la pantalla. En horizontal la cámara se aparta en paralelo para
 * ponerla en la columna de la izquierda, y el cielo, en el infinito, no se
 * mueve con ella: la luz quedaba a su lado y ella en la parte más oscura.
 */
internal object MashaBacklight {

    /** El punto del torso por debajo de los ojos (≈1,21 m, a la altura del pecho). */
    const val TORSO_BELOW_EYES = 0.45f

    /** Media anchura de los hombros: lo que el anillo deja casi limpio. */
    const val SHOULDER_HALF_WIDTH = 0.22f

    /** Media anchura del halo (m) y su alto respecto al tramo del plano. */
    const val HALF_WIDTH = 0.62f
    const val HEIGHT_OF_SPAN = 0.62f

    /** El hueco limpio del anillo, en fracción del radio: ni un punto ni media pantalla. */
    const val MIN_INNER = 0.25f
    const val MAX_INNER = 0.6f

    /**
     * Opacidad del pico del anillo: baja a propósito, es luz de ambiente, no un
     * foco de escenario. Se mezcla con alfa normal: la vista de Filament es una
     * SurfaceView, debajo de la ventana, y un modo de fusión ("trama", "sumar")
     * solo vería el hueco transparente de la ventana, no el 3D.
     */
    const val PEAK_ALPHA = 0.16f

    /** Dentro del anillo, una pizca de luz: se ve a través del holograma translúcido. */
    const val CORE_ALPHA = 0.2f

    /** Cuánto se acerca al gris azulado claro el color del ánimo: baja saturación. */
    const val DESATURATE = 0.45f

    /**
     * Un halo en px de la vista: centro, radios de la elipse y el hueco limpio
     * del anillo ([inner], fracción del radio).
     */
    class Halo(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val inner: Float)

    /**
     * Dónde va el halo con el plano [shot] en una vista de [viewW]×[viewH] px y
     * el hueco libre [left]..[bottom] (mismas coordenadas, como en
     * [MashaFraming.solve]). Null si aún no hay medidas.
     *
     * Se centra en su torso (o, en el primer plano, en el centro de lo que se
     * ve) y en horizontal en el hueco; nunca más ancho que el hueco, para que
     * no se derrame sobre la conversación.
     */
    fun place(
        viewW: Float,
        viewH: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        shot: MashaShot,
        landscape: Boolean,
        eyeY: Float = MashaFraming.REST_EYES[1],
        tanHalf: Float = MashaFraming.tanHalfFov(MashaFraming.FOCAL_LENGTH_MM),
    ): Halo? {
        if (viewW <= 0f || viewH <= 0f) return null
        val out = FloatArray(3)
        MashaFraming.solve(viewW, viewH, left, top, right, bottom, tanHalf, shot, eyeY, out, landscape)
        val ppm = viewH / (out[0] * 2f * tanHalf)
        val spanTop = MashaFraming.spanTop(eyeY)
        val spanBottom = MashaFraming.spanBottom(shot, eyeY, landscape)
        val torso = max(eyeY - TORSO_BELOW_EYES, (spanTop + spanBottom) / 2f)
        val cx = MashaFraming.screenX(viewW, viewH, tanHalf, out)
        val cy = MashaFraming.screenY(torso, viewH, tanHalf, out)

        // El ancho que hay: el hueco medido o, sin él, toda la vista.
        val bandW = (right.coerceIn(0f, viewW) - left.coerceIn(0f, viewW)).takeIf { it > 1f } ?: viewW
        val bandH = (bottom.coerceIn(0f, viewH) - top.coerceIn(0f, viewH)).takeIf { it > 1f } ?: viewH
        val rx = min(HALF_WIDTH * ppm, bandW / 2f)
        val ry = min(HEIGHT_OF_SPAN * (spanTop - spanBottom) * ppm, bandH * 0.6f).coerceAtLeast(rx)
        val inner = (SHOULDER_HALF_WIDTH * ppm / rx).coerceIn(MIN_INNER, MAX_INNER)
        return Halo(cx, cy, rx, ry, inner)
    }

    /** El color del halo: el del ánimo, apagado hacia un gris azulado claro. */
    fun tint(glow: Color): Color = lerp(glow, NEUTRAL, DESATURATE)

    /**
     * Opacidad del pico. En calidad alta sube y baja un poco con la energía del
     * ánimo; en la ligera y con "reducir movimiento", fija (no se redibuja por ello).
     */
    fun peak(energy: Float, lite: Boolean, reduced: Boolean): Float =
        if (lite || reduced) PEAK_ALPHA else PEAK_ALPHA * (0.85f + 0.3f * energy.coerceIn(0f, 1f))

    private val NEUTRAL = Color(0xFFDCE6F5)
}

/**
 * Dibuja [halo] (ver [MashaBacklight]). [visible] lo enciende y lo apaga con
 * un fundido (al instante con "reducir movimiento"); [lite]: el mismo
 * degradado 2D, sin seguir la energía del ánimo.
 */
@Composable
internal fun HoloBacklight(
    halo: () -> MashaBacklight.Halo?,
    presence: MashaPresence,
    visible: Boolean,
    lite: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val shown by animateFloatAsState(if (visible) 1f else 0f, if (reduced) tween(0) else tween(420), label = "backlight")
    Spacer(
        modifier.fillMaxSize().drawBehind {
            if (shown <= 0f) return@drawBehind
            val h = halo() ?: return@drawBehind
            val color = MashaBacklight.tint(Color(presence.visibleMood.glow))
            val a = MashaBacklight.peak(if (lite || reduced) 0f else presence.energy, lite, reduced) * shown
            val inner = h.inner
            val brush = Brush.radialGradient(
                0f to color.copy(alpha = a * MashaBacklight.CORE_ALPHA),
                inner to color.copy(alpha = a * 0.55f),
                (inner + (1f - inner) * 0.35f) to color.copy(alpha = a),
                1f to Color.Transparent,
                center = Offset(h.cx, h.cy),
                radius = h.rx,
            )
            // Un círculo de radio rx estirado a lo alto hasta ry: la elipse de su silueta.
            scale(scaleX = 1f, scaleY = h.ry / h.rx, pivot = Offset(h.cx, h.cy)) {
                drawCircle(brush, h.rx, Offset(h.cx, h.cy))
            }
        },
    )
}
