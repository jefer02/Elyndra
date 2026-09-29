package com.elyndra.launcher.metadata

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.net.URLEncoder

/* ─────────────────────────────────────────────────────────────
   Sinopsis de Wikipedia para el hero y la ficha del juego.

   No forma parte del motor de metadatos (ScreenScraper/IGDB/…): no
   identifica el juego por hash ni se guarda en GameMeta, así que no
   hace falta clave ni cuenta, y una respuesta de hoy no ata a nada
   si mañana cambia. Se pide a demanda y **en el idioma de la app**
   (es.wikipedia.org, fr.wikipedia.org…), y se cachea en memoria por
   sesión e idioma: quien vuelve a seleccionar el juego no repite la
   llamada, y cambiar de idioma no enseña la sinopsis del anterior.
   ───────────────────────────────────────────────────────────── */

object Wikipedia {

    /** Nada más largo que esto en el hero: es una pista bajo el título, no la ficha. */
    const val SHORT_LENGTH = 150

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, String?>()

    private fun host(lang: String) = "https://${lang.lowercase().filter { it in 'a'..'z' }.ifEmpty { "en" }}.wikipedia.org"

    /**
     * Sinopsis de [title] en la Wikipedia de [lang], entera ([short] = false)
     * o recortada para el hero. Null si no hay artículo en ese idioma o falla
     * la red: quien llama decide a qué idioma volver.
     */
    suspend fun summary(title: String, lang: String, short: Boolean): String? {
        val clean = title.trim()
        if (clean.isEmpty()) return null
        val key = "$lang|${clean.lowercase()}"
        fun fit(text: String?) = text?.let { if (short) shorten(it) else it }
        mutex.withLock { if (cache.containsKey(key)) return fit(cache[key]) }
        val fetched = runCatching { fetch(clean, lang) }.getOrNull()
        mutex.withLock { cache[key] = fetched }
        return fit(fetched)
    }

    private suspend fun fetch(title: String, lang: String): String? {
        val resolved = resolveTitle(title, lang) ?: return null
        val encoded = URLEncoder.encode(resolved, "UTF-8").replace("+", "%20")
        val result = Http.text(Request.Builder().url("${host(lang)}/api/rest_v1/page/summary/$encoded").build())
        if (result.code != 200) return null
        val obj = Http.parse(result.body).asObject() ?: return null
        // Las páginas de desambiguación no traen sinopsis de nada en concreto.
        if (obj.str("type") == "disambiguation") return null
        return obj.str("extract")?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
    }

    /**
     * El nombre del juego rara vez coincide letra a letra con el título del
     * artículo (mayúsculas, subtítulos, "(video game)"…), así que primero se
     * busca y se usa el título que Wikipedia ya identificó como el mejor.
     */
    private suspend fun resolveTitle(title: String, lang: String): String? {
        val encoded = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val url = "${host(lang)}/w/api.php?action=opensearch&format=json&namespace=0&limit=1&search=$encoded"
        val result = Http.text(Request.Builder().url(url).build())
        if (result.code != 200) return null
        val titles = Http.parse(result.body).asArray()?.getOrNull(1).asArray() ?: return null
        return titles.getOrNull(0)?.asString()
    }

    /** Recorta para el hero: mejor en el punto de la primera frase, si cabe. */
    fun shorten(text: String, max: Int = SHORT_LENGTH): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= max) return clean
        val sentenceEnd = Regex("[.。!?！？](\\s|$)").find(clean)?.range?.first?.takeIf { it in 1 until max }
        if (sentenceEnd != null) return clean.take(sentenceEnd + 1)
        val cut = clean.take(max)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > 0) cut.take(lastSpace) else cut).trimEnd() + "…"
    }
}
