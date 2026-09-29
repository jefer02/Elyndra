package com.elyndra.launcher.masha.offline

import com.elyndra.launcher.domain.lists.DynamicList
import com.elyndra.launcher.domain.session.SessionMood
import java.text.Normalizer

/** Lo que el usuario le pide a Masha, entendido sin IA. */
sealed interface OfflineIntent {
    data class Launch(val title: String) : OfflineIntent
    data class Plan(val minMinutes: Int, val maxMinutes: Int, val mood: SessionMood) : OfflineIntent
    data object Recommend : OfflineIntent
    data object Stats : OfflineIntent
    data class ShowList(val list: DynamicList) : OfflineIntent
    data object UpdateMetadata : OfflineIntent
    data object Cleanup : OfflineIntent
    data object Arcs : OfflineIntent
    data object Unknown : OfflineIntent
}

/**
 * Masha sin conexión (o sin clave) entiende lo esencial por palabras clave, en
 * los idiomas de la app: lanzar un juego, planear un rato, recomendar, contar
 * horas, enseñar listas, actualizar metadatos, revisar la biblioteca y los
 * arcos. No conversa, pero hace — con los mismos datos que la IA.
 */
object OfflineIntents {

    private val LAUNCH = Regex(
        """^(?:juega(?:r)?(?:\s+a)?|abre|abrir|lanza|lanzar|pon|arranca|play|open|launch|start|run|jogar|joga|lancer|lance|jouer(?:\s+a)?|spiele?n?|starte|öffne)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val RANGE = Regex("""(\d{1,3})\s*(?:-|–|a|to|y|and|bis|ate)\s*(\d{1,3})\s*(min|minutos|minutes|minuten|m\b|h|horas?|hours?|heures?|stunden?)?""")
    private val SINGLE = Regex("""(\d{1,3})\s*(min|minutos|minutes|minuten|m\b|h\b|horas?|hours?|heures?|stunden?)""")

    fun detect(message: String): OfflineIntent {
        val raw = message.trim()
        if (raw.isEmpty()) return OfflineIntent.Unknown
        val t = fold(raw)

        LAUNCH.matchEntire(raw)?.let { m ->
            val title = m.groupValues[1].trim().trimEnd('.', '!', '?')
            // "juega algo ligero" o "abre los abandonados" no son títulos.
            val folded = fold(title)
            if (title.isNotEmpty() && mood(folded) == null && minutes(folded) == null && list(folded) == null) {
                return OfflineIntent.Launch(title)
            }
        }

        // "Descarga las carátulas que faltan" es actualizar, aunque nombre una lista: manda el verbo.
        if (isMetadataUpdate(t)) return OfflineIntent.UpdateMetadata

        list(t)?.let { return OfflineIntent.ShowList(it) }

        val range = minutes(t)
        val mood = mood(t)
        if (range != null || mood != null) {
            val (lo, hi) = range ?: (if (mood == SessionMood.Light) 15 to 30 else 30 to 45)
            return OfflineIntent.Plan(lo, hi, mood ?: SessionMood.Any)
        }

        return when {
            has(t, "limpi", "clean", "curat", "revisa", "organiz", "aufraum", "nettoy") -> OfflineIntent.Cleanup
            word(t, "arco", "arcos", "arc", "arcs", "saga", "sagas") || has(t, "maraton", "marathon") -> OfflineIntent.Arcs
            has(t, "que juego", "what should i play", "recomien", "recommend", "suggest", "sugier", "que jugar", "what to play", "o que jogar", "quoi jouer", "was soll ich spielen") -> OfflineIntent.Recommend
            word(t, "hora", "horas", "hour", "hours", "tiempo", "time", "semana", "week", "stats", "heure", "heures", "stunde", "stunden", "semaine", "woche") ||
                has(t, "estadist", "statist", "jugado", "played", "playtime") -> OfflineIntent.Stats
            else -> OfflineIntent.Unknown
        }
    }

    private fun isMetadataUpdate(t: String): Boolean =
        has(t, "metadat", "caratula", "portada", "cover", "artwork", "scrap", "capa", "jaquette") &&
            has(t, "actualiz", "descarg", "update", "download", "baja", "atualiz", "mettre", "aktualis", "refresh", "fetch")

    /** Rango de minutos de una frase ("30-40 min", "media hora", "an hour and a half"…). */
    fun minutes(t: String): Pair<Int, Int>? {
        RANGE.find(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val hours = m.groupValues[3].startsWith("h") || m.groupValues[3].startsWith("stund")
            val (lo, hi) = if (hours) a * 60 to b * 60 else a to b
            if (hi >= lo && hi > 0) return lo to hi
        }
        when {
            has(t, "hora y media", "an hour and a half", "hour and a half", "uma hora e meia", "une heure et demie", "anderthalb") -> return 80 to 100
            has(t, "media hora", "half an hour", "meia hora", "demi-heure", "demi heure", "halbe stunde") -> return 25 to 35
            has(t, "un cuarto de hora", "quarter of an hour", "15 min") -> return 10 to 20
            has(t, "una hora", "an hour", "one hour", "uma hora", "une heure", "eine stunde") -> return 50 to 70
            has(t, "dos horas", "two hours", "duas horas", "deux heures", "zwei stunden") -> return 100 to 130
        }
        SINGLE.find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            val minutes = if (m.groupValues[2].startsWith("h") || m.groupValues[2].startsWith("stund")) n * 60 else n
            if (minutes > 0) return (minutes - 10).coerceAtLeast(5) to minutes + 5
        }
        return null
    }

    fun mood(t: String): SessionMood? = when {
        has(t, "continu", "seguir", "retom", "pick up", "pick back", "keep playing", "weiter", "reprendre") || word(t, "sigo") -> SessionMood.Continue
        has(t, "ligero", "ligera", "tranqui", "relax", "casual") || word(t, "light", "chill", "corto", "short", "leve", "leger", "locker", "kurz") -> SessionMood.Light
        has(t, "sin empezar", "never played", "algo distinto", "something different") ||
            word(t, "nuevo", "nueva", "nuevos", "new", "novo", "nouveau", "neu") -> SessionMood.Fresh
        else -> null
    }

    private fun list(t: String): DynamicList? = when {
        has(
            t, "nunca abiert", "nunca he abiert", "no he abierto", "sin abrir", "never opened", "never played", "unplayed",
            "sin jugar", "nunca jugad", "nunca he jugad", "nunca abert", "jamais", "nie gespielt",
        ) -> DynamicList.NeverOpened
        has(t, "abandon", "dejad", "a medias", "half finished", "unfinished", "sin terminar", "inacabad") -> DynamicList.Abandoned
        has(t, "sin caratula", "sin portada", "missing cover", "no cover", "without cover", "falta arte", "missing art", "sem capa", "sans jaquette", "ohne cover") -> DynamicList.ArtworkMissing
        has(t, "arte completo", "complete art", "full art", "con caratula", "with cover") -> DynamicList.ArtworkComplete
        has(t, "duplicad", "duplicate", "repetid", "doublon", "doppelt") -> DynamicList.Duplicates
        has(t, "incomplet", "falta disco", "missing disc", "discos que faltan") -> DynamicList.IncompleteSets
        has(t, "mejor rendimiento", "mejor van", "mejor funcion", "best performance", "run best", "runs best", "best on this", "rodam melhor", "tournent le mieux", "laufen am besten") -> DynamicList.BestOnDevice
        has(t, "mal nombrad", "poorly named", "badly named", "bad names", "mal nomead", "mal nommes") -> DynamicList.PoorlyNamed
        has(t, "regiones", "regions", "regioes", "régions", "regionen") -> DynamicList.MixedRegions
        has(t, "sin identificar", "unmatched", "not recognized", "no reconocid") -> DynamicList.Unmatched
        has(t, "anadido reciente", "añadido reciente", "recently added", "lo ultimo que anadi", "nuevos en la biblioteca") -> DynamicList.RecentlyAdded
        else -> null
    }

    /** Minúsculas y sin acentos: "Carátula" y "caratula" son lo mismo. */
    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")

    /** Contiene alguna raíz o frase ("abandon" vale para "abandonados"). */
    private fun has(t: String, vararg stems: String) = stems.any { t.contains(fold(it)) }

    /**
     * Contiene alguna palabra entera. Para las cortas, que dentro de otras
     * engañan: "hora" está en "ahora", "stat" en "PlayStation", "arc" en "arcade".
     */
    private fun word(t: String, vararg words: String) = words.any { w ->
        Regex("""(^|[^\p{L}\p{N}])${Regex.escape(fold(w))}($|[^\p{L}\p{N}])""").containsMatchIn(t)
    }
}
