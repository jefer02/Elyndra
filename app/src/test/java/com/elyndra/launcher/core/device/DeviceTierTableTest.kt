package com.elyndra.launcher.core.device

import com.elyndra.launcher.core.device.PerformanceTier.Mode
import com.elyndra.launcher.core.device.PerformanceTier.Reason
import com.elyndra.launcher.core.device.PerformanceTier.Signals
import com.elyndra.launcher.data.MemoryPrefs
import com.elyndra.launcher.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La regla de equipos con perfiles de verdad (lo que devuelven Build, ActivityManager y cpufreq). */
class DeviceTierTableTest {

    private fun device(maker: String?, soc: String?, ramMb: Long, cores: Int = 8, mhz: Int = 0, lowRam: Boolean = false, perfClass: Int = 0) =
        Signals(lowRam, ramMb, cores, perfClass, powerSave = false, socManufacturer = maker, socModel = soc, maxCpuMhz = mhz)

    // Tecno Spark 30C: Helio G81 (MT6769), 8 núcleos a 2,0 GHz; se vende con 4, 6 y 8 GB (totalMem algo por debajo).
    private val spark4 = device("Mediatek", "MT6769Z", 3_700, mhz = 2_000)
    private val spark6 = device("Mediatek", "MT6769Z", 5_700, mhz = 2_000)
    private val spark8 = device("Mediatek", "MT6769Z", 7_600, mhz = 2_000)
    private val unisoc = device("Unisoc", "T606", 3_800, mhz = 1_600)
    private val snapdragon7 = device("QTI", "SM7325", 7_600, mhz = 2_400)
    private val legion = device("QTI", "SM8650", 11_700, mhz = 3_300)
    private val tvBox = device("Amlogic", "S905X4", 1_900, cores = 4, mhz = 2_000)
    private val emulator = device(null, null, 7_900, cores = 8)
    private val smallEmulator = device(null, null, 2_000, cores = 2)

    private fun auto(s: Signals) = PerformanceTier.decide(Mode.Auto, s)

    @Test
    fun `el Tecno Spark 30C va en Rendimiento con 4, 6 y 8 GB`() {
        for (s in listOf(spark4, spark6, spark8)) {
            val d = auto(s)
            assertTrue("${s.totalRamMb} MB", d.lowEnd)
            assertEquals(Reason.BudgetChipset, d.reason)
        }
    }

    @Test
    fun `sin chip conocido (Android 11) el Spark de 8 GB lo delata su CPU`() {
        val d = auto(spark8.copy(socManufacturer = null, socModel = null))
        assertTrue(d.lowEnd)
        assertEquals(Reason.BudgetChipset, d.reason)
    }

    @Test
    fun `un Unisoc y una caja de TV van en Rendimiento`() {
        assertEquals(Reason.BudgetChipset, auto(unisoc).reason)
        assertEquals(Reason.BudgetChipset, auto(tvBox).reason)
    }

    @Test
    fun `un Snapdragon 7 de gama media y la Legion Y700 van en Calidad`() {
        assertEquals(Reason.Capable, auto(snapdragon7).reason)
        assertEquals(Reason.Capable, auto(legion).reason)
        assertFalse(auto(legion).lowEnd)
    }

    @Test
    fun `la gama alta nunca baja aunque fallen las otras senales`() {
        // RAM y cpufreq sin leer, o una lectura rara: el chip la protege.
        assertEquals(Reason.Capable, auto(legion.copy(totalRamMb = 0, maxCpuMhz = 0, cores = 0)).reason)
        assertEquals(Reason.Capable, auto(legion.copy(totalRamMb = 3_000, maxCpuMhz = 1_000)).reason)
        // Solo isLowRamDevice (lo dice el sistema) manda por encima.
        assertEquals(Reason.LowMemory, auto(legion.copy(lowRamDevice = true)).reason)
    }

    @Test
    fun `un emulador sin datos de chip decide por RAM y nucleos`() {
        assertEquals(Reason.Capable, auto(emulator).reason)
        assertEquals(Reason.LowMemory, auto(smallEmulator).reason)
        // "unknown" (lo que devuelve el emulador) no es un chip.
        assertEquals(Reason.Capable, auto(emulator.copy(socManufacturer = "unknown", socModel = "unknown")).reason)
    }

    @Test
    fun `un gama alta antiguo justo de RAM pero con CPU rapida se queda en Calidad`() {
        // Snapdragon 855 (2,84 GHz) con ~4,6 GB útiles, sin SOC_MODEL (Android 11).
        assertEquals(Reason.Capable, auto(device(null, null, 4_700, mhz = 2_840)).reason)
        // El mismo sin frecuencia legible: el umbral de siempre.
        assertEquals(Reason.LowMemory, auto(device(null, null, 4_700)).reason)
    }

    @Test
    fun `clase de rendimiento de Android 12 manda sobre el chip y la RAM`() {
        assertEquals(Reason.Capable, auto(spark4.copy(mediaPerformanceClass = 31)).reason)
    }

    @Test
    fun `en Auto el ahorro de bateria y los tirones tambien pasan a Rendimiento, con su motivo`() {
        assertEquals(Reason.BatterySaver, auto(legion.copy(powerSave = true)).reason)
        assertEquals(Reason.Stutter, PerformanceTier.decide(Mode.Auto, legion, stutter = true).reason)
        // Lo del equipo va primero: en el Spark el motivo sigue siendo el chip.
        assertEquals(Reason.BudgetChipset, PerformanceTier.decide(Mode.Auto, spark8, stutter = true).reason)
    }

