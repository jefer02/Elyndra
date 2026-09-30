package com.elyndra.launcher.data

/**
 * La paleta de marca de Elyndra en un solo sitio: cada color de la interfaz
 * sale de aquí (vía [P], los acentos y los tintes). Enteros ARGB, sin
 * Compose, para poder comprobar contrastes en la JVM.
 *
 * Dirección: panel de consola de gama alta. Neutros de tinta y grafito fríos,
 * un índigo profundo como primario y un cian suave de secundario. Nada de
 * neón ni de colores de caramelo.
 *
 * Contraste (WCAG AA, comprobado en PaletteTest): texto ≥ 4,5:1 y piezas de
 * interfaz (aro de foco, selección) ≥ 3:1, en claro y en oscuro, sobre papel
 * y sobre superficie.
 */
object BrandTokens {

    /* ── Neutros ── */

    class Neutrals(
        /** Fondo general. */
        val paper: Int,
        /** Base opaca de diálogos, hojas y paneles. */
        val surface: Int,
        /** Texto principal. */
        val ink: Int,
        /** Texto secundario. */
        val ink2: Int,
        /** Relleno de píldoras, botones fantasma y etiquetas. */
        val chip: Int,
        /** Filo de láminas y cards. */
        val hairline: Int,
        val success: Int,
        val warning: Int,
        val error: Int,
    )

    val LIGHT = Neutrals(
        paper = 0xFFF3F4F8.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        ink = 0xFF151827.toInt(),
        ink2 = 0xFF474D63.toInt(),
        chip = 0xB3FFFFFF.toInt(),
        hairline = 0xB8FFFFFF.toInt(),
        success = 0xFF177148.toInt(),
        warning = 0xFF8A5B0E.toInt(),
        error = 0xFFC02C48.toInt(),
    )

    val DARK = Neutrals(
        paper = 0xFF0D0F16.toInt(),
        surface = 0xFF171A25.toInt(),
        ink = 0xFFECEEF6.toInt(),
        ink2 = 0xFFA3A9BE.toInt(),
        chip = 0x17FFFFFF,
        hairline = 0x24FFFFFF,
        success = 0xFF4CD497.toInt(),
        warning = 0xFFF2B85B.toInt(),
        error = 0xFFFF8397.toInt(),
    )

    /**
     * Tinta física: sombras, velos, el fondo del hero y el cristal oscuro. No
     * cambia con el tema (una sombra sigue siendo oscura en modo oscuro).
     */
    const val SHADE = 0xFF1A1D2B.toInt()

    /** El negro de la consola apagada: arranque, splash del sistema y fondo del widget. */
    const val CONSOLE_BLACK = 0xFF05070A.toInt()

    /** Fondo del widget: la tinta física casi opaca. */
    const val WIDGET_BG = 0xF01A1D2B.toInt()

    /** Fondo neutro de las miniaturas de imagen y de las vistas previas oscuras. */
    const val MEDIA_BACK = 0xFF141722.toInt()

    /* ── Primario de marca (Índigo Obsidiana) ── */

    /** Relleno de botones y píldoras: blanco encima ≥ 4,5:1 en todo el degradado. */
    const val PRIMARY = 0xFF4E56D8.toInt()
    const val PRIMARY_DEEP = 0xFF2B2F92.toInt()

    /** Secundario frío: brillos, auroras, detalles. */
    const val SECONDARY = 0xFF5BC3DC.toInt()

    /** El primario como color de contenido (texto, iconos, aro de foco). */
    const val PRIMARY_ON_LIGHT = 0xFF3036A3.toInt()
    const val PRIMARY_ON_DARK = 0xFFA9B0FF.toInt()

    /* ── Masha: la sala del holograma. Su aspecto no depende de la marca. ── */

    const val HOLO_BG = 0xFF040913.toInt()
    const val HOLO_PANEL = 0xFF0A1630.toInt()
    const val HOLO_LINE = 0xFF5CE1FF.toInt()
    const val HOLO_TEXT = 0xFFE9F4FF.toInt()
    const val HOLO_DIM = 0xFF8EA6C8.toInt()
    const val HOLO_USER = 0xFF1B3B7A.toInt()
}
