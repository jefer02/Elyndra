package com.elyndra.launcher.ui.masha.voice

import java.util.Locale

/**
 * Un motor de voz para Masha: convierte una frase en PCM y lo entrega a trozos,
 * con la misma forma que `UtteranceProgressListener` (que es lo que la sincronía
 * de labios ya sabe consumir). Ver docs/MASHA_VOICE.md.
 *
 * Contrato:
 * - Por frase: [SynthesisCallback.onBegin] → ([SynthesisCallback.onAudio] | [SynthesisCallback.onRange])*
 *   → exactamente un [SynthesisCallback.onEnd]. Entre frases, en orden (FIFO): la
 *   frase N+1 se sintetiza mientras suena la N, pero su onBegin llega tras el onEnd de la N.
 * - PCM mono (16 bits LE o float LE), tramas enteras, una frecuencia fija por voz.
 * - Los avisos llegan en un hilo del motor (nunca el principal).
 * - [stop] corta ya: lo pendiente se descarta sin más avisos (o con onEnd(ok = false)).
 */
interface VoiceEngine {
    val id: String

    /** Acepta frases ya (aunque aún esté cargando: entonces las encola). */
    val ready: Boolean

    /** Idioma de la app ("es", "en", …). Hilo principal. */
    fun setLanguage(tag: String)

    /** La voz que tomará la próxima frase (null = este motor no puede hablar ahora). */
    fun voice(): VoiceInfo?

    /** Paga ya el coste de la primera síntesis (cargar el modelo, etc.). */
    fun warmUp()

    /** Encola una frase. False = rechazada al momento (y sin avisos). */
    fun synthesize(req: SynthesisRequest, cb: SynthesisCallback): Boolean

    fun stop()

    fun release()
}

/** La voz concreta que habla: su idioma (con país: decide el G2P de los labios) y cómo tratar el texto. */
data class VoiceInfo(
    val engineId: String,
    /** Estable (modelo + hablante): si cambia, se olvida lo aprendido de la voz anterior. */
    val voiceId: String,
    val locale: Locale,
    /** El texto se normaliza antes (números, siglas…): el motor no lo hace bien por sí mismo. */
    val wantsNormalizedText: Boolean,
)

data class SynthesisRequest(
    val id: String,
    /** El texto exacto que también lee la sincronía de labios. */
    val text: String,
    /** Multiplicador de velocidad (1 = normal). */
    val rate: Float = 1f,
)

interface SynthesisCallback {
    fun onBegin(id: String, sampleRate: Int, encoding: Int, channels: Int)

    /** PCM de la frase, en orden y en tramas enteras. El array no se reutiliza. */
    fun onAudio(id: String, pcm: ByteArray)

    /** Opcional: la palabra [start, end) del texto empieza en la muestra [frame] de la frase. */
    fun onRange(id: String, start: Int, end: Int, frame: Int) {}

    /** Repuesto del sistema: el propio motor reproduce (sin PCM propio); la boca va con el reloj de pared. */
    fun onSelfPlayback(id: String) {}

    /** En ese repuesto, el motor empieza a sonar ahora. */
    fun onPlaybackStart(id: String) {}

    /** Fin de la frase: [ok] = false si falló o se paró. */
    fun onEnd(id: String, ok: Boolean)
}
