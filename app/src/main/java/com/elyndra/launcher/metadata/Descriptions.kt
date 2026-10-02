package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.GameMeta

/* ─────────────────────────────────────────────────────────────
   Descripciones por idioma.

   Cada fuente dice en qué idioma viene cada sinopsis (ScreenScraper
   las trae etiquetadas; IGDB solo tiene inglés; Steam la da en el
   idioma que se le pide). Se guardan todas, con su idioma, y al
   enseñarlas se elige:

     1. la del idioma de la app, venga de la fuente que venga —una
        fuente de menos prioridad que la tenga gana a una de más
        prioridad que no—;
     2. si no hay, la inglesa;
     3. si tampoco, la que haya.

   Las 2 y 3 se enseñan con una etiqueta discreta del idioma (y la
   opción de traducirla): no parecen un fallo. Todo es Kotlin puro.
   ───────────────────────────────────────────────────────────── */

object DescriptionLangs {

    const val FALLBACK = "en"

    /**
     * Código de idioma de dos letras a partir de lo que dé la fuente:
     * "jp" (ScreenScraper) → "ja", "pt-BR" → "pt", "spanish" (Steam) → "es".
     * Null si no se reconoce.
     */
    fun normalize(code: String?): String? {
        val c = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        STEAM_NAMES[c]?.let { return it }
        val base = c.substringBefore('-').substringBefore('_')
        return when (base) {
            "jp" -> "ja"
            "sp" -> "es"
            "br" -> "pt"
            else -> base.takeIf { it.length == 2 && it.all { ch -> ch in 'a'..'z' } }
        }
    }

    /** Nombres de idioma de la tienda de Steam (`l=`), para pedir y para reconocer. */
    private val STEAM_NAMES = mapOf(
        "english" to "en", "spanish" to "es", "latam" to "es", "portuguese" to "pt", "brazilian" to "pt",
        "french" to "fr", "german" to "de", "japanese" to "ja", "italian" to "it", "russian" to "ru",
        "koreana" to "ko", "schinese" to "zh", "tchinese" to "zh", "polish" to "pl", "turkish" to "tr",
    )

    /** El `l=` de Steam para un idioma de la app. */
    fun steamName(lang: String): String = when (lang) {
        "es" -> "spanish"
        "pt" -> "brazilian"
        "fr" -> "french"
        "de" -> "german"
        "ja" -> "japanese"
        else -> "english"
    }
}

object DescriptionMerge {

    /**
     * Junta las descripciones de varias fuentes, ya ordenadas por prioridad:
     * por cada idioma se queda la de la fuente con más prioridad que lo tenga.
     */
    fun combine(bySource: List<Map<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (variants in bySource) for ((lang, text) in variants) {
            val clean = text.trim()
            if (clean.isNotEmpty() && lang !in out) out[lang] = clean
        }
        return out
    }

    /** Idioma elegido para [appLang]: el suyo, si no inglés, si no el primero que haya. */
    fun chooseLang(variants: Map<String, String>, appLang: String): String? = when {
        variants.isEmpty() -> null
        appLang in variants -> appLang
        DescriptionLangs.FALLBACK in variants -> DescriptionLangs.FALLBACK
        else -> variants.keys.first()
    }

    /**
     * Tras una pasada: lo nuevo manda por idioma, pero no se pierde lo que ya
     * había en otros idiomas (una pasada en francés no borra la española).
     */
    fun update(old: Map<String, String>, fresh: Map<String, String>): Map<String, String> =
        LinkedHashMap(old).apply { putAll(fresh) }
}

/** Lo que se enseña como descripción de un juego. */
data class DescriptionView(
    val text: String,
    /** Idioma del texto enseñado; null = desconocido (datos de antes de etiquetar idiomas). */
    val lang: String?,
    /** El texto es una traducción de [originalLang]. */
    val translated: Boolean,
    /** Idioma del original (el de [lang] si no está traducido). */
    val originalLang: String?,
    /** Está en un idioma distinto del de la app: va con etiqueta y "Traducir". */
    val foreign: Boolean,
)

object DescriptionPick {

    /**
     * Las descripciones de [meta] por idioma. Los datos anteriores a esta
     * versión tienen una sola, sin idioma: entra con idioma desconocido (null).
     */
    fun variants(meta: GameMeta): Map<String?, String> {
        if (meta.descriptions.isNotEmpty()) return LinkedHashMap<String?, String>(meta.descriptions)
        val legacy = meta.description?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return mapOf(meta.descriptionLang to legacy)
    }

    /**
     * Qué descripción enseñar para [appLang]. [translation] es la traducción
     * guardada al idioma de la app, si la hay y sigue valiendo para el
     * original de ahora; [showOriginal] la aparca.
     */
    fun view(meta: GameMeta, appLang: String, translation: String? = null, showOriginal: Boolean = false): DescriptionView? {
        val all = variants(meta)
        if (all.isEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        val tagged = all.filterKeys { it != null } as Map<String, String>
        val lang: String? = DescriptionMerge.chooseLang(tagged, appLang)
        val (textLang, text) = if (lang != null) lang to tagged.getValue(lang) else null to all.values.first()
        // Sin idioma conocido (datos viejos) no se puede decir que sea "otro idioma".
        val foreign = textLang != null && textLang != appLang
        if (foreign && translation != null && !showOriginal) {
            return DescriptionView(translation, appLang, translated = true, originalLang = textLang, foreign = false)
        }
        return DescriptionView(text, textLang, translated = false, originalLang = textLang, foreign = foreign)
    }

    /** El original que se traduciría para [appLang] (idioma, texto); null si ya está en ese idioma o no hay. */
    fun translatable(meta: GameMeta, appLang: String): Pair<String, String>? {
        val v = view(meta, appLang) ?: return null
        if (!v.foreign || v.lang == null) return null
        return v.lang to v.text
    }
}

/** Cómo entra en un juego lo que trae una pasada (Kotlin puro, se prueba en la JVM). */
object GameDescriptionUpdate {

    /**
     * Las descripciones nuevas se suman a las que había (otros idiomas no se
     * pierden); la principal pasa a ser la del idioma de la app, y queda
     * anotado para qué idioma se preguntó.
     */
    fun apply(old: GameMeta, fresh: Map<String, String>, lang: String): GameMeta {
        // Datos de antes de etiquetar idiomas: esa descripción no se sabe de qué
        // idioma es, así que en cuanto llega alguna etiquetada deja de contar.
        val all = DescriptionMerge.update(old.descriptions, fresh)
        val chosen = DescriptionMerge.chooseLang(all, lang)
        return old.copy(
            description = chosen?.let { all[it] } ?: old.description,
            descriptionLang = chosen ?: old.descriptionLang,
            descriptions = all,
            descriptionCheckedLang = lang,
        )
    }
}
