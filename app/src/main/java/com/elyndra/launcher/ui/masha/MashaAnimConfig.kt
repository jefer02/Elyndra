package com.elyndra.launcher.ui.masha

/*
 * Ajustes del cuerpo de Masha en tiempo real ([MashaAnimator]): fundidos,
 * variaciones en reposo, gestos del habla, capas procedurales, mirada y pies.
 * Todo en segundos, grados y metros (espacio del modelo glTF). Se agrupan
 * como [SpringConfig]: una clase de datos por tema, con valores por defecto
 * ya afinados; se puede cambiar cualquiera con `copy(...)`.
 */

/**
 * Tiempos de fundido (s) por tipo de transición. Todas las curvas son
 * smootherstep; un cambio a mitad de fundido se apila (nunca salta).
 *
 * @property idleVariation entrar y salir de una variación en reposo (Var_*).
 * @property gestureIn / [gestureOut] gestos del habla (Talk_*), Point, Explain, Nod, Shrug.
 * @property waveIn / [waveOut] saludo.
 * @property holdIn entrar en escuchar o pensar (poses mantenidas).
 * @property holdOut salir de escuchar o pensar al reposo.
 * @property reactIn reacciones (React_*): rápidas.
 * @property reactOut salida de las reacciones.
 * @property talkIn / [talkOut] entrar y salir del habla.
 * @property inertiaHalfLife semivida (s) de la inercialización: al cortar una
 *   animación de golpe (p. ej. una variación cuando empieza a hablar) se guarda
 *   la diferencia con la pose anterior y se deshace con un muelle crítico.
 * @property inertiaMaxSpeed tope (rad/s, m/s) de la velocidad inicial que
 *   hereda esa diferencia (evita latigazos si el fotograma anterior fue raro).
 */
data class FadeConfig(
    val idleVariation: Float = 0.5f,
    val gestureIn: Float = 0.3f,
    val gestureOut: Float = 0.5f,
    val waveIn: Float = 0.3f,
    val waveOut: Float = 0.5f,
    val holdIn: Float = 0.6f,
    val holdOut: Float = 0.6f,
    val reactIn: Float = 0.15f,
    val reactOut: Float = 0.5f,
    val talkIn: Float = 0.3f,
    val talkOut: Float = 0.6f,
    val inertiaHalfLife: Float = 0.15f,
    val inertiaMaxSpeed: Float = 6f,
)

/**
 * Variaciones en reposo (Var_*): cada [minInterval]–[maxInterval] s de reposo
 * libre (sin hablar, escuchar ni pensar), una al azar de una bolsa que no
 * repite ninguna de las [avoidLast] anteriores.
 *
 * @property boostSeconds tras un gesto, la respiración sale ×[BreathConfig.boost] durante esto.
 */
data class IdleConfig(
    val minInterval: Float = 8f,
    val maxInterval: Float = 20f,
    val avoidLast: Int = 2,
    val boostSeconds: Float = 4f,
    /** Probabilidad de asentir (Nod) al terminar de escuchar, si el clip existe. */
    val nodAfterListen: Float = 0.6f,
    /** Reacción alegre (React_Happy) cuando el ánimo pasa a juguetón, si el clip existe. */
    val reactToPlayful: Boolean = true,
)

/**
 * Habla: gestos de ritmo (Talk_*) al compás de la voz.
 *
 * La envolvente sigue el canal Open de [LipSync] (ataque [attack], caída
 * [release]); un golpe sale cuando sube [riseDb] dB sobre su media lenta
 * ([meanTau]) y pasa de [minLevel], o cuando pasa de [strongLevel] (sílaba
 * fuerte), si hace al menos [minGap]–[maxGap] s del
 * anterior. No repite ninguno de los [avoidLast] últimos; velocidad ±[timeJitter];
 * peso [minAmp]–[maxAmp] según el volumen.
 *
 * @property silenceHold se sigue "hablando" hasta este silencio (s): los huecos
 *   entre frases del TTS no hacen parpadear el estado.
 */
