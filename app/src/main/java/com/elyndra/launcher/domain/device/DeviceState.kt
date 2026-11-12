package com.elyndra.launcher.domain.device

import com.elyndra.launcher.domain.SystemWeight

/**
 * Cómo está el dispositivo ahora: batería, temperatura, ahorro de energía y
 * red. Lo lee `DeviceStateMonitor` sin pedir ningún permiso (la batería llega
 * por un broadcast pegajoso; lo térmico, por PowerManager).
 */
data class DeviceState(
    /** 0…100, o null si el sistema no lo dice. */
    val batteryPct: Int? = null,
    val charging: Boolean = false,
    /** Temperatura de la batería en °C (lo que Android llama EXTRA_TEMPERATURE / 10). */
    val batteryTempC: Float? = null,
    val thermal: Thermal = Thermal.Unknown,
    val powerSave: Boolean = false,
    val network: Network = Network.Unknown,
) {
    enum class Thermal { Unknown, None, Light, Moderate, Severe, Critical, Emergency, Shutdown }
    enum class Network { Unknown, Offline, Metered, Unmetered }

    val isHot: Boolean
        get() = thermal >= Thermal.Severe || (batteryTempC ?: 0f) >= HOT_BATTERY_C

    val isLowBattery: Boolean
        get() = !charging && (batteryPct ?: 100) <= LOW_BATTERY_PCT

    val isOnline: Boolean get() = network == Network.Metered || network == Network.Unmetered || network == Network.Unknown

    /**
     * Qué conviene decir antes de lanzar algo de este peso. Un juego ligero con
     * poca batería no merece un aviso; una PS2 a 12 % y sin cargador, sí.
     */
    fun warningsFor(weight: SystemWeight): List<DeviceWarning> = buildList {
        if (isHot && weight != SystemWeight.Light) add(DeviceWarning.Hot)
        if (isLowBattery && weight == SystemWeight.Heavy) add(DeviceWarning.LowBattery)
        else if (!charging && (batteryPct ?: 100) <= CRITICAL_BATTERY_PCT) add(DeviceWarning.LowBattery)
        if (powerSave && weight == SystemWeight.Heavy) add(DeviceWarning.PowerSave)
    }

    companion object {
        const val LOW_BATTERY_PCT = 20
        const val CRITICAL_BATTERY_PCT = 8
        const val HOT_BATTERY_C = 44f
    }
}

enum class DeviceWarning { LowBattery, Hot, PowerSave }

/**
 * Cuándo Lucy puede hablar de la batería o del calor (pura): solo si de verdad
 * es un problema (menos del [DeviceState.LOW_BATTERY_PCT] % sin cargar, o
 * caliente) y como mucho una vez por sesión. El contexto del modelo no lleva
 * cifras de batería si no hay nada que avisar, así no lo menciona porque sí;
 * con aviso, solo lo dice antes de lanzar o instalar algo pesado.
 */
object DeviceAdvice {

    fun problem(device: DeviceState?): DeviceWarning? = when {
        device == null -> null
        device.isHot -> DeviceWarning.Hot
        device.isLowBattery -> DeviceWarning.LowBattery
        else -> null
    }

    /** El aviso que va en el contexto: el problema, si aún no se avisó en esta sesión. */
    fun forContext(device: DeviceState?, warnedThisSession: Boolean): DeviceWarning? =
        if (warnedThisSession) null else problem(device)

    /** Las herramientas que lanzan o instalan algo (cuando el aviso cuenta como dado). */
    val HEAVY_TOOLS = setOf("launch_game", "install_port")
}
