package com.elyndra.launcher.core.device

/**
 * Qué tipo de equipo es este y qué perfil de coste le toca, sin Android: las
 * señales llegan medidas ([Signals]) y aquí solo se decide (se prueba en la JVM).
 *
 * **Por qué existe.** En un móvil modesto lo que cuesta no es una pantalla
 * concreta sino lo que nunca para: desenfoques a pantalla completa
 * (RenderEffect), relojes por fotograma y acercamientos lentos. El perfil
 * [PerformanceProfile.PERFORMANCE] quita *ese* coste y deja el diseño.
 *
 * **Por qué no basta con la RAM.** Un Tecno Spark 30C (Helio G81, 8 núcleos)
 * se vende con 4, 6 y 8 GB (más "RAM virtual", que no cuenta): con la regla
 * de antes (< 5,5 GB o < 6 núcleos) la versión de 8 GB pasaba por Calidad.
 * Ahora se miran varias señales, de la más fiable a la menos:
 * 1. `isLowRamDevice` → Rendimiento (lo dice el propio sistema).
 * 2. Clase de rendimiento multimedia ≥ Android 12 → Calidad.
 * 3. El chip ([SocCatalog]): gama alta conocida → Calidad; familia de entrada
 *    conocida (Helio, Unisoc, Snapdragon 4xx/6xx bajos, cajas de TV…) → Rendimiento.
 * 4. Menos de 4 GB → Rendimiento.
 * 5. CPU lenta (ningún núcleo llega a [SLOW_CPU_MHZ]) o menos de [MIN_CORES]
 *    núcleos → Rendimiento.
 * 6. Menos de 5,5 GB sin una CPU rápida que lo compense ([FAST_CPU_MHZ]) → Rendimiento.
 * 7. Si no, Calidad. Lo que no se pudo medir (0 o null) nunca baja de calidad:
 *    la regla es conservadora con los equipos potentes (un Snapdragon 8 Gen 3
 *    sale por el paso 3 aunque falle todo lo demás).
 *
 * Encima, en Auto: el ahorro de batería y los tirones medidos de verdad
 * ([JankFallback]) también pasan a Rendimiento, cada uno con su motivo.
 */
object PerformanceTier {

    /** Lo que se elige en Ajustes. [Auto], de partida, decide con las señales. */
    enum class Mode(val id: String) {
        Auto("auto"),
        Quality("quality"),
        Performance("performance"),
        ;

        companion object {
            /** Lo guardado, o [Auto] si no hay nada (o es un valor que ya no existe). */
            fun byId(id: String?): Mode = entries.firstOrNull { it.id == id } ?: Auto
        }
    }

    /** Por qué se eligió el perfil (se enseña en Ajustes bajo Auto). */
    enum class Reason {
        /** Equipo capaz: Calidad. */
        Capable,
        /** Chip de gama de entrada, CPU lenta o pocos núcleos. */
        BudgetChipset,
        /** Poca memoria (o `isLowRamDevice`). */
        LowMemory,
        /** Tirones medidos en la biblioteca ([JankFallback]). */
        Stutter,
        /** Ahorro de batería puesto. */
        BatterySaver,
        /** Elegido a mano en Ajustes (Calidad o Rendimiento). */
        Chosen,
    }

    /** Lo que se mide del equipo. Lo que no se pudo medir va a 0 / null (no cuenta). */
    data class Signals(
        val lowRamDevice: Boolean,
        val totalRamMb: Long,
        val cores: Int,
        /** `Build.VERSION.MEDIA_PERFORMANCE_CLASS` (0 = no declarada o Android < 12). */
        val mediaPerformanceClass: Int,
        val powerSave: Boolean,
        /** `Build.SOC_MANUFACTURER` / `Build.SOC_MODEL` (Android 12+). */
        val socManufacturer: String? = null,
        val socModel: String? = null,
        /** La frecuencia máxima del núcleo más rápido (MHz, de cpufreq). */
        val maxCpuMhz: Int = 0,
    )

    /** La decisión: el perfil y su motivo. */
    data class Decision(val profile: PerformanceProfile, val reason: Reason) {
        val lowEnd: Boolean get() = profile.lowEnd
    }

    /** Por debajo de esta RAM (MB), sin una CPU rápida, el equipo es modesto (el umbral de siempre). */
    const val LOW_RAM_MB = 5_632L

    /** Por debajo de esta RAM (MB) es modesto siempre. */
    const val VERY_LOW_RAM_MB = 4_096L

    /** Menos núcleos que esto: CPU de entrada. */
    const val MIN_CORES = 6

    /**
     * Si ningún núcleo llega aquí (MHz), la CPU es de entrada: Helio G81 2,0;
     * Unisoc T606 1,6; Snapdragon 680 2,4 (lo pilla el catálogo de chips). Un
     * gama media actual pasa de 2,4 y un gama alta de 3.
     */
    const val SLOW_CPU_MHZ = 2_200

