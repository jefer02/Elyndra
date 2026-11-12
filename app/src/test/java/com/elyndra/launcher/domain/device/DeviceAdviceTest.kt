package com.elyndra.launcher.domain.device

import com.elyndra.launcher.ui.MashaController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La batería y el calor solo cuando son un problema, y una vez por sesión; la fila de cifras vacía no sale. */
class DeviceAdviceTest {

    @Test
    fun onlyARealProblemIsAWarning() {
        assertNull(DeviceAdvice.problem(DeviceState(batteryPct = 47)))
        assertNull(DeviceAdvice.problem(DeviceState(batteryPct = 12, charging = true)))
        assertEquals(DeviceWarning.LowBattery, DeviceAdvice.problem(DeviceState(batteryPct = 19)))
        assertEquals(DeviceWarning.Hot, DeviceAdvice.problem(DeviceState(batteryPct = 90, charging = true, thermal = DeviceState.Thermal.Severe)))
        assertEquals(DeviceWarning.Hot, DeviceAdvice.problem(DeviceState(batteryTempC = 46f)))
        assertNull(DeviceAdvice.problem(null))
    }

    @Test
    fun atMostOncePerSession() {
        val low = DeviceState(batteryPct = 10)
        assertEquals(DeviceWarning.LowBattery, DeviceAdvice.forContext(low, warnedThisSession = false))
        assertNull(DeviceAdvice.forContext(low, warnedThisSession = true))
        assertTrue("launch_game" in DeviceAdvice.HEAVY_TOOLS && "install_port" in DeviceAdvice.HEAVY_TOOLS)
        assertFalse("find_ports" in DeviceAdvice.HEAVY_TOOLS)
    }

    @Test
    fun theStatsStripIsHiddenWhileAllThreeAreEmpty() {
        assertTrue(MashaController.Stats(0, 0, null, 0, 0, 0).isEmpty)
        assertFalse(MashaController.Stats(0, 0, "Okami", 120, 0, 0).isEmpty)
        assertFalse(MashaController.Stats(45, 45, null, 0, 2, 22).isEmpty)
    }
}
