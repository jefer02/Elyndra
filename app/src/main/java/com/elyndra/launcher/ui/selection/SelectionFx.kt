package com.elyndra.launcher.ui.selection

import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.data.ColorMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/* ─────────────────────────────────────────────────────────────
   La selección del carrusel, sin Compose: tiempos, fases, cuántas
   partículas, la paleta clara/oscura y la simulación del polvo
   estelar. Todo son números para poder probarlo en la JVM; el
   dibujo vive en SelectionFrame.kt y Stardust.kt.
   ───────────────────────────────────────────────────────────── */

object SelectionFx {

    /**
     * Perímetro (dp) de una card típica: la de icono de un móvil en vertical
     * o una carátula 2:3 de ~150 dp de alto. Es la vara de medir del barrido y
     * del número de partículas.
     */
    const val REF_PERIMETER_DP = 520f

    /** Vuelta del brillo alrededor de la card típica. */
    const val SHEEN_TURN_SECONDS = 4f

    /** Velocidad del brillo: la misma en dp/s para cualquier card. */
    const val SHEEN_SPEED_DP = REF_PERIMETER_DP / SHEEN_TURN_SECONDS

    /** Topes de la vuelta: una miniatura no zumba y una tablet no se arrastra. */
    const val SHEEN_MIN_SECONDS = 2.4f
    const val SHEEN_MAX_SECONDS = 6.5f

    /**
     * Segundos por vuelta para un contorno de [perimeterDp]: a velocidad
     * constante, así una carátula grande y un icono pequeño se ven igual de rápidos.
     */
    fun sheenPeriodSeconds(perimeterDp: Float): Float =
        (perimeterDp / SHEEN_SPEED_DP).coerceIn(SHEEN_MIN_SECONDS, SHEEN_MAX_SECONDS)

    /** Dónde va el brillo (0…1 del contorno) a los [seconds] de encenderse. */
    fun sheenFraction(seconds: Float, perimeterDp: Float): Float {
        val turns = seconds / sheenPeriodSeconds(perimeterDp)
        return turns - turns.toInt()
    }

    /** Largo de la estela del brillo: fijo en dp, sin pasar de una fracción del contorno. */
    fun sheenLengthDp(perimeterDp: Float): Float = minOf(64f, perimeterDp * 0.2f)

    /* ── Respiración y encendido ── */

    const val BREATH_SECONDS = 3f
    const val BREATH_DEPTH = 0.1f

    /** Multiplicador del alfa del halo: 1 ± 10 % en un ciclo de ~3 s. */
    fun breath(seconds: Float): Float = 1f + BREATH_DEPTH * sin(seconds / BREATH_SECONDS * 2f * PI.toFloat())

    /** Lo que tarda el marco en dibujarse al llegar la selección. */
    const val IGNITE_MS = 280
    const val FADE_IN_MS = 200
    /** Al irse la selección: más corto que la entrada, lo que se va no se hace esperar. */
    const val FADE_OUT_MS = 140

    /** Fracción del contorno ya dibujada para un progreso de encendido (ya suavizado) [p]. */
    fun igniteRim(p: Float): Float = p.coerceIn(0f, 1f)

    /** El halo arranca cuando el filo ya va por un tercio y nunca lo adelanta: la luz sigue al trazo. */
    fun igniteBloom(p: Float): Float = smoothstep(0.35f, 1f, p) * p.coerceIn(0f, 1f)

    /* ── Partículas ── */

    /** Tope duro de partículas vivas a la vez. */
    const val MAX_PARTICLES = 32
    private const val HIGH_BASE = 26f
    private const val HIGH_MIN = 8
    private const val LITE_BASE = 13f
    private const val LITE_MAX = 16
    private const val LITE_MIN = 5

    /**
     * Cuántas partículas para un contorno de [perimeterDp]: crecen con la raíz
     * del perímetro (suave) y nunca pasan del tope. Una card típica queda en
     * 22–30 en calidad alta y 11–15 en la ligera; una miniatura, menos.
     */
    fun particleCount(perimeterDp: Float, lite: Boolean): Int {
        val ratio = (perimeterDp / REF_PERIMETER_DP).coerceIn(0.05f, 4f)
        val k = sqrt(ratio)
        return if (lite) (LITE_BASE * k).roundToInt().coerceIn(LITE_MIN, LITE_MAX)
        else (HIGH_BASE * k).roundToInt().coerceIn(HIGH_MIN, MAX_PARTICLES)
    }