    /** Una CPU que llega aquí (MHz) compensa ir algo justo de RAM. */
    const val FAST_CPU_MHZ = 2_600

    /** Clase de rendimiento multimedia que ya garantiza un equipo capaz (Android 12, API 31). */
    const val PERFORMANCE_CLASS_S = 31

    /** El motivo del equipo, sin contar ahorro de batería ni tirones (null = capaz). */
    fun deviceReason(s: Signals): Reason? {
        if (s.lowRamDevice) return Reason.LowMemory
        if (s.mediaPerformanceClass >= PERFORMANCE_CLASS_S) return null
        when (SocCatalog.classify(s.socManufacturer, s.socModel)) {
            SocCatalog.Tier.HighEnd -> return null
            SocCatalog.Tier.Budget -> return Reason.BudgetChipset
            SocCatalog.Tier.Unknown -> Unit
        }
        if (s.totalRamMb in 1 until VERY_LOW_RAM_MB) return Reason.LowMemory
        if (s.maxCpuMhz in 1 until SLOW_CPU_MHZ) return Reason.BudgetChipset
        if (s.cores in 1 until MIN_CORES) return Reason.BudgetChipset
        if (s.totalRamMb in 1 until LOW_RAM_MB && s.maxCpuMhz < FAST_CPU_MHZ) return Reason.LowMemory
        return null
    }

    /** ¿Equipo modesto? (sin ahorro de batería ni tirones). */
    fun isLowEnd(s: Signals): Boolean = deviceReason(s) != null

    /**
     * El perfil y su motivo con [mode] elegido en Ajustes, las señales [s] y
     * si en Auto ya se midieron tirones ([stutter], guardado).
     */
    fun decide(mode: Mode, s: Signals, stutter: Boolean = false): Decision = when (mode) {
        Mode.Quality -> Decision(PerformanceProfile.QUALITY, Reason.Chosen)
        Mode.Performance -> Decision(PerformanceProfile.PERFORMANCE, Reason.Chosen)
        Mode.Auto -> {
            val device = deviceReason(s)
            when {
                device != null -> Decision(PerformanceProfile.PERFORMANCE, device)
                stutter -> Decision(PerformanceProfile.PERFORMANCE, Reason.Stutter)
                s.powerSave -> Decision(PerformanceProfile.PERFORMANCE, Reason.BatterySaver)
                else -> Decision(PerformanceProfile.QUALITY, Reason.Capable)
            }
        }
    }

    /** Solo el perfil (ver [decide]). */
    fun resolve(mode: Mode, s: Signals, stutter: Boolean = false): PerformanceProfile = decide(mode, s, stutter).profile
}

/**
 * Lo que cuesta pintar la interfaz. Los dos perfiles son la *misma* app: cada
 * campo cambia cómo se consigue un efecto, no qué se ve.
 */
data class PerformanceProfile(
    /** El perfil ahorrador está puesto (lo leen Masha, la caché de imágenes y el polvo estelar). */
    val lowEnd: Boolean,
    /**
     * Desenfoque en vivo con RenderEffect (fondo vivo, cristal de la rueda,
     * velo de las capas, hero de baja resolución). Sin él, la versión teñida
     * de cada uno, la que ya se ve en Android 8–11.
     */
    val liveBlur: Boolean,
    /**
     * Movimiento ambiental continuo: flotación de logos, respiración de la
     * pista y acercamientos lentos de varios segundos. Cada uno pide un
     * fotograma tras otro aunque no pase nada; sin él quedan quietos en su pose.
     */
    val ambientMotion: Boolean,
    /** Lado mayor (px) al que se decodifica el fondo vivo. Diminuto + bilineal = ya desenfocado. */
    val backdropDecodePx: Int,
    /** Fracción del tamaño en pantalla a la que se decodifica el arte del hero. */
    val heroDecodeScale: Float,
    /** Sin elección explícita de 120 fps en Ajustes, la pantalla va a 60. */
    val capFrameRate: Boolean,
) {
    companion object {
        val QUALITY = PerformanceProfile(
            lowEnd = false,
            liveBlur = true,
            ambientMotion = true,
            backdropDecodePx = 320,
            heroDecodeScale = 1f,
            capFrameRate = false,
        )

        val PERFORMANCE = PerformanceProfile(
            lowEnd = true,
            liveBlur = false,
            ambientMotion = false,
            // 32 px ampliados a pantalla completa con filtrado bilineal se ven
            // como el desenfoque de 48 dp, sin calcular nada por fotograma.
            backdropDecodePx = 32,
            // El hero se ve tras un velo y con el texto encima: al 75 % no se nota y es ~44 % menos memoria.
            heroDecodeScale = 0.75f,
            capFrameRate = true,
        )
    }
}
