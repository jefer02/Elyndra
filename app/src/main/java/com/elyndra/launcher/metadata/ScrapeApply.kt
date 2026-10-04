package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.ArtOrigin
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.MatchMethod

/**
 * Cómo entra en un juego lo que trae una pasada de metadatos (Kotlin puro,
 * se prueba en la JVM). Lo que el usuario decidió a mano manda siempre:
 *
 *  · el nombre fijado ([GameMeta.nameLocked]) no se toca;
 *  · las imágenes fijadas ([GameMeta.pinned]) no se descargan ni se sustituyen;
 *  · una identificación a mano ([MatchMethod.MANUAL]) conserva sus ids y su
 *    confianza, aunque la pasada crea haber encontrado otro juego.
 */
object ScrapeApply {

    private fun pick(kind: String, fresh: String?, old: String?, pinned: List<String>): String? =
        if (kind in pinned) old else fresh ?: old

    /** Rutas de lo descargado en esta pasada (null = nada nuevo de esa clase). */
    data class Art(
        val cover: String? = null,
        val hero: String? = null,
        val logo: String? = null,
        val screenshot: String? = null,
        val icon: String? = null,
        val origins: Map<String, ArtOrigin> = emptyMap(),
    )

    /** [consulted]: las fuentes de descripción que respondieron (ver [GameDescriptionUpdate]). */
    fun apply(old: GameMeta, merged: MergedMetadata, lang: String, art: Art, now: Long, consulted: Set<String> = emptySet()): GameMeta {
        val described = GameDescriptionUpdate.apply(old, merged.descriptions, lang, consulted)
        val text = merged.text
        val manual = old.matchedBy == MatchMethod.MANUAL
        val matched = merged.matched
        return old.copy(
            scrapedAt = now,
            matched = matched || old.matched,
            sources = if (matched) merged.sources.map { it.id } else old.sources,
            // El nombre de los metadatos se actualiza, pero si el usuario fijó
            // uno, es ese el que se enseña (GameMeta.lockedName) y no se pisa.
            name = text[MetaField.Name] ?: old.name,
            userName = old.userName,
            nameLocked = old.nameLocked,
            description = described.description,
            descriptionLang = described.descriptionLang,
            descriptions = described.descriptions,
            descriptionCheckedLang = described.descriptionCheckedLang,
            descriptionSources = described.descriptionSources,
            releaseDate = text[MetaField.ReleaseDate] ?: old.releaseDate,
            developer = text[MetaField.Developer] ?: old.developer,
            publisher = text[MetaField.Publisher] ?: old.publisher,
            genre = text[MetaField.Genre] ?: old.genre,
            players = text[MetaField.Players] ?: old.players,
            rating = merged.rating ?: old.rating,
            // Lo fijado a mano manda: aunque la pasada trajera algo, se queda lo elegido.
            cover = pick("cover", art.cover, old.cover, old.pinned),
            hero = pick("hero", art.hero, old.hero, old.pinned),
            logo = pick("logo", art.logo, old.logo, old.pinned),
            screenshot = art.screenshot ?: old.screenshot,
            icon = pick("icon", art.icon, old.icon, old.pinned),
            pinned = old.pinned,
            ssGameId = if (manual) old.ssGameId ?: merged.ssId else merged.ssId ?: old.ssGameId,
            igdbId = if (manual) old.igdbId ?: merged.igdbId else merged.igdbId ?: old.igdbId,
            sgdbId = if (manual) old.sgdbId ?: merged.sgdbId else merged.sgdbId ?: old.sgdbId,
            steamAppId = if (manual) old.steamAppId ?: merged.steamAppId else merged.steamAppId ?: old.steamAppId,
            libretroName = old.libretroName,
            ra = merged.ra ?: old.ra,
            artOrigins = old.artOrigins + art.origins.filterKeys { it !in old.pinned },
            // Una identificación a mano no la corrige una pasada automática.
            matchedBy = if (manual) old.matchedBy else merged.matchedBy ?: old.matchedBy,
            matchConfidence = if (manual) old.matchConfidence else merged.confidence ?: old.matchConfidence,
        )
    }
}
