package com.elyndra.launcher.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Tirones al desplazarse, no solo el arranque: [FrameTimingMetric] mientras se
 * recorre el estante del Clásico (vertical) y la rueda de Meridian (apaisado),
 * sin compilar nada frente a con el perfil base, en Calidad y en Rendimiento.
 * Lo que importa: frameDurationCpuMs (P50/P90/P99) y frameOverrunMs (cuánto se
 * pasa cada fotograma de su plazo; positivo = tirón).
 *
 *   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 *
 * Ver docs/RELEASING.md.
 */
@RunWith(Parameterized::class)
class ScrollBenchmark(private val compilation: Compilation, private val mode: PerfMode) {

    enum class Compilation(val mode: () -> CompilationMode) {
        None({ CompilationMode.None() }),
        BaselineProfile({ CompilationMode.Partial(BaselineProfileMode.Require) }),
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> = Compilation.entries.flatMap { c ->
            listOf(PerfMode.Performance, PerfMode.Quality).map { m -> arrayOf<Any>(c, m) }
        }

        private const val ITERATIONS = 5
    }

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun setUp() {
        checkTargetPackage()
        seedFixtureLibrary()
    }

    @After
    fun tearDown() = unfreezeRotation()

    /** El estante del Clásico: cruceta y golpes de dedo. */
    @Test
    fun classicShelf() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation.mode(),
        iterations = ITERATIONS,
        setupBlock = {
            start(portrait = true)
            require(Tags.SHELF)
        },
    ) {
        require(Tags.SHELF)
        repeat(12) {
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
            Thread.sleep(120)
        }
        flingShelf()
    }

    /** La rueda de Meridian: cruceta abajo y arriba y golpes de dedo. */
    @Test
    fun meridianWheel() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation.mode(),
        iterations = ITERATIONS,
        setupBlock = {
            start(portrait = false)
            require(Tags.WHEEL)
        },
    ) {
        require(Tags.WHEEL)
        repeat(15) {
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
            Thread.sleep(120)
        }
        repeat(5) {
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_UP)
            Thread.sleep(120)
        }
        flingWheel()
    }

    private fun MacrobenchmarkScope.start(portrait: Boolean) {
        pressHome()
        if (portrait) device.setOrientationPortrait() else device.setOrientationLandscape()
        startActivityAndWait { it.putExtra(PerfMode.EXTRA, mode.id) }
        require(Tags.LIBRARY)
        device.waitForIdle()
    }
}
