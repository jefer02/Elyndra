package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.data.Palettes
import com.elyndra.launcher.ui.theme.GlassTokens

/**
 * Las medidas del cristal curvo de Meridian (Biblioteca y Carpeta usan el
 * mismo, [WheelGlassLayer]) que dependen del tamaño de la ventana y del
 * dispositivo, sin Compose. Todo en dp; los valores salen de [GlassTokens].
 *
 * El desenfoque y el halo crecen con el ancho de la ventana, con topes para
 * que un móvil pequeño no quede borroso del todo ni una tele lechosa.
 *
 * Y el canto nunca llega a ser recto: si la cabecera pedía llegar más allá
 * del punto más a la derecha del arco ([MeridianScrims.glassArc] con
 * `reach`), el radio se iba al infinito y el canto era una raya vertical. Se
 * recoge siempre al menos [minSag] en los filos de la pantalla.
 */
object GlassPanelLayout {

    /**
     * Lo que se recoge el canto, como poco, en los filos de arriba y abajo de
     * la pantalla: una fracción de medio alto, entre dos topes. Con un 6 % la
     * curva se sigue viendo curva en una tableta (~24 dp en 800 dp de alto) y
     * en un móvil (~12 dp), y deja abrirse el arco lo que pide una cabecera normal.
     */
    const val MIN_SAG_FRACTION = 0.06f
    const val MIN_SAG_MIN = 12f
    const val MIN_SAG_MAX = 48f

    /**
     * Cuánto más ancho que el cristal se pinta el arte desenfocado, en
     * radios de desenfoque: así el canto derecho del desenfoque no estira el
     * último píxel (queda fuera del arco, recortado).
     */
    const val BLUR_BLEED = 3f

    /** Desde qué versión de Android hay RenderEffect (Android 12). */
    const val RENDER_EFFECT_API = 31

    private fun scale(windowWidth: Float): Float = if (windowWidth <= 0f) 1f else windowWidth / GlassTokens.REFERENCE_WIDTH

    /**
     * El desenfoque del arte en una ventana de [windowWidth] dp con el ajuste
     * "Desenfoque" en [userBlur] (0–40 px; por omisión el de siempre): el
     * ajuste lo escala desde el valor por defecto, así que quien no lo ha
     * tocado tiene el cristal nuevo y quien lo subió o lo bajó conserva su
     * proporción.
     */
    fun blurDp(windowWidth: Float, userBlur: Int = Palettes.DEFAULT_BLUR): Float =
        (GlassTokens.BLUR_DP * scale(windowWidth)).coerceIn(GlassTokens.BLUR_MIN, GlassTokens.BLUR_MAX) * userScale(userBlur)

    /** Lo que el ajuste "Desenfoque" multiplica el del cristal: 1 en su valor por defecto. */
    fun userScale(userBlur: Int): Float = (userBlur.toFloat() / Palettes.DEFAULT_BLUR).coerceIn(0f, GlassTokens.USER_BLUR_MAX)

    /** Si con ese desenfoque el cristal desenfoca (si no, se pinta como sin RenderEffect). */
    fun blurs(blurDp: Float): Boolean = blurDp >= GlassTokens.BLUR_OFF

    /** El halo de luz por dentro del canto en una ventana de [windowWidth] dp. El filo (1 dp) no crece: es una línea de luz. */
    fun glowDp(windowWidth: Float): Float = (GlassTokens.GLOW_DP * scale(windowWidth)).coerceIn(GlassTokens.GLOW_MIN, GlassTokens.GLOW_MAX)

    /** Lo que el arte desenfocado sobresale del cristal por la derecha (dp). */
    fun blurBleed(windowWidth: Float, userBlur: Int = Palettes.DEFAULT_BLUR): Float = blurDp(windowWidth, userBlur) * BLUR_BLEED

    /** Lo menos que se recoge el canto en los filos, para un arco de medio alto [half] (dp). */
    fun minSag(half: Float): Float = (half * MIN_SAG_FRACTION).coerceIn(MIN_SAG_MIN, MIN_SAG_MAX).coerceAtMost(half)

    /** El radio más abierto que puede tener el arco de medio alto [half]: el que en los filos se recoge [minSag]. */
    fun maxRadius(half: Float): Float =
        if (half <= 0f) Float.POSITIVE_INFINITY else GlassArc.radiusThrough(half, minSag(half))

    /** Cómo se pinta la lámina detrás de la rueda. */
    enum class Path {
        /** Arte de verdad desenfocado con RenderEffect (Android 12+). */
        Blur,

        /** Sin RenderEffect (Android 8–11) o con "Desenfoque" a 0: el arte nítido con más tinte y velo. */
        Clear,

        /** "Fondo de la rueda: difuminado" elegido en Ajustes: el velo de siempre, no el cristal. */
        Wash,
    }

    /** El cristal que toca en un dispositivo: el camino, por qué, y sus valores (dp y alfas). */
    data class Spec(
        val path: Path,
        val reason: String,
        val blurDp: Float,
        val tint: Float,
        val frost: Float,
        val rim: Float,
        val glow: Float,
    )

    /**
     * El cristal para una ventana de [windowWidth] dp en Android [sdk], con el
     * fondo de la rueda en cristal ([glass]) o difuminado, en tema oscuro
     * ([dark]) o claro y el ajuste "Desenfoque" en [userBlur].
     *
     * No mira la RAM ni la calidad de Masha: antes la calidad ligera
     * (cualquier móvil por debajo de 5,5 GB, como un Tecno Spark) se quedaba
     * sin desenfoque, así que el mismo APK se veía distinto según el móvil.
     * La capa desenfocada mide lo que el cristal (no la pantalla), y eso lo
     * aguanta cualquier GPU con RenderEffect.
     */
    fun resolve(windowWidth: Float, sdk: Int, glass: Boolean, dark: Boolean, userBlur: Int): Spec {
        val glow = glowDp(windowWidth)
        if (!glass) return Spec(Path.Wash, "wheel backdrop = blur wash (Settings)", 0f, 0f, 0f, 0f, 0f)
        val blur = blurDp(windowWidth, userBlur)
        val clear = when {
            sdk < RENDER_EFFECT_API -> "API $sdk < $RENDER_EFFECT_API: no RenderEffect"
            !blurs(blur) -> "blur setting $userBlur: no blur"
            else -> null
        }
        return if (clear == null) {
            Spec(Path.Blur, "RenderEffect, API $sdk", blur, GlassTokens.TINT_ALPHA, if (dark) GlassTokens.FROST_ALPHA_DARK else GlassTokens.FROST_ALPHA_LIGHT, GlassTokens.RIM_ALPHA, glow)
        } else {
            Spec(Path.Clear, clear, 0f, GlassTokens.FALLBACK_TINT_ALPHA, if (dark) GlassTokens.FALLBACK_FROST_DARK else GlassTokens.FALLBACK_FROST_LIGHT, GlassTokens.RIM_ALPHA, glow)
        }
    }
}
