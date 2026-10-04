package com.elyndra.launcher.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/* ─────────────────────────────────────────────────────────────
   Google Play, sin clave: la ficha pública de la tienda
   (play.google.com/store/apps/details?id=…), pedida por el nombre
   de paquete. Es la fuente principal de los juegos Android: el
   paquete identifica el juego sin dudas, así que no hay que
   adivinar por el nombre.

   De la página se saca:
     · el bloque JSON-LD (SoftwareApplication): título oficial,
       icono grande, desarrollador, categoría y nota;
     · la descripción completa (data-g-id="description");
     · las capturas (data-screenshot-index), en orden.

   No es una API: es HTML y puede cambiar. Si algo no cuadra, no
   hay datos y el motor sigue con la siguiente fuente (Steam). Una
   petición cada 1,5 s y caché de fallos: un paquete que no está en
   Play (APK de fuera, juego retirado) no se vuelve a preguntar en días.
   ───────────────────────────────────────────────────────────── */

/** Lo que dice la ficha de Play de un paquete. [icon] y [screenshots] son URLs base, sin tamaño. */
data class PlayListing(
    val packageName: String,
    val title: String,
    val description: String?,
    val developer: String?,
    val genre: String?,
    /** Nota 0…1 (Play la da sobre 5). */
    val rating: Float?,
    val icon: String?,
    val screenshots: List<String>,
)

/** La ficha ya lista para el motor: descripciones por idioma y la captura apaisada para el fondo. */
data class PlayGame(
    val listing: PlayListing,
    val descriptions: Map<String, String>,
    /** La primera captura apaisada (sirve de fondo); null si todas son verticales o no se pudo saber. */
    val landscape: String?,
)

object PlayParser {

