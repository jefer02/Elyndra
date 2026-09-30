package com.elyndra.launcher.core.device

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Qué enseña la píldora de estado. */
enum class StatusMode(val id: String) {
    Both("both"),
    Time("time"),
    Battery("battery"),
    ;

    val showsTime: Boolean get() = this != Battery
    val showsBattery: Boolean get() = this != Time

    companion object {
        val DEFAULT = Both

        fun byId(id: String?): StatusMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** La batería, ya leída: porcentaje, si carga y si está baja. */
data class BatteryInfo(val percent: Int, val charging: Boolean) {
    /** Baja: el 15 % o menos y sin cargar. */
    val low: Boolean get() = !charging && percent <= LOW_PERCENT

    companion object {
        const val LOW_PERCENT = 15

        // Los valores de BatteryManager (no se importa para poder probarlo en la JVM).
        const val STATUS_CHARGING = 2
        const val STATUS_FULL = 5

        /**
         * Del intent `ACTION_BATTERY_CHANGED`: [level] sobre [scale], [status]
         * (cargando / llena…) y [plugged] (0 = desenchufado). Null si el
         * sistema no da un nivel válido.
         */
        fun from(level: Int, scale: Int, status: Int, plugged: Int): BatteryInfo? {
            if (level < 0 || scale <= 0) return null
            val percent = ((level * 100f) / scale + 0.5f).toInt().coerceIn(0, 100)
            val charging = status == STATUS_CHARGING || (status == STATUS_FULL && plugged != 0)
            return BatteryInfo(percent, charging)
        }
    }
}

/**
 * La hora como la escribe el sistema: 24 h o 12 h según su ajuste y con la
 * marca de mañana/tarde del idioma. Kotlin puro (java.time).
 */
object StatusFormat {

    /** En japonés, chino y coreano la marca va delante ("午後 3:05"). */
    private val AMPM_FIRST = setOf("ja", "zh", "ko")

    fun pattern(use24: Boolean, locale: Locale): String = when {
        use24 -> "HH:mm"
        locale.language in AMPM_FIRST -> "a h:mm"
        else -> "h:mm a"
    }

    fun time(hour: Int, minute: Int, use24: Boolean, locale: Locale): String =
        LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern(use24, locale), locale))

    /**
     * 12 o 24 h según el ajuste del sistema. [setting] es `Settings.System.TIME_12_24`
     * ("12", "24" o null = lo que diga el idioma **del sistema**); [systemPattern],
     * el patrón corto de hora de ese idioma (p. ej. "h:mm a" o "HH:mm").
     */
    fun uses24h(setting: String?, systemPattern: String): Boolean = when (setting) {
        "24" -> true
        "12" -> false
        else -> systemPattern.contains('H') || systemPattern.contains('k')
    }

    /** Milisegundos hasta el próximo cambio de minuto (el reloj no se repinta antes). */
    fun msToNextMinute(nowMs: Long): Long = 60_000L - (nowMs % 60_000L)
}
