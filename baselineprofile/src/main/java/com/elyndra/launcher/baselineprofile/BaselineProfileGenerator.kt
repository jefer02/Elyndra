package com.elyndra.launcher.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El perfil base de Elyndra: lo que se compila de antemano (AOT) al instalar.
 * Corre sobre "Elyndra Bench" (com.elyndra.launcher.benchmark) con su
 * biblioteca de prueba fija (app/src/benchmark): 25 juegos Android y cuatro
 * carpetas de ROMs con arte local, así se recorren las pantallas que más pesan
 * en un equipo modesto aunque el móvil no tenga biblioteca propia.
 *
 * Una pasada, dos veces (Calidad y Rendimiento, porque en un móvil modesto
 * casi todo el mundo acaba en Rendimiento y los efectos de cada modo son otro
 * código):
 *  1. arranque en frío hasta la biblioteca (Meridian, apaisado);
 *  2. ~15 pasos por la rueda (fondo, arte, cristal);
 *  3. pestañas con LB/RB;
 *  4. búsqueda: abrir, escribir, cerrar;
 *  5. ficha y opciones de un juego;
 *  6. una carpeta en Meridian, con cruceta y a golpe de dedo;
 *  7. vertical (Clásico): estante de la biblioteca y de una carpeta;
 *  8. Ajustes → Pantalla → Rendimiento;
 *  9. el selector de arte con imágenes locales, recorrido con la cruceta.
 *
 * Masha no se abre: su escena 3D (Filament) y la voz (ONNX) pesan mucho, no
 * son de la navegación de cada día y, sin red ni paquete de voz, la pantalla
 * no sería la de un usuario de verdad. Nunca se pulsa Jugar.
 *
 *   ./gradlew :app:generateReleaseBaselineProfile   (con el móvil conectado)
 *
 * Ver docs/RELEASING.md.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Before
    fun setUp() {
        checkTargetPackage()
        seedFixtureLibrary()
    }

    @After
    fun tearDown() {
        unfreezeRotation()
    }

    @Test
    fun quality() = generate(PerfMode.Quality)

    @Test
    fun performance() = generate(PerfMode.Performance)

    private fun generate(mode: PerfMode) = rule.collect(
        packageName = PACKAGE,
        // Tres pasadas por modo, siempre (no puede haber tres iguales seguidas en tres):
        // el perfil se acumula de una a otra y tres recorridos completos ya lo cubren
        // todo. Más pasadas en el Tecno solo daban más ocasiones de que una pulsación
        // se perdiera a mitad (~3 min cada una). Sin estabilidad estricta, la última vale.
        maxIterations = 3,
        stableIterations = 3,
        includeInStartupProfile = true,
        // La biblioteca de prueba no existe en la release: fuera de los dos ficheros (baseline y
        // startup). El plugin la filtra otra vez en app/build.gradle.kts, pero solo en baseline-prof.txt.
        filterPredicate = { !it.contains(FIXTURE_RULES) },
    ) {
        coldStartToLibrary(mode)
        walkMeridianWheel()
        switchTabs()
        searchAndClose()
        openDetailsAndOptions()
        openFolderAndScrollWheel()
        classicShelves()
        openPerformanceSettings()
        browseArtPicker()
    }

    private companion object {
        const val FIXTURE_RULES = "Lcom/elyndra/launcher/bench/"
    }
}
