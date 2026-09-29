package com.elyndra.launcher.ui.masha

import com.elyndra.launcher.ui.masha.lipsync.Ch
import com.elyndra.launcher.ui.masha.lipsync.LipSyncConfig
import com.elyndra.launcher.ui.masha.lipsync.Track
import com.elyndra.launcher.ui.masha.lipsync.Vis
import kotlin.math.exp
import kotlin.math.floor

/**
 * Sincronía de labios, lado del render.
 *
 * El hilo de la voz ([MashaVoice]) prepara, por cada frase, unas curvas de
 * 100 Hz ([Track]: visemas coarticulados, mandíbula, nivel de la voz, cejas,
 * cabeceos, parpadeos) a partir del texto, los rangos de palabra del motor y
 * el propio audio (ver `lipsync/`). Aquí, en cada fotograma, se mira dónde va
 * el audio que se oye ([Clock]: la cabeza de reproducción de AudioTrack con
 * su latencia) y se leen las curvas en ese instante + el adelanto visual
 * (60 ms por defecto), con un suavizado final exponencial en función de dt
 * (independiente de los fps). Sin objetos por fotograma.
 */
class LipSync(val config: LipSyncConfig = LipSyncConfig()) {

    /** Una frase: sus curvas (las publica el hilo de la voz) y su posición en la salida de audio. */
    class Item internal constructor(val id: String) {
        @Volatile var track: Track = Track.EMPTY
        /** Frecuencia de muestreo del audio de la frase (0 = aún no se sabe). */
        @Volatile var sampleRate = 0
        /** Trama de la salida (AudioTrack) donde empieza la frase; −1 = aún no se ha escrito. */
        @Volatile var startFrame = -1L
        /** Trama de la salida donde acaba (−1 = sigue llegando). */
        @Volatile var endFrame = -1L
        /** Pista de audio a la que pertenecen [startFrame]/[endFrame]. */
        @Volatile var outputId = -1
        /** Modo de repuesto (el motor reproduce): instante en que empezó a sonar, o −1. */
        @Volatile var startNanos = -1L
        /** Modo de repuesto: el motor avisó de que terminó. */
        @Volatile var spokenDone = false
    }

    /** Dónde va el audio. */
    interface Clock {
        /** Segundos desde el principio de [item] que se oyen en [nowNanos] (NaN = no ha empezado o no se sabe). */
        fun seconds(item: Item, nowNanos: Long): Double
    }

    /** Lo que sale en cada fotograma (ya suavizado). */
    class Frame {
        /** Visemas de labios, por ordinal de [Vis]. */
        val lips = FloatArray(Vis.COUNT)
        /** Peso de `jawOpen`. */
        var jaw = 0f
        /** Nivel real de la voz 0..1 (sin adelanto): gestos del cuerpo y brillo. */
        var env = 0f
        /** Acento de cejas 0..1. */
        var brow = 0f
        /** Cabeceo (rad, + = barbilla abajo). */
        var nod = 0f
        /** Parpadear ahora (coma o punto). */
        var blink = false
        /** Hay una frase sonando bajo la boca. */
        var active = false

        fun clear() {
            lips.fill(0f); jaw = 0f; env = 0f; brow = 0f; nod = 0f; blink = false; active = false
        }
    }

    @Volatile var clock: Clock? = null

    @Volatile private var items: Array<Item> = emptyArray()
    private val lock = Any()

    internal fun add(item: Item) = synchronized(lock) { items = items + item }

    internal fun remove(item: Item) = synchronized(lock) { items = items.filter { it !== item }.toTypedArray() }

    internal fun clear() = synchronized(lock) { items = emptyArray() }

    internal val count: Int get() = items.size

    private val target = FloatArray(Ch.COUNT)
    private var lastItem: Item? = null
    private var lastT = 0.0

    /**
     * Pesos en [now] (System.nanoTime / tiempo de vsync); [dt] en s desde el
     * fotograma anterior. Escribe en [out], que se reutiliza.
     */
    fun sample(now: Long, dt: Float, out: Frame) {
        target.fill(0f)
        out.blink = false
        var active = false
        val c = clock
        val list = items
        val lead = config.leadSeconds.toDouble()
        if (c != null) {
            // La más reciente que ya suena (las siguientes aún tienen tiempo negativo).
            var i = list.size - 1
            while (i >= 0) {
                val item = list[i--]
                val t = c.seconds(item, now)
                if (t.isNaN() || t < -lead - 0.05) continue
                val tr = item.track
                if (tr.complete && t > tr.frames * 0.01) continue
                if (tr.frames > 0) {
                    for (ch in 0 until Ch.COUNT) if (ch != Ch.ENV) target[ch] = value(tr, t + lead, ch)
                    target[Ch.ENV] = value(tr, t, Ch.ENV)
                    if (item === lastItem) {
                        val a = lastT * 100.0
                        val b = t * 100.0
                        for (k in tr.blinks) if (k > a && k <= b) out.blink = true
                    }
                }
                active = t >= 0.0 && t * 100.0 < tr.audioFrames + 5
                lastItem = item
                lastT = t
                break
            }
        }
        out.active = active

        // Suavizado final (las curvas ya son suaves: solo quita escalones entre fotogramas y cierra al acabar).
        // Filtro de primer orden integrado exactamente con el objetivo lineal entre fotogramas
        // (retención de primer orden): el resultado casi no depende de los fps.
        val s = config.smoothing.coerceAtLeast(0.05f)
        if (!primed) {
            target.copyInto(prev)
            primed = true
        }
        for (v in 0 until Vis.COUNT) {
            // El cierre de P/B/M es más rápido (τ ≈ 11 ms): el contacto de labios no se puede perder.
            out.lips[v] = foh(out.lips[v], prev[v], target[v], (if (v == PP) 90f else 55f) * s, dt)
        }
        out.jaw = foh(out.jaw, prev[Ch.JAW], target[Ch.JAW], 40f * s, dt)
        out.env = foh(out.env, prev[Ch.ENV], target[Ch.ENV], 60f, dt)
        out.brow = foh(out.brow, prev[Ch.BROW], target[Ch.BROW], 25f * s, dt)
        out.nod = foh(out.nod, prev[Ch.NOD], target[Ch.NOD], 25f * s, dt)
        target.copyInto(prev)
    }

    private val prev = FloatArray(Ch.COUNT)
    private var primed = false

    private companion object {
        val PP = Vis.PP.ordinal

        /**
         * y' = λ(x − y) con x lineal de [x0] a [x1] durante [dt]: solución exacta.
         * Con dt → 0 o λ·dt muy pequeño, un paso de Euler.
         */
        fun foh(y: Float, x0: Float, x1: Float, lambda: Float, dt: Float): Float {
            if (dt <= 0f) return y
            val ld = lambda * dt
            if (ld < 1e-4f) return y + (x1 - y) * ld
            val e = exp(-ld)
            val slope = (x1 - x0) / ld
            return x1 - slope + e * (y - x0 + slope)
        }
    }

    private fun value(tr: Track, t: Double, ch: Int): Float {
        val x = t * 100.0
        if (x <= 0.0) return if (x > -1.0) tr.at(0, ch) else 0f
        val k = floor(x).toInt()
        // Más allá de lo calculado: la última trama (en una pista completa es la cola, ya cerrada).
        if (k >= tr.frames - 1) return tr.at(tr.frames - 1, ch)
        val f = (x - k).toFloat()
        return tr.at(k, ch) * (1f - f) + tr.at(k + 1, ch) * f
    }
}
