package com.elyndra.launcher.perf

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import com.elyndra.launcher.core.device.DevicePerformance
import com.elyndra.launcher.core.device.JankMonitor

/**
 * Solo en builds de depuración (src/debug): mide sin dependencias nuevas
 * (nada de JankStats ni Macrobenchmark) lo justo para encontrar tirones en un
 * equipo modesto. La de src/release no hace nada.
 *
 * - **Arranque**: del inicio del proceso al primer fotograma pintado
 *   (`Process.getStartElapsedRealtime`, API 24+).
 * - **Tirones**: `FrameMetrics` de la ventana; cada [WINDOW_MS] se escribe un
 *   resumen (fotogramas, lentos > [SLOW_MS], congelados > [FROZEN_MS], p50/p90/
 *   p99) y el perfil de coste en vigor, para comparar Calidad y Rendimiento en
 *   el mismo móvil.
 *
 *   adb logcat -s ElyndraPerf
 */
object PerfProbe {

    private const val TAG = "ElyndraPerf"
    private const val WINDOW_MS = 5_000L
    private const val SLOW_MS = 16.7
    private const val FROZEN_MS = 700.0

    private var installed = false

    fun install(activity: Activity) {
        if (installed) return
        installed = true
        val window = activity.window ?: return
        val thread = HandlerThread("ElyndraPerf").apply { start() }
        val handler = Handler(thread.looper)
        val stats = FrameStats()
        var firstFrame = true
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val totalMs = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
            if (firstFrame && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
                firstFrame = false
                val sinceStart = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                Log.i(TAG, "first frame ${sinceStart} ms after process start (${DevicePerformance.profile.label()})")
            }
            stats.add(totalMs)
            stats.flushIfDue()
        }
        window.addOnFrameMetricsAvailableListener(listener, handler)
        Log.i(TAG, "probe on: SDK ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}, ${DevicePerformance.profile.label()}")
    }

    private fun com.elyndra.launcher.core.device.PerformanceProfile.label(): String =
        (if (lowEnd) "profile=Performance" else "profile=Quality") +
            " mode=${DevicePerformance.mode} reason=${DevicePerformance.reason} stutterWatch=${JankMonitor.eligible}"

    /** Un resumen por ventana de tiempo (solo lo usa el hilo del listener). */
    private class FrameStats {
        private val frames = ArrayList<Double>(512)
        private var since = SystemClock.elapsedRealtime()

        fun add(ms: Double) {
            frames += ms
        }

        fun flushIfDue() {
            val now = SystemClock.elapsedRealtime()
            if (now - since < WINDOW_MS) return
            since = now
            // Sin fotogramas no hay nada que contar (la pantalla estaba quieta: bien).
            if (frames.isEmpty()) return
            val sorted = frames.sorted()
            fun p(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
            val slow = sorted.count { it > SLOW_MS }
            val frozen = sorted.count { it > FROZEN_MS }
            Log.i(
                TAG,
                "frames=${sorted.size} slow=$slow (${slow * 100 / sorted.size}%) frozen=$frozen " +
                    "p50=${"%.1f".format(p(0.5))} p90=${"%.1f".format(p(0.9))} p99=${"%.1f".format(p(0.99))} ms " +
                    "(${DevicePerformance.profile.label()})",
            )
            frames.clear()
        }
    }
}