    const val MIN_LIFE = 1.2f
    const val MAX_LIFE = 2.4f
    /** Diámetro del núcleo, en dp; el tamaño no cambia con la card. */
    const val MIN_SIZE_DP = 1.1f
    const val MAX_SIZE_DP = 3f
    /** Probabilidad de que una partícula nazca destello de cuatro puntas. */
    const val GLINT_CHANCE = 0.1f

    /** Variantes de color de las partículas (hueco del atlas). */
    const val VARIANTS = 4

    /** Fundido de entrada y salida de una partícula según su vida [u] 0…1. */
    fun envelope(u: Float): Float = smoothstep(0f, 0.15f, u) * (1f - smoothstep(0.7f, 1f, u))

    /** El destello brilla de golpe a media vida. */
    fun glintEnvelope(u: Float): Float = sin(u.coerceIn(0f, 1f) * PI.toFloat()).pow(3)

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/* ── Paleta ─────────────────────────────────────────────────── */

/**
 * Los colores (ARGB) del marco y del polvo para un color de selección y un
 * tema. En oscuro la luz se suma (BlendMode.Plus); en claro se mezcla
 * normal con un tono más hondo y saturado, que llega al 3:1 frente al estante.
 */
class SelectionPalette(
    val dark: Boolean,
    /** Filo: el tono principal. */
    val rim: Int,
    /** El otro extremo del degradado del filo: más claro. */
    val rimLight: Int,
    /** Filo interior que separa el marco de una carátula del mismo brillo (con alfa). */
    val keyline: Int,
    val bloom: Int,
    val underglow: Int,
    /** El brillo que recorre el contorno y el reflejo de arriba a la izquierda. */
    val sheen: Int,
    /** Núcleo champán-blanco de las partículas. */
    val core: Int,
    /** [SelectionFx.VARIANTS] tonos de partícula, con algo de variación. */
    val particles: IntArray,
    /** Alfa de las tres capas del halo, de dentro a fuera. */
    val bloomAlphas: FloatArray,
    val underglowAlpha: Float,
    val keylineAlpha: Float,
)

object SelectionPalettes {

    /** Filo, halo y partículas frente al estante (WCAG, piezas gráficas). */
    const val MIN_CONTRAST = 3.0

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** Los fondos del estante en cada tema: el papel y la superficie. */
    fun shelves(dark: Boolean): IntArray {
        val n = if (dark) BrandTokens.DARK else BrandTokens.LIGHT
        return intArrayOf(n.paper, n.surface)
    }

    fun derive(base: Int, dark: Boolean): SelectionPalette =
        if (dark) dark(ColorMath.opaque(base)) else light(ColorMath.opaque(base))

    private fun legible(c: Int, dark: Boolean, ratio: Double = MIN_CONTRAST): Int {
        var out = c
        for (bg in shelves(dark)) out = ColorMath.ensureContrast(out, bg, ratio)
        return out
    }

    private fun dark(base: Int): SelectionPalette {
        val vivid = legible(base, dark = true, ratio = 4.0)
        val (h, _, l) = ColorMath.toHsl(vivid)
        val s = saturation(vivid)
        val champagneWhite = ColorMath.mix(BrandTokens.CHAMPAGNE, WHITE, 0.55f)
        return SelectionPalette(
            dark = true,
            rim = vivid,
            rimLight = ColorMath.mix(vivid, WHITE, 0.55f),
            keyline = BLACK,
            bloom = vivid,
            underglow = vivid,
            sheen = ColorMath.mix(BrandTokens.CHAMPAGNE, WHITE, 0.6f),
            core = champagneWhite,
            particles = IntArray(SelectionFx.VARIANTS) { i ->
                val dh = HUE_SHIFTS[i]
                val dl = LIGHT_SHIFTS[i]
                legible(ColorMath.fromHsl(h + dh, s, (l + dl).coerceIn(0.35f, 0.92f)), dark = true)
            },
            bloomAlphas = floatArrayOf(0.42f, 0.2f, 0.09f),
            underglowAlpha = 0.34f,
            keylineAlpha = 0.45f,
        )
    }

