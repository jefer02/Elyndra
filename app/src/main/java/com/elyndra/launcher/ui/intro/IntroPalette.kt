package com.elyndra.launcher.ui.intro

import androidx.annotation.StringRes
import com.elyndra.launcher.R
import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.data.ColorMath

/** Colores de la intro que se pueden elegir en Ajustes. [Accent] toma el acento activo. */
enum class IntroColor(val id: String, @StringRes val nameRes: Int, private val argb: Int?) {
    Gold("gold", R.string.intro_color_gold, 0xFFE9B44C.toInt()),
    Cyan("cyan", R.string.intro_color_cyan, 0xFF4FD6EA.toInt()),
    Violet("violet", R.string.intro_color_violet, 0xFFA07CFF.toInt()),
    Crimson("crimson", R.string.intro_color_crimson, 0xFFE5485F.toInt()),
    Emerald("emerald", R.string.intro_color_emerald, 0xFF3FCF8E.toInt()),
    Accent("accent", R.string.intro_color_accent, null),
    ;

    fun base(accent: Int): Int = argb ?: accent

    companion object {
        val DEFAULT = Gold

        fun byId(id: String?): IntroColor = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Todos los colores de la intro (ARGB), sacados de un único color base.
 *
 * El fondo no depende del color elegido: es el mismo que pinta el splash del
 * sistema antes del primer fotograma, y así el paso de uno a otro no se ve.
 */
class IntroPalette(
    val dark: Boolean,
    val background: Int,
    /** Niebla cálida (oscuro) o degradado champán (claro), pintado con [fogAlpha]. */
    val fog: Int,
    val fogAlpha: Float,
    /** Borde de la viñeta, pintado con [vignetteAlpha]. */
    val vignette: Int,
    val vignetteAlpha: Float,
    val core: Int,
    val glow: Int,
    val streak: Int,
    /** Filo metálico de las letras: brillo, tono medio y fondo del degradado. */
    val rimLight: Int,
    val rim: Int,
    val rimDeep: Int,
    val bodyTop: Int,
    val bodyBottom: Int,
    val bevelLight: Int,
    val bevelDark: Int,
    /** Resplandor exterior (oscuro) o sombra proyectada (claro). */
    val depth: Int,
    val sheen: Int,
    val particle: Int,
    val particleLight: Int,
    val subtitle: Int,
    /** Resplandor del subtítulo (oscuro) o su subrayado dorado (claro). */
    val subtitleAccent: Int,
) {
    /** El fondo con la niebla encima en su punto más denso: el peor caso para el contraste. */
    val fogPeak: Int get() = ColorMath.over(ColorMath.withAlpha(fog, fogAlpha), background)
}

object IntroPalettes {

    /** Filos, partículas y subtítulo frente al fondo (WCAG, piezas gráficas). */
    const val MIN_CONTRAST = 3.0

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    fun derive(base: Int, dark: Boolean): IntroPalette = if (dark) dark(ColorMath.opaque(base)) else light(ColorMath.opaque(base))

    /**
     * Oscuro: luz que suma sobre ámbar ahumado. El color base ya brilla de por
     * sí; solo se aclara si es tan oscuro que se perdería en el fondo.
     */
    private fun dark(base: Int): IntroPalette {
        val (h, s) = ColorMath.toHsl(base)
        val background = BrandTokens.INTRO_SMOKE
        val fog = ColorMath.fromHsl(h, (s * 0.7f).coerceAtMost(0.6f), 0.16f)
        val fogAlpha = 0.55f
        val peak = ColorMath.over(ColorMath.withAlpha(fog, fogAlpha), background)
        fun legible(c: Int, ratio: Double = MIN_CONTRAST) = ColorMath.ensureContrast(ColorMath.ensureContrast(c, background, ratio), peak, ratio)
        val vivid = legible(base, 5.0)
        val l = ColorMath.toHsl(vivid)[2]
        return IntroPalette(
            dark = true,
            background = background,
            fog = fog,
            fogAlpha = fogAlpha,
            vignette = BLACK,
            vignetteAlpha = 0.7f,
            core = ColorMath.mix(vivid, WHITE, 0.72f),
            glow = vivid,
            streak = ColorMath.mix(vivid, WHITE, 0.45f),
            rimLight = ColorMath.mix(vivid, WHITE, 0.62f),
            rim = vivid,
            rimDeep = legible(ColorMath.fromHsl(h, s, l * 0.62f)),
            bodyTop = ColorMath.fromHsl(h, (s * 0.5f).coerceAtMost(0.4f), 0.10f),
            bodyBottom = ColorMath.fromHsl(h, (s * 0.5f).coerceAtMost(0.4f), 0.03f),
            bevelLight = ColorMath.mix(vivid, WHITE, 0.25f),
            bevelDark = BLACK,
            depth = vivid,
            sheen = ColorMath.mix(vivid, WHITE, 0.7f),
            particle = ColorMath.mix(vivid, WHITE, 0.2f),
            particleLight = ColorMath.mix(vivid, WHITE, 0.8f),
            subtitle = legible(ColorMath.mix(vivid, WHITE, 0.35f), 4.5),
            subtitleAccent = vivid,
        )
    }

    /**
     * Claro, "perla y oro champán": sobre el perla, la luz no suma, así que el
     * color se vuelve metal. Cada pieza es el tono base algo más saturado y
     * oscurecido lo justo para llegar al contraste frente al perla con su
     * degradado champán encima; las luces (núcleo, destello, barrido) son
     * blancos dorados que se ven por mezcla normal.
     */
    private fun light(base: Int): IntroPalette {
        val (h, s, l) = ColorMath.toHsl(base)
        val background = BrandTokens.INTRO_PEARL
        val fog = ColorMath.fromHsl(h, 0.55f, 0.86f)
        val fogAlpha = 0.6f
        val peak = ColorMath.over(ColorMath.withAlpha(fog, fogAlpha), background)
        val sat = (s + 0.12f).coerceAtMost(1f)
        fun metal(lightness: Float, ratio: Double) =
            ColorMath.ensureContrast(ColorMath.ensureContrast(ColorMath.fromHsl(h, sat, lightness), background, ratio), peak, ratio)
        val rim = metal(l, 3.6)
        return IntroPalette(
            dark = false,
            background = background,
            fog = fog,
            fogAlpha = fogAlpha,
            vignette = ColorMath.fromHsl(h, 0.3f, 0.55f),
            vignetteAlpha = 0.16f,
            core = ColorMath.fromHsl(h, 0.9f, 0.985f),
            glow = ColorMath.fromHsl(h, 0.75f, 0.8f),
            streak = ColorMath.fromHsl(h, 0.8f, 0.7f),
            rimLight = metal(0.62f, MIN_CONTRAST),
            rim = rim,
            rimDeep = metal(0.3f, 5.5),
            bodyTop = ColorMath.fromHsl(h, 0.16f, 0.24f),
            bodyBottom = ColorMath.fromHsl(h, 0.2f, 0.11f),
            bevelLight = ColorMath.fromHsl(h, 0.5f, 0.62f),
            bevelDark = BLACK,
            depth = ColorMath.fromHsl(h, 0.35f, 0.16f),
            sheen = ColorMath.fromHsl(h, 0.9f, 0.93f),
            particle = metal(l, 3.2),
            particleLight = ColorMath.fromHsl(h, 0.6f, 0.94f),
            subtitle = ColorMath.ensureContrast(ColorMath.ensureContrast(ColorMath.fromHsl(h, 0.14f, 0.32f), background, 4.5), peak, 4.5),
            subtitleAccent = rim,
        )
    }
}
