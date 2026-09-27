package com.elyndra.launcher.ui.masha

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Lo que Masha está haciendo ahora mismo, compartido por todo lo que la
 * representa: el holograma 3D, su voz, el ambiente sonoro y la interfaz.
 *
 * Los estados de conversación (habla, escucha, piensa, ánimo) son estado de
 * Compose: la interfaz reacciona a ellos. Lo que cambia en cada fotograma
 * (la boca, el nivel del micrófono) son campos planos que el bucle de render
 * lee sin recomponer nada.
 */
@Stable
class MashaPresence {

    /** La voz de Masha está sonando. */
    var speaking by mutableStateOf(false)
        internal set

    /** El micrófono está abierto (reconocimiento de voz en curso). */
    var listening by mutableStateOf(false)
        internal set

    /** Esperando a la IA o usando una herramienta, sin texto todavía. */
    var thinking by mutableStateOf(false)
        internal set

    var mood by mutableStateOf(MashaMood.Neutral)
        internal set

    /** Nivel del micrófono (0..1), suavizado. Lo lee el bucle de render. */
    @Volatile
    var micLevel: Float = 0f
        internal set

    /** Sincronía de labios de la frase en curso. */
    val lipSync = LipSync()

    /** Gesto puntual pedido (saludar, explicar). Cada petición sube la secuencia. */
    var cue by mutableStateOf(Cue.None)
        private set
    var cueSeq by mutableIntStateOf(0)
        private set

    fun play(c: Cue) {
        cue = c
        cueSeq++
    }

    /** Ánimo que se ve: pensar manda sobre el de la última respuesta. */
    val visibleMood: MashaMood
        get() = when {
            thinking -> MashaMood.Thinking
            else -> mood
        }

    /** Energía del holograma (partículas, pulso de brillo), 0..1. */
    val energy: Float
        get() = when {
            speaking -> 0.85f
            thinking -> 0.95f
            listening -> 0.7f
            else -> visibleMood.energy * 0.6f
        }

    enum class Cue { None, Wave, Explain }
}
