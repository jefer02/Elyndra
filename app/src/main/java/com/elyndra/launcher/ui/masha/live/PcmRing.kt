package com.elyndra.launcher.ui.masha.live

/**
 * El último trozo de audio del micrófono (PCM de 16 bits, mono), en anillo.
 *
 * Cuando la conversación en vivo decide que el usuario está hablando ya han
 * pasado ~250 ms de su frase: el reconocedor recibe primero [last] (el
 * "preroll") y así no se pierden las primeras palabras.
 *
 * Lo escribe y lo lee el mismo hilo (el del micrófono).
 */
class PcmRing(val capacity: Int) {

    init {
        require(capacity > 0)
    }

    private val data = ShortArray(capacity)
    private var head = 0

    /** Muestras guardadas (como mucho [capacity]). */
    var size = 0
        private set

    fun write(samples: ShortArray, n: Int = samples.size) {
        val count = n.coerceIn(0, samples.size)
        // De lo que no cabe, solo cuenta el final.
        val from = (count - capacity).coerceAtLeast(0)
        for (i in from until count) {
            data[head] = samples[i]
            head = (head + 1) % capacity
        }
        size = minOf(capacity, size + (count - from))
    }

    /** Las últimas [n] muestras (o todas las que hay), de la más antigua a la más nueva. */
    fun last(n: Int): ShortArray {
        val k = n.coerceIn(0, size)
        val out = ShortArray(k)
        val start = Math.floorMod(head - k, capacity)
        for (i in 0 until k) out[i] = data[(start + i) % capacity]
        return out
    }

    fun clear() {
        head = 0
        size = 0
    }
}