    /**
     * Claro: sobre el perla la luz no suma, así que el marco se vuelve metal de
     * color, algo más saturado y hondo que la base, oscurecido lo justo para el 3:1.
     */
    private fun light(base: Int): SelectionPalette {
        val (h, _, l) = ColorMath.toHsl(base)
        val s = saturation(base)
        // Un color casi neutro (el blanco de la paleta) se queda neutro: grafito, no azul.
        val sat = if (s < GRAY_CHROMA) s else (s + 0.15f).coerceAtMost(1f)
        val deep = legible(ColorMath.fromHsl(h, sat, l.coerceAtMost(0.5f)), dark = false, ratio = 3.4)
        val dl = ColorMath.toHsl(deep)[2]
        return SelectionPalette(
            dark = false,
            rim = deep,
            rimLight = legible(ColorMath.fromHsl(h, sat, (dl + 0.14f).coerceAtMost(0.8f)), dark = false),
            keyline = WHITE,
            bloom = deep,
            underglow = deep,
            sheen = ColorMath.mix(BrandTokens.CHAMPAGNE, WHITE, 0.75f),
            core = ColorMath.mix(deep, WHITE, 0.45f),
            particles = IntArray(SelectionFx.VARIANTS) { i ->
                legible(ColorMath.fromHsl(h + HUE_SHIFTS[i], sat, (dl + LIGHT_SHIFTS[i] * 0.6f).coerceIn(0.12f, 0.6f)), dark = false)
            },
            bloomAlphas = floatArrayOf(0.3f, 0.14f, 0.06f),
            underglowAlpha = 0.2f,
            keylineAlpha = 0.6f,
        )
    }

    /**
     * Saturación HSL, salvo en los casi neutros: un blanco frío tiene una
     * saturación HSL altísima con apenas color, y oscurecerlo daría un azul.
     */
    private fun saturation(c: Int): Float {
        val r = ColorMath.red(c)
        val g = ColorMath.green(c)
        val b = ColorMath.blue(c)
        val chroma = (maxOf(r, g, b) - minOf(r, g, b)) / 255f
        return if (chroma < GRAY_CHROMA) chroma else ColorMath.toHsl(c)[1]
    }

    private const val GRAY_CHROMA = 0.2f

    private val HUE_SHIFTS = floatArrayOf(0f, -12f, 10f, 4f)
    private val LIGHT_SHIFTS = floatArrayOf(0f, 0.06f, -0.04f, 0.12f)
}

/* ── Simulación del polvo estelar ───────────────────────────── */

/**
 * El contorno de la card visto por la simulación: el punto a la fracción
 * [fraction] (0…1) del perímetro y su normal hacia fuera, en px, escritos en
 * [out] como x, y, nx, ny. Sin objetos por llamada.
 */
fun interface PerimeterSampler {
    fun sample(fraction: Float, out: FloatArray)
}

/**
 * Las partículas de la selección en arrays planos (sin un objeto por
 * partícula, sin basura por fotograma). Cada una nace en un punto del
 * contorno y se aleja despacio hacia fuera y hacia arriba con un leve rizo;
 * al morir renace en otro punto. Las posiciones salen de la edad con una
 * fórmula, así que el paso solo suma tiempo.
 */
class Stardust(val capacity: Int = SelectionFx.MAX_PARTICLES) {
    private val x0 = FloatArray(capacity)
    private val y0 = FloatArray(capacity)
    private val nx = FloatArray(capacity)
    private val ny = FloatArray(capacity)
    private val drift = FloatArray(capacity)
    private val rise = FloatArray(capacity)
    private val curl = FloatArray(capacity)
    private val curlRate = FloatArray(capacity)
    private val phase = FloatArray(capacity)
    private val twinkleRate = FloatArray(capacity)
    private val strength = FloatArray(capacity)
    private val age = FloatArray(capacity)
    private val lifeOf = FloatArray(capacity)
    private val sizeDp = FloatArray(capacity)
    private val variantOf = IntArray(capacity)
    private val glintOf = BooleanArray(capacity)
    private val probe = FloatArray(4)

