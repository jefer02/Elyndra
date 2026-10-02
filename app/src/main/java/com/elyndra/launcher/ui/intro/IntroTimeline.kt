package com.elyndra.launcher.ui.intro

import com.elyndra.launcher.ui.theme.EaseInOut
import com.elyndra.launcher.ui.theme.Swift
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lo que se ve en cada instante de la intro. Todo 0…1 salvo [sweep], que es
 * la posición del barrido de luz sobre el rótulo (0 = borde izquierdo, 1 =
 * derecho). Se rellena en el sitio para no crear objetos por fotograma.
 */
class IntroFrame {
    /** Fondo: niebla y viñeta. */
    var atmosphere = 0f
    /** Polvo que llega desde los bordes hacia el centro. */
    var gather = 0f
    /** Núcleo de luz del centro. */
    var core = 0f
    /** Destello radial. */
    var burst = 0f
    /** Intensidad de la raya anamórfica horizontal. */
    var streak = 0f
    /** Lo que se ha abierto la raya, de un punto a casi todo el ancho. */
    var streakSpread = 0f
    /** Rótulo descubierto desde el centro hacia fuera. */
    var reveal = 0f
    /** Ascuas que se desprenden de las letras. */
    var embers = 0f
    var sweep = 0f
    var sweepAlpha = 0f
    /** El polvo se dispersa hacia fuera al final. */
    var disperse = 0f
    /** Entrada del contenido en la versión reducida (en la completa, 1). */
    var fadeIn = 1f
    /** Opacidad de toda la intro: 0 = ya se ve la biblioteca. */
    var alpha = 1f
    var done = false
}

/**
 * La línea de tiempo de la intro, en milisegundos y sin Compose:
 *
 *   0.0–0.4 s  atmósfera: niebla y viñeta;
 *   0.4–1.4 s  el polvo dorado converge y el núcleo de luz crece;
 *   1.4–1.6 s  destello radial y raya anamórfica;
 *   1.5–2.4 s  el rótulo se abre desde el centro, con ascuas;
 *   2.4–3.0 s  barrido de luz sobre las letras;
 *   3.0–3.5 s  pausa, el polvo se dispersa y la intro se funde.
 *
 * Con "reducir movimiento", solo un fundido de 0,8 s con el fotograma final.
 * Saltar (a partir de 0,8 s) adelanta un fundido corto desde ese instante.
 */
object IntroTimeline {

    const val TOTAL_MS = 3500f
    const val REDUCED_MS = 800f
    const val SKIP_AFTER_MS = 800f
    const val SKIP_FADE_MS = 280f

    /** Sin salto pedido. */
    const val NO_SKIP = -1f

    fun duration(reduced: Boolean): Float = if (reduced) REDUCED_MS else TOTAL_MS

    fun canSkip(elapsedMs: Float): Boolean = elapsedMs >= SKIP_AFTER_MS

    fun end(reduced: Boolean, skipAt: Float): Float =
        if (skipAt < 0f) duration(reduced) else min(duration(reduced), skipAt + SKIP_FADE_MS)

    fun fill(t: Float, reduced: Boolean, skipAt: Float, out: IntroFrame): IntroFrame {
        if (reduced) reducedFrame(t, out) else fullFrame(t, out)
        if (skipAt >= 0f && t >= skipAt) {
            val k = EaseInOut.transform(range(t, skipAt, skipAt + SKIP_FADE_MS))
            out.alpha = min(out.alpha, 1f - k)
            out.disperse = max(out.disperse, k)
        }
        out.done = t >= end(reduced, skipAt)
        return out
    }

    /** Comodidad para pruebas: un fotograma nuevo. */
    fun at(t: Float, reduced: Boolean = false, skipAt: Float = NO_SKIP): IntroFrame = fill(t, reduced, skipAt, IntroFrame())

    private fun fullFrame(t: Float, o: IntroFrame) {
        o.fadeIn = 1f
        o.atmosphere = Swift.transform(range(t, 0f, 400f))
        o.gather = EaseInOut.transform(range(t, 400f, 1450f))
        // El núcleo crece con el polvo y, tras el destello, se queda en un
        // resplandor suave detrás del rótulo.
        o.core = smooth(range(t, 400f, 1400f)) * (1f - 0.55f * smooth(range(t, 1600f, 2600f)))
        o.burst = Swift.transform(range(t, 1400f, 1480f)) * fall(range(t, 1480f, 2300f))
        o.streak = Swift.transform(range(t, 1400f, 1500f)) * fall(range(t, 1560f, 2500f))
        o.streakSpread = Swift.transform(range(t, 1400f, 1800f))
        o.reveal = Swift.transform(range(t, 1500f, 2400f))
        o.embers = range(t, 1600f, 2000f) * (1f - range(t, 3050f, 3400f))
        val s = range(t, 2400f, 3000f)
        o.sweep = EaseInOut.transform(s)
        o.sweepAlpha = sin(PI.toFloat() * s).coerceAtLeast(0f)
        o.disperse = EaseInOut.transform(range(t, 3050f, 3500f))
        o.alpha = 1f - EaseInOut.transform(range(t, 3150f, 3500f))
    }

    private fun reducedFrame(t: Float, o: IntroFrame) {
        o.fadeIn = smooth(range(t, 0f, 250f))
        o.atmosphere = 1f
        o.gather = 0f
        o.core = 0.45f
        o.burst = 0f
        o.streak = 0f
        o.streakSpread = 0f
        o.reveal = 1f
        o.embers = 0f
        o.sweep = 0f
        o.sweepAlpha = 0f
        o.disperse = 0f
        o.alpha = 1f - smooth(range(t, 500f, REDUCED_MS))
    }

    private fun range(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

    private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

    /** De 1 a 0, rápido al principio: la cola de un destello. */
    private fun fall(x: Float): Float = (1f - x) * (1f - x)
}
