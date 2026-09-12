package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.GameSystem
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.library.Names
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Qué imagen se personaliza. [media] es el nombre de archivo en [MediaCache] y
 * la marca en GameMeta.pinned (y el campo en RomFolder, para las carpetas).
 */
enum class ArtKind(val media: String) {
    Cover("cover"),
    Background("hero"),
    Logo("logo"),
    Icon("icon"),
}

/** Una imagen propuesta: [url] se descarga al elegirla, [thumb] se enseña en el selector. */
data class ArtCandidate(val url: String, val thumb: String, val label: String)

/**
 * "Personalizar carátula / fondo / logo / icono": busca imágenes de un juego en
 * un servicio concreto y guarda la elegida. Si el juego ya está identificado en
 * ese servicio se usa su id; si no, se busca por nombre y se ofrecen las
 * imágenes de los mejores resultados.
 *
 * Sirve para las tres clases de elemento de la biblioteca:
 *   · ROM   ("r:…")  — metadatos propios, identificada por hash o nombre.
 *   · App   ("a:…")  — metadatos propios, identificada por nombre.
 *   · Carpeta ("f:…") — no tiene metadatos; se busca por el nombre del sistema
 *     y la imagen se guarda en el propio RomFolder.
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

    /** ¿Hay imagen de esta clase que se pueda quitar? */
    fun has(key: String, kind: ArtKind): Boolean {
        repo.folderByKey(key)?.let { f ->
            return when (kind) {
                ArtKind.Cover -> f.cover
                ArtKind.Background -> f.hero
                ArtKind.Logo -> f.logo
                ArtKind.Icon -> f.icon
            } != null
        }
        val meta = repo.romByKey(key)?.meta ?: repo.appByKey(key)?.meta ?: return false
        return when (kind) {
            ArtKind.Cover -> meta.cover
            ArtKind.Background -> meta.hero
            ArtKind.Logo -> meta.logo
            ArtKind.Icon -> meta.icon
        } != null
    }

    suspend fun candidates(key: String, kind: ArtKind, service: Service): List<ArtCandidate> = withContext(Dispatchers.IO) {
        val rom = repo.romByKey(key)
        val app = repo.appByKey(key)
        val folder = repo.folderByKey(key)
        if (rom == null && app == null && folder == null) return@withContext emptyList()
        // Una carpeta no tiene metadatos propios: se busca por el nombre del sistema.
        val meta = rom?.meta ?: app?.meta ?: GameMeta()
        val system = (rom?.systemId ?: folder?.systemId)?.let { Systems.byId(it) }
        val name = meta.name?.takeIf { it.isNotBlank() }
            ?: rom?.title
            ?: app?.label
            ?: system?.name.orEmpty()
        if (name.isBlank()) return@withContext emptyList()
        val isApp = app != null
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
        val folderId = folderIdOf(key)
        if (folderId != null) {
            repo.setFolderArt(folderId, kind.media, path)
        } else {
            repo.updateMeta(key) { old ->
                val updated = when (kind) {
                    ArtKind.Cover -> old.copy(cover = path)
                    ArtKind.Background -> old.copy(hero = path)
                    ArtKind.Logo -> old.copy(logo = path)
                    ArtKind.Icon -> old.copy(icon = path)
                }
                updated.copy(pinned = (old.pinned + kind.media).distinct())
            }
        }
        repo.flush()
        return true
    }

    /** Quita la imagen: borra el archivo y suelta la marca, para que vuelva a descargarse. */
    suspend fun clear(key: String, kind: ArtKind) {
        media.delete(key, kind.media)
        val folderId = folderIdOf(key)
        if (folderId != null) {
            repo.setFolderArt(folderId, kind.media, null)
        } else {
            repo.updateMeta(key) { old ->
                val updated = when (kind) {
                    ArtKind.Cover -> old.copy(cover = null)
                    ArtKind.Background -> old.copy(hero = null)
                    ArtKind.Logo -> old.copy(logo = null)
                    ArtKind.Icon -> old.copy(icon = null)
                }
                updated.copy(pinned = old.pinned - kind.media)
            }
        }
        repo.flush()
    }

    private fun folderIdOf(key: String): String? =
        key.takeIf { it.startsWith("f:") }?.removePrefix("f:")

    private suspend fun screenScraper(meta: GameMeta, name: String, system: GameSystem?, kind: ArtKind): List<ArtCandidate> {
        val ss = engine.screenScraper
        val games = meta.ssGameId?.let { ss.gameById(it) }?.let(::listOf)
            ?: ss.search(system?.ssId, name).take(MAX_GAMES)
        val types = when (kind) {
            ArtKind.Cover -> listOf("box-2D", "box-3D")
            ArtKind.Background -> listOf("fanart", "ss", "sstitle")
            ArtKind.Logo -> listOf("wheel-hd", "wheel", "screenmarquee")
            ArtKind.Icon -> listOf("wheel-hd", "wheel", "box-2D")
        }
        val width = when (kind) {
            ArtKind.Cover -> 640
            ArtKind.Background -> 1280
            ArtKind.Logo, ArtKind.Icon -> 512
        }
        // Los wheels llevan transparencia: en PNG para logo e icono.
        val jpg = kind == ArtKind.Cover || kind == ArtKind.Background
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
        // IGDB no publica logos con transparencia.
        if (kind == ArtKind.Logo) return emptyList()
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
                ArtKind.Logo -> emptyList()
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
                ArtKind.Logo -> sgdb.logos(id)
                ArtKind.Icon -> sgdb.icons(id)
            }.take(PER_GAME).map { ArtCandidate(it.url, it.thumb, title) }
        }
    }

    private suspend fun retroAchievements(meta: GameMeta, name: String, system: GameSystem?, kind: ArtKind): List<ArtCandidate> {
        // RetroAchievements no tiene logos con transparencia.
        if (kind == ArtKind.Logo) return emptyList()
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
                ArtKind.Logo -> emptyList()
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
