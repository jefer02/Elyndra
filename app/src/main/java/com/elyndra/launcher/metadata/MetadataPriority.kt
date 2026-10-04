package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.MatchMethod
import com.elyndra.launcher.data.RaInfo
import com.elyndra.launcher.data.SettingsStore

/* ─────────────────────────────────────────────────────────────
   Prioridad de fuentes de metadatos, elegida por el usuario.

   Hay dos órdenes: uno para los textos (nombre, sinopsis, fecha,
   género…) y otro para las imágenes. Cada campo se resuelve por
   separado: la sinopsis puede salir de IGDB y la carátula de
   SteamGridDB en el mismo juego, si así se ha ordenado.

   Identificar el juego (hash → ScreenScraper) sigue yendo primero
   pase lo que pase: da el nombre bueno con el que se busca en los
   demás. La prioridad decide qué se queda, no quién se pregunta
   antes.
   ───────────────────────────────────────────────────────────── */

/** Un campo de metadatos que aporta alguna fuente. */
enum class MetaField(val isArt: Boolean, val artKind: String? = null) {
    Name(false),
    Description(false),
    ReleaseDate(false),
    Developer(false),
    Publisher(false),
    Genre(false),
    Players(false),
    Rating(false),
    Cover(true, "cover"),
    Hero(true, "hero"),
    Logo(true, "logo"),
    Icon(true, "icon"),
    Screenshot(true, "shot"),
    ;

    companion object {
        val TEXT = entries.filterNot { it.isArt }
        val ART = entries.filter { it.isArt }
    }
}

data class MetadataPriority(val text: List<Service>, val art: List<Service>) {

    fun rank(service: Service, field: MetaField): Int {
        val order = if (field.isArt) art else text
        return order.indexOf(service).let { if (it < 0) order.size else it }
    }

    /** Orden en que conviene preguntar: primero lo que puede ganar en algún campo. */
    fun queryOrder(): List<Service> =
        Service.entries.sortedWith(compareBy({ minOf(rank(it, MetaField.Name), rank(it, MetaField.Cover)) }, { it.ordinal }))

    companion object {
        /**
         * El orden de siempre: es el que seguía el motor antes de que se pudiera
         * elegir (ScreenScraper → IGDB → SteamGridDB → RetroAchievements), así
         * que quien no toque nada no nota ningún cambio.
         */
        val DEFAULT = MetadataPriority(
            // Las fuentes sin clave, detrás: rellenan huecos y mandan cuando no
            // hay ninguna con cuenta. (La descripción va además por idioma: ver
            // DescriptionMerge.) Google Play va delante de todo: solo se le
            // pregunta por juegos Android, y por paquete, que no se equivoca;
            // Steam queda de reserva cuando Play no tiene la ficha.
            text = listOf(Service.GooglePlay, Service.ScreenScraper, Service.Igdb, Service.Steam, Service.RetroAchievements, Service.SteamGridDb, Service.Libretro),
            art = listOf(Service.GooglePlay, Service.ScreenScraper, Service.Igdb, Service.SteamGridDb, Service.Steam, Service.Libretro, Service.RetroAchievements),
        )

        /**
         * Lista guardada ("ss,igdb,…") a orden completo; lo repetido se ignora.
         * Lo que falte se añade al final, salvo lo que en [fallback] va antes
         * que todo lo guardado (una fuente nueva que va primera de serie, como
         * Google Play): eso entra delante, para que quien ya tenía un orden
         * guardado la reciba en su sitio y no detrás de Steam.
         */
        fun parse(stored: String?, fallback: List<Service>): List<Service> {
            val parsed = stored.orEmpty().split(',')
                .mapNotNull { id -> Service.entries.firstOrNull { it.id == id.trim() } }
                .distinct()
            if (parsed.isEmpty()) return fallback
            val missing = fallback.filterNot { it in parsed }
            val firstStored = fallback.indexOfFirst { it in parsed }
            val (front, back) = missing.partition { fallback.indexOf(it) < firstStored }
            return front + parsed + back
        }

        fun format(order: List<Service>): String = order.joinToString(",") { it.id }
    }
}

