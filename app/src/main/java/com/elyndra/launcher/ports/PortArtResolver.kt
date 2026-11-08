package com.elyndra.launcher.ports

import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.metadata.IgdbClient
import com.elyndra.launcher.metadata.LibretroNames
import com.elyndra.launcher.metadata.MetadataEngine
import com.elyndra.launcher.metadata.ServiceCredentials
import com.elyndra.launcher.metadata.SteamGridDbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** El arte del juego original de un port: URLs remotas (las pinta Coil con su caché). */
data class OriginalArt(val cover: String? = null, val hero: String? = null, val logo: String? = null) {
    val isEmpty: Boolean get() = cover == null && hero == null && logo == null
}

/**
 * Busca el arte del juego original de un port con los mismos clientes de
 * metadatos que la biblioteca: SteamGridDB (héroe, logo, carátula) e IGDB
 * (carátula, arte) si el usuario puso sus claves, y las miniaturas de
 * libretro (sin clave) como respaldo. Solo lo pide la tienda para el
 * destacado y la ficha, y se recuerda mientras dura la app.
 *
 * Para los juegos que ya están en la biblioteca la tienda usa su arte local
 * (el de MediaCache) y esto ni se llama.
 */
class PortArtResolver(private val engine: MetadataEngine, private val credentials: ServiceCredentials) {

    private val cache = ConcurrentHashMap<String, OriginalArt>()
    private val lock = Mutex()

    fun cached(port: PortEntry): OriginalArt? = cache[port.id]

    suspend fun resolve(port: PortEntry): OriginalArt = cache[port.id] ?: lock.withLock {
        cache[port.id] ?: withContext(Dispatchers.IO) { lookup(port) }.also { cache[port.id] = it }
    }

    private suspend fun lookup(port: PortEntry): OriginalArt {
        val title = port.original.title
        var art = OriginalArt()
        if (credentials.sgdbKey().isNotBlank()) {
            runCatching {
                val sgdb = engine.steamGridDb
                val id = port.original.sgdbId ?: sgdb.search(title).firstOrNull()?.id
                if (id != null) {
                    art = art.copy(
                        hero = sgdb.heroes(id).firstOrNull()?.url,
                        logo = sgdb.logos(id).firstOrNull()?.url,
                        cover = sgdb.grids(id, SteamGridDbClient.PORTRAIT).firstOrNull()?.url,
                    )
                }
            }
        }
        if ((art.cover == null || art.hero == null) && credentials.igdb().isComplete) {
            runCatching {
                val system = Systems.byId(port.original.platform)
                val game = port.original.igdbId?.let { engine.igdb.byId(it) }
                    ?: engine.igdb.search(title, system?.igdbIds?.ifEmpty { null }).firstOrNull()
                if (game != null) {
                    art = art.copy(
                        cover = art.cover ?: game.coverId?.let { IgdbClient.imageUrl(it, "cover_big_2x") },
                        hero = art.hero ?: (game.artworkIds + game.screenshotIds).firstOrNull()?.let { IgdbClient.imageUrl(it, "1080p") },
                    )
                }
            }
        }
        if (art.cover == null || art.hero == null) {
            runCatching {
                val folder = LibretroNames.folderFor(port.original.platform)
                if (folder != null) {
                    val name = LibretroNames.candidates(title, engine.libretro.names(folder), limit = 1).firstOrNull()
                    if (name != null) {
                        val lr = engine.libretro.art(folder, name)
                        art = art.copy(cover = art.cover ?: lr.boxart, hero = art.hero ?: lr.title)
                    }
                }
            }
        }
        return art
    }
}
