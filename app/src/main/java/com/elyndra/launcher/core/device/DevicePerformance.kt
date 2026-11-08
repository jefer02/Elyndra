package com.elyndra.launcher.core.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * El perfil de coste en vigor ([PerformanceTier] con las señales de este
 * equipo, lo elegido en Ajustes y los tirones medidos en Auto).
 *
 * Es un estado de Compose: cambiar el modo en Ajustes (o encender el ahorro
 * de batería en Auto, o que [JankMonitor] mida tirones) se nota al instante en
 * lo que lo lee, sin reiniciar. Lo que solo se decide una vez por proceso —la
 * caché en memoria de Coil— se queda como arrancó hasta la siguiente apertura.
 *
 * Las señales del equipo se miden una vez (RAM, núcleos, chip y frecuencias no
 * cambian mientras vive el proceso; son lecturas del sistema y de /sys, sin
 * disco de verdad); el ahorro de batería se vuelve a leer en [refresh].
 */
object DevicePerformance {

    @Volatile private var signals: PerformanceTier.Signals? = null

    var mode: PerformanceTier.Mode by mutableStateOf(PerformanceTier.Mode.Auto)
        private set

    /** Tirones medidos en Auto (guardado en Ajustes; se olvida al elegir Auto otra vez). */
    var stutter: Boolean by mutableStateOf(false)
        private set

    /** El perfil en vigor. Se lee desde cualquier hilo; en Compose, recompone al cambiar. */
    var profile: PerformanceProfile by mutableStateOf(PerformanceProfile.QUALITY)
        private set

    /** Por qué (lo que se enseña en Ajustes bajo Auto). */
    var reason: PerformanceTier.Reason by mutableStateOf(PerformanceTier.Reason.Capable)
        private set

    /** Al arrancar (Application.onCreate): mide el equipo y aplica lo guardado. */
    fun init(context: Context, mode: PerformanceTier.Mode, stutter: Boolean) {
        this.mode = mode
        this.stutter = stutter
        refresh(context)
    }

    /** El usuario ha cambiado Auto / Calidad / Rendimiento ([stutter]: lo que queda guardado tras elegir). */
    fun updateMode(context: Context, mode: PerformanceTier.Mode, stutter: Boolean) {
        this.mode = mode
        this.stutter = stutter
        refresh(context)
    }

    /** [JankMonitor] ha medido tirones sostenidos en Auto. */
    fun markStutter(context: Context) {
        stutter = true
        refresh(context)
    }

    /** Vuelve a decidir (el ahorro de batería puede haber cambiado). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val base = signals ?: measure(app).also { signals = it }
        val decision = PerformanceTier.decide(mode, base.copy(powerSave = powerSave(app)), stutter)
        if (decision.profile != profile) profile = decision.profile
        if (decision.reason != reason) reason = decision.reason
    }

    /** ¿Equipo modesto por sus señales (sin ahorro de batería ni tirones)? */
    fun isLowEndDevice(context: Context): Boolean =
        PerformanceTier.isLowEnd(signals ?: measure(context.applicationContext).also { signals = it })

    private fun measure(context: Context): PerformanceTier.Signals {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(info) }
        val s = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        return PerformanceTier.Signals(
            lowRamDevice = am?.isLowRamDevice == true,
            totalRamMb = info.totalMem / (1024L * 1024L),
            cores = Runtime.getRuntime().availableProcessors(),
            mediaPerformanceClass = if (s) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0,
            powerSave = false,
            socManufacturer = if (s) Build.SOC_MANUFACTURER else null,
            socModel = if (s) Build.SOC_MODEL else null,
            maxCpuMhz = maxCpuMhz(),
        )
    }

    /**
     * La frecuencia máxima del núcleo más rápido (MHz), de cpufreq. Algunos
     * fabricantes cierran /sys: entonces 0 y no cuenta.
     */
    private fun maxCpuMhz(): Int = runCatching {
        File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(CPU_DIR) }.orEmpty()
            .maxOfOrNull { cpu ->
                runCatching { File(cpu, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 }.getOrDefault(0L)
            }?.toInt() ?: 0
    }.getOrDefault(0)

    private val CPU_DIR = Regex("cpu\\d+")

    private fun powerSave(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true }.getOrDefault(false)
}
