package com.elyndra.launcher.masha

/**
 * La forma de una respuesta con tarjetas (pura): cuando Lucy recomienda o
 * propone algo, lo que se ve son las tarjetas y lo que se dice es una frase
 * corta. Si el modelo escribe de más, aquí se recorta: [MAX_SENTENCES] frases
 * y como mucho [MAX_CHARS] caracteres. Desde v4 vale para cualquier respuesta
 * con tarjeta, confirmación o ficha de "hecho" ([isCardReply]) y lo guardado
 * en el hilo es ya la frase corta ([finalText]): lo que oyó el usuario.
 */
object ReplyShape {

    const val MAX_SENTENCES = 2
    const val MAX_CHARS = 200

    /** Fin de frase: puntuación (también la de japonés) seguida de espacio o del final. */
    private val SENTENCE_END = Regex("""[.!?…]+["”»')]?(?=\s|$)|[。！？]+[」』]?""")

    /** ¿Trae [attachment] tarjetas que ya cuentan los detalles? (Una ficha de "hecho" no.) */
    fun hasCards(attachment: MashaAttachment?): Boolean = attachment != null && attachment !is MashaAttachment.Done

    /**
     * ¿Es una respuesta con tarjeta? Cualquier adjunto (tarjetas, confirmación
     * o ficha de "hecho", la ponga la herramienta que sea) o algo hecho en el
     * turno ([done]). Entonces lo que se dice y se ve es la frase corta
     * ([spokenLine]); lo recorta el código, no solo el prompt (v4).
     */
    fun isCardReply(attachment: MashaAttachment?, done: List<String> = emptyList()): Boolean =
        attachment != null || done.isNotEmpty()

    /** El texto que se guarda y se enseña de una respuesta ya terminada. */
    fun finalText(text: String, attachment: MashaAttachment?, done: List<String> = emptyList()): String =
        if (isCardReply(attachment, done)) spokenLine(text, ellipsis = false) else text

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

    /** Lo que se enseña en la burbuja (y en el subtítulo): corto si hay tarjeta, entero si no. */
    fun display(text: String, attachment: MashaAttachment?, done: List<String> = emptyList()): String =
        if (isCardReply(attachment, done)) spokenLine(text) else text
}
