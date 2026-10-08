package com.elyndra.launcher.data

import androidx.annotation.StringRes
import com.elyndra.launcher.R

/**
 * Un color de marca como par de luz: [primary] es el tono que manda (filos,
 * eje, nodo, partículas), [secondary] el que lo acompaña (halo, degradados) y
 * [spark] el destello casi blanco (núcleos, brillos y barridos). Enteros ARGB,
 * sin Compose, para poder comprobar contrastes en la JVM.
 */
data class ColorPair(val primary: Int, val secondary: Int, val spark: Int)

/** Las paletas de firma de Ajustes → Apariencia: acento, selección e intro de una vez. */
enum class SignaturePreset(
    val id: String,
    @StringRes val nameRes: Int,
    val pair: ColorPair,
    /** Id del acento que pone (ver [ACCENTS]). */
    val accentId: String,
    /** Id del color de la intro que pone (ver `IntroColor`). */
    val introId: String,
) {
    Plasma(
        "plasma", R.string.palette_plasma,
        ColorPair(0xFF7C5CFF.toInt(), 0xFF2BD9FF.toInt(), 0xFFFFF1C9.toInt()),
        accentId = "plasma", introId = "plasma",
    ),
    Ember(
        "ember", R.string.palette_ember,
        ColorPair(0xFFFF8A1F.toInt(), 0xFFFF3D5A.toInt(), 0xFFFFE2B8.toInt()),
        accentId = "ember", introId = "ember",
    ),
    Aurora(
        "aurora", R.string.palette_aurora,
        ColorPair(0xFF19E3A5.toInt(), 0xFF3AA8FF.toInt(), 0xFFE6FFF6.toInt()),
        accentId = "aurora", introId = "aurora",
    ),
    NeonRose(
        "neon_rose", R.string.palette_neon_rose,
        ColorPair(0xFFFF4FA3.toInt(), 0xFFB15CFF.toInt(), 0xFFFFE6F3.toInt()),
        accentId = "neon_rose", introId = "neon_rose",
    ),

    /** El oro de siempre, tal cual: el acento "oro" y el oro de la intro. */
    Solar(
        "solar", R.string.palette_solar,
        ColorPair(SignaturePalettes.SOLAR_GOLD, 0xFFF3CE7A.toInt(), 0xFFFFF1D6.toInt()),
        accentId = "oro", introId = "gold",
    ),
    ;

    companion object {
        val DEFAULT = Plasma
    }
}

/**
 * Lo que decide la paleta de firma sin Compose: el par de cualquier color
 * (los de firma tienen el suyo; uno elegido con el tono se completa solo) y
 * su versión para cada tema.
 *
 * Oscuro: la luz suma, así que el par se usa tal cual. Claro: sobre el perla
 * la luz no suma; cada tono se vuelve algo más saturado y hondo, lo justo para
 * el 3:1 de las piezas esenciales frente a la perla, el papel y la superficie,
 * y el texto sale de [text] (4,5:1).
 */
object SignaturePalettes {

    /** El oro de la intro (y de la paleta Solar). */
    const val SOLAR_GOLD = 0xFFE9B44C.toInt()

    /** Giro de tono del secundario de un color propio. */
    const val SECONDARY_HUE_SHIFT = 35f

    /** Piezas esenciales (eje, nodo, filo) frente a los fondos claros. */
    const val MIN_UI = 3.0

    /** Texto. */
    const val MIN_TEXT = 4.5

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Lo que sube la saturación y baja la luminosidad la variante clara antes de ajustar el contraste. */
    private const val LIGHT_SATURATION = 0.12f
    private const val LIGHT_DEEPEN = 0.06f

    /** Los fondos claros sobre los que tienen que leerse: perla de la intro, papel y superficie. */
    val lightBackgrounds: IntArray = intArrayOf(BrandTokens.INTRO_PEARL, BrandTokens.LIGHT.paper, BrandTokens.LIGHT.surface)

    val presets: List<SignaturePreset> get() = SignaturePreset.entries

    /** La paleta de firma cuyo primario es exactamente [argb]; null si es otro color. */
    fun presetOf(argb: Int): SignaturePreset? {
        val c = ColorMath.opaque(argb)
        return SignaturePreset.entries.firstOrNull { it.pair.primary == c }
    }

    /**
     * El par de un color: el de su paleta de firma o, para cualquier otro, el
     * secundario con el tono girado [SECONDARY_HUE_SHIFT] grados y un destello
     * casi blanco del mismo tono.
     */
    fun pairFor(argb: Int): ColorPair = presetOf(argb)?.pair ?: derive(argb)

    /**
     * El par de un acento ([accentA] su arranque, [accentC] su secundario): el
     * de su paleta de firma si lo es; si el acento lleva secundario propio, ese
     * secundario con el destello derivado; si es de un solo tono, ese tono solo.
     */
    fun accentPair(accentId: String, accentA: Int, accentC: Int): ColorPair {
        SignaturePreset.entries.firstOrNull { it.accentId == accentId && it != SignaturePreset.Solar }?.let { return it.pair }
        val a = ColorMath.opaque(accentA)
        val c = ColorMath.opaque(accentC)
        return if (a == c) ColorPair(a, a, a) else ColorPair(a, c, derive(a).spark)
    }

    /** El par de un color propio (deslizador de tono): secundario girado +35° y destello claro. */
    fun derive(argb: Int): ColorPair {
        val c = ColorMath.opaque(argb)
        val (h, s, l) = ColorMath.toHsl(c)
        val secondary = ColorMath.fromHsl(h + SECONDARY_HUE_SHIFT, s, l)
        val spark = ColorMath.mix(ColorMath.fromHsl(h + SECONDARY_HUE_SHIFT / 2f, (s * 0.8f).coerceAtMost(1f), 0.86f), WHITE, 0.45f)
        return ColorPair(c, secondary, spark)
    }

    /** El par para el tema: tal cual en oscuro; en claro, más hondo y saturado hasta el 3:1. */
    fun forTheme(pair: ColorPair, dark: Boolean): ColorPair =
        if (dark) pair else ColorPair(lightVariant(pair.primary), lightVariant(pair.secondary), pair.spark)

    /** Una pieza esencial en el tema claro: el tono algo más saturado y hondo, con 3:1 frente a los fondos claros. */
    fun lightVariant(argb: Int): Int {
        val (h, s, l) = ColorMath.toHsl(ColorMath.opaque(argb))
        var c = ColorMath.fromHsl(h, (s + LIGHT_SATURATION).coerceAtMost(1f), (l - LIGHT_DEEPEN).coerceAtLeast(0f))
        for (bg in lightBackgrounds) c = ColorMath.ensureContrast(c, bg, MIN_UI)
        return c
    }

    /** El primario como color de texto del tema (4,5:1 sobre papel, superficie y cristal). */
    fun text(pair: ColorPair, dark: Boolean): Int = Palettes.contentFor(pair.primary, dark)
}
