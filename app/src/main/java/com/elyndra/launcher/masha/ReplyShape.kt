package com.elyndra.launcher.masha

/**
 * La forma de una respuesta con tarjetas (pura): cuando Lucy recomienda o
 * propone algo, lo que se ve son las tarjetas y lo que se dice es una frase
 * corta. Si el modelo escribe de más, aquí se recorta: [MAX_SENTENCES] frases
 * y como mucho [MAX_CHARS] caracteres. Lo guardado en el hilo (lo que vuelve a
 * leer el modelo) sigue siendo el texto entero.
 */
object ReplyShape {

    const val MAX_SENTENCES = 2
    const val MAX_CHARS = 200

    /** Fin de frase: puntuación (también la de japonés) seguida de espacio o del final. */
    private val SENTENCE_END = Regex("""[.!?…]+["”»')]?(?=\s|$)|[。！？]+[」』]?""")

    /** ¿Trae [attachment] tarjetas que ya cuentan los detalles? (Una ficha de "hecho" no.) */
    fun hasCards(attachment: MashaAttachment?): Boolean = attachment != null && attachment !is MashaAttachment.Done

    /**
     * Las primeras frases de [text] (las que se dicen). Es siempre un prefijo
     * del texto, también a medio llegar en streaming (la voz lo va leyendo
     * así); con [ellipsis], un corte por largo termina en "…".
     */
    fun spokenLine(text: String, ellipsis: Boolean = true): String {
        val t = text.trim()
        var end = -1
        var count = 0
        for (m in SENTENCE_END.findAll(t)) {
            end = m.range.last + 1
            if (++count == MAX_SENTENCES) break
        }
        val cut = if (count == MAX_SENTENCES) t.substring(0, end) else t
        if (cut.length <= MAX_CHARS) return cut
        val space = cut.lastIndexOf(' ', MAX_CHARS).takeIf { it > MAX_CHARS / 2 } ?: MAX_CHARS
        val short = cut.substring(0, space).trimEnd(',', ';', ':', ' ')
        return if (ellipsis) "$short…" else short
    }

    /** Lo que se enseña en la burbuja (y en el subtítulo): corto si hay tarjetas, entero si no. */
    fun display(text: String, attachment: MashaAttachment?): String = if (hasCards(attachment)) spokenLine(text) else text
}
