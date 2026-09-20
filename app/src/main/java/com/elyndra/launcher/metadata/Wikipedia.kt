package com.elyndra.launcher.metadata

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.net.URLEncoder

/* ─────────────────────────────────────────────────────────────
   Sinopsis corta de Wikipedia para el hero de la biblioteca.

   No forma parte del motor de metadatos (ScreenScraper/IGDB/…): no
   identifica el juego por hash ni se guarda en GameMeta, así que no
   hace falta clave ni cuenta, y una respuesta de hoy no ata a nada
   si mañana cambia. Se pide a demanda, cuando el hero enseña un
   juego, y se cachea en memoria por sesión: quien vuelve a
   seleccionarlo no repite la llamada.
   ───────────────────────────────────────────────────────────── */

object Wikipedia {

    private const val SEARCH = "https://en.wikipedia.org/w/api.php" +
        "?action=opensearch&format=json&namespace=0&limit=1&search="
    private const val SUMMARY = "https://en.wikipedia.org/api/rest_v1/page/summary/"

    /** Nada más largo que esto: es una pista bajo el título, no la ficha. */
    private const val MAX_LENGTH = 150

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, String?>()

    /** Sinopsis corta para [title], o null si no hay artículo o falla la red. */
    suspend fun shortSummary(title: String): String? {
        val key = title.trim().lowercase()
        if (key.isEmpty()) return null
        mutex.withLock { if (cache.containsKey(key)) return cache[key] }
        val summary = runCatching { fetch(title) }.getOrNull()
        mutex.withLock { cache[key] = summary }
        return summary
    }

    private suspend fun fetch(title: String): String? {
        val resolved = resolveTitle(title) ?: return null
        val encoded = URLEncoder.encode(resolved, "UTF-8").replace("+", "%20")
        val result = Http.text(Request.Builder().url("$SUMMARY$encoded").build())
        if (result.code != 200) return null
        val obj = Http.parse(result.body).asObject() ?: return null
        // Las páginas de desambiguación no traen sinopsis de nada en concreto.
        if (obj.str("type") == "disambiguation") return null
        return obj.str("extract")?.let { shorten(it) }
    }

    /**
     * El nombre del juego rara vez coincide letra a letra con el título del
     * artículo (mayúsculas, subtítulos, "(video game)"…), así que primero se
     * busca y se usa el título que Wikipedia ya identificó como el mejor.
     */
    private suspend fun resolveTitle(title: String): String? {
        val encoded = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val result = Http.text(Request.Builder().url("$SEARCH$encoded").build())
        if (result.code != 200) return null
        val titles = Http.parse(result.body).asArray()?.getOrNull(1).asArray() ?: return null
        return titles.getOrNull(0)?.asString()
    }

    private fun shorten(text: String): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= MAX_LENGTH) return clean
        // Mejor cortar en el punto de la primera frase, si cabe.
        val sentenceEnd = clean.indexOf(". ", 1).takeIf { it in 1 until MAX_LENGTH }
        if (sentenceEnd != null) return clean.take(sentenceEnd + 1)
        val cut = clean.take(MAX_LENGTH)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > 0) cut.take(lastSpace) else cut).trimEnd() + "…"
    }
}