/** La prioridad guardada en Ajustes. */
class MetadataPriorityStore(private val settings: SettingsStore) {

    fun get(): MetadataPriority = MetadataPriority(
        text = MetadataPriority.parse(settings.metaPriorityText, MetadataPriority.DEFAULT.text),
        art = MetadataPriority.parse(settings.metaPriorityArt, MetadataPriority.DEFAULT.art),
    )

    fun set(priority: MetadataPriority) {
        settings.metaPriorityText = MetadataPriority.format(priority.text)
        settings.metaPriorityArt = MetadataPriority.format(priority.art)
    }

    fun reset() {
        settings.metaPriorityText = null
        settings.metaPriorityArt = null
    }
}

/** Lo que un servicio sabe de un juego, antes de mezclarlo con los demás. */
class SourceData(val service: Service) {
    val text = HashMap<MetaField, String>()

    /** Descripciones que da este servicio, por idioma ("es", "en"…): ver [DescriptionMerge]. */
    val descriptions = LinkedHashMap<String, String>()

    fun addDescription(lang: String?, value: String?) {
        val l = DescriptionLangs.normalize(lang) ?: return
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (l !in descriptions) descriptions[l] = v
    }
    var rating: Float? = null

    /** Clase de imagen → URL de descarga. */
    val art = LinkedHashMap<MetaField, String>()
    var ssId: String? = null
    var igdbId: Long? = null
    var sgdbId: Long? = null
    var steamAppId: Long? = null
    var ra: RaInfo? = null

    /** Cómo reconoció este servicio el juego (ver [MatchMethod]) y con qué confianza. */
    var matchedBy: String? = null
    var confidence: Float = 0f

    fun has(field: MetaField): Boolean = when {
        field == MetaField.Rating -> rating != null
        field.isArt -> field in art
        field == MetaField.Description -> descriptions.isNotEmpty() || field in text
        else -> field in text
    }

    fun setText(field: MetaField, value: String?) {
        if (!value.isNullOrBlank()) text[field] = value.trim()
    }

    fun setArt(field: MetaField, url: String?) {
        if (!url.isNullOrBlank()) art[field] = url
    }

    fun matched(method: String, confidence: Double) {
        matchedBy = method
        this.confidence = confidence.toFloat().coerceIn(0f, 1f)
    }
}

/** El resultado de mezclar todas las fuentes según la prioridad. */
data class MergedMetadata(
    val text: Map<MetaField, String>,
    /** Todas las descripciones por idioma; en cada idioma gana la fuente con más prioridad. */
    val descriptions: Map<String, String> = emptyMap(),
    val rating: Float?,
    /** Clase de imagen → (servicio, URL). */
    val art: Map<MetaField, Pair<Service, String>>,
    val sources: List<Service>,
    val ssId: String?,
    val igdbId: Long?,
    val sgdbId: Long?,
    val steamAppId: Long? = null,
    val ra: RaInfo?,
    val matchedBy: String?,
    val confidence: Float?,
    /**
     * Por cada clase de imagen, todas las que hay en orden de prioridad (la
     * primera es la de [art]). Si la descarga de una falla, se prueba la
     * siguiente: un 404 en una fuente no deja el juego sin imagen.
     */
    val artChain: Map<MetaField, List<Pair<Service, String>>> = emptyMap(),
) {
    val matched: Boolean get() = sources.isNotEmpty()
}

object MetadataMerge {

