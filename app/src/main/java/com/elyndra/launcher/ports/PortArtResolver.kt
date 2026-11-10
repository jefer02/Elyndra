package com.elyndra.launcher.ports

import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.domain.ports.OriginalArt
import com.elyndra.launcher.domain.ports.PortArt
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

/**
 * Busca el arte del juego original de un port en el orden de [PortArt.chain]:
 * el del catálogo, luego SteamGridDB e IGDB si el usuario puso sus claves, la
 * CDN de Steam (juegos de PC), las miniaturas de libretro y la tarjeta de
 * GitHub. Lo pide la tienda para las cápsulas, el destacado y la ficha, y se
 * recuerda mientras dura la app.
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
        val title = port.game.name
        val libretroFolder = LibretroNames.folderFor(port.platform)
        val chain = PortArt.chain(
            port,
            hasSgdbKey = credentials.sgdbKey().isNotBlank(),
            hasIgdbKeys = credentials.igdb().isComplete,
            hasLibretroFolder = libretroFolder != null,
        )
        return PortArt.resolve(chain) { source ->
            runCatching {
                when (source) {
                    PortArt.Source.Catalog -> PortArt.fromCatalog(port)
                    PortArt.Source.SteamGridDb -> {
                        val sgdb = engine.steamGridDb
                        val id = port.game.sgdbId ?: sgdb.search(title).firstOrNull()?.id
                        id?.let {
                            OriginalArt(
                                cover = sgdb.grids(it, SteamGridDbClient.PORTRAIT).firstOrNull()?.url,
                                hero = sgdb.heroes(it).firstOrNull()?.url,
                                logo = sgdb.logos(it).firstOrNull()?.url,
                            )
                        }
                    }
                    PortArt.Source.Igdb -> {
                        val system = Systems.byId(port.platform)
                        val game = port.game.igdbId?.let { engine.igdb.byId(it) }
                            ?: engine.igdb.search(title, system?.igdbIds?.ifEmpty { null }).firstOrNull()
                        game?.let { g ->
                            OriginalArt(
                                cover = g.coverId?.let { IgdbClient.imageUrl(it, "cover_big_2x") },
                                hero = (g.artworkIds + g.screenshotIds).firstOrNull()?.let { IgdbClient.imageUrl(it, "1080p") },
                            )
                        }
                    }
                    PortArt.Source.Steam -> port.game.steamAppId?.let { PortArt.steam(it) }
                    PortArt.Source.Libretro -> libretroFolder?.let { folder ->
                        LibretroNames.candidates(title, engine.libretro.names(folder), limit = 1).firstOrNull()?.let { name ->
                            val lr = engine.libretro.art(folder, name)
                            OriginalArt(cover = lr.boxart, hero = lr.title)
                        }
                    }
                    PortArt.Source.GitHub -> PortArt.gitHub(port)
                }
            }.getOrNull()
        }
    }
}
