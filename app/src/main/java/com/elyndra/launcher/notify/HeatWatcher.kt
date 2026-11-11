package com.elyndra.launcher.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.elyndra.launcher.core.device.DeviceStateMonitor
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.domain.device.DeviceState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vigila el calor solo mientras se juega: desde que Elyndra pasa a segundo
 * plano con una sesión abierta (SessionTracker) hasta que vuelve. Sin servicio
 * en primer plano: si Android mata el proceso a mitad de partida, se deja de
 * vigilar y no pasa nada.
 *
 *  · Android 10+: PowerManager.addThermalStatusListener (avisa al registrarse
 *    con el estado actual y luego en cada cambio).
 *  · Android 8–9: la temperatura de la batería en ACTION_BATTERY_CHANGED.
 *
 * Los umbrales son los de [DeviceState.isHot] (térmico ≥ grave o batería ≥
 * [DeviceState.HOT_BATTERY_C]); cuándo se avisa lo decide [LucyNotices].
 */
@Singleton
class HeatWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val notices: LucyNotices,
) {
    private var thermalListener: Any? = null
    private var batteryReceiver: BroadcastReceiver? = null

    /** MainActivity.onStop: empieza a vigilar si se acaba de lanzar un juego. */
    @Synchronized
    fun onBackground() {
        if (settings.pendingSessionKey == null || !settings.mashaNudges) return
        if (thermalListener != null || batteryReceiver != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) watchThermal() else watchBattery()
    }

    /** MainActivity.onStart: de vuelta en Elyndra, deja de vigilar (lo cuenta el bocadillo). */
    @Synchronized
    fun onForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let { l ->
                runCatching { context.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(l) }
            }
        }
        thermalListener = null
        batteryReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        batteryReceiver = null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun watchThermal() {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            if (DeviceState(thermal = DeviceStateMonitor.thermalOf(status)).isHot) hot()
        }
        runCatching { power.addThermalStatusListener(context.mainExecutor, listener) }
            .onSuccess { thermalListener = listener }
    }

    private fun watchBattery() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (raw != Int.MIN_VALUE && raw > 0 && DeviceState(batteryTempC = raw / 10f).isHot) hot()
            }
        }
        runCatching { context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
            .onSuccess { batteryReceiver = receiver }
    }

    /** Ya fuera de sesión (volvió, o se cerró) no hay nada que vigilar. */
    private fun hot() {
        if (settings.pendingSessionKey == null) {
            onForeground()
            return
        }
        notices.deviceHot()
    }
}
