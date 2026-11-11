package com.elyndra.launcher.ui.masha.voice

import com.elyndra.launcher.masha.AffinityTag

/**
 * Parte en frases una respuesta que llega en streaming, para que la voz
 * empiece a hablar con la primera frase sin esperar al final.
 *
 * Se le da el texto completo **hasta ahora** de un mensaje cada vez que crece:
 * mientras llega solo suelta frases terminadas (las muy cortas, con la
 * siguiente); al final, el resto. Cada frase sale una sola vez y ya lista para
 * leer ([speakable]). Un mensaje nuevo (otro id) empieza de cero.
 *
 * Sin Android: lo usa MashaVoice y se prueba solo (StreamChunkerTest).
 */
class StreamChunker {

    private var id: Long = -1
    private var queuedUpTo = 0

    /** Las frases nuevas de [text] (mensaje [id]) listas para decir; vacío si aún no hay ninguna entera. */
    fun feed(id: Long, text: String, final: Boolean): List<String> {
        if (id != this.id) {
            this.id = id
            queuedUpTo = 0
        }
        if (queuedUpTo >= text.length) return emptyList()
        val end = if (final) text.length else lastSentenceEnd(text, queuedUpTo)
        if (end <= queuedUpTo) return emptyList()
        val out = sentences(text, queuedUpTo, end).map(::speakable).filter { it.isNotBlank() }
        queuedUpTo = end
        return out
    }

    /** El mensaje [id] ya se leyó hasta [length] (p. ej. al volver a la pantalla): no se repite. */
    fun skip(id: Long, length: Int) {
        this.id = id
        queuedUpTo = length
    }

    /** Olvida el mensaje en curso (al callar). */
    fun reset() {
        id = -1
        queuedUpTo = 0
    }

    companion object {
        // Los signos CJK (。！？) no llevan espacio detrás: cierran la frase por sí solos.
        private val SENTENCE_END = Regex("[.!?…]+[\"'»”)]*(\\s|$)|[。！？]+[」』）”]*|\\n+")
        private val URL = Regex("https?://\\S+")
        private val MARKUP = Regex("[*_#`>|~▍•]+")
        private val EMOJI = Regex("[\\p{So}\\p{Sk}\\x{FE0F}\\x{200D}\\x{20E3}]")
        private val SPACES = Regex("\\s+")
        private const val MIN_CHUNK = 18

        /** Hasta dónde hay frases terminadas a partir de [from] (0 = ninguna). */
        fun lastSentenceEnd(text: String, from: Int): Int {
            var end = from
            for (m in SENTENCE_END.findAll(text, from)) {
                // Frases muy cortas ("Vale.") se juntan con la siguiente: suena más natural.
                if (spokenLength(text, from, m.range.last + 1) >= MIN_CHUNK || end > from) end = m.range.last + 1
            }
            return end
        }

        /**
         * [from, to) partido en frases (las muy cortas, con la siguiente). Lo que
         * queda sin punto al final va como última frase.
         */
        fun sentences(text: String, from: Int, to: Int): List<String> {
            val out = ArrayList<String>()
            var start = from
            for (m in SENTENCE_END.findAll(text.substring(0, to), from)) {
                val end = m.range.last + 1
                if (spokenLength(text, start, end) >= MIN_CHUNK) {
                    out += text.substring(start, end)
                    start = end
                }
            }
            if (start < to) {
                val tail = text.substring(start, to)
                // Un final corto ("¡Suerte!") se une a la frase anterior.
                if (out.isNotEmpty() && spokenLength(text, start, to) < MIN_CHUNK) out[out.lastIndex] += tail else out += tail
            }
            return out
        }

        /** Largo "hablado": un carácter CJK (kana, kanji, hangul) vale ~una sílaba, como tres letras. */
        private fun spokenLength(text: String, from: Int, to: Int): Int {
            var n = 0
            for (i in from until to) n += if (text[i].code >= 0x2E80) 3 else 1
            return n
        }

        /**
         * Lo que se lee: sin enlaces, marcas de formato ni emojis. Tampoco la marca de
         * puntuación de la relación (AffinityTag): ya llega quitada, esto es la segunda red.
         */
        fun speakable(s: String): String =
            AffinityTag.strip(s).text.replace(URL, " ").replace(MARKUP, " ").replace(EMOJI, "").replace(SPACES, " ").trim()
    }
}
