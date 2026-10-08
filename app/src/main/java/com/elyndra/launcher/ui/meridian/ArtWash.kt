package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.data.ColorMath
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Los colores que se sacan del arte enfocado (una copia de ~24 × 24 px).
 *
 * [dominant] y [secondary] son null si el arte es gris; [edges] son los
 * colores de la franja izquierda (la que cae detrás de la rueda), de arriba
 * abajo; [leftLight]/[leftDark] el píxel más claro y el más oscuro de esa
 * franja (los peores casos para el texto) y [topLight] el más claro de arriba
 * a la derecha (detrás de la barra).
 */
class ArtColors(
    val dominant: Int?,
    val secondary: Int?,
    val edges: IntArray,
    val leftLight: Int,
    val leftDark: Int,
    val topLight: Int,
)

/**
 * Lo que pinta el fondo adaptable para un arte: el tono del velo, los tonos
 * de su modulación vertical (como luz que sale del arte por el canto), cuánto
 * cubre el velo ([coverage], dentro de la máscara de [MeridianScrims]), y la
 * tinta del velo de arriba ([top], con su opacidad [topAlpha]). [adaptive]
 * es false si se usa el velo neutro del tema.
 */
class WashPlan(
    val tone: Int,
    val edges: IntArray,
    val coverage: Float,
    val top: Int,
    val topAlpha: Float,
    val adaptive: Boolean,
)

/**
 * Del arte al fondo de la rueda, en Kotlin puro (se prueba en la JVM).
 *
 * El dominante no es la media (la media de una carátula colorida es un
 * marrón): se agrupan los píxeles por tono pesando cada uno por su saturación
 * y por lo cerca que está de los medios tonos, y gana la cubeta más pesada
 * (con sus vecinas). El velo toma su tono: en oscuro, hondo y algo
 * desaturado hacia la tinta (luminosidad 8–16 %); en claro, un pastel teñido
 * (80–88 %, saturación 30–45 %), nunca casi blanco. Luego se calcula lo que
 * tiene que cubrir para que los nombres lleguen al 4,5:1 sobre el peor píxel
 * de la franja; si ni cubriendo del todo llegaría, el velo neutro del tema.
 */
object ArtWash {

    /** Lado de la muestra que se analiza (px). */
    const val SAMPLE = 24

    /** Ancho de la copia para el fondo ambiental (px): estirada, es un desenfoque gratis. */
    const val AMBIENT = 48

    const val EDGE_SAMPLES = 5

    /** La franja del arte que cae detrás de la rueda: el arte mide 1,35 ventanas y la rueda ~0,42 (0,42 / 1,35). */
    const val LEFT_FRACTION = 0.32f

    /** La zona de la barra: arriba (18 %) y de la rueda a lo que se ve del arte (1 / 1,35). */
    const val TOP_FRACTION = 0.18f
    const val TOP_RIGHT_END = 0.74f

    private const val BUCKETS = 24
    private const val MIN_SATURATION = 0.16f
    private const val MIN_COLORFUL = 0.05f

    /** El secundario: otra cubeta a 30° o más del dominante. */
    private const val SECONDARY_MIN_HUE = 30f

    /** Luminosidad y saturación del tono del velo por tema. */
    const val DARK_L_MIN = 0.08f
    const val DARK_L_MAX = 0.16f
    const val DARK_S_MIN = 0.20f
    const val DARK_S_MAX = 0.62f
    const val LIGHT_L_MIN = 0.80f
    const val LIGHT_L_MAX = 0.88f
    const val LIGHT_S_MIN = 0.30f
    const val LIGHT_S_MAX = 0.45f

    /** Lo menos que cubre el velo (el arte desenfocado se ve detrás) y lo más antes de pasar al neutro. */
    const val MIN_COVERAGE = 0.58f
    const val MAX_COVERAGE = 1f

    /** Opacidad del texto de las vecinas (la de la fila ±1), el caso más justo. */
    const val NEIGHBOR_TEXT_ALPHA = 0.85f

