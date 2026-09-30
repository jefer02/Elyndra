package com.elyndra.launcher.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class StatusFormatTest {

    @Test
    fun twentyFourHourClock() {
        assertEquals("00:05", StatusFormat.time(0, 5, use24 = true, Locale.US))
        assertEquals("13:07", StatusFormat.time(13, 7, use24 = true, Locale.forLanguageTag("es")))
        assertEquals("23:59", StatusFormat.time(23, 59, use24 = true, Locale.JAPANESE))
    }

    @Test
    fun twelveHourClockUsesTheLocaleMarker() {
        assertEquals("1:07 PM", StatusFormat.time(13, 7, use24 = false, Locale.US))
        assertEquals("12:00 AM", StatusFormat.time(0, 0, use24 = false, Locale.US))
        assertEquals("12:30 PM", StatusFormat.time(12, 30, use24 = false, Locale.US))
        // Sin cero delante de la hora.
        assertTrue(StatusFormat.time(9, 5, use24 = false, Locale.US).startsWith("9:05"))
        // En japonés la marca va delante.
        val ja = StatusFormat.time(15, 5, use24 = false, Locale.JAPANESE)
        assertTrue(ja, ja.endsWith("3:05") && !ja.startsWith("3"))
        // Español: marca propia del idioma, detrás.
        val es = StatusFormat.time(15, 5, use24 = false, Locale.forLanguageTag("es"))
        assertTrue(es, es.startsWith("3:05 "))
    }

    @Test
    fun systemSettingDecides12Or24() {
        // Fijado en el sistema: manda eso, diga lo que diga el idioma.
        assertTrue(StatusFormat.uses24h("24", "h:mm a"))
        assertFalse(StatusFormat.uses24h("12", "HH:mm"))
        // "Usar el del idioma": el patrón del idioma del sistema.
        assertFalse(StatusFormat.uses24h(null, "h:mm a"))   // es-US, en-US
        assertTrue(StatusFormat.uses24h(null, "H:mm"))      // es-ES
        assertTrue(StatusFormat.uses24h(null, "HH:mm"))
        assertFalse(StatusFormat.uses24h(null, "a h:mm"))   // ja 12 h
        assertTrue(StatusFormat.uses24h(null, "k:mm"))
    }

    @Test
    fun nextMinuteBoundary() {
        assertEquals(60_000L, StatusFormat.msToNextMinute(120_000L))
        assertEquals(1L, StatusFormat.msToNextMinute(119_999L))
        assertEquals(30_000L, StatusFormat.msToNextMinute(90_000L))
    }

    @Test
    fun batteryMapping() {
        assertEquals(BatteryInfo(76, false), BatteryInfo.from(76, 100, 3, 0))
        // Escala distinta de 100 y redondeo.
        assertEquals(50, BatteryInfo.from(128, 255, 3, 0)!!.percent)
        assertEquals(100, BatteryInfo.from(255, 255, 3, 0)!!.percent)
        // Cargando, y llena enchufada cuenta como cargando.
        assertTrue(BatteryInfo.from(40, 100, BatteryInfo.STATUS_CHARGING, 1)!!.charging)
        assertTrue(BatteryInfo.from(100, 100, BatteryInfo.STATUS_FULL, 2)!!.charging)
        assertFalse(BatteryInfo.from(100, 100, BatteryInfo.STATUS_FULL, 0)!!.charging)
        // Sin datos válidos.
        assertNull(BatteryInfo.from(-1, 100, 3, 0))
        assertNull(BatteryInfo.from(50, 0, 3, 0))
    }

    @Test
    fun lowBatteryIsFifteenOrLessAndNotCharging() {
        assertTrue(BatteryInfo(15, false).low)
        assertTrue(BatteryInfo(3, false).low)
        assertFalse(BatteryInfo(16, false).low)
        assertFalse(BatteryInfo(10, true).low)
    }

    @Test
    fun modes() {
        assertEquals(StatusMode.Both, StatusMode.byId(null))
        assertEquals(StatusMode.Both, StatusMode.byId("raro"))
        assertTrue(StatusMode.Time.showsTime && !StatusMode.Time.showsBattery)
        assertTrue(!StatusMode.Battery.showsTime && StatusMode.Battery.showsBattery)
        assertTrue(StatusMode.Both.showsTime && StatusMode.Both.showsBattery)
    }
}
