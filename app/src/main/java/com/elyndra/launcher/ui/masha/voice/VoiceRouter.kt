package com.elyndra.launcher.ui.masha.voice

import java.util.concurrent.ConcurrentHashMap

/**
 * Elige quién habla cada frase: la voz natural si se puede (instalada, activada,
 * móvil capaz, idioma incluido) y, si no, la del sistema. Si la voz natural falla
 * en una frase antes de dar audio, esa misma frase se repite con la del sistema:
 * Masha nunca se queda muda por el modelo.
 *
 * Los cambios de motor, mejor entre respuestas: cada uno tiene su frecuencia y la
 * salida tiene que abrir otra pista (ver SpeechOutput.ensureTrack).
 */
class VoiceRouter(val neural: NeuralVoiceEngine, val system: SystemTtsEngine) {

    /** Ajuste del usuario: false = siempre la voz del sistema. */
    @Volatile var naturalEnabled = true

    private val stopped = ConcurrentHashMap.newKeySet<String>()

    /** Puede hablar ya (o encolar hasta que cargue). */
    val ready: Boolean get() = system.ready || (naturalEnabled && neural.voice() != null)

    fun setLanguage(tag: String) {
        neural.setLanguage(tag)
        system.setLanguage(tag)
    }

    /** La voz de la próxima frase (null = ninguna disponible todavía). */
    fun voice(): VoiceInfo? = (if (naturalEnabled) neural.voice() else null) ?: system.voice()

    fun warmUp() {
        if (naturalEnabled && neural.voice() != null) neural.warmUp()
    }

    /** Sintetiza con el motor de [via] (la voz que se usó para preparar el texto). */
    fun synthesize(req: SynthesisRequest, via: VoiceInfo, cb: SynthesisCallback): Boolean {
        if (via.engineId != neural.id) return system.synthesize(req, cb)
        return neural.synthesize(req, object : SynthesisCallback by cb {
            @Volatile var audio = false

            override fun onBegin(id: String, sampleRate: Int, encoding: Int, channels: Int) {
                audio = true
                cb.onBegin(id, sampleRate, encoding, channels)
            }

            override fun onEnd(id: String, ok: Boolean) {
                // Falló sin sonar nada (modelo corrupto, sin memoria…): la voz del sistema la dice.
                if (!ok && !audio && !stopped.remove(id) && system.ready) {
                    if (system.synthesize(req, cb)) return
                }
                cb.onEnd(id, ok)
            }
        }) || system.synthesize(req, cb)
    }

    /** Las frases en curso se paran: su fallo no debe pasar a la voz del sistema. */
    fun stop(ids: Collection<String>) {
        stopped.addAll(ids)
        neural.stop()
        system.stop()
    }

    fun release() {
        neural.release()
        system.release()
    }
}