    var count = 0
        private set

    /**
     * Arranca [count] partículas. Con [warm] salen ya repartidas por su vida
     * (vistas previas estáticas); si no, entran poco a poco tras el encendido.
     */
    fun reset(count: Int, sampler: PerimeterSampler, density: Float, random: Random, warm: Boolean = false) {
        this.count = count.coerceIn(0, capacity)
        for (i in 0 until this.count) {
            spawn(i, sampler, density, random)
            age[i] = if (warm) random.nextFloat() * lifeOf[i] else -random.nextFloat() * lifeOf[i] * 0.8f
        }
    }

    fun step(dt: Float, sampler: PerimeterSampler, density: Float, random: Random) {
        for (i in 0 until count) {
            age[i] += dt
            if (age[i] >= lifeOf[i]) spawn(i, sampler, density, random)
        }
    }

    private fun spawn(i: Int, sampler: PerimeterSampler, density: Float, random: Random) {
        sampler.sample(random.nextFloat(), probe)
        val out = random.nextFloat() * 1.5f * density
        nx[i] = probe[2]
        ny[i] = probe[3]
        x0[i] = probe[0] + nx[i] * out
        y0[i] = probe[1] + ny[i] * out
        drift[i] = (2f + random.nextFloat() * 3f) * density
        rise[i] = (4f + random.nextFloat() * 5f) * density
        curl[i] = (1.5f + random.nextFloat() * 2.5f) * density
        curlRate[i] = 1.2f + random.nextFloat() * 1.2f
        phase[i] = random.nextFloat() * 2f * PI.toFloat()
        twinkleRate[i] = 5f + random.nextFloat() * 6f
        strength[i] = 0.8f + random.nextFloat() * 0.2f
        lifeOf[i] = SelectionFx.MIN_LIFE + random.nextFloat() * (SelectionFx.MAX_LIFE - SelectionFx.MIN_LIFE)
        // Más finas que gruesas: la mayoría cerca del mínimo.
        sizeDp[i] = SelectionFx.MIN_SIZE_DP + (SelectionFx.MAX_SIZE_DP - SelectionFx.MIN_SIZE_DP) * random.nextFloat().pow(1.2f)
        variantOf[i] = random.nextInt(SelectionFx.VARIANTS)
        glintOf[i] = random.nextFloat() < SelectionFx.GLINT_CHANCE
        if (glintOf[i]) sizeDp[i] = sizeDp[i].coerceAtLeast(2.2f)
        age[i] = 0f
    }

    fun x(i: Int): Float {
        val t = age[i].coerceAtLeast(0f)
        return x0[i] + nx[i] * drift[i] * t - ny[i] * curl[i] * sin(curlRate[i] * t + phase[i])
    }

    fun y(i: Int): Float {
        val t = age[i].coerceAtLeast(0f)
        return y0[i] + ny[i] * drift[i] * t + nx[i] * curl[i] * sin(curlRate[i] * t + phase[i]) - rise[i] * t
    }

    /** 0 mientras espera su turno; luego fundido, parpadeo suave y fundido. */
    fun alpha(i: Int): Float {
        val a = age[i]
        if (a <= 0f) return 0f
        val u = a / lifeOf[i]
        val shape = if (glintOf[i]) SelectionFx.glintEnvelope(u) else SelectionFx.envelope(u)
        val twinkle = 0.88f + 0.12f * cos(twinkleRate[i] * a + phase[i])
        return (strength[i] * shape * twinkle).coerceIn(0f, 1f)
    }

    fun sizeDp(i: Int): Float = sizeDp[i]
    fun life(i: Int): Float = lifeOf[i]
    fun age(i: Int): Float = age[i]
    fun variant(i: Int): Int = variantOf[i]
    fun isGlint(i: Int): Boolean = glintOf[i]
}