    /** Los campos que puede dar cada servicio. */
    fun provides(service: Service): Set<MetaField> = when (service) {
        Service.ScreenScraper -> MetaField.entries.toSet()
        // IGDB no publica logos con transparencia.
        Service.Igdb -> MetaField.entries.toSet() - MetaField.Logo
        // SteamGridDB es solo arte: su "nombre" es el del buscador, no aporta.
        Service.SteamGridDb -> setOf(MetaField.Cover, MetaField.Hero, MetaField.Logo, MetaField.Icon)
        Service.RetroAchievements -> setOf(
            MetaField.Name, MetaField.Developer, MetaField.Publisher, MetaField.Genre,
            MetaField.ReleaseDate, MetaField.Cover, MetaField.Icon,
        )
        // Carátula, captura y pantalla de título (como fondo, si no hay otro mejor).
        Service.Libretro -> setOf(MetaField.Cover, MetaField.Screenshot, MetaField.Hero)
        // Por paquete: la ficha oficial del juego Android. Sin carátula vertical ni logo.
        Service.GooglePlay -> setOf(
            MetaField.Name, MetaField.Description, MetaField.Developer, MetaField.Genre, MetaField.Rating,
            MetaField.Icon, MetaField.Hero, MetaField.Screenshot,
        )
        // El icono de un juego Android instalado es el suyo: Steam no lo da.
        Service.Steam -> setOf(
            MetaField.Name, MetaField.Description, MetaField.Developer, MetaField.Publisher, MetaField.Genre,
            MetaField.Cover, MetaField.Hero, MetaField.Logo,
        )
    }

    /**
     * Qué podría mejorar [service] con lo reunido hasta ahora: los campos que
     * da y que ninguna fuente de más prioridad ha resuelto ya. Vacío = no hace
     * falta preguntarle.
     */
    fun wanted(
        service: Service,
        results: Map<Service, SourceData>,
        priority: MetadataPriority,
        fields: Set<MetaField> = provides(service),
    ): Set<MetaField> = fields.filterTo(LinkedHashSet()) { field ->
        val mine = priority.rank(service, field)
        results.values.none { it.service != service && it.has(field) && priority.rank(it.service, field) < mine }
    }

    fun merge(results: Map<Service, SourceData>, priority: MetadataPriority): MergedMetadata {
        fun <T> pick(field: MetaField, value: (SourceData) -> T?): Pair<Service, T>? = results.values
            .sortedBy { priority.rank(it.service, field) }
            .firstNotNullOfOrNull { d -> value(d)?.let { d.service to it } }

        val text = MetaField.TEXT.filter { it != MetaField.Rating }
            .mapNotNull { f -> pick(f) { it.text[f] }?.let { f to it.second } }
            .toMap()
        val art = MetaField.ART.mapNotNull { f -> pick(f) { it.art[f] }?.let { f to it } }.toMap()
        val artChain = MetaField.ART.associateWith { f ->
            results.values.sortedBy { priority.rank(it.service, f) }.mapNotNull { d -> d.art[f]?.let { d.service to it } }
        }.filterValues { it.isNotEmpty() }
        val matched = results.values.filter { it.matchedBy != null }
        val best = matched.maxByOrNull { it.confidence }
        val descriptions = DescriptionMerge.combine(
            results.values.sortedBy { priority.rank(it.service, MetaField.Description) }.map { it.descriptions },
        )
        return MergedMetadata(
            text = text,
            descriptions = descriptions,
            rating = pick(MetaField.Rating) { it.rating }?.second,
            art = art,
            sources = Service.entries.filter { s -> matched.any { it.service == s } },
            ssId = results[Service.ScreenScraper]?.ssId,
            igdbId = results[Service.Igdb]?.igdbId,
            sgdbId = results[Service.SteamGridDb]?.sgdbId,
            steamAppId = results[Service.Steam]?.steamAppId,
            ra = results[Service.RetroAchievements]?.ra,
            matchedBy = best?.matchedBy,
            confidence = best?.confidence,
            artChain = artChain,
        )
    }

    /** Del parecido de nombres (0…1) a cómo se identificó. */
    fun nameMethod(similarity: Double): String =
        if (similarity >= 0.95) MatchMethod.NAME else MatchMethod.FUZZY
}
