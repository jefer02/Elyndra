package com.elyndra.launcher.ui.masha

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.elyndra.launcher.ui.masha.lipsync.AudioRoute
import com.elyndra.launcher.ui.masha.lipsync.AudioRouteOffsets
import com.elyndra.launcher.ui.masha.lipsync.RouteKind

/**
 * Por dónde sale la voz de Masha (altavoz, cable, Bluetooth y cuál), y aviso
 * cuando cambia: auriculares que se conectan o desconectan a mitad de la
 * conversación ([AudioDeviceCallback]). [refresh] vuelve a mirar (p. ej. al
 * empezar cada frase), por si la salida cambió sin conectar ni desconectar nada.
 *
 * API 33+: la salida real para `USAGE_ASSISTANT` (`getAudioDevicesForAttributes`).
 * Antes: la mejor conectada (Bluetooth > cable > altavoz), que es lo que hace
 * Android con el audio multimedia fuera de una llamada.
 *
 * Todo en el hilo principal; [onChange] también.
 */
internal class AudioRouteWatcher(context: Context, private val onChange: (AudioRoute) -> Unit) {

    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    var current: AudioRoute = detect(); private set

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    init {
        runCatching { am.registerAudioDeviceCallback(callback, main) }
    }

    fun refresh() {
        val r = detect()
        if (r != current) {
            current = r
            onChange(r)
        }
    }

    fun release() {
        runCatching { am.unregisterAudioDeviceCallback(callback) }
    }

    private fun detect(): AudioRoute = runCatching {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            am.getAudioDevicesForAttributes(ATTRS).firstOrNull()
        } else {
            null
        } ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
            .maxByOrNull { AudioRouteOffsets.rank(AudioRouteOffsets.kindOf(it.type)) }
        device?.let(::toRoute)
    }.getOrNull() ?: AudioRoute.SPEAKER

    private fun toRoute(d: AudioDeviceInfo): AudioRoute {
        val kind = AudioRouteOffsets.kindOf(d.type)
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { d.address }.getOrNull().orEmpty() else ""
        val name = if (kind == RouteKind.Bluetooth || kind == RouteKind.Wired) d.productName?.toString().orEmpty() else ""
        return AudioRoute(kind, name = name, address = if (kind == RouteKind.Bluetooth) address else "")
    }

    private companion object {
        val ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