data class TalkConfig(
    val silenceHold: Float = 0.8f,
    val attack: Float = 0.03f,
    val release: Float = 0.25f,
    val meanTau: Float = 1.5f,
    val riseDb: Float = 6f,
    val minLevel: Float = 0.12f,
    val strongLevel: Float = 0.45f,
    val minGap: Float = 0.8f,
    val maxGap: Float = 1.5f,
    val avoidLast: Int = 3,
    val timeJitter: Float = 0.1f,
    val minAmp: Float = 0.5f,
    val maxAmp: Float = 1f,
    /** Envolvente que da el peso máximo. */
    val loudLevel: Float = 0.6f,
    /** Un golpe nuevo no pisa al anterior antes de esta fracción de su duración. */
    val minOverlap: Float = 0.45f,
)

/**
 * Respiración: [rateHz] asimétrica (inspira [inhale] del ciclo, espira el
 * resto). Spine1/Spine2 se extienden [spinePitch]° en total y las clavículas
 * suben [shoulderRaise]°. Tras un gesto, amplitud ×[boost].
 */
data class BreathConfig(
    val rateHz: Float = 0.23f,
    val inhale: Float = 0.4f,
    val spinePitch: Float = 1.2f,
    val shoulderRaise: Float = 0.7f,
    val boost: Float = 1.3f,
    /** Segundos que tarda el refuerzo en entrar/salir. */
    val boostTau: Float = 0.8f,
)

/**
 * Balanceo con ruido suave de 2 octavas (no senos puros): amplitud (°) y
 * frecuencia (Hz) por hueso. Y el cambio de peso ocasional: cada
 * [shiftMin]–[shiftMax] s, la cadera se desplaza [shiftDistance] m hacia un
 * lado y se inclina [shiftRoll]° en [shiftDurMin]–[shiftDurMax] s, y vuelve.
 */
data class SwayConfig(
    val hipsRoll: Float = 0.7f,
    val hipsHz: Float = 0.1f,
    val spine: Float = 0.7f,
    val spineHz: Float = 0.15f,
    val head: Float = 1.5f,
    val headHz: Float = 0.2f,
    val arms: Float = 0.6f,
    val armsHz: Float = 0.1f,
    val shiftMin: Float = 6f,
    val shiftMax: Float = 12f,
    val shiftDurMin: Float = 1.5f,
    val shiftDurMax: Float = 2f,
    val shiftDistance: Float = 0.012f,
    val shiftRoll: Float = 1.0f,
)

/**
 * Mirada: los ojos van delante (huesos `masha:eye.L/R`), la cabeza, el cuello
 * y Spine2 siguen con muelles críticos. Las constantes de partida son las de
 * [GazeConfig] (contrato de la cara).
 *
 * @property follow parte del desvío hacia la cámara que acaba tomando la cabeza (el resto, los ojos).
 * @property spine2Share / [neckShare] / [headShare] reparto del giro de cabeza.
 * @property behindYaw más allá de este desvío (cámara detrás) deja de mirar.
 * @property blinkShift un cambio de mirada mayor que esto (°) provoca un parpadeo.
 * @property weightTau segundos para entrar/salir la mirada (clips que miran solos la reducen).
 */
