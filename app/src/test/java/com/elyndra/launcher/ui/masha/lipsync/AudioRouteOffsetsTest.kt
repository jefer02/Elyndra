package com.elyndra.launcher.ui.masha.lipsync

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRouteOffsetsTest {

    private val buds = AudioRoute(RouteKind.Bluetooth, name = "Buds", address = "aa:bb:cc:dd:ee:ff")
    private val other = AudioRoute(RouteKind.Bluetooth, name = "Speaker X", address = "11:22:33:44:55:66")

    @Test
    fun keys() {
        assertEquals("speaker", AudioRouteOffsets.key(AudioRoute.SPEAKER))
        assertEquals("wired", AudioRouteOffsets.key(AudioRoute(RouteKind.Wired, name = "USB-C")))
        assertEquals("bt:AA:BB:CC:DD:EE:FF", AudioRouteOffsets.key(buds))
        assertEquals("bt:name:Buds", AudioRouteOffsets.key(AudioRoute(RouteKind.Bluetooth, name = "Buds")))
        // Dirección anónima: se usa el nombre.
        assertEquals("bt:name:Buds", AudioRouteOffsets.key(AudioRoute(RouteKind.Bluetooth, "Buds", "02:00:00:00:00:00")))
        assertEquals("bt", AudioRouteOffsets.key(AudioRoute(RouteKind.Bluetooth)))
    }

    @Test
    fun nothingStoredIsZero() {
        assertEquals(0, AudioRouteOffsets.select(emptyMap(), AudioRoute.SPEAKER))
        assertEquals(0, AudioRouteOffsets.select(emptyMap(), buds))
    }

    @Test
    fun exactRouteWins() {
        val stored = mapOf("speaker" to 20, "wired" to -10, "bt" to 150, "bt:AA:BB:CC:DD:EE:FF" to 230)
        assertEquals(20, AudioRouteOffsets.select(stored, AudioRoute.SPEAKER))
        assertEquals(-10, AudioRouteOffsets.select(stored, AudioRoute(RouteKind.Wired)))
        assertEquals(230, AudioRouteOffsets.select(stored, buds))
    }

    @Test
    fun unknownBluetoothFallsBackToGeneric() {
        val stored = mapOf("bt" to 150, "bt:AA:BB:CC:DD:EE:FF" to 230)
        assertEquals(150, AudioRouteOffsets.select(stored, other))
        assertEquals(150, AudioRouteOffsets.select(stored, AudioRoute(RouteKind.Bluetooth)))
        // El genérico de Bluetooth no se aplica al altavoz ni al cable.
        assertEquals(0, AudioRouteOffsets.select(stored, AudioRoute.SPEAKER))
        assertEquals(0, AudioRouteOffsets.select(stored, AudioRoute(RouteKind.Wired)))
    }

    @Test
    fun savingBluetoothAlsoUpdatesGeneric() {
        assertEquals(listOf("bt:AA:BB:CC:DD:EE:FF", "bt"), AudioRouteOffsets.keysToSave(buds))
        assertEquals(listOf("bt"), AudioRouteOffsets.keysToSave(AudioRoute(RouteKind.Bluetooth)))
        assertEquals(listOf("speaker"), AudioRouteOffsets.keysToSave(AudioRoute.SPEAKER))
    }

    @Test
    fun clampAndSnap() {
        assertEquals(-150, AudioRouteOffsets.clamp(-400))
        assertEquals(400, AudioRouteOffsets.clamp(9999))
        assertEquals(120, AudioRouteOffsets.clamp(123))
        assertEquals(130, AudioRouteOffsets.clamp(125))
        assertEquals(-20, AudioRouteOffsets.clamp(-17))
        // Un valor guardado fuera de rango se acota al leerlo.
        assertEquals(400, AudioRouteOffsets.select(mapOf("speaker" to 1000), AudioRoute.SPEAKER))
    }

    @Test
    fun deviceKinds() {
        assertEquals(RouteKind.Speaker, AudioRouteOffsets.kindOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(RouteKind.Wired, AudioRouteOffsets.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(RouteKind.Wired, AudioRouteOffsets.kindOf(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(RouteKind.Bluetooth, AudioRouteOffsets.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(RouteKind.Bluetooth, AudioRouteOffsets.kindOf(26)) // TYPE_BLE_HEADSET
        assertEquals(RouteKind.Other, AudioRouteOffsets.kindOf(AudioDeviceInfo.TYPE_HDMI))
        assert(AudioRouteOffsets.rank(RouteKind.Bluetooth) > AudioRouteOffsets.rank(RouteKind.Wired))
        assert(AudioRouteOffsets.rank(RouteKind.Wired) > AudioRouteOffsets.rank(RouteKind.Speaker))
    }
}
