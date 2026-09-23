package com.elyndra.launcher.display

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import kotlin.math.abs

/* ─────────────────────────────────────────────────────────────
   Frecuencia de refresco.

   Elyndra pide 120 Hz en las pantallas que lo admiten: con muchas
   carátulas moviéndose, 120 fotogramas por segundo se notan. Pero
   sostenerlos gasta batería y calienta, así que en Ajustes se puede
   bajar a 60. En una pantalla de 60 Hz la opción de 120 no existe.

   La petición se hace con `preferredDisplayModeId` (el modo de
   pantalla de la misma resolución más cercano a lo pedido): es la
   única vía que funciona desde API 23 para una ventana normal, y el
   sistema la respeta mientras Elyndra está en primer plano. En
   Android 15+ se añade además la sugerencia de frecuencia de la
   vista raíz.
   ───────────────────────────────────────────────────────────── */

object FrameRate {

    const val HIGH = 120
    const val STANDARD = 60

    /** 0 en Ajustes = "lo mejor que admita la pantalla". */
    const val AUTO = 0

    /** Una pantalla de 120 Hz reales (o 144, 165…): con eso basta para ofrecer 120 fps. */
    private const val HIGH_THRESHOLD = 110f

    private fun defaultDisplay(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    /** La frecuencia más alta que admite la pantalla principal. */
    fun maxSupported(context: Context): Float {
        val display = defaultDisplay(context) ?: return STANDARD.toFloat()
        return display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
    }

    fun supportsHigh(context: Context): Boolean = maxSupported(context) >= HIGH_THRESHOLD

    /**
     * Lo que se aplica de verdad a partir de lo guardado: AUTO = 120 si la
     * pantalla puede y 60 si no; 120 en una pantalla que no puede, 60.
     */
    fun effective(stored: Int, supportsHigh: Boolean): Int = when {
        !supportsHigh -> STANDARD
        stored == STANDARD -> STANDARD
        else -> HIGH
    }

    /** Pide a la ventana de [activity] el modo de pantalla más cercano a [fps]. */
    fun apply(activity: Activity, fps: Int) {
        val window = activity.window ?: return
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay)
            ?: return
        val current = display.mode
        // Solo modos de la misma resolución: cambiar de resolución para ganar
        // hercios haría parpadear la pantalla y reescalaría la interfaz.
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }.ifEmpty { listOf(current) }
        val target = sameSize.minWith(
            compareBy<Display.Mode> { abs(it.refreshRate - fps) }
                // A igual distancia (p. ej. 90 y 144 para 120), el más alto.
                .thenByDescending { it.refreshRate },
        )
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != target.modeId || attrs.preferredRefreshRate != target.refreshRate) {
            attrs.preferredDisplayModeId = target.modeId
            attrs.preferredRefreshRate = target.refreshRate
            window.attributes = attrs
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Android 15+: la sugerencia por vista que usa el planificador de
            // frecuencia variable (LTPO). Complementa al modo de pantalla.
            window.decorView.requestedFrameRate = target.refreshRate
        }
    }
}
