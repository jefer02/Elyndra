package com.elyndra.launcher.ui.masha.live

/**
 * El oído de la conversación en vivo, tramo a tramo (puro): une el VAD (la
 * probabilidad de voz, con histéresis), la puerta de eco ([EchoGate]: que no
 * sea la propia Lucy por el altavoz) y el segmentador de turnos
 * ([SpeechSegmenter]). Lo usa el hilo del micrófono (LiveMic).
 */
class LiveEar(
    val gate: EchoGate = EchoGate(),
    val segmenter: SpeechSegmenter = SpeechSegmenter(frameMs = gate.frameMs),
    val onThreshold: Float = ON,
    val offThreshold: Float = OFF,
) {
    private var voice = false

    /** El último tramo contó como voz del usuario. */
    var voiced = false
        private set

    /** El último tramo tenía voz, pero la puerta la atribuyó a Lucy (para medir). */
    var echoRejected = false
        private set

    fun accept(micDb: Float, refDb: Float, vadProb: Float): SpeechSegmenter.Signal? {
        voice = when {
            vadProb >= onThreshold -> true
            vadProb < offThreshold -> false
            else -> voice
        }
        val notHers = gate.accept(micDb, refDb)
        voiced = voice && notHers
        echoRejected = voice && !notHers
        // Con su voz sonando hace falta más voz para abrir turno.
        return segmenter.accept(voiced, overLucy = gate.predictedDb > EchoGate.SILENT)
    }

    companion object {
        const val ON = 0.5f
        const val OFF = 0.35f
    }
}

/**
 * Qué hacer con lo que el usuario dijo mientras Lucy hablaba (pura). Lucy ya
 * está en pausa (para reaccionar en ~300 ms); falta decidir:
 * - [Decision.Commit]: era de verdad (cualquier palabra que no sea muletilla,
 *   o habló más de [COMMIT_SPEECH_MS], o se calló tras más de
 *   [BACKCHANNEL_MAX_MS]), o es la respuesta a la pregunta con la que ella
 *   termina ("¿Te lo pongo?" → "sí").
 * - [Decision.Resume]: una muletilla corta ("mm", "ajá") o nada reconocible:
 *   sigue donde iba.
 * - [Decision.Wait]: aún no se sabe.
 */
object HoldDecision {

    enum class Decision { Wait, Resume, Commit }

    const val BACKCHANNEL_MAX_MS = 700
    const val COMMIT_SPEECH_MS = 900

    /**
     * [speechMs]: voz del usuario en este turno; [paused]: ya lleva ~250 ms
     * callado; [partial]: lo que va entendiendo el reconocedor (null si no da
     * parciales); [lastIsQuestion]: lo que dice ella ahora es su última frase y
     * es una pregunta.
     */
    fun decide(speechMs: Int, paused: Boolean, partial: String?, lastIsQuestion: Boolean): Decision {
        val text = partial?.trim().orEmpty()
        return when {
            Backchannel.hasContent(text) -> Decision.Commit
            text.isNotEmpty() && lastIsQuestion -> Decision.Commit
            speechMs > COMMIT_SPEECH_MS -> Decision.Commit
            paused && speechMs > BACKCHANNEL_MAX_MS -> Decision.Commit
            paused -> Decision.Resume
            else -> Decision.Wait
        }
    }

    /** ¿Termina [sentence] en pregunta? (cualquier idioma de la app). */
    fun isQuestion(sentence: String?): Boolean {
        val s = sentence?.trimEnd()?.trimEnd('"', '”', '»', ')', '」', '』') ?: return false
        return s.endsWith("?") || s.endsWith("？") || s.endsWith("か") || s.endsWith("かな")
    }
}
