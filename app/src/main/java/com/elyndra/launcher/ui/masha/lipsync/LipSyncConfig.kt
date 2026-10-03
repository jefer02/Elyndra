package com.elyndra.launcher.ui.masha.lipsync

import kotlin.math.ln

/**
 * Todos los ajustes de la sincronía de labios en un sitio (ver
 * `docs/MASHA_LIPSYNC.md`, sección "Runtime"). Valores por defecto pensados
 * para la voz local de Google a velocidad 1 y el modelo v2.
 */
data class LipSyncConfig(
    /** Multiplica la forma de los labios (1 = topes del contrato: vocales 0,55–0,7). */
    val visemeIntensity: Float = 1f,
    /** Multiplica la apertura de la mandíbula. */
    val jawAmount: Float = 1f,
    /** Máximo de `jawOpen` al hablar (tope del contrato v2: 0,6; una "a" acentuada llega a ~0,5). */
    val jawMax: Float = 0.6f,
    /** Velocidad del suavizado final (1 = por defecto; más = más nervioso, menos = más blando). */
    val smoothing: Float = 1f,
    /** La boca va por delante del sonido (ms). ITU-R BT.1359: que el vídeo adelante, nunca el audio. */
    val visualLeadMs: Float = 60f,
    /**
     * Retardo extra de la salida de audio (ms): calibración de auriculares Bluetooth. Puede ser negativo.
     * Mutable y volátil: [com.elyndra.launcher.ui.masha.MashaVoice] lo cambia en vivo (hilo principal)
     * según la salida de audio actual ([AudioRouteOffsets]); el reloj lo lee desde el render.
     */
    @field:Volatile var audioOffsetMs: Float = 0f,
    /** Latencia supuesta de la salida hasta que AudioTrack da una marca de tiempo válida (ms). */
    val fallbackLatencyMs: Float = 60f,
    /** Cejas, cabeceos y levantar la cabeza al preguntar (0 = nada, 1 = por defecto). */
    val expressionIntensity: Float = 1f,
    /** Audio que se acumula antes de empezar a sonar (ms): margen para anticipar la boca. */
    val lookaheadMs: Float = 200f,
    /** Anticipación del redondeo de O/U (ms): los labios empiezan a redondearse antes. */
    val anticipationMs: Float = 170f,
    /** Duración mínima del cierre de labios en P/B/M (ms). */
    val closureMinMs: Float = 60f,
    /** Por encima de estas sílabas/s se articula menos (hipoarticulación). */
    val fastSyllablesPerSecond: Float = 6f,
    /** Cuánto se reduce la forma de los labios al hablar rápido (0..1). */
    val hypoArticulation: Float = 0.2f,
    /** Cabeceo en las palabras acentuadas (grados). */
    val nodDeg: Float = 2.2f,
    /** Subida de cejas en las palabras acentuadas (0..1 de la receta BrowUp). */
    val browAccent: Float = 0.32f,
    /** Cabeza arriba al final de una pregunta (grados). */
    val questionLiftDeg: Float = 3f,
    /** Probabilidad de parpadear en una coma o un punto. */
    val punctuationBlink: Float = 0.5f,
    /** Cuánto baja la sonrisa del ánimo en O/U/P (para que se vea el redondeo y el cierre). */
    val smileRoundingCut: Float = 0.65f,
    /** Separación mínima entre acentos de ceja (s). */
    val accentGap: Float = 1.4f,
    /**
     * Subida pequeña de cejas (sin cabeceo) en el resto de tónicas de palabras con contenido que
     * no son acento fuerte (0 = ninguna). Las cejas "puntúan" el habla sin parecer un tic.
     */
    val minorBrowAccent: Float = 0.16f,
    /** Separación mínima entre esas subidas pequeñas y cualquier otro acento (s). */
    val minorAccentGap: Float = 0.45f,
) {
    /** Caída de la dominancia antes de O/U: a [anticipationMs] vale 1/3. */
    val roundThetaPre: Float get() = (ln(3.0) / (anticipationMs.coerceAtLeast(40f) / 1000.0)).toFloat()

    val leadSeconds: Float get() = visualLeadMs / 1000f
}
