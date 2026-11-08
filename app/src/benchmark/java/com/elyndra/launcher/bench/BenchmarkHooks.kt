package com.elyndra.launcher.bench

import android.app.Activity
import android.content.Intent
import com.elyndra.launcher.ElyndraApplication
import com.elyndra.launcher.bench.fixtures.BenchmarkFixtures
import com.elyndra.launcher.bench.fixtures.BenchmarkMode
import com.elyndra.launcher.bench.fixtures.FixtureArt
import com.elyndra.launcher.bench.fixtures.FixtureLibrary
import com.elyndra.launcher.metadata.ArtCandidate
import com.elyndra.launcher.metadata.ArtKind
import java.io.File

/**
 * Solo en las variantes del perfil base ("Elyndra Bench", src/benchmark): una
 * biblioteca de prueba fija, sin red, para que el generador del perfil y las
 * pruebas de rendimiento recorran lo mismo que una biblioteca de verdad. En
 * debug y release esto es un stub vacío (src/noBenchmark).
 */
object BenchmarkHooks {

    private var filesDir: File? = null

    /** Application.onCreate, antes de leer Ajustes: lo que hace las pasadas iguales y, una vez, la biblioteca. */
    fun onAppCreate(app: ElyndraApplication) {
        filesDir = app.filesDir
        BenchmarkFixtures.prepare(app)
    }

    /** MainActivity.onCreate, antes de la interfaz: el modo de rendimiento que pide la prueba. */
    fun onActivityCreate(activity: Activity, intent: Intent?) = BenchmarkMode.apply(activity, intent)

    /**
     * Las apps de la biblioteca de prueba y los emuladores de sus carpetas no
     * están instalados: se dan por instalados para que la biblioteca se pinte
     * como la de verdad (sin atenuar ni avisos de "no instalado"). Lanzar uno
     * falla sin más; el generador nunca pulsa Jugar.
     */
    val installedPackages: Set<String> get() = FixtureLibrary.installedPackages

    /** El selector de arte enseña las imágenes locales de la biblioteca de prueba, nunca las de un servicio. */
    fun artCandidates(key: String, kind: ArtKind): List<ArtCandidate>? =
        filesDir?.let { FixtureArt.candidates(it, key, kind) }
}
