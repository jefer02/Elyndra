package com.elyndra.launcher.core.device

import com.elyndra.launcher.core.device.PerformanceTier.Mode
import com.elyndra.launcher.core.device.PerformanceTier.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceTierTest {

    /** Un móvil de gama baja: 4 GB, 8 núcleos lentos, sin clase de rendimiento (p. ej. Tecno Spark 30C). */
    private val budget = Signals(lowRamDevice = false, totalRamMb = 3_800, cores = 8, mediaPerformanceClass = 0, powerSave = false)

    /** Una tableta potente: 12 GB, 8 núcleos (p. ej. Legion Y700 Gen 3). */
    private val flagship = Signals(lowRamDevice = false, totalRamMb = 11_700, cores = 8, mediaPerformanceClass = 0, powerSave = false)

    @Test
    fun `un movil de gama baja es modesto y uno potente no`() {
        assertTrue(PerformanceTier.isLowEnd(budget))
        assertFalse(PerformanceTier.isLowEnd(flagship))
    }

    @Test
    fun `isLowRamDevice manda aunque haya RAM de sobra`() {
        assertTrue(PerformanceTier.isLowEnd(flagship.copy(lowRamDevice = true)))
    }

    @Test
    fun `una clase de rendimiento de Android 12 o mas descarta el modo modesto`() {
        assertFalse(PerformanceTier.isLowEnd(budget.copy(mediaPerformanceClass = 31)))
        assertFalse(PerformanceTier.isLowEnd(budget.copy(mediaPerformanceClass = 33)))
        // Una clase anterior no cuenta.
        assertTrue(PerformanceTier.isLowEnd(budget.copy(mediaPerformanceClass = 30)))
    }

    @Test
    fun `el umbral de RAM es el de la calidad ligera de Masha`() {
        assertTrue(PerformanceTier.isLowEnd(flagship.copy(totalRamMb = PerformanceTier.LOW_RAM_MB - 1)))
        assertFalse(PerformanceTier.isLowEnd(flagship.copy(totalRamMb = PerformanceTier.LOW_RAM_MB)))
    }

    @Test
    fun `pocos nucleos es CPU de entrada`() {
        assertTrue(PerformanceTier.isLowEnd(flagship.copy(cores = 4)))
        assertFalse(PerformanceTier.isLowEnd(flagship.copy(cores = 6)))
    }

    @Test
    fun `lo que no se pudo medir no cuenta`() {
        assertFalse(PerformanceTier.isLowEnd(Signals(false, totalRamMb = 0, cores = 0, mediaPerformanceClass = 0, powerSave = false)))
    }

    @Test
    fun `Auto sigue al equipo y al ahorro de bateria`() {
        assertEquals(PerformanceProfile.PERFORMANCE, PerformanceTier.resolve(Mode.Auto, budget))
        assertEquals(PerformanceProfile.QUALITY, PerformanceTier.resolve(Mode.Auto, flagship))
        assertEquals(PerformanceProfile.PERFORMANCE, PerformanceTier.resolve(Mode.Auto, flagship.copy(powerSave = true)))
    }

    @Test
    fun `lo elegido a mano manda sobre el equipo`() {
        assertEquals(PerformanceProfile.QUALITY, PerformanceTier.resolve(Mode.Quality, budget))
        assertEquals(PerformanceProfile.QUALITY, PerformanceTier.resolve(Mode.Quality, budget.copy(powerSave = true)))
        assertEquals(PerformanceProfile.PERFORMANCE, PerformanceTier.resolve(Mode.Performance, flagship))
    }

    @Test
    fun `el modo guardado se lee y lo desconocido es Auto`() {
        assertEquals(Mode.Auto, Mode.byId(null))
        assertEquals(Mode.Auto, Mode.byId("turbo"))
        assertEquals(Mode.Quality, Mode.byId("quality"))
        assertEquals(Mode.Performance, Mode.byId("performance"))
        // Los ids no cambian: son lo que se guarda.
        assertEquals(listOf("auto", "quality", "performance"), Mode.entries.map { it.id })
    }

    @Test
    fun `Rendimiento solo cambia el coste`() {
        val q = PerformanceProfile.QUALITY
        val p = PerformanceProfile.PERFORMANCE
        assertNotEquals(q, p)
        assertTrue(q.liveBlur && q.ambientMotion && !q.capFrameRate && !q.lowEnd)
        assertTrue(!p.liveBlur && !p.ambientMotion && p.capFrameRate && p.lowEnd)
        // El fondo vivo se decodifica diminuto (desenfoque por bilineal) y el hero a menos tamaño, nunca a miniatura.
        assertTrue(p.backdropDecodePx < q.backdropDecodePx)
        assertTrue(p.backdropDecodePx <= 64)
        assertEquals(1f, q.heroDecodeScale)
        assertTrue(p.heroDecodeScale in 0.5f..1f)
    }
}
