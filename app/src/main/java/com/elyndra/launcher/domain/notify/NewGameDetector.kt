package com.elyndra.launcher.domain.notify

/** Un juego Android instalado, tal como lo ve el PackageManager. */
data class InstalledGame(val packageName: String, val label: String, val installedAt: Long)

/**
 * Juegos instalados desde la última vez que se miró y que aún no están en la
 * biblioteca: lo que vale un aviso de "juegos nuevos" con Elyndra cerrado. Lo
 * que el usuario añadió desde la app ya está en la biblioteca y no cuenta.
 */
object NewGameDetector {

    /** Los recién instalados después de [since] que no están en [inLibrary], el más nuevo primero. */
    fun fresh(installed: List<InstalledGame>, inLibrary: Set<String>, since: Long): List<InstalledGame> =
        installed.asSequence()
            .filter { it.installedAt > since && it.packageName !in inLibrary }
            .distinctBy { it.packageName }
            .sortedByDescending { it.installedAt }
            .toList()
}
