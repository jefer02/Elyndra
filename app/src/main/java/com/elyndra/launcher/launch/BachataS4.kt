package com.elyndra.launcher.launch

import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.library.Ps4

/**
 * Cómo se arranca un juego en Bachata S4. Todo lo que depende de Bachata está
 * aquí y solo aquí, para cambiarlo en un sitio si Bachata cambia.
 *
 * - **Directo**: `com.bachatas4.android.DirectLaunchActivity` (exportada y
 *   sin intent-filter en la 0.2.3) con el extra de texto `game_id` = CUSA
 *   (`AAAA00000`). Arranca el juego si Bachata ya lo tiene en su biblioteca.
 *   No está documentada: se comprueba con el PackageManager antes de usarla.
 * - **Plan B**: si el id no es un CUSA válido, la actividad no está o el
 *   lanzamiento falla, se abre Bachata (`MainActivity`) y se avisa al usuario
 *   de que elija el juego allí.
 *
 * Nunca se usa el proveedor `adb-controller` que exporta Bachata. Kotlin puro
 * (los intents se describen con [LaunchSpec]).
 */
object BachataS4 {

    const val PROFILE_ID = "bachata_s4"

    /** El build de GitHub primero: es el que se instala fuera de Google Play. */
    val PACKAGES = listOf("com.bachatas4.android.github", "com.bachatas4.android")

    /** Las clases no cambian con el paquete (el build de GitHub solo cambia el applicationId). */
    const val DIRECT_ACTIVITY = "com.bachatas4.android.DirectLaunchActivity"
    const val MAIN_ACTIVITY = "com.bachatas4.android.MainActivity"
    const val EXTRA_GAME_ID = "game_id"

    sealed interface Plan {
        /** Arrancar el juego directamente. */
        data class Direct(val spec: LaunchSpec) : Plan

        /** Abrir Bachata para que el usuario elija el juego. */
        data class Fallback(val spec: LaunchSpec, val reason: Reason) : Plan
    }

    enum class Reason { InvalidId, NoDirectActivity, DirectFailed }

    /**
     * Qué hacer con el paquete [pkg] instalado. [directAvailable]: la actividad
     * directa existe en ese paquete (lo dice el PackageManager).
     */
    fun plan(pkg: String, titleId: String?, directAvailable: Boolean): Plan = when {
        !Ps4.isTitleId(titleId) -> Plan.Fallback(fallback(pkg), Reason.InvalidId)
        !directAvailable -> Plan.Fallback(fallback(pkg), Reason.NoDirectActivity)
        else -> Plan.Direct(direct(pkg, titleId!!))
    }

    fun direct(pkg: String, titleId: String): LaunchSpec = LaunchSpec(
        packageName = pkg,
        className = DIRECT_ACTIVITY,
        action = null,
        category = null,
        data = null,
        stringExtras = mapOf(EXTRA_GAME_ID to titleId),
        boolExtras = emptyMap(),
        intExtras = emptyMap(),
        arrayExtras = emptyMap(),
        grantUris = emptyList(),
        clearTask = false,
        clearTop = false,
    )

    /** Bachata abierto por su pantalla principal, como desde el lanzador de Android. */
    fun fallback(pkg: String): LaunchSpec = LaunchSpec(
        packageName = pkg,
        className = MAIN_ACTIVITY,
        action = Emulators.MAIN,
        category = LAUNCHER,
        data = null,
        stringExtras = emptyMap(),
        boolExtras = emptyMap(),
        intExtras = emptyMap(),
        arrayExtras = emptyMap(),
        grantUris = emptyList(),
        clearTask = false,
        clearTop = false,
    )

    private const val LAUNCHER = "android.intent.category.LAUNCHER"
}
