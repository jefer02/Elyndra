package com.elyndra.launcher.update

/** En release no hay simulación del actualizador (la de depuración está en src/debug). */
object UpdateDebug {
    val label: String? = null

    @Suppress("UNUSED_PARAMETER")
    fun fakeNewer(releases: List<Release>, current: String): Release? = null
}
