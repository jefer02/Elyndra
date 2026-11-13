package com.elyndra.launcher.masha

import com.elyndra.launcher.domain.device.DeviceState
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** get_device_state: las cifras de verdad, solo cuando se piden. */
class DeviceToolTest {

    @Test
    fun itReportsBatteryChargingAndTemperature() {
        val r = DeviceTool.result(DeviceState(batteryPct = 37, charging = true, batteryTempC = 31.46f, thermal = DeviceState.Thermal.None))
        assertTrue(r.ok)
        assertFalse(r.mutating)
        val p = r.payload
        assertEquals(37, p["battery_pct"]!!.jsonPrimitive.int)
        assertTrue(p["charging"]!!.jsonPrimitive.boolean)
        assertEquals("normal", p["thermal"]!!.jsonPrimitive.content)
        assertEquals("31.5", p["battery_temp_c"]!!.jsonPrimitive.content)
        assertFalse(p["low_battery"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun aLowUnpluggedBatteryAndAHotPhoneAreFlagged() {
        val p = DeviceTool.result(DeviceState(batteryPct = 12, thermal = DeviceState.Thermal.Severe)).payload
        assertTrue(p["low_battery"]!!.jsonPrimitive.boolean)
        assertTrue(p["hot"]!!.jsonPrimitive.boolean)
        assertEquals("hot", p["thermal"]!!.jsonPrimitive.content)
    }

    @Test
    fun thermalStatesInWords() {
        assertEquals("warm", DeviceTool.thermal(DeviceState(thermal = DeviceState.Thermal.Moderate)))
        assertEquals("very_hot", DeviceTool.thermal(DeviceState(thermal = DeviceState.Thermal.Critical)))
        assertEquals("unknown", DeviceTool.thermal(DeviceState()))
        // Sin estado térmico pero con la batería a 46 °C: caliente.
        assertEquals("hot", DeviceTool.thermal(DeviceState(batteryTempC = 46f)))
    }

    @Test
    fun withoutAStateItSaysSoInsteadOfInventingNumbers() {
        val r = DeviceTool.result(null)
        assertFalse(r.ok)
        assertFalse("battery_pct" in r.payload)
    }
}
