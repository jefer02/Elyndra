package com.elyndra.launcher.sound

/**
 * Qué suena y cuándo, sin Android (se prueba en la JVM con un reloj a mano).
 *
 *  · Navegar tiene un mínimo de [NAV_MIN_GAP_MS] entre dos pasos. Si se
 *    mantiene el stick, los pasos seguidos forman una racha y el tono sube
 *    un poco y vuelve ([HELD_RATES]): se oye que se avanza, no un taladro.
 *  · Aceptar y volver esperan [DEFER_MS]: si en ese rato llega algo que
 *    manda más (abrir un menú, un error, lanzar), se oye solo eso.
 *  · Dos sonidos casi a la vez: el de menos prioridad se descarta.
 *  · Tras lanzar un juego, silencio [LAUNCH_MUTE_MS]: al salir de la app no
 *    se oye nada detrás del sonido de lanzamiento.
 *
 * No reserva memoria en la ruta caliente: devuelve códigos y deja la
 * velocidad de reproducción en [rate].
 */
class SoundScheduler {

    /** Velocidad de reproducción del último [PLAY] (1 = tono original). */
    var rate: Float = 1f; private set

    /** El que está esperando ([DEFER]); null = ninguno. */
    var pending: UiSound? = null; private set
    private var pendingAt = 0L

    private var lastNavAt = Long.MIN_VALUE / 2
    private var navStreak = 0
    private var lastPlayedAt = Long.MIN_VALUE / 2
    private var lastPlayedPriority = -1
    private var mutedUntil = Long.MIN_VALUE / 2

    /**
     * Llega [sound] en [now] (ms). [PLAY]: que suene ya (a [rate]).
     * [DEFER]: queda en espera; pregúntese con [due]. [DROP]: no suena.
     */
    fun offer(sound: UiSound, now: Long): Int {
        if (now < mutedUntil) return DROP
        if (sound == UiSound.Navigate) {
            if (now - lastNavAt < NAV_MIN_GAP_MS) return DROP
            navStreak = if (now - lastNavAt <= HELD_WINDOW_MS) navStreak + 1 else 0
            lastNavAt = now
            // Algo más importante acaba de sonar: el paso no lo pisa.
            if (now - lastPlayedAt < COLLAPSE_MS && lastPlayedPriority > sound.priority) return DROP
            rate = if (navStreak == 0) 1f else HELD_RATES[(navStreak - 1) % HELD_RATES.size]
            return PLAY
        }
        if (sound.deferrable) {
            val waiting = pending
            if (waiting != null && waiting.priority > sound.priority) return DROP
            pending = sound
            pendingAt = now
            return DEFER
        }
        // Manda más que lo que esperaba: lo que esperaba ya no suena.
        pending?.let { if (it.priority <= sound.priority) pending = null }
        if (now - lastPlayedAt < COLLAPSE_MS && lastPlayedPriority >= sound.priority) return DROP
        played(sound, now)
        if (sound == UiSound.Launch) mutedUntil = now + LAUNCH_MUTE_MS
        rate = 1f
        return PLAY
    }

    /** El sonido en espera si ya le toca (y lo da por sonado); si no, null. */
    fun due(now: Long): UiSound? {
        val waiting = pending ?: return null
        if (now - pendingAt < DEFER_MS) return null
        pending = null
        if (now < mutedUntil) return null
        if (now - lastPlayedAt < COLLAPSE_MS && lastPlayedPriority >= waiting.priority) return null
        played(waiting, now)
        rate = 1f
        return waiting
    }

    /** Silencio total durante [ms] (p. ej. al salir de la app). */
    fun muteFor(now: Long, ms: Long) {
        mutedUntil = maxOf(mutedUntil, now + ms)
        pending = null
    }

    /** Vuelve a sonar ya (al volver a la app). */
    fun unmute() {
        mutedUntil = Long.MIN_VALUE / 2
    }

    private fun played(sound: UiSound, now: Long) {
        lastPlayedAt = now
        lastPlayedPriority = sound.priority
    }

    companion object {
        const val PLAY = 0
        const val DEFER = 1
        const val DROP = 2

        const val NAV_MIN_GAP_MS = 60L
        /** Pasos más juntos que esto cuentan como stick mantenido. */
        const val HELD_WINDOW_MS = 220L
        const val DEFER_MS = 45L
        const val COLLAPSE_MS = 90L
        const val LAUNCH_MUTE_MS = 1_500L

        /** Tonos de la racha: sube un poco, vuelve, y así. */
        val HELD_RATES = floatArrayOf(1.04f, 1.08f, 1.04f, 1f)
    }
}
