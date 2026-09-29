package com.elyndra.launcher.core.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.elyndra.launcher.domain.device.DeviceState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lee cómo está el dispositivo, sin pedir ningún permiso:
 *
 *  · batería y temperatura: el broadcast pegajoso ACTION_BATTERY_CHANGED
 *    (se lee pasando un receptor nulo; no se queda registrado nada);
 *  · estado térmico: PowerManager.currentThermalStatus (Android 10+);
 *  · ahorro de energía: PowerManager.isPowerSaveMode;
 *  · red: ConnectivityManager (ACCESS_NETWORK_STATE, que ya se declaraba).
 *
 * Se consulta cuando hace falta (al lanzar, al hablar con Masha, al volver a
 * la app); no hay nada escuchando en segundo plano.
 */
@Singleton
class DeviceStateMonitor @Inject constructor(@ApplicationContext private val context: Context) {

    fun snapshot(): DeviceState {
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val power = context.getSystemService(PowerManager::class.java)
        return DeviceState(
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            batteryTempC = if (temp == Int.MIN_VALUE || temp <= 0) null else temp / 10f,
            thermal = thermal(power),
            powerSave = runCatching { power?.isPowerSaveMode }.getOrNull() ?: false,
            network = network(),
        )
    }

    private fun thermal(power: PowerManager?): DeviceState.Thermal {
        if (power == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return DeviceState.Thermal.Unknown
        return when (runCatching { power.currentThermalStatus }.getOrNull()) {
            PowerManager.THERMAL_STATUS_NONE -> DeviceState.Thermal.None
            PowerManager.THERMAL_STATUS_LIGHT -> DeviceState.Thermal.Light
            PowerManager.THERMAL_STATUS_MODERATE -> DeviceState.Thermal.Moderate
            PowerManager.THERMAL_STATUS_SEVERE -> DeviceState.Thermal.Severe
            PowerManager.THERMAL_STATUS_CRITICAL -> DeviceState.Thermal.Critical
            PowerManager.THERMAL_STATUS_EMERGENCY -> DeviceState.Thermal.Emergency
            PowerManager.THERMAL_STATUS_SHUTDOWN -> DeviceState.Thermal.Shutdown
            else -> DeviceState.Thermal.Unknown
        }
    }

    private fun network(): DeviceState.Network {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return DeviceState.Network.Unknown
        return runCatching {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return DeviceState.Network.Offline
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return DeviceState.Network.Offline
            if (cm.isActiveNetworkMetered) DeviceState.Network.Metered else DeviceState.Network.Unmetered
        }.getOrDefault(DeviceState.Network.Unknown)
    }
}
