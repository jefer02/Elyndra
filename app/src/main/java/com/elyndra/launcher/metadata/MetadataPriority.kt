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
            text = listOf(Service.ScreenScraper, Service.Igdb, Service.RetroAchievements, Service.SteamGridDb),
            art = listOf(Service.ScreenScraper, Service.Igdb, Service.SteamGridDb, Service.RetroAchievements),
        )

        /** Lista guardada ("ss,igdb,…") a orden completo: lo que falte se añade al final, lo repetido se ignora. */
        fun parse(stored: String?, fallback: List<Service>): List<Service> {
            val parsed = stored.orEmpty().split(',')
                .mapNotNull { id -> Service.entries.firstOrNull { it.id == id.trim() } }
                .distinct()
            return parsed + fallback.filterNot { it in parsed }
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
    var rating: Float? = null

    /** Clase de imagen → URL de descarga. */
    val art = LinkedHashMap<MetaField, String>()
    var ssId: String? = null
    var igdbId: Long? = null
    var sgdbId: Long? = null
    var ra: RaInfo? = null

    /** Cómo reconoció este servicio el juego (ver [MatchMethod]) y con qué confianza. */
    var matchedBy: String? = null
    var confidence: Float = 0f

    fun has(field: MetaField): Boolean = when {
        field == MetaField.Rating -> rating != null
        field.isArt -> field in art
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
    val rating: Float?,
    /** Clase de imagen → (servicio, URL). */
    val art: Map<MetaField, Pair<Service, String>>,
    val sources: List<Service>,
    val ssId: String?,
    val igdbId: Long?,
    val sgdbId: Long?,
    val ra: RaInfo?,
    val matchedBy: String?,
    val confidence: Float?,
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
        val matched = results.values.filter { it.matchedBy != null }
        val best = matched.maxByOrNull { it.confidence }
        return MergedMetadata(
            text = text,
            rating = pick(MetaField.Rating) { it.rating }?.second,
            art = art,
            sources = Service.entries.filter { s -> matched.any { it.service == s } },
            ssId = results[Service.ScreenScraper]?.ssId,
            igdbId = results[Service.Igdb]?.igdbId,
            sgdbId = results[Service.SteamGridDb]?.sgdbId,
            ra = results[Service.RetroAchievements]?.ra,
            matchedBy = best?.matchedBy,
            confidence = best?.confidence,
        )
    }

    /** Del parecido de nombres (0…1) a cómo se identificó. */
    fun nameMethod(similarity: Double): String =
        if (similarity >= 0.95) MatchMethod.NAME else MatchMethod.FUZZY
}
