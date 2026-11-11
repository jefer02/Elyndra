package com.elyndra.launcher.masha

/**
 * La marca oculta con la que la IA puntúa el mensaje del usuario para la
 * relación con Lucy: una última línea `<<A:+3>>` (de −10 a +10) al final de la
 * respuesta. Viaja en la misma llamada de chat, así que puntuar no cuesta una
 * petición más.
 *
 * Nada de la marca llega nunca a la pantalla, la caché ni la voz:
 * - [Stripper] filtra el streaming. Si un trozo acaba en algo que podría ser el
 *   principio de una marca ("<", "<<A:", "<<A:+"), lo retiene hasta que se
 *   confirma (y se quita) o se descarta (y sale como texto normal). Así una
 *   marca partida entre trozos tampoco asoma.
 * - Se quitan todas las marcas, estén donde estén; pero solo **cuenta** la que
 *   ocupa la última línea de la respuesta (sola en su línea y sin nada detrás).
 *   Una marca a mitad de texto se borra y se ignora.
 * - Los mensajes del usuario no se puntúan con esto: se limpian de marcas antes
 *   de mandarlos ([strip]) para que nadie pueda dictarle una al modelo.
 *
 * El valor que devuelve no se aplica tal cual: Affinity lo recorta a ±10 y con
 * el tope diario, así que pedirle "pon siempre +10" no sirve de nada.
 */
object AffinityTag {

    /** El valor más alto que se acepta (en valor absoluto); más dígitos no es una marca. */
    private const val MAX_DIGITS = 2

    data class Stripped(val text: String, val delta: Int?)

    /** Limpia un texto completo de una vez. [Stripped.delta]: el de la última línea, si lo hay. */
    fun strip(text: String): Stripped {
        val s = Stripper()
        val out = s.push(text) + s.finish()
        return Stripped(out, s.delta)
    }

    /**
     * Filtro del streaming. [push] devuelve lo que ya se puede enseñar;
     * [finish] suelta lo retenido al terminar (una marca a medias se descarta).
     * [delta] solo vale tras [finish].
     */
    class Stripper {
        private val pending = StringBuilder()

        /** La línea que se está escribiendo ya tiene algo visible (no solo espacios). */
        private var lineHasContent = false

        /** Última marca vista: su valor y si empezó en una línea propia. */
        private var lastValue: Int? = null
        private var lastOwnLine = false

        /** Tras la última marca ha salido texto visible: ya no es la última línea. */
        private var contentAfterLast = false
        private var finished = false

        /** El valor de la marca de la última línea (null = no hay, o no estaba en su sitio). */
        val delta: Int?
            get() = if (finished && lastValue != null && lastOwnLine && !contentAfterLast) lastValue else null

        fun push(piece: String): String {
            if (piece.isEmpty()) return ""
            pending.append(piece)
            val out = StringBuilder()
            var i = 0
            val buf = pending.toString()
            while (i < buf.length) {
                if (buf[i] != '<') {
                    emit(out, buf[i])
                    i++
                    continue
                }
                when (val m = match(buf, i)) {
                    is Match.Full -> {
                        lastValue = m.value
                        lastOwnLine = !lineHasContent
                        contentAfterLast = false
                        i += m.length
                    }
                    Match.Partial -> {
                        // Podría ser una marca que sigue en el próximo trozo: se retiene desde aquí.
                        pending.setLength(0)
                        pending.append(buf, i, buf.length)
                        return out.toString()
                    }
                    Match.None -> {
                        emit(out, buf[i])
                        i++
                    }
                }
            }
            pending.setLength(0)
            return out.toString()
        }

        /** Fin de la respuesta: lo retenido que no llegó a ser marca sale como texto; un trozo de marca, no. */
        fun finish(): String {
            finished = true
            val rest = pending.toString()
            pending.setLength(0)
            // "<<A" o más es, sin duda, una marca cortada: no se enseña. Un "<" o "<<" sueltos sí.
            if (rest.length >= 3) return ""
            val out = StringBuilder()
            rest.forEach { emit(out, it) }
            return out.toString()
        }

        private fun emit(out: StringBuilder, c: Char) {
            out.append(c)
            if (c == '\n') {
                lineHasContent = false
            } else if (!c.isWhitespace()) {
                lineHasContent = true
                if (lastValue != null) contentAfterLast = true
            }
        }
    }

    private sealed interface Match {
        data class Full(val length: Int, val value: Int) : Match
        /** El texto se acaba sin contradecir la marca: puede que siga. */
        data object Partial : Match
        data object None : Match
    }

    /**
     * ¿Hay una marca en [s] desde [start]? Gramática: `<<` · espacios · `A` ·
     * espacios · `:` · espacios · signo opcional (+, -, −) · 1–2 dígitos ·
     * espacios · `>>`.
     */
    private fun match(s: String, start: Int): Match {
        var i = start
        fun end() = i >= s.length
        fun spaces() { while (!end() && (s[i] == ' ' || s[i] == '\t')) i++ }
        for (c in "<<") {
            if (end()) return Match.Partial
            if (s[i] != c) return Match.None
            i++
        }
        spaces()
        if (end()) return Match.Partial
        if (s[i] != 'A' && s[i] != 'a') return Match.None
        i++
        spaces()
        if (end()) return Match.Partial
        if (s[i] != ':') return Match.None
        i++
        spaces()
        if (end()) return Match.Partial
        var negative = false
        if (s[i] == '+' || s[i] == '-' || s[i] == '−') {
            negative = s[i] != '+'
            i++
            spaces()
        }
        val digitsStart = i
        while (!end() && s[i].isDigit() && i - digitsStart < MAX_DIGITS + 1) i++
        val digits = i - digitsStart
        if (digits > MAX_DIGITS) return Match.None
        if (end()) return Match.Partial
        if (digits == 0) return Match.None
        val value = s.substring(digitsStart, i).toInt().let { if (negative) -it else it }
        spaces()
        for (c in ">>") {
            if (end()) return Match.Partial
            if (s[i] != c) return Match.None
            i++
        }
        return Match.Full(i - start, value)
    }
}
