package com.elyndra.launcher.domain.ports

/** El arte del juego original de un port: URLs remotas (las pinta Coil con su caché; al instalar van a MediaCache). */
data class OriginalArt(val cover: String? = null, val hero: String? = null, val logo: String? = null, val icon: String? = null) {
    val isEmpty: Boolean get() = cover == null && hero == null && logo == null && icon == null

    /** Lo que falta aquí, de [other]. */
    fun orElse(other: OriginalArt): OriginalArt = OriginalArt(cover ?: other.cover, hero ?: other.hero, logo ?: other.logo, icon ?: other.icon)
}

/**
 * De dónde sale el arte de un port, en orden (Kotlin puro, PortArtTest).
 *
 * Primero el del catálogo, que el generador ya resolvió. Lo que falte se
 * busca en la app: SteamGridDB e IGDB si el usuario tiene sus claves, la CDN
 * de Steam para los juegos de PC con `steamAppId`, las miniaturas de libretro
 * y, lo último, la tarjeta de GitHub del repositorio (solo como carátula).
 */
object PortArt {

    enum class Source { Catalog, SteamGridDb, Igdb, Steam, Libretro, GitHub }

    /** Plataformas cuyo juego original puede estar en Steam. */
    val STEAM_PLATFORMS = setOf("pc", "dos")

    /** Los pasos que tocan para [port], en orden. */
    fun chain(port: PortEntry, hasSgdbKey: Boolean, hasIgdbKeys: Boolean, hasLibretroFolder: Boolean): List<Source> = buildList {
        add(Source.Catalog)
        if (hasSgdbKey) add(Source.SteamGridDb)
        if (hasIgdbKeys) add(Source.Igdb)
        if (port.game.steamAppId != null && port.platform in STEAM_PLATFORMS) add(Source.Steam)
        if (hasLibretroFolder) add(Source.Libretro)
        add(Source.GitHub)
    }

    /** Hay carátula y héroe: no hace falta seguir buscando (el logo es un extra). */
    fun complete(art: OriginalArt): Boolean = art.cover != null && art.hero != null

    fun fromCatalog(port: PortEntry): OriginalArt =
        OriginalArt(port.game.art.cover, port.game.art.hero ?: port.game.art.screenshots.firstOrNull(), port.game.art.logo, port.game.art.icon)

    /** La CDN de Steam por appid (las mismas rutas que SteamParser). */
    fun steam(appId: Long): OriginalArt = OriginalArt(
        cover = "$STEAM_CDN/$appId/library_600x900_2x.jpg",
        hero = "$STEAM_CDN/$appId/library_hero.jpg",
        logo = "$STEAM_CDN/$appId/logo.png",
    )

    /** El último recurso: la tarjeta OpenGraph del repositorio, solo como carátula. */
    fun gitHub(port: PortEntry): OriginalArt = OriginalArt(cover = port.image)

    /**
     * Recorre [chain] pidiendo a [fetch] cada fuente hasta tener el arte
     * completo; cada paso solo rellena lo que aún falta. Un paso que falla
     * (null) no corta la cadena.
     */
    suspend fun resolve(chain: List<Source>, fetch: suspend (Source) -> OriginalArt?): OriginalArt {
        var art = OriginalArt()
        for (source in chain) {
            if (complete(art)) break
            // La tarjeta de GitHub solo cuenta si no apareció ninguna carátula del juego.
            if (source == Source.GitHub && art.cover != null) break
            fetch(source)?.let { art = art.orElse(it) }
        }
        return art
    }

    private const val STEAM_CDN = "https://cdn.akamai.steamstatic.com/steam/apps"
}
