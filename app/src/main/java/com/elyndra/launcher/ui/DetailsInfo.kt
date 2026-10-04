package com.elyndra.launcher.ui

import androidx.annotation.StringRes
import com.elyndra.launcher.R
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.PlayStats
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

/**
 * La sección "Información" de la ficha, ya decidida: solo lo que hay.
 *
 * Un campo vacío no se enseña —ni con guion ni con "Desconocido"—, así que
 * la ficha de un juego sin metadatos no es una tabla de huecos. Nada se
 * inventa: todo sale de [GameMeta], de las estadísticas o del archivo.
 *
 * Es lógica pura (sin Compose) para poder probarla: las fechas las formatea
 * quien llama, en el idioma de la app.
 */
data class DetailsInfo(
    /** Géneros como etiquetas sueltas ("Acción, Aventura" → dos). */
    val genres: List<String>,
    /** Pares rótulo/valor de la rejilla, en el orden en que se leen. */
    val facts: List<Fact>,
    /** Ids de `Service` de los que salieron los datos, sin repetir. */
    val sources: List<String>,
    /** Paquete de una app Android: va aparte, pequeño y apagado. */
    val packageName: String?,
) {
    data class Fact(@StringRes val label: Int, val value: String)

    val isEmpty: Boolean get() = genres.isEmpty() && facts.isEmpty() && sources.isEmpty() && packageName == null

    companion object {
        /**
         * [formatDate] da formato a un instante (la última partida) y
         * [formatDay] a un día (el lanzamiento); [fileSize] ya viene formateado.
         */
        fun of(
            meta: GameMeta,
            stats: PlayStats,
            file: String? = null,
            fileSize: String? = null,
            packageName: String? = null,
            formatDate: (Long) -> String,
            formatDay: (LocalDate) -> String,
        ): DetailsInfo {
            val facts = buildList {
                clean(meta.developer)?.let { add(Fact(R.string.details_developer, it)) }
                clean(meta.publisher)?.let { add(Fact(R.string.details_publisher, it)) }
                releaseText(meta.releaseDate, formatDay)?.let { add(Fact(R.string.details_release, it)) }
                clean(meta.players)?.let { add(Fact(R.string.details_players, it)) }
                ratingText(meta.rating)?.let { add(Fact(R.string.details_rating, it)) }
                if (stats.lastPlayed > 0) add(Fact(R.string.details_last_played, formatDate(stats.lastPlayed)))
                clean(file)?.let { add(Fact(R.string.details_file, it)) }
                clean(fileSize)?.let { add(Fact(R.string.details_size, it)) }
            }
            return DetailsInfo(
                genres = splitGenres(meta.genre),
                facts = facts,
                sources = meta.sources.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                packageName = clean(packageName),
            )
        }

        /** Texto que no dice nada: vacío, guiones o "desconocido" de alguna fuente. */
        fun clean(text: String?): String? {
            val t = text?.trim() ?: return null
            if (t.isEmpty() || t.all { it == '-' || it == '?' || it == '–' || it == '—' }) return null
            if (t.equals("unknown", ignoreCase = true) || t.equals("n/a", ignoreCase = true)) return null
            return t
        }

        /** "Acción, Aventura / Plataformas" → ["Acción", "Aventura", "Plataformas"], sin repetidos. */
        fun splitGenres(genre: String?): List<String> {
            val raw = clean(genre) ?: return emptyList()
            val seen = HashSet<String>()
            return raw.split(',', '/', ';', '|', '·')
                .mapNotNull { clean(it) }
                .filter { seen.add(it.lowercase()) }
        }

        /** Nota 0…1 → "87 / 100"; fuera de rango o cero, nada (cero es "sin nota"). */
        fun ratingText(rating: Float?): String? {
            val r = rating ?: return null
            if (r.isNaN() || r <= 0f || r > 1f) return null
            return "${(r * 100).roundToInt()} / 100"
        }

        /** "AAAA-MM-DD" con formato del idioma; "AAAA" (o lo que no se entienda) tal cual. */
        fun releaseText(date: String?, formatDay: (LocalDate) -> String): String? {
            val d = clean(date) ?: return null
            if (d.length == 10) {
                try {
                    return formatDay(LocalDate.parse(d))
                } catch (_: DateTimeParseException) {
                    // Se enseña como venga.
                }
            }
            return d
        }
    }
}
