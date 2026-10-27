package com.elyndra.launcher.core.text

/** Recortes de texto para la interfaz. */
object TextTrim {

    /** Lo más largo que cabe bajo el título del hero: es una pista, no la ficha. */
    const val HERO_LENGTH = 150

    /** Lo más largo que mide un rótulo de sección ("CREA", "JUEGA EN COMPAÑÍA"). */
    const val HEADING_MAX = 40

    /**
     * Recorta para el hero: mejor en el punto de la primera frase, si cabe.
     * Antes se quitan los rótulos de sección (ver [dropHeadings]): al juntar
     * las líneas, "CREA" + "Crea lo que…" se leía "CREA Crea lo que…".
     */
    fun shorten(text: String, max: Int = HERO_LENGTH): String {
        val clean = dropHeadings(text).replace(Regex("\\s+"), " ")
        if (clean.length <= max) return clean
        val sentenceEnd = Regex("[.。!?！？](\\s|$)").find(clean)?.range?.first?.takeIf { it in 1 until max }
        if (sentenceEnd != null) return clean.take(sentenceEnd + 1)
        val cut = clean.take(max)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > 0) cut.take(lastSpace) else cut).trimEnd() + "…"
    }

    /**
     * Las fichas de Google Play van por secciones, cada una con su rótulo en
     * una línea aparte ("CREA\nCrea lo que sea…\nEXPLORA\nDescubre…"). En la
     * ficha completa están bien; en el resumen del hero, que junta las líneas,
     * se pegaban a la frase. Fuera todos, salvo que no quede nada más. Un
     * rótulo es una línea corta, toda en mayúsculas (con alguna letra
     * mayúscula: los idiomas sin mayúsculas no tienen rótulos así).
     */
    fun dropHeadings(text: String): String {
        val lines = text.trim().lines()
        val body = lines.filterNot(::isHeading)
        return (if (body.any { it.isNotBlank() }) body else lines).joinToString("\n").trim()
    }

    fun isHeading(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > HEADING_MAX) return false
        val letters = t.filter { it.isLetter() }
        return letters.any { it.isUpperCase() } && letters.none { it.isLowerCase() }
    }
}
