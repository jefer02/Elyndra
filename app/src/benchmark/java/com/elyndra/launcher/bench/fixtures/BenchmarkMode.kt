package com.elyndra.launcher.bench.fixtures

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.elyndra.launcher.ElyndraApplication
import com.elyndra.launcher.core.device.DevicePerformance
import com.elyndra.launcher.core.device.PerformanceTier

/**
 * El modo de rendimiento de cada pasada lo elige la prueba, no el equipo: en
 * un móvil modesto Auto acabaría casi siempre en Rendimiento, y un perfil
 * generado solo así no cubriría los efectos de Calidad (ni al revés).
 *
 *   am start … --es elyndra.bench.perfMode performance   (o quality, auto)
 *
 * Se guarda como si se eligiera en Ajustes → Pantalla → Rendimiento (perf.mode)
 * y se aplica antes de la primera composición. Un modo fijo también apaga el
 * paso automático a Rendimiento por tirones (JankMonitor solo mide en Auto),
 * así que el modo no cambia a mitad de pasada.
 */
object BenchmarkMode {

    /** Lo mismo en baselineprofile/…/Ui.kt. */
    const val EXTRA_PERF_MODE = "elyndra.bench.perfMode"

    fun apply(activity: Activity, intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_PERF_MODE) ?: return
        val mode = PerformanceTier.Mode.entries.firstOrNull { it.id == id }
            ?: error("Unknown $EXTRA_PERF_MODE '$id' (expected one of ${PerformanceTier.Mode.entries.map { it.id }})")
        val settings = (activity.application as ElyndraApplication).settings
        settings.performanceMode = mode.id
        settings.performanceStutter = false
        DevicePerformance.updateMode(activity, mode, stutter = false)
        Log.i("ElyndraBench", "Performance mode ${mode.id} → profile ${DevicePerformance.profile}")
    }
}
