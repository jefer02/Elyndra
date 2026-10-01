package com.elyndra.launcher.ui.intro

/**
 * La intro se ve una vez por proceso: en un arranque en frío y nunca más
 * mientras el proceso viva (giros, recreación de la Activity, volver del
 * segundo plano o salir con "atrás" y volver a entrar).
 *
 * Vive en ElyndraApplication y no en `rememberSaveable`: lo guardado en el
 * estado de la Activity sobrevive a que el sistema mate el proceso, y lo que
 * se quiere aquí es justo lo contrario.
 */
class IntroGate {

    private var claimed = false

    /**
     * La primera llamada del proceso decide; las demás devuelven `false`.
     * [restored] = la Activity vuelve de un proceso que el sistema mató en
     * segundo plano: el usuario no está abriendo la app, está volviendo a ella.
     */
    @Synchronized
    fun claim(enabled: Boolean, restored: Boolean): Boolean {
        if (claimed) return false
        claimed = true
        return enabled && !restored
    }
}
