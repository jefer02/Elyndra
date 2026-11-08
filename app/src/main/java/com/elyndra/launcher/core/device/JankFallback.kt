package com.elyndra.launcher.core.device

/**
 * El último recurso de Auto: si las señales del equipo dicen "Calidad" pero
 * la biblioteca va a tirones de verdad, se pasa a Rendimiento. Sin Android:
 * recibe ventanas de fotogramas ya resumidas y decide (se prueba en la JVM).
 *
 * **Cuándo cuenta una ventana** ([WINDOW_MS] de fotogramas):
 * - solo en Biblioteca y Carpeta, sin la intro, sin Masha (su 3D va a otro
 *   ritmo) y sin una descarga de metadatos en marcha (la biblioteca se
 *   recompone con cada juego que llega): fuera de eso no cuenta ni para bien
 *   ni para mal;
 * - nunca en los primeros [STARTUP_GRACE_MS] del proceso (arranque en frío,
 *   primera composición, caché de imágenes vacía);
 * - con al menos [MIN_FRAMES] fotogramas: una pantalla quieta no pinta y no
 *   dice nada.
 *
 * **Cuándo salta:** [BAD_WINDOWS] ventanas seguidas con el percentil 90 por
 * encima de [P90_BUDGET_MS] (un fotograma de cada diez tarda más que un
 * fotograma y medio de 60 Hz, sostenido ~20 s). Una ventana buena vuelve a
 * empezar la cuenta: un tirón suelto (un juego que entra, una imagen grande)
 * no basta. Una vez saltado no vuelve atrás solo: se guarda, y se deshace
 * eligiendo Auto otra vez en Ajustes ([StutterMemory]).
 */
object JankFallback {

    const val WINDOW_MS = 5_000L
    const val MIN_FRAMES = 60
    const val P90_BUDGET_MS = 25f
    const val BAD_WINDOWS = 4
    const val STARTUP_GRACE_MS = 20_000L

    /** Una ventana de fotogramas ya resumida. [eligible]: todos sus fotogramas fueron en una pantalla que cuenta. */
    data class Window(val frames: Int, val p90Ms: Float, val eligible: Boolean, val sinceStartMs: Long)

    data class State(val badWindows: Int = 0, val triggered: Boolean = false)

    fun step(state: State, w: Window): State {
        if (state.triggered) return state
        // Lo que no cuenta no suma ni resta.
        if (!w.eligible || w.sinceStartMs < STARTUP_GRACE_MS || w.frames < MIN_FRAMES) return state
        if (w.p90Ms <= P90_BUDGET_MS) return State()
        val bad = state.badWindows + 1
        return State(badWindows = bad, triggered = bad >= BAD_WINDOWS)
    }

    /**
     * El percentil 90 de los primeros [count] valores de [frames] (ms). Ordena
     * una copia: se llama una vez por ventana, no por fotograma.
     */
    fun p90(frames: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        val sorted = frames.copyOf(count).also { it.sort() }
        return sorted[((count - 1) * 0.9f).toInt()]
    }
}

/**
 * Lo que se recuerda del salto por tirones (en `SettingsStore`): si está
 * puesto y si ya se avisó. Sin Android, para probar el guardado y el reinicio.
 */
object StutterMemory {

    data class Stored(val stutter: Boolean = false, val noticeShown: Boolean = false)

    /** Se han medido tirones: se guarda y se avisa solo la primera vez. */
    fun onStutter(s: Stored): Pair<Stored, Boolean> = Stored(stutter = true, noticeShown = true) to !s.noticeShown

    /**
     * Elegir Auto (aunque ya lo estuviera) olvida los tirones medidos y vuelve
     * a decidir con las señales; si vuelven, se vuelve a avisar. Calidad o
     * Rendimiento elegidos a mano no tocan lo guardado.
     */
    fun onModeChosen(mode: PerformanceTier.Mode, s: Stored): Stored =
        if (mode == PerformanceTier.Mode.Auto) Stored() else s
}