    private val LD_JSON = Regex("""<script type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val DESCRIPTION = Regex("""data-g-id="description"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val SCREENSHOT = Regex("""<img[^>]*data-screenshot-index="\d+"[^>]*>""")
    private val SRC = Regex("""\ssrc="([^"]+)"""")
    /** El chip de la categoría: el enlace lleva la etiqueta ya traducida. */
    private val GENRE = Regex("""href="/store/apps/category/GAME_[A-Z_]+"\s+aria-label="([^"]+)"""")
    private val OG_TITLE = Regex("""<meta property="og:title" content="([^"]+)"""")

    /** La ficha de [packageName] a partir del HTML de la página, o null si no es una ficha. */
    fun parse(html: String, packageName: String): PlayListing? {
        val ld = LD_JSON.findAll(html)
            .mapNotNull { runCatching { Http.parse(it.groupValues[1].trim()).asObject() }.getOrNull() }
            .firstOrNull { it.str("@type") in APP_TYPES }
        val title = ld?.str("name")?.let(::unescape)?.trim()
            ?: OG_TITLE.find(html)?.groupValues?.get(1)?.let(::unescape)?.substringBeforeLast(" - ")?.trim()
        if (title.isNullOrBlank()) return null
        val description = DESCRIPTION.find(html)?.groupValues?.get(1)?.let(::cleanDescription)?.takeIf { it.isNotBlank() }
            ?: ld?.str("description")?.let(::cleanDescription)?.takeIf { it.isNotBlank() }
        val genre = GENRE.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotBlank() }
            ?: ld?.str("applicationCategory")?.let(::categoryName)
        val rating = ld?.get("aggregateRating").asObject()?.get("ratingValue").asDouble()
            ?.let { (it / 5.0).toFloat().coerceIn(0f, 1f) }
        return PlayListing(
            packageName = packageName,
            title = title,
            description = description,
            developer = ld?.get("author").asObject()?.str("name")?.let(::unescape)?.trim(),
            genre = genre,
            rating = rating,
            icon = ld?.str("image")?.let(::baseUrl),
            screenshots = screenshots(html),
        )
    }

    /** Las capturas en el orden de la ficha, sin repetir (la página trae el carrusel varias veces). */
    fun screenshots(html: String): List<String> = SCREENSHOT.findAll(html)
        .mapNotNull { SRC.find(it.value)?.groupValues?.get(1) }
        .filter { it.startsWith("http") }
        .map(::baseUrl)
        .distinct()
        .toList()

    /** La URL sin el sufijo de tamaño ("=w526-h296", "=s0-br30"…). */
    fun baseUrl(url: String): String = unescape(url).substringBefore('=')

    /** Icono cuadrado de [side] px. */
    fun icon(base: String, side: Int = 512) = "$base=s$side"

    /** Imagen que cabe en [w]×[h] sin deformarse (Play conserva la proporción). */
    fun sized(base: String, w: Int, h: Int) = "$base=w$w-h$h"

    /** "GAME_ROLE_PLAYING" → "Role Playing", por si la página no trae la etiqueta traducida. */
    fun categoryName(category: String): String? {
        if (!category.startsWith("GAME_")) return null
        return category.removePrefix("GAME_").split('_')
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
            .takeIf { it.isNotBlank() }
    }

    /** La descripción de Play es HTML con <br>: texto con saltos de línea. */
    fun cleanDescription(s: String): String = unescape(
        s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), ""),
    )
        .replace(' ', ' ')
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun unescape(s: String): String = s
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&amp;", "&")

    private val APP_TYPES = setOf("SoftwareApplication", "MobileApplication", "VideoGame")
}

class GooglePlayClient(
    cacheDir: File?,
    private val codec: ImageCodec = AndroidImageCodec,
    private val base: String = "https://play.google.com",
    private val limiter: RateLimiter = RateLimiter(1_500),
    clock: () -> Long = System::currentTimeMillis,
) {

    // "_ip": los fallos de cuando se pedía la tienda de EE. UU. (gl=US) ya no valen.
    private val misses = MissCache(cacheDir?.let { File(it, "keyless/gplay_misses_ip.txt") }, MISS_TTL_MS, clock)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** ¿Play ya dijo que este paquete no está (y aún no caducó)? Solo un 404 lo marca, nunca un fallo de red. */
    fun isKnownMiss(packageName: String): Boolean = misses.isMiss(packageName)

    /**
     * La ficha de [packageName] en [lang]. null = no está en Play (se recuerda)
     * o no se pudo leer (red, página distinta): en ambos casos, "no hay".
     *
     * Sin `gl`: Play usa la región de la conexión, la misma de la tienda donde
     * se instaló el juego. Con una fija, un juego que no se vende allí (Mobile
     * Legends en EE. UU.) daría 404 y se tomaría por "no está en Play".
     */
    suspend fun listing(packageName: String, lang: String): PlayListing? {
        if (misses.isMiss(packageName)) return null
        val r = KeylessHttp.get("$base/store/apps/details?id=${enc(packageName)}&hl=${enc(lang)}", limiter) ?: return null
        if (r.code == 404) {
            misses.markMiss(packageName)
            return null
        }
        if (r.code != 200) return null
        return runCatching { PlayParser.parse(r.body, packageName) }.getOrNull()
    }

    /**
     * La ficha en [lang] de una tienda de un país de ese idioma ([STORES]: la
     * primera que tenga el juego). La de la región del usuario no sirve para
     * la descripción: puede ser una ficha propia de su país, en su idioma,
     * pida el idioma que pida (Mobile Legends desde Colombia sale en español
     * aunque se pida en francés). Que falte aquí no dice que el juego no esté
     * en Play: no se recuerda como fallo.
     */
    private suspend fun storeListing(packageName: String, lang: String): PlayListing? {
        for (gl in STORES[lang].orEmpty()) {
            val r = KeylessHttp.get("$base/store/apps/details?id=${enc(packageName)}&hl=${enc(lang)}&gl=$gl", limiter)
                ?: return null
            if (r.code == 200) return runCatching { PlayParser.parse(r.body, packageName) }.getOrNull()
            if (r.code != 404) return null
        }
        return null
    }

    private suspend fun storeListingOrNull(packageName: String, lang: String): PlayListing? = try {
        storeListing(packageName, lang)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Lo que Play sabe de [packageName]: la ficha de la región del usuario
     * (si está en Play, título, arte y capturas), la descripción en el idioma
     * de la app y en inglés —de tiendas de esos idiomas, para saber de verdad
     * en qué idioma está cada una— y la primera captura apaisada, de fondo.
     */
    suspend fun find(packageName: String, lang: String): PlayGame? {
        val listing = listing(packageName, lang) ?: return null
        val english = storeListingOrNull(packageName, DescriptionLangs.FALLBACK)
        val native = if (lang == DescriptionLangs.FALLBACK) english else storeListingOrNull(packageName, lang)
        // Play enseña la descripción original cuando no hay traducción: misma regla que Steam.
        val descriptions = SteamDescriptions.tag(lang, native?.description, english?.description)
        return PlayGame(listing, descriptions, firstLandscape(listing.screenshots))
    }

    /**
     * La primera captura más ancha que alta. Se mira una miniatura de
     * [PROBE_SIDE] px (un par de KB): la página no dice la orientación y un
     * juego vertical no sirve de fondo apaisado.
     */
    private suspend fun firstLandscape(shots: List<String>): String? = withContext(Dispatchers.IO) {
        for (shot in shots.take(MAX_PROBES)) {
            val bytes = try {
                Http.client.newCall(KeylessHttp.request("$shot=s$PROBE_SIDE")).await().use { if (it.isSuccessful) it.body?.bytes() else null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            val (w, h) = runCatching { codec.bounds(bytes) }.getOrNull() ?: continue
            if (w > h) return@withContext shot
        }
        null
    }

    companion object {
        /** Un paquete que no está en Play no se vuelve a preguntar en una semana. */
        const val MISS_TTL_MS = 7 * MissCache.DAY_MS
        /** Tiendas de un país de cada idioma de la app, en orden: de ahí sale la descripción en ese idioma. */
        private val STORES = mapOf(
            "en" to listOf("US", "GB"),
            "es" to listOf("ES", "MX"),
            "pt" to listOf("BR", "PT"),
            "fr" to listOf("FR", "CA"),
            "de" to listOf("DE", "AT"),
            "ja" to listOf("JP"),
        )
        private const val PROBE_SIDE = 160
        private const val MAX_PROBES = 6
    }
}
