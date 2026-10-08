package com.elyndra.launcher.ui

import kotlin.math.hypot

/* ─────────────────────────────────────────────────────────────
   Tocar para abrir, sin Compose (en la rueda de Meridian y en el
   carrusel): un toque en lo no seleccionado lo selecciona; uno en lo
   seleccionado lo abre (con "Tocar lo seleccionado para abrir"
   activado). Dos toques rápidos en el mismo sitio abren lo que
   seleccionó el primero, aunque la lista se haya movido entre medias.
   Nada se abre dos veces seguidas (ver OpenGuard).
   ───────────────────────────────────────────────────────────── */

enum class TapAction { Select, Open, Ignore }

data class TapDecision(val action: TapAction, val key: String?) {
    companion object {
        val IGNORE = TapDecision(TapAction.Ignore, null)
    }
}

/**
 * La memoria de los toques de una lista: el último (qué elemento, cuándo y
 * dónde en la ventana), para reconocer el segundo de un doble toque.
 */
class TapGate {

    private var lastKey: String? = null
    private var lastAt = Long.MIN_VALUE
    private var lastX = 0f
    private var lastY = 0f

    /**
     * Un toque en [key] (en [x], [y] de la ventana, a los [nowMs]) con
     * [selectedKey] seleccionado. [scrolling]: la lista estaba arrastrándose o
     * deslizándose al apoyar el dedo (ese toque solo la para).
     */
    fun tap(
        key: String,
        selectedKey: String?,
        scrolling: Boolean,
        tapToOpen: Boolean,
        nowMs: Long,
        x: Float,
        y: Float,
        slopPx: Float,
    ): TapDecision {
        if (scrolling) {
            lastKey = null
            return TapDecision.IGNORE
        }
        val previous = lastKey
        val repeat = previous != null && nowMs - lastAt in 0..DOUBLE_TAP_MS && hypot(x - lastX, y - lastY) <= slopPx
        val target = if (repeat) previous!! else key
        lastKey = target
        lastAt = nowMs
        lastX = x
        lastY = y
        return when {
            repeat || (tapToOpen && key == selectedKey) -> TapDecision(TapAction.Open, target)
            key == selectedKey -> TapDecision.IGNORE
            else -> TapDecision(TapAction.Select, key)
        }
    }

    companion object {
        /** Lo que puede tardar el segundo toque de un doble toque. */
        const val DOUBLE_TAP_MS = 320L
    }
}

/**
 * Lo que impide abrir dos veces lo mismo (o abrir una carpeta y, con la
 * segunda pulsación, el primer juego de dentro): tras abrir algo, durante
 * [debounceMs] no se abre nada más, ni con el dedo ni con el mando.
 */
class OpenGuard(private val debounceMs: Long = DEBOUNCE_MS) {

    private var lastOpen = Long.MIN_VALUE / 2

    fun tryOpen(nowMs: Long): Boolean {
        if (nowMs - lastOpen < debounceMs) return false
        lastOpen = nowMs
        return true
    }

    companion object {
        const val DEBOUNCE_MS = 600L
    }
}
