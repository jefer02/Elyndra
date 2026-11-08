package com.elyndra.launcher.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Arranque en frío sin compilar nada ([CompilationMode.None]) frente a con el
 * perfil base ([BaselineProfileMode.Require]: falla si no hay perfil, así no
 * se mide en balde). La diferencia entre las dos es lo que gana el perfil.
 *
 * Con la biblioteca de prueba ya cargada (se arranca a una biblioteca llena,
 * como la de un usuario) y en Auto: lo que el equipo elegiría solo. El modo se
 * pasa siempre, porque el generador deja guardado el último que usó.
 *
 *   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 *
 * Ver docs/RELEASING.md.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun setUp() {
        checkTargetPackage()
        seedFixtureLibrary()
    }

    @After
    fun tearDown() = unfreezeRotation()

    @Test
    fun startupWithoutCompilation() = startup(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            pressHome()
            device.setOrientationLandscape()
        },
    ) {
        startActivityAndWait { it.putExtra(PerfMode.EXTRA, PerfMode.Auto.id) }
    }
}