data class LookConfig(
    val enabled: Boolean = true,
    val eyeYawMax: Float = GazeConfig.EYE_YAW_MAX,
    val eyePitchUp: Float = GazeConfig.EYE_PITCH_UP,
    val eyePitchDown: Float = GazeConfig.EYE_PITCH_DOWN,
    val headYawMax: Float = GazeConfig.HEAD_YAW_MAX,
    val headPitchMax: Float = GazeConfig.HEAD_PITCH_MAX,
    val follow: Float = 0.75f,
    val spine2Share: Float = 0.10f,
    val neckShare: Float = 0.35f,
    val headShare: Float = 0.55f,
    val spine2HalfLife: Float = 0.5f,
    val neckHalfLife: Float = 0.25f,
    val headHalfLife: Float = 0.2f,
    val behindYaw: Float = 100f,
    val blinkShift: Float = 20f,
    val weightTau: Float = 0.3f,
    val fixationMin: Float = GazeConfig.FIXATION_MIN,
    val fixationMax: Float = GazeConfig.FIXATION_MAX,
    val refixMin: Float = GazeConfig.REFIX_MIN,
    val refixMax: Float = GazeConfig.REFIX_MAX,
    val microMin: Float = GazeConfig.MICRO_MIN,
    val microMax: Float = GazeConfig.MICRO_MAX,
    val microIntervalMin: Float = GazeConfig.MICRO_INTERVAL_MIN,
    val microIntervalMax: Float = GazeConfig.MICRO_INTERVAL_MAX,
    val awayMin: Float = GazeConfig.AWAY_MIN,
    val awayMax: Float = GazeConfig.AWAY_YAW,
    val awayPitchMin: Float = GazeConfig.AWAY_PITCH_MIN,
    val awayPitchMax: Float = GazeConfig.AWAY_PITCH_MAX,
    val awayIntervalMin: Float = GazeConfig.AWAY_INTERVAL_MIN,
    val awayIntervalMax: Float = GazeConfig.AWAY_INTERVAL_MAX,
    val awayHoldMin: Float = GazeConfig.AWAY_HOLD_MIN,
    val awayHoldMax: Float = GazeConfig.AWAY_HOLD_MAX,
    /** Parte de un vistazo que toma la cabeza. */
    val awayHeadShare: Float = GazeConfig.HEAD_SHARE,
    /** Sacada balística: duración = [saccadeBase] + [saccadePerDeg]·amplitud. */
    val saccadeBase: Float = GazeConfig.SACCADE_BASE,
    val saccadePerDeg: Float = GazeConfig.SACCADE_PER_DEG,
    /** Posición de la cámara si el escenario no la da (espacio del modelo). */
    val defaultTarget: FloatArray = floatArrayOf(0f, 1.3f, 3.4f),
)

/**
 * Pies clavados con IK analítica de dos huesos (UpLeg–Leg–Foot).
 *
 * Cada pie se fija donde lo pone el primer fotograma de los clips que suenan
 * (todos empiezan en la pose base), así el balanceo y el cambio de peso no
 * lo hacen patinar. Los clips que dan pasos ([steppingPrefixes]) sueltan el
 * pie (sigue al clip); y si un clip lo aleja más de [releaseStart]–[releaseEnd] m
 * de su sitio, se suelta solo.
 *
 * @property maxReach alcance máximo, fracción de la pierna estirada (IK suave).
 * @property softness ancho de la zona suave (fracción de la pierna).
 */
data class FootIkConfig(
    val enabled: Boolean = true,
    val maxReach: Float = 0.99f,
    val softness: Float = 0.005f,
    val steppingPrefixes: List<String> = listOf("Var_WeightShift"),
    val releaseStart: Float = 0.07f,
    val releaseEnd: Float = 0.14f,
)

/**
 * Todo el cuerpo. [lookAtScale] y [smileClips] van por prefijo de nombre de
 * clip: cuánto se deja la mirada a la cámara mientras suena (0 = el clip
 * manda en la cabeza) y cuánta sonrisa añade a la cara.
 *
 * @property seed semilla del azar (pruebas deterministas).
 */
data class MashaAnimConfig(
    val fades: FadeConfig = FadeConfig(),
    val idle: IdleConfig = IdleConfig(),
    val talk: TalkConfig = TalkConfig(),
    val breath: BreathConfig = BreathConfig(),
    val sway: SwayConfig = SwayConfig(),
    val look: LookConfig = LookConfig(),
    val feet: FootIkConfig = FootIkConfig(),
    val lookAtScale: Map<String, Float> = mapOf("Var_LookAround" to 0.2f, "Var_Stretch" to 0.5f, "Think" to 0.7f),
    val smileClips: Map<String, Float> = mapOf("Var_GlanceSmile" to 0.5f, "React_Happy" to 0.7f),
    /** Capa procedural entera (respiración, balanceo, cambio de peso), 0..1. */
    val procedural: Float = 1f,
    val seed: Long = 7L,
)
