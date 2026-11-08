package com.elyndra.launcher.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * La tienda de ports tiene su propio clima: azul noche en degradado, cápsulas
 * de cristal azul pizarra y un área de acción a la derecha de cada cápsula
 * (Gratis / Instalar / Actualizar / Instalado). Siempre oscura, como el
 * escaparate de una tienda de juegos de escritorio, con la letra (Poppins),
 * los radios y los aros de foco de Elyndra Console.
 */
object Store {
    val bgTop = Color(0xFF16263C)
    val bgMid = Color(0xFF1B3352)
    val bgBottom = Color(0xFF0D1522)

    /** El fondo de la tienda: el degradado de arriba abajo. */
    val background: Brush get() = Brush.verticalGradient(listOf(bgTop, bgMid, bgBottom))

    val panel = Color(0xFF1F3149)
    val panelHigh = Color(0xFF2A4362)
    val capsule = Color(0xFF1C2D44)
    val line = Color(0x33A9C7E6)

    val text = Color(0xFFE6EEF7)
    val textDim = Color(0xFF9AB0C6)
    val textFaint = Color(0xFF677D93)
    val link = Color(0xFF66C0F4)

    val chip = Color(0xFF26405D)
    val chipText = Color(0xFFA9C7E6)
    val chipOn = Color(0xFF3E6A96)

    /** Instalar: verde lima; Actualizar: azul; Instalado y Solo PC: pizarra. */
    val install = listOf(Color(0xFF6FA720), Color(0xFF4C8A13))
    val update = listOf(Color(0xFF3D8FE0), Color(0xFF2468B5))
    val installed = Color(0xFF34495F)
    val muted = Color(0xFF2A3646)
    val free = Color(0xFF223246)

    val ok = Color(0xFF8BC34A)
    val warn = Color(0xFFE0B450)
    val requirement = Color(0xFF1A2B40)
}