    /** El velo de arriba: tinta honda del arte, para el texto blanco de la barra (la ruta va al 78 %). */
    const val TOP_L = 0.10f
    const val TOP_TEXT_ALPHA = 0.78f
    const val TOP_MIN_ALPHA = 0.34f
    const val TOP_MAX_ALPHA = 0.82f

    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * Analiza [pixels] (ARGB, [width] × [height], ya reducido a ~[SAMPLE] px).
     * Null si no hay nada que leer (vacío o transparente).
     */
    fun extract(pixels: IntArray, width: Int, height: Int): ArtColors? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val weight = FloatArray(BUCKETS)
        val r = FloatArray(BUCKETS)
        val g = FloatArray(BUCKETS)
        val b = FloatArray(BUCKETS)
        val hsl = FloatArray(3)
        var counted = 0
        var colorful = 0f
        val leftEnd = max(1, (width * LEFT_FRACTION).toInt())
        val topEnd = max(1, (height * TOP_FRACTION).toInt())
        val trStart = min(width - 1, leftEnd)
        val trEnd = max(trStart + 1, (width * TOP_RIGHT_END).toInt())
        var leftLight = 0
        var leftLightL = -1.0
        var leftDark = 0
        var leftDarkL = 2.0
        var topLight = 0
        var topLightL = -1.0
        val bandR = FloatArray(EDGE_SAMPLES)
        val bandG = FloatArray(EDGE_SAMPLES)
        val bandB = FloatArray(EDGE_SAMPLES)
        val bandN = IntArray(EDGE_SAMPLES)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                if ((p ushr 24) < 128) continue
                counted++
                val pr = (p shr 16) and 0xFF
                val pg = (p shr 8) and 0xFF
                val pb = p and 0xFF
                val solid = p or (0xFF shl 24)
                if (x < leftEnd) {
                    val lum = ColorMath.luminance(solid)
                    if (lum > leftLightL) { leftLightL = lum; leftLight = solid }
                    if (lum < leftDarkL) { leftDarkL = lum; leftDark = solid }
                    val band = (y * EDGE_SAMPLES / height).coerceIn(0, EDGE_SAMPLES - 1)
                    bandR[band] += pr.toFloat()
                    bandG[band] += pg.toFloat()
                    bandB[band] += pb.toFloat()
                    bandN[band]++
                }
                if (y < topEnd && x in trStart until trEnd) {
                    val lum = ColorMath.luminance(solid)
                    if (lum > topLightL) { topLightL = lum; topLight = solid }
                }
                ColorMath.toHsl(solid, hsl)
                val s = hsl[1]
                val l = hsl[2]
                if (s < MIN_SATURATION || l < 0.06f || l > 0.96f) continue
                val w = s * (1f - abs(l - 0.5f) * 1.4f).coerceAtLeast(0.1f)
                colorful += w
                val k = ((hsl[0] / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
                weight[k] += w
                r[k] += pr * w
                g[k] += pg * w
                b[k] += pb * w
            }
        }
        if (counted == 0) return null
        val edges = IntArray(EDGE_SAMPLES) { i ->
            val n = bandN[i]
            if (n == 0) leftDark else ColorMath.argb(255, (bandR[i] / n).toInt(), (bandG[i] / n).toInt(), (bandB[i] / n).toInt())
        }
        if (leftLightL < 0) {
            leftLight = edges[0]
            leftDark = edges[0]
        }
        if (topLightL < 0) topLight = leftLight
        val gray = colorful / counted < MIN_COLORFUL
        val best = if (gray) -1 else heaviest(weight, -1)
        val dominant = if (best < 0 || weight[best] <= 0f) null else bucketColor(best, weight, r, g, b)
        val second = if (dominant == null) -1 else heaviest(weight, best)
        val secondary = if (second < 0 || weight[second] <= 0f) null else bucketColor(second, weight, r, g, b)
        return ArtColors(dominant, secondary, edges, leftLight, leftDark, topLight)
    }

    /** La cubeta más pesada (con media de sus vecinas); con [avoid] ≥ 0, a [SECONDARY_MIN_HUE] o más de ella. */
    private fun heaviest(weight: FloatArray, avoid: Int): Int {
        var best = -1
        var bestW = 0f
        for (k in 0 until BUCKETS) {
            if (avoid >= 0) {
                val d = abs(k - avoid).let { min(it, BUCKETS - it) } * (360f / BUCKETS)
                if (d < SECONDARY_MIN_HUE) continue
            }
            val w = weight[k] + 0.5f * (weight[(k + 1) % BUCKETS] + weight[(k + BUCKETS - 1) % BUCKETS])
            if (w > bestW) {
                bestW = w
                best = k
            }
        }
        return best
    }

    private fun bucketColor(k: Int, weight: FloatArray, r: FloatArray, g: FloatArray, b: FloatArray): Int {
        val w = weight[k]
        return ColorMath.argb(255, (r[k] / w).toInt(), (g[k] / w).toInt(), (b[k] / w).toInt())
    }

    /**
     * El tono del velo para [color] en el tema [dark]. Si [color] es gris (sin
     * tono propio), toma el tono de [hueFrom].
     */
    fun tone(color: Int, dark: Boolean, hueFrom: Int = color): Int {
        val hsl = ColorMath.toHsl(color)
        val hue = if (hsl[1] < 0.08f) ColorMath.toHsl(hueFrom)[0] else hsl[0]
        val sat = if (hsl[1] < 0.08f) ColorMath.toHsl(hueFrom)[1] else hsl[1]
        return if (dark) {
            ColorMath.fromHsl(hue, (sat * 0.85f).coerceIn(DARK_S_MIN, DARK_S_MAX), (0.09f + hsl[2] * 0.08f).coerceIn(DARK_L_MIN, DARK_L_MAX))
        } else {
            ColorMath.fromHsl(hue, (sat * 0.55f).coerceIn(LIGHT_S_MIN, LIGHT_S_MAX), (0.80f + hsl[2] * 0.08f).coerceIn(LIGHT_L_MIN, LIGHT_L_MAX))
        }
    }

    /** La tinta honda del arte para el velo de arriba (texto blanco), en los dos temas. */
    fun deep(color: Int): Int {
        val hsl = ColorMath.toHsl(color)
        return ColorMath.fromHsl(hsl[0], (hsl[1] * 0.7f).coerceIn(0.12f, 0.5f), TOP_L)
    }

    /** Contraste del texto del tema (con opacidad [textAlpha]) sobre [bg]. */
    fun textContrast(bg: Int, dark: Boolean, textAlpha: Float = 1f): Double = MeridianScrims.textContrast(bg, dark, textAlpha)

    /**
     * Lo menos que tiene que cubrir [tint] sobre [worst] (0…1) para que el
     * texto del tema, con opacidad [textAlpha], llegue a [min]. 2 si no llega
     * ni cubriendo del todo.
     */
    fun coverageFor(tint: Int, worst: Int, dark: Boolean, textAlpha: Float = NEIGHBOR_TEXT_ALPHA, min: Double = MeridianScrims.MIN_TEXT): Float {
        var c = 0f
        while (c <= 1f + 1e-4f) {
            val bg = ColorMath.over(ColorMath.withAlpha(tint, c), ColorMath.opaque(worst))
            if (textContrast(bg, dark, textAlpha) >= min) return c
            c += 0.01f
        }
        return 2f
    }

    /** Lo menos que oscurece la tinta [deep] sobre [worst] para el texto blanco de la barra. */
    fun topAlphaFor(deep: Int, worst: Int): Float {
        var a = 0f
        while (a <= 1f + 1e-4f) {
            val bg = ColorMath.over(ColorMath.withAlpha(deep, a), ColorMath.opaque(worst))
            val text = ColorMath.over(ColorMath.withAlpha(WHITE, TOP_TEXT_ALPHA), bg)
            if (ColorMath.contrast(text, bg) >= MeridianScrims.MIN_TEXT) return a.coerceIn(TOP_MIN_ALPHA, TOP_MAX_ALPHA)
            a += 0.01f
        }
        return TOP_MAX_ALPHA
    }

    /**
     * El plan del fondo para [colors] (null = sin arte o sin leer) en el tema
     * [dark]. [fallback] es el primario de la paleta de firma (sin arte o arte
     * gris); [enabled] = "Color de fondo adaptable".
     *
     * La cobertura cuenta con que, a la altura del texto, la máscara del velo
     * vale como poco [MeridianScrims.AXIS_ALPHA]: lo que cubre de verdad es
     * esa máscara por [WashPlan.coverage].
     */
    fun plan(colors: ArtColors?, dark: Boolean, fallback: Int, enabled: Boolean = true): WashPlan {
        val worstText = if (colors == null) (if (dark) WHITE else 0xFF000000.toInt()) else if (dark) colors.leftLight else colors.leftDark
        val topSource = colors?.dominant ?: fallback
        val top = deep(topSource)
        val topAlpha = topAlphaFor(top, colors?.topLight ?: WHITE)
        if (!enabled) return neutral(dark, top, topAlpha)
        val base = colors?.dominant ?: fallback
        val tone = tone(base, dark)
        val edges = IntArray(EDGE_SAMPLES) { i ->
            val e = colors?.edges?.getOrNull(i)
            if (e == null) tone else tone(e, dark, hueFrom = colors.secondary ?: base)
        }
        var needed = coverageFor(tone, worstText, dark)
        for (e in edges) needed = max(needed, coverageFor(e, worstText, dark))
        val coverage = needed / MeridianScrims.AXIS_ALPHA
        if (coverage > MAX_COVERAGE) return neutral(dark, top, topAlpha)
        return WashPlan(tone, edges, coverage.coerceAtLeast(MIN_COVERAGE), top, topAlpha, adaptive = true)
    }

    /** El velo neutro del tema (perla o tinta), el del primer pase: cubre del todo dentro de su máscara. */
    fun neutral(dark: Boolean, top: Int, topAlpha: Float): WashPlan {
        val tint = MeridianScrims.tint(dark)
        return WashPlan(tint, IntArray(EDGE_SAMPLES) { tint }, MAX_COVERAGE, top, topAlpha, adaptive = false)
    }

    /** Lo que se ve detrás del texto de la rueda en el eje (el caso más justo de la máscara) sobre [art]. */
    fun behindText(plan: WashPlan, art: Int, tone: Int = plan.tone): Int =
        ColorMath.over(ColorMath.withAlpha(tone, MeridianScrims.AXIS_ALPHA * plan.coverage), ColorMath.opaque(art))

    /* ── Las tarjetas claras sobre el velo ── */

    /** Fuerza base del filo y la sombra de contacto de una tarjeta (0…1); sube hasta 1 cuando su canto se confunde con el velo. */
    const val TILE_EDGE_BASE = 0.35f

    /** Contraste canto/velo por debajo del cual la tarjeta empieza a perderse, y donde ya se lee sola. */
    const val TILE_EDGE_LOST = 1.25
    const val TILE_EDGE_CLEAR = 2.2

    /**
     * Fuerza del filo (1 dp) y de la sombra de contacto de una tarjeta cuyo
     * canto tiene la luminancia [edgeLum] (null = sin leer) sobre un velo de
     * luminancia [washLum].
     */
    fun tileEdgeStrength(edgeLum: Double?, washLum: Double): Float {
        if (edgeLum == null) return 0.6f
        val ratio = (max(edgeLum, washLum) + 0.05) / (min(edgeLum, washLum) + 0.05)
        val t = WheelTransform.smoothstep(TILE_EDGE_LOST.toFloat(), TILE_EDGE_CLEAR.toFloat(), ratio.toFloat())
        return 1f + (TILE_EDGE_BASE - 1f) * t
    }

    /** Luminancia media del canto (2 px) de [pixels] ([width] × [height]); null si es todo transparente. */
    fun edgeLuminance(pixels: IntArray, width: Int, height: Int): Double? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        var sum = 0.0
        var n = 0
        val ring = min(2, min(width, height) / 2).coerceAtLeast(1)
        for (y in 0 until height) for (x in 0 until width) {
            if (x >= ring && x < width - ring && y >= ring && y < height - ring) continue
            val p = pixels[y * width + x]
            if ((p ushr 24) < 128) continue
            sum += ColorMath.luminance(p or (0xFF shl 24))
            n++
        }
        return if (n == 0) null else sum / n
    }

    /* ── El dial sobre el velo ── */

    /** Grafito de las marcas en claro; luz en oscuro (que suma). */
    const val TICK_LIGHT = 0xFF2B2D3A.toInt()
    const val TICK_DARK = 0xFFE9ECF5.toInt()
    const val TICK_ALPHA_LIGHT = 0.78f
    const val TICK_ALPHA_DARK = 0.82f

    fun tickColor(dark: Boolean): Int = if (dark) TICK_DARK else TICK_LIGHT

    fun tickAlpha(dark: Boolean): Float = if (dark) TICK_ALPHA_DARK else TICK_ALPHA_LIGHT

    /** Contraste de una marca del dial sobre [bg]. */
    fun tickContrast(bg: Int, dark: Boolean): Double {
        val tick = ColorMath.over(ColorMath.withAlpha(tickColor(dark), tickAlpha(dark)), bg)
        return ColorMath.contrast(tick, bg)
    }
}
