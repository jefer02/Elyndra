package com.elyndra.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elyndra.launcher.ui.intro.IntroTimeline

/**
 * La intro de arranque mientras está en pantalla (ver BootIntro).
 *
 * Cuelga del ViewModel para que un giro a mitad no la reinicie: el reloj
 * sigue contando desde [startNanos], que fija el primer fotograma.
 */
class IntroController {

    var visible by mutableStateOf(false); private set

    /** Cambia con cada vista previa: la intro se vuelve a montar desde cero. */
    var run by mutableIntStateOf(0); private set

    /** Milisegundos desde el inicio en que se pidió saltarla, o [IntroTimeline.NO_SKIP]. */
    var skipAt by mutableFloatStateOf(IntroTimeline.NO_SKIP); private set

    /** Tiempo del primer fotograma (base de `withFrameNanos`, la de System.nanoTime); 0 = aún no ha empezado. */
    var startNanos = 0L

    /** Arranque en frío: lo decide MainActivity con la compuerta del proceso. */
    fun start() = restart()

    /** "Vista previa" de Ajustes: la reproduce entera aunque ya se viera. */
    fun preview() = restart()

    private fun restart() {
        startNanos = 0L
        skipAt = IntroTimeline.NO_SKIP
        run++
        visible = true
    }

    /**
     * Toque, botón del mando o atrás. Antes de [IntroTimeline.SKIP_AFTER_MS]
     * no hace nada (la pulsación se consume igual).
     */
    fun skip(nowNanos: Long = System.nanoTime()) {
        if (!visible || startNanos == 0L || skipAt >= 0f) return
        val elapsed = (nowNanos - startNanos) / 1_000_000f
        if (IntroTimeline.canSkip(elapsed)) skipAt = elapsed
    }

    /** Se quita sin fundido: se ha llegado desde el widget o un aviso, a otra cosa. */
    fun cancel() {
        visible = false
    }

    fun finish() {
        visible = false
        startNanos = 0L
    }
}
