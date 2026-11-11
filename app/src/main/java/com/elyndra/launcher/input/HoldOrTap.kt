package com.elyndra.launcher.input

/**
 * Un botón con dos gestos: tocar o mantener. Sin Android, con el reloj
 * inyectado en cada llamada.
 *
 * Lo usa el micrófono de Lucy (botón Y del mando y el botón grande de su
 * pantalla): tocar activa o desactiva la conversación en vivo; mantener es
 * pulsar para hablar, y soltar termina la frase.
 *
 * [press] al bajar, [tick] mientras sigue pulsado (lo llama quien programa el
 * aviso a los [holdMs]) y [release] al soltar.
 */
class HoldOrTap(val holdMs: Long = HOLD_MS) {

    enum class Gesture { Tap, HoldStart, HoldEnd }

    private var downAt = -1L
    private var holding = false

    val pressed: Boolean get() = downAt >= 0

    fun press(now: Long) {
        downAt = now
        holding = false
    }

    /** Si ya lleva [holdMs] pulsado, empieza el "mantener" (una vez). */
    fun tick(now: Long): Gesture? {
        if (downAt < 0 || holding || now - downAt < holdMs) return null
        holding = true
        return Gesture.HoldStart
    }

    /**
     * Al soltar: [Gesture.HoldEnd] si se estaba manteniendo; si no, [Gesture.Tap]
     * (aunque el aviso de [tick] no llegara a tiempo: soltar decide). Null si
     * no había nada pulsado.
     */
    fun release(): Gesture? {
        if (downAt < 0) return null
        val gesture = if (holding) Gesture.HoldEnd else Gesture.Tap
        downAt = -1
        holding = false
        return gesture
    }

    /** Se perdió la pulsación (se salió de la pantalla, se canceló el gesto). */
    fun cancel(): Gesture? {
        val wasHolding = holding
        downAt = -1
        holding = false
        return if (wasHolding) Gesture.HoldEnd else null
    }

    companion object {
        const val HOLD_MS = 350L
    }
}
