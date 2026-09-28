package com.elyndra.launcher.ui.masha.lipsync

import android.media.AudioDeviceInfo

/** Tipo de salida de audio, a efectos de calibrar la sincronía de la voz. */
enum class RouteKind { Speaker, Wired, Bluetooth, Other }

/**
 * La salida por la que suena la voz: tipo y, en Bluetooth, el dispositivo
 * (dirección y/o nombre, si el sistema los da).
 */
data class AudioRoute(val kind: RouteKind, val name: String = "", val address: String = "") {
    companion object {
        val SPEAKER = AudioRoute(RouteKind.Speaker)
    }
}

/**
 * Ajuste manual `audioOffsetMs` por salida de audio (Kotlin puro).
 *
 * Claves guardadas: `speaker`, `wired`, `other`, `bt:<dirección>` (o
 * `bt:name:<nombre>` si no hay dirección) y `bt` (genérico: el último
 * Bluetooth calibrado, para auriculares que aún no se han calibrado).
 *
 * Selección: la clave exacta de la salida; en Bluetooth, si ese dispositivo no
 * tiene valor, el genérico `bt`; si nada, 0.
 */
object AudioRouteOffsets {
    const val MIN_MS = -150
    const val MAX_MS = 400
    const val STEP_MS = 10

    const val SPEAKER = "speaker"
    const val WIRED = "wired"
    const val OTHER = "other"
    const val BLUETOOTH = "bt"

    /** Clave de la salida. */
    fun key(route: AudioRoute): String = when (route.kind) {
        RouteKind.Speaker -> SPEAKER
        RouteKind.Wired -> WIRED
        RouteKind.Other -> OTHER
        RouteKind.Bluetooth -> {
            val address = route.address.trim()
            val name = route.name.trim()
            when {
                address.isNotEmpty() && address != ANONYMOUS_ADDRESS -> "$BLUETOOTH:${address.uppercase()}"
                name.isNotEmpty() -> "$BLUETOOTH:name:$name"
                else -> BLUETOOTH
            }
        }
    }

    /** El ajuste (ms) que toca para [route] con lo guardado en [stored]. */
    fun select(stored: Map<String, Int>, route: AudioRoute): Int {
        stored[key(route)]?.let { return clamp(it) }
        if (route.kind == RouteKind.Bluetooth) stored[BLUETOOTH]?.let { return clamp(it) }
        return 0
    }

    /**
     * Claves que se escriben al calibrar [route]: la suya y, en un Bluetooth
     * concreto, también el genérico (mejor punto de partida para otros auriculares).
     */
    fun keysToSave(route: AudioRoute): List<String> {
        val k = key(route)
        return if (route.kind == RouteKind.Bluetooth && k != BLUETOOTH) listOf(k, BLUETOOTH) else listOf(k)
    }

    /** Acota al rango del control y redondea al paso de 10 ms. */
    fun clamp(ms: Int): Int {
        val c = ms.coerceIn(MIN_MS, MAX_MS)
        return Math.floorDiv(c + STEP_MS / 2, STEP_MS) * STEP_MS
    }

    /** Tipo de salida según `AudioDeviceInfo.getType()`. */
    fun kindOf(deviceType: Int): RouteKind = when (deviceType) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        TYPE_BUILTIN_SPEAKER_SAFE,
        -> RouteKind.Speaker
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> RouteKind.Wired
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        TYPE_BLE_HEADSET,
        TYPE_BLE_SPEAKER,
        TYPE_BLE_BROADCAST,
        -> RouteKind.Bluetooth
        else -> RouteKind.Other
    }

    /** Preferencia entre varias salidas conectadas (API < 33, sin la ruta real): Bluetooth > cable > altavoz. */
    fun rank(kind: RouteKind): Int = when (kind) {
        RouteKind.Bluetooth -> 3
        RouteKind.Wired -> 2
        RouteKind.Speaker -> 1
        RouteKind.Other -> 0
    }

    // Constantes de API 30+/31+/33+ copiadas (valores fijos del SDK), para compilar con minSdk 26 sin avisos.
    private const val TYPE_BUILTIN_SPEAKER_SAFE = 24
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_SPEAKER = 27
    private const val TYPE_BLE_BROADCAST = 30

    /** Dirección que da Android cuando la oculta a la app. */
    private const val ANONYMOUS_ADDRESS = "02:00:00:00:00:00"
}
