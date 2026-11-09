package com.elyndra.launcher.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset

/**
 * La tienda de ports tiene el clima de la tienda de una consola de salón:
 * fondo casi negro con una viñeta suave, texto blanco para lo activo y
 * blanco al 55 % para lo demás, títulos en peso fino, baldosas cuadradas y el
 * foco como un marco blanco. Lo de Elyndra es el acento (el del tema, en el
 * botón principal), la letra (Poppins) y los glifos del mando.
 */
object Store {
    val bg = Color(0xFF0B0B0F)
    val bgLift = Color(0xFF16161D)

    /** Baldosas de la cabecera, botones redondos y la píldora de pestaña. */
    val tile = Color(0xFF1C1C24)
    val tileHigh = Color(0xFF2A2A34)
    val pillBorder = Color(0x59FFFFFF)

    /** El panel de la ficha y las hojas: gris muy oscuro, casi opaco. */
    val panel = Color(0xF21A1A20)
    val panelRow = Color(0xFF2B2B33)
    val line = Color(0x26FFFFFF)

    val text = Color(0xFFFFFFFF)
    val textDim = Color(0x8CFFFFFF)
    val textFaint = Color(0x59FFFFFF)

    /** Lo que se lee sobre el arte de una baldosa: la base oscura del degradado. */
    val scrim = Color(0xE6000000)

    /** La caja de NUEVO / ACTUALIZADO / v1.3: blanca con letra negra. */
    val badge = Color(0xFFFFFFFF)
    val badgeText = Color(0xFF000000)

    val ok = Color(0xFF8BC34A)
    val warn = Color(0xFFE0B450)
    val link = Color(0xFF8AB4F8)

    /** La pestaña o el icono con el foco: el marco claro. */
    val focus = Color(0xFFFFFFFF)

    /** El fondo de la tienda: casi negro con un brillo muy suave arriba a la izquierda y viñeta abajo. */
    fun background(width: Float, height: Float): List<Brush> = listOf(
        Brush.verticalGradient(listOf(bgLift, bg, bg)),
        Brush.radialGradient(
            listOf(Color(0x1FFFFFFF), Color.Transparent),
            center = Offset(width * 0.18f, 0f),
            radius = maxOf(width, height) * 0.75f,
        ),
        Brush.radialGradient(
            listOf(Color.Transparent, Color(0x99000000)),
            center = Offset(width / 2f, height * 0.45f),
            radius = maxOf(width, height) * 0.85f,
        ),
    )
}