    @Test
    fun `lo elegido a mano manda y no mira tirones`() {
        val q = PerformanceTier.decide(Mode.Quality, spark4, stutter = true)
        assertFalse(q.lowEnd)
        assertEquals(Reason.Chosen, q.reason)
        val p = PerformanceTier.decide(Mode.Performance, legion)
        assertTrue(p.lowEnd)
        assertEquals(Reason.Chosen, p.reason)
    }

    /* ── Catálogo de chips ───────────────────────────────────── */

    @Test
    fun `familias de chips`() {
        val budget = listOf(
            "Mediatek" to "MT6769Z", "Mediatek" to "MT6765", "MediaTek" to "MT6789", "Mediatek" to "MT8183",
            "Unisoc" to "T606", "Spreadtrum" to "SC9863A", "" to "ums9230", "" to "T616",
            "QTI" to "SM6225", "QTI" to "SM4350", "QTI" to "SDM450", "QTI" to "SM6115", "Qualcomm" to "MSM8953",
            "Samsung" to "s5e3830", "Amlogic" to "S905X4", "Rockchip" to "RK3566",
        )
        for ((maker, model) in budget) assertEquals("$maker $model", SocCatalog.Tier.Budget, SocCatalog.classify(maker, model))
        val high = listOf("QTI" to "SM8650", "QTI" to "SM8550", "Mediatek" to "MT6989", "Mediatek" to "MT6895", "Google" to "Tensor G3", "Google" to "GS201", "Samsung" to "s5e9945")
        for ((maker, model) in high) assertEquals("$maker $model", SocCatalog.Tier.HighEnd, SocCatalog.classify(maker, model))
        val unknown = listOf("QTI" to "SM7325", "Mediatek" to "MT6877", null to null, "unknown" to "unknown", "" to "")
        for ((maker, model) in unknown) assertEquals("$maker $model", SocCatalog.Tier.Unknown, SocCatalog.classify(maker, model))
    }

    /* ── Tirones: la máquina de estados ──────────────────────── */

    private val late = JankFallback.STARTUP_GRACE_MS + 1
    private fun bad(eligible: Boolean = true, since: Long = late, frames: Int = 120) = JankFallback.Window(frames, 40f, eligible, since)
    private fun good() = JankFallback.Window(120, 12f, true, late)

    private fun run(vararg windows: JankFallback.Window): JankFallback.State =
        windows.fold(JankFallback.State()) { s, w -> JankFallback.step(s, w) }

    @Test
    fun `tirones sostenidos saltan y uno suelto no`() {
        assertTrue(run(*Array(JankFallback.BAD_WINDOWS) { bad() }).triggered)
        assertFalse(run(*Array(JankFallback.BAD_WINDOWS - 1) { bad() }).triggered)
        // Una ventana buena en medio vuelve a empezar.
        assertFalse(run(bad(), bad(), good(), bad(), bad()).triggered)
    }

    @Test
    fun `no cuentan el arranque, Masha, las descargas ni una pantalla quieta`() {
        assertFalse(run(*Array(10) { bad(since = 1_000) }).triggered)
        assertFalse(run(*Array(10) { bad(eligible = false) }).triggered)
        assertFalse(run(*Array(10) { bad(frames = 10) }).triggered)
        // Lo que no cuenta tampoco borra lo que llevaba.
        assertTrue(run(bad(), bad(), bad(eligible = false), bad(frames = 5), bad(), bad()).triggered)
    }

    @Test
    fun `una vez saltado no vuelve atras solo`() {
        val s = run(*Array(JankFallback.BAD_WINDOWS) { bad() })
        assertTrue(JankFallback.step(s, good()).triggered)
    }

    @Test
    fun `percentil 90`() {
        val frames = FloatArray(10) { (it + 1) * 10f }
        assertEquals(90f, JankFallback.p90(frames, 10), 0f)
        assertEquals(0f, JankFallback.p90(frames, 0), 0f)
        // Solo mira los primeros [count].
        assertEquals(10f, JankFallback.p90(floatArrayOf(10f, 999f), 1), 0f)
    }

    /* ── Guardado y reinicio ─────────────────────────────────── */

    @Test
    fun `los tirones se guardan, avisan una vez y se olvidan al elegir Auto`() {
        val store = SettingsStore(MemoryPrefs())
        assertEquals(StutterMemory.Stored(), store.stutterMemory)

        val (first, notice) = StutterMemory.onStutter(store.stutterMemory)
        assertTrue(notice)
        store.stutterMemory = first
        assertTrue(SettingsStore(MemoryPrefsFrom(store)).performanceStutter)

        // Si vuelve a pasar (otra sesión), no se repite el aviso.
        val (_, again) = StutterMemory.onStutter(store.stutterMemory)
        assertFalse(again)

        // Calidad o Rendimiento a mano no lo tocan; Auto lo olvida.
        assertEquals(first, StutterMemory.onModeChosen(Mode.Performance, first))
        store.stutterMemory = StutterMemory.onModeChosen(Mode.Auto, first)
        assertFalse(store.performanceStutter)
        assertFalse(store.performanceStutterNoticed)
    }

    @Test
    fun `sin nada guardado no hay tirones ni aviso`() {
        val store = SettingsStore(MemoryPrefs())
        assertFalse(store.performanceStutter)
        assertFalse(store.performanceStutterNoticed)
    }

    /** Las mismas preferencias, vistas por otro SettingsStore (como al reabrir la app). */
    private fun MemoryPrefsFrom(store: SettingsStore): MemoryPrefs =
        MemoryPrefs(mutableMapOf("perf.stutter" to store.performanceStutter, "perf.stutterNotice" to store.performanceStutterNoticed))
}
