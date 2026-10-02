package com.elyndra.launcher.data

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/* ─────────────────────────────────────────────────────────────
   Paleta de la interfaz: neutros, estados, acentos y tintes del
   cristal. Los valores salen de [BrandTokens]; aquí se presentan
   como Color de Compose y según el tema.

   Modo claro / oscuro
   ───────────────────
   `P` guarda el tema en un estado de Compose: cambiar [P.isDark]
   recompone toda la interfaz sin tocar ninguno de los sitios que
   ya leen `P.ink`, `P.paper`… Claro es el de partida.

   Ojo con los dos papeles de la "tinta":

     · [ink] / [ink2] son color de TEXTO — se invierten con el tema.
     · [shade] es tinta FÍSICA (sombras, velos, el fondo del hero y
       el cristal oscuro) — nunca se invierte: una sombra sigue
       siendo oscura en modo oscuro.
   ───────────────────────────────────────────────────────────── */

/** Paleta base de la interfaz. */
object P {

    /** Tema activo. Lo fija SettingsController al arrancar y al conmutar. */
    var isDark by mutableStateOf(false)

    private val n: BrandTokens.Neutrals get() = if (isDark) BrandTokens.DARK else BrandTokens.LIGHT

    /* ── Marca: constantes, no dependen del tema ─────────────── */

    /** Primario de marca (relleno): el índigo de Elyndra. */
    val primary = Color(BrandTokens.PRIMARY)
    val primaryDeep = Color(BrandTokens.PRIMARY_DEEP)

    /** Secundario frío de marca. */
    val secondary = Color(BrandTokens.SECONDARY)

    /** Tinta física: sombras, velos, fondo del hero y cristal oscuro. Siempre oscura. */
    val shade = Color(BrandTokens.SHADE)

    /** Fondo neutro de las miniaturas de imagen y de las vistas previas oscuras. */
    val mediaBack = Color(BrandTokens.MEDIA_BACK)

    /** El brillo champán de la intro (cantos y foco), según el tema. */
    val champagne: Color get() = Color(if (isDark) BrandTokens.CHAMPAGNE else BrandTokens.CHAMPAGNE_DEEP)

    /* ── Dependientes del tema ───────────────────────────────── */

    /** Fondo general de la app. */
    val paper: Color get() = Color(n.paper)

    /** Base opaca de diálogos y hojas. */
    val surface: Color get() = Color(n.surface)

    /** Texto principal. */
    val ink: Color get() = Color(n.ink)

    /** Texto secundario. */
    val ink2: Color get() = Color(n.ink2)

    /** Error y acciones que no se deshacen. */
    val red: Color get() = Color(n.error)

    /** Éxito (servicio conectado, acceso concedido). */
    val success: Color get() = Color(n.success)

    /** Aviso. */
    val warning: Color get() = Color(n.warning)

    /**
     * Relleno de las piezas pequeñas (píldoras, botones fantasma, etiquetas).
     * En claro es un velo blanco; en oscuro un velo claro sobre oscuro, o el
     * texto [ink] —casi blanco— se pierde encima.
     */
    val chip: Color get() = Color(n.chip)

    /** Filo de las láminas de cristal y de las cards. */
    val hairline: Color get() = Color(n.hairline)
}

/**
 * Color de acento. [a] → [b] es el degradado de relleno (botones, píldoras,
 * pastillas); [c] el secundario (brillos, auroras). El color de contenido
 * —texto, iconos, aro de foco sobre el papel— sale de [content]: el de
 * [onLight]/[onDark] si se da, o [b] ajustado hasta el contraste AA.
 */
