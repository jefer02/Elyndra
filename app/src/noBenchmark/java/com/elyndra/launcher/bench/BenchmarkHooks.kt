package com.elyndra.launcher.bench

import android.app.Activity
import android.content.Intent
import com.elyndra.launcher.ElyndraApplication
import com.elyndra.launcher.metadata.ArtCandidate
import com.elyndra.launcher.metadata.ArtKind

/**
 * En debug y release no hay biblioteca de prueba: no se hace nada. La de
 * verdad está en src/benchmark y solo entra en las variantes del perfil base
 * (nonMinifiedRelease y benchmarkRelease, ver app/build.gradle.kts). Todo
 * devuelve lo neutro, así que R8 quita las llamadas y la release se comporta
 * igual que sin ellas.
 */
object BenchmarkHooks {

    @Suppress("UNUSED_PARAMETER")
    fun onAppCreate(app: ElyndraApplication) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onActivityCreate(activity: Activity, intent: Intent?) = Unit

    /** Paquetes que se dan por instalados además de los de verdad. */
    val installedPackages: Set<String> = emptySet()

    /** Imágenes del selector de arte en lugar de las del servicio; null = las del servicio. */
    @Suppress("UNUSED_PARAMETER")
    fun artCandidates(key: String, kind: ArtKind): List<ArtCandidate>? = null
}
