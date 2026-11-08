package com.elyndra.launcher.core.device

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window

/**
 * Mide los fotogramas de la ventana en release para el último recurso de Auto
 * ([JankFallback]): si la biblioteca va a tirones sostenidos, pasa a Rendimiento.
 *
 * **Coste mínimo.** Solo está puesto con Auto, en Calidad y sin tirones ya
 * medidos (en cuanto salta se quita), y solo con la app en primer plano
 * (onResume/onPause). Por fotograma: leer una duración y guardarla en un
 * array fijo, en un hilo propio; ordenar se hace una vez cada 5 s. El registro
 * detallado sigue siendo solo de debug (`PerfProbe`).
 *
 * [eligible] lo pone la interfaz: Biblioteca o Carpeta, sin intro ni descarga
 * de metadatos (Masha es otra pantalla, así que también queda fuera).
 */
object JankMonitor {

    /** ¿La pantalla de ahora cuenta para medir? (lo escribe la interfaz). */
    @Volatile var eligible: Boolean = false

    private const val MAX_FRAMES = 1_200

    private var window: Window? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var thread: HandlerThread? = null

    /** Empieza a medir si toca; [onStutter] llega en el hilo principal (una vez). */
    fun resume(activity: Activity, onStutter: () -> Unit) {
        pause()
        val wantsIt = DevicePerformance.mode == PerformanceTier.Mode.Auto &&
            !DevicePerformance.profile.lowEnd && !DevicePerformance.stutter
        if (!wantsIt) return
        val w = activity.window ?: return
        val t = HandlerThread("ElyndraJank", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val handler = Handler(t.looper)
        val main = Handler(Looper.getMainLooper())
        val frames = FloatArray(MAX_FRAMES)
        var count = 0
        var allEligible = true
        var windowStart = SystemClock.elapsedRealtime()
        var state = JankFallback.State()
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (state.triggered) return@OnFrameMetricsAvailableListener
            if (count < MAX_FRAMES) frames[count++] = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000f
            if (!eligible) allEligible = false
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart < JankFallback.WINDOW_MS) return@OnFrameMetricsAvailableListener
            state = JankFallback.step(
                state,
                JankFallback.Window(
                    frames = count,
                    p90Ms = JankFallback.p90(frames, count),
                    eligible = allEligible,
                    sinceStartMs = now - Process.getStartElapsedRealtime(),
                ),
            )
            count = 0
            allEligible = true
            windowStart = now
            if (state.triggered) main.post {
                pause()
                onStutter()
            }
        }
        w.addOnFrameMetricsAvailableListener(l, handler)
        window = w
        listener = l
        thread = t
    }

    /** Deja de medir (al ir a segundo plano o tras saltar). */
    fun pause() {
        val w = window
        val l = listener
        if (w != null && l != null) runCatching { w.removeOnFrameMetricsAvailableListener(l) }
        thread?.quitSafely()
        window = null
        listener = null
        thread = null
    }
}