class Accent(
    val id: String,
    @StringRes val nameRes: Int,
    val a: Color,
    val b: Color,
    val c: Color = a,
    onLight: Color? = null,
    onDark: Color? = null,
) {
    private val contentLight = onLight ?: Color(Palettes.contentFor(b.argb(), dark = false))
    private val contentDark = onDark ?: Color(Palettes.contentFor(b.argb(), dark = true))

    /** Color de contenido del acento para el tema [dark]. */
    fun content(dark: Boolean): Color = if (dark) contentDark else contentLight

    override fun equals(other: Any?): Boolean = other is Accent && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/** Color → ARGB sin pasar por el espacio de color de Compose. */
fun Color.argb(): Int {
    fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return ColorMath.argb(ch(alpha), ch(red), ch(green), ch(blue))
}

/** Tinte del cristal. */
data class Tint(val id: String, val color: Color)

/** El primero es el de partida: Índigo Obsidiana. Los de siempre siguen detrás. */
val ACCENTS = listOf(
    Accent(
        "indigo", com.elyndra.launcher.R.string.accent_indigo,
        Color(BrandTokens.PRIMARY), Color(BrandTokens.PRIMARY_DEEP), Color(BrandTokens.SECONDARY),
        Color(BrandTokens.PRIMARY_ON_LIGHT), Color(BrandTokens.PRIMARY_ON_DARK),
    ),
    Accent(
        "abismo", com.elyndra.launcher.R.string.accent_abismo,
        Color(0xFF1E6FA6), Color(0xFF0D3C66), Color(0xFF3FCFDF),
        Color(0xFF155A8A), Color(0xFF7FD0F2),
    ),
    Accent(
        "medianoche", com.elyndra.launcher.R.string.accent_medianoche,
        Color(0xFF6A48CF), Color(0xFF381F86), Color(0xFFD9B77E),
        Color(0xFF4B2FA8), Color(0xFFC3B0FF),
    ),
    Accent("mandarina", com.elyndra.launcher.R.string.accent_mandarina, Color(0xFFF59659), Color(0xFFE26D19)),
    Accent("fuego", com.elyndra.launcher.R.string.accent_fuego, Color(0xFFEE7E28), Color(0xFFB24A08)),
    Accent("menta", com.elyndra.launcher.R.string.accent_menta, Color(0xFF9BD494), Color(0xFF3F9A62)),
    Accent("cobalto", com.elyndra.launcher.R.string.accent_cobalto, Color(0xFF84B6F7), Color(0xFF2C63C8)),
    Accent("lila", com.elyndra.launcher.R.string.accent_lila, Color(0xFFC2A6F2), Color(0xFF7343CE)),
    Accent("coral", com.elyndra.launcher.R.string.accent_coral, Color(0xFFF79BA8), Color(0xFFD33F5B)),
    Accent("turquesa", com.elyndra.launcher.R.string.accent_turquesa, Color(0xFF8CD9D3), Color(0xFF1E9A93)),
    Accent("oro", com.elyndra.launcher.R.string.accent_oro, Color(0xFFF3CE7A), Color(0xFFC08A12)),
    Accent("chicle", com.elyndra.launcher.R.string.accent_chicle, Color(0xFFF5A3D6), Color(0xFFC02E9B)),
    Accent("grafito", com.elyndra.launcher.R.string.accent_grafito, Color(0xFF8C9196), Color(0xFF333333)),
)

/** Tintes del cristal. El primero es el de partida (Niebla, blanco frío). */
val TINTS = listOf(
    Tint("niebla", Color(0xFFEEF0FA)), // = Palettes.DEFAULT_TINT_ARGB
    Tint("grafito", Color(0xFFEDF1F4)),
    Tint("bruma", Color(0xFFF1EEF8)),
    Tint("papel", Color(0xFFFFFFFF)),
    Tint("arena", Color(0xFFF59659)),
    Tint("ámbar", Color(0xFFEE7E28)),
    Tint("cobre", Color(0xFFE26D19)),
    Tint("menta", Color(0xFF9BD494)),
    Tint("cielo", Color(0xFF84B6F7)),
    Tint("lila", Color(0xFFC2A6F2)),
    Tint("humo", Color(0xFF555555)),
)

/**
 * Lo que decide la paleta sin Compose: el color de contenido de un acento y
 * qué acento, tinte y tema tocan cuando no hay nada guardado.
 */
object Palettes {

    const val DEFAULT_ACCENT = "indigo"
    const val DEFAULT_TINT = "niebla"
    const val DEFAULT_DARK = false
    const val DEFAULT_BLUR = 18
    const val DEFAULT_ALPHA = 58
    const val DEFAULT_SCRIM = 62

    /** Tinte de partida del cristal (Niebla), el mismo que el primero de [TINTS]. */
    private const val DEFAULT_TINT_ARGB = 0xFFEEF0FA.toInt()

    /**
     * Texto AA (4,5:1) sobre el papel, la superficie y el cristal de partida
     * del tema (en claro, el tinte al [DEFAULT_ALPHA] % encima del papel).
     */
    fun contentFor(fill: Int, dark: Boolean): Int {
        val n = if (dark) BrandTokens.DARK else BrandTokens.LIGHT
        var c = ColorMath.ensureContrast(fill, n.paper, 4.5)
        c = ColorMath.ensureContrast(c, n.surface, 4.5)
        // En oscuro el cristal no lleva el tinte claro encima del papel (se
        // oscurece con el tema), así que ahí basta con papel y superficie.
        if (!dark) {
            val glass = ColorMath.over(ColorMath.withAlpha(DEFAULT_TINT_ARGB, DEFAULT_ALPHA / 100f), n.paper)
            c = ColorMath.ensureContrast(c, glass, 4.5)
        }
        return c
    }

    /**
     * Un color como relleno con texto blanco encima (botones): el mismo tono,
     * oscurecido lo justo para el 4,5:1.
     */
    fun fillFor(color: Int): Int = ColorMath.ensureContrast(color, WHITE, 4.5)

    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * El acento guardado, si sigue existiendo; si no hay nada guardado (o es
     * uno que ya no existe), el de partida. Nunca se pisa una elección del usuario.
     */
    fun accentId(stored: String?, known: Collection<String>): String =
        stored?.takeIf { it in known } ?: DEFAULT_ACCENT

    fun tintId(stored: String?, known: Collection<String>): String =
        stored?.takeIf { it in known } ?: DEFAULT_TINT
}

/** "742" → "12h 22m". */
fun fmtMinutes(m: Int): String = "${m / 60}h ${m % 60}m"
