package com.elyndra.launcher.update

/**
 * Solo en builds de depuración (src/debug): una release "más nueva" de
 * mentira para probar el actualizador sin publicar nada. La de src/release
 * no hace nada, así que esto no existe en el APK firmado.
 */
object UpdateDebug {

    /** El rótulo del botón en Ajustes → Actualizaciones (texto de desarrollo, sin traducir). */
    val label: String? = "Simulate a newer release (debug build)"

    /**
     * Una versión por encima de [current] con los APK de la release real más
     * nueva de [releases] (si la lista no llegó, sin APK: prueba la salida al
     * navegador). Instalarla en una build de depuración prueba además el
     * aviso de firma distinta.
     */
    fun fakeNewer(releases: List<Release>, current: String): Release? {
        val base = releases.filter { !it.draft && it.version != null }.maxByOrNull { it.version!! }
        val next = (SemVer.parse(current)?.major ?: 0) + 1
        return Release(
            tag = "v$next.0.0-debug",
            title = "Elyndra v$next.0.0-debug (simulated)",
            notes = "Simulated release, debug builds only.\n\n" + (base?.notes ?: "No release list: the update falls back to the release page."),
            pageUrl = base?.pageUrl ?: UpdateSource.RELEASES_URL,
            prerelease = true,
            draft = false,
            assets = base?.assets.orEmpty(),
        )
    }
}
