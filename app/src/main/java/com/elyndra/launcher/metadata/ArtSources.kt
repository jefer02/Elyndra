package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.GameSystem
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.library.Names
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Qué imagen se personaliza. [media] es el nombre de archivo en [MediaCache] y la marca en GameMeta.pinned. */
enum class ArtKind(val media: String) { Cover("cover"), Background("hero"), Icon("icon") }

/** Una imagen propuesta: [url] se descarga al elegirla, [thumb] se enseña en el selector. */
data class ArtCandidate(val url: String, val thumb: String, val label: String)

/**
 * "Personalizar carátula / fondo / icono": busca imágenes de un juego en un
 * servicio concreto y guarda la elegida. Si el juego ya está identificado en
 * ese servicio se usa su id; si no, se busca por nombre y se ofrecen las
 * imágenes de los mejores resultados.
 */
class ArtSources(
    private val engine: MetadataEngine,
    private val repo: LibraryRepository,
    private val media: MediaCache,
) {

    /** RetroAchievements solo cubre ROMs de sistemas con logros. */
    fun supports(key: String, service: Service): Boolean = when (service) {
        Service.RetroAchievements -> repo.romByKey(key)?.let { Systems.byId(it.systemId)?.raId } != null
        else -> true
    }

    suspend fun candidates(key: String, kind: ArtKind, service: Service): List<ArtCandidate> = withContext(Dispatchers.IO) {
        val rom = repo.romByKey(key)
        val app = repo.appByKey(key)
        val meta = rom?.meta ?: app?.meta ?: return@withContext emptyList()
        val name = meta.name?.takeIf { it.isNotBlank() } ?: rom?.title ?: app?.label.orEmpty()
        val system = rom?.let { Systems.byId(it.systemId) }
        val isApp = rom == null
        when (service) {
            Service.ScreenScraper -> screenScraper(meta, name, system, kind)
            Service.Igdb -> igdb(meta, name, system, isApp, kind)
            Service.SteamGridDb -> steamGridDb(meta, name, isApp, kind)
            Service.RetroAchievements -> retroAchievements(meta, name, system, kind)
        }.distinctBy { it.url }.take(MAX_CANDIDATES)
    }

    /** Descarga la imagen elegida y la fija para que "Actualizar metadatos" no la sustituya. */
    suspend fun apply(key: String, kind: ArtKind, url: String): Boolean {
        val path = media.download(url, key, kind.media) ?: return false
        repo.updateMeta(key) { old ->
            val updated = when (kind) {
                ArtKind.Cover -> old.copy(cover = path)
                ArtKind.Background -> old.copy(hero = path)
                ArtKind.Icon -> old.copy(icon = path)
            }
            updated.copy(pinned = (old.pinned + kind.media).distinct())
        }
        repo.flush()
        return true
    }

    private suspend fun screenScraper(meta: GameMeta, name: String, system: GameSystem?, kind: ArtKind): List<ArtCandidate> {
        val ss = engine.screenScraper
        val games = meta.ssGameId?.let { ss.gameById(it) }?.let(::listOf)
            ?: ss.search(system?.ssId, name).take(MAX_GAMES)
        val types = when (kind) {
            ArtKind.Cover -> listOf("box-2D", "box-3D")
            ArtKind.Background -> listOf("fanart", "ss", "sstitle")
            ArtKind.Icon -> listOf("wheel-hd", "wheel", "box-2D")
        }
        val width = when (kind) {
            ArtKind.Cover -> 640
            ArtKind.Background -> 1280
            ArtKind.Icon -> 512
        }
        // Los wheels llevan transparencia: en PNG para el icono.
        val jpg = kind != ArtKind.Icon
        return games.flatMap { g ->
            val title = g.name(emptyList()).orEmpty()
            g.medias.filter { it.type in types }
                .sortedBy { types.indexOf(it.type) }
                .map { m ->
                    ArtCandidate(
                        url = ScreenScraperClient.sizedMediaUrl(m, width, jpg),
                        thumb = ScreenScraperClient.sizedMediaUrl(m, 320, jpg),
                        label = listOfNotNull(title, m.region?.uppercase()).joinToString(" · "),
                    )
                }
        }
    }

    private suspend fun igdb(meta: GameMeta, name: String, system: GameSystem?, isApp: Boolean, kind: ArtKind): List<ArtCandidate> {
        val igdb = engine.igdb
        val games = meta.igdbId?.let { igdb.byId(it) }?.let(::listOf)
            ?: igdb.search(name, if (isApp) null else system?.igdbIds?.ifEmpty { null }).take(MAX_GAMES)
        return games.flatMap { g ->
            when (kind) {
                ArtKind.Cover, ArtKind.Icon -> listOfNotNull(g.coverId).map {
                    ArtCandidate(IgdbClient.imageUrl(it, "cover_big_2x"), IgdbClient.imageUrl(it, "cover_big"), g.name)
                }
                ArtKind.Background ->
                    g.artworkIds.map { ArtCandidate(IgdbClient.imageUrl(it, "1080p"), IgdbClient.imageUrl(it, "screenshot_med"), g.name) } +
                        g.screenshotIds.map { ArtCandidate(IgdbClient.imageUrl(it, "screenshot_huge"), IgdbClient.imageUrl(it, "screenshot_med"), g.name) }
            }
        }
    }

    private suspend fun steamGridDb(meta: GameMeta, name: String, isApp: Boolean, kind: ArtKind): List<ArtCandidate> {
        val sgdb = engine.steamGridDb
        val games = meta.sgdbId?.let { listOf(it to name) }
            ?: sgdb.search(name).take(SGDB_GAMES).map { it.id to it.name }
        return games.flatMap { (id, title) ->
            when (kind) {
                ArtKind.Cover -> sgdb.grids(id, if (isApp) SteamGridDbClient.SQUARE + SteamGridDbClient.PORTRAIT else SteamGridDbClient.PORTRAIT)
                ArtKind.Background -> sgdb.heroes(id)
                ArtKind.Icon -> sgdb.icons(id)
            }.take(PER_GAME).map { ArtCandidate(it.url, it.thumb, title) }
        }
    }

    private suspend fun retroAchievements(meta: GameMeta, name: String, system: GameSystem?, kind: ArtKind): List<ArtCandidate> {
        val ra = engine.retroAchievements
        val consoleId = system?.raId ?: return emptyList()
        val ids = meta.ra?.gameId?.let(::listOf) ?: ra.gameList(consoleId).asSequence()
            .filterNot { it.title.startsWith("~") }
            .map { it to it.title.split(" | ").maxOf { t -> Names.similarity(name, t) } }
            .filter { it.second >= 0.6 }
            .sortedByDescending { it.second }
            .take(SGDB_GAMES)
            .map { it.first.id }
            .toList()
        return ids.flatMap { id ->
            val p = ra.progress(id)
            when (kind) {
                ArtKind.Cover -> listOfNotNull(p.boxArt)
                ArtKind.Background -> listOfNotNull(p.imageIngame, p.imageTitle)
                ArtKind.Icon -> listOfNotNull(p.imageIcon)
            }.map { path ->
                val url = RetroAchievementsClient.mediaUrl(path)
                ArtCandidate(url, url, p.title)
            }
        }
    }

    private companion object {
        const val MAX_GAMES = 5
        const val SGDB_GAMES = 3
        const val PER_GAME = 20
        const val MAX_CANDIDATES = 60
    }
}
