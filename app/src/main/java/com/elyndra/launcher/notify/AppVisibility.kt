package com.elyndra.launcher.notify

/**
 * ¿Está Elyndra en pantalla? Lo apunta MainActivity en onStart/onStop.
 *
 * Vive en memoria a propósito: si Android mató el proceso, quien pregunta
 * (un worker, el vigilante del calor) corre en uno nuevo con la app fuera de
 * pantalla, y `false` es justo la respuesta correcta.
 */
object AppVisibility {
    @Volatile
    var foreground: Boolean = false
}
