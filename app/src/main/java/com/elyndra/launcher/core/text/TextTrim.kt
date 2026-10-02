package com.elyndra.launcher.core.text

/** Recortes de texto para la interfaz. */
object TextTrim {

    /** Lo más largo que cabe bajo el título del hero: es una pista, no la ficha. */
    const val HERO_LENGTH = 150

    /** Recorta para el hero: mejor en el punto de la primera frase, si cabe. */
    fun shorten(text: String, max: Int = HERO_LENGTH): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= max) return clean
        val sentenceEnd = Regex("[.。!?！？](\\s|$)").find(clean)?.range?.first?.takeIf { it in 1 until max }
        if (sentenceEnd != null) return clean.take(sentenceEnd + 1)
        val cut = clean.take(max)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > 0) cut.take(lastSpace) else cut).trimEnd() + "…"
    }
}
