package com.elyndra.launcher.masha

import com.elyndra.launcher.domain.device.DeviceState
import kotlinx.serialization.json.put

/**
 * La herramienta de solo lectura get_device_state (pura): batería, carga y
 * temperatura de ahora mismo. Lucy la llama solo cuando el usuario pregunta
 * por el dispositivo; el contexto de siempre no lleva estas cifras (ver
 * DeviceAdvice), así no habla de la batería porque sí.
 */
object DeviceTool {

    fun result(state: DeviceState?): ToolResult {
        if (state == null) return toolFail("the device state isn't available right now")
        return toolOk {
            state.batteryPct?.let { put("battery_pct", it) }
            put("charging", state.charging)
            put("thermal", thermal(state))
            state.batteryTempC?.let { put("battery_temp_c", Math.round(it * 10) / 10.0) }
            put("power_save", state.powerSave)
            put("low_battery", state.isLowBattery)
            put("hot", state.isHot)
        }
    }

    /** El estado térmico en palabras que el modelo entiende ("normal", "warm", "hot", "very_hot"). */
    fun thermal(state: DeviceState): String = when {
        state.thermal >= DeviceState.Thermal.Critical -> "very_hot"
        state.isHot -> "hot"
        state.thermal == DeviceState.Thermal.Light || state.thermal == DeviceState.Thermal.Moderate -> "warm"
        state.thermal == DeviceState.Thermal.None -> "normal"
        else -> "unknown"
    }
}
