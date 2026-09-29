package com.elyndra.launcher.data.db

import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.ArtOrigin
import com.elyndra.launcher.data.FileHashes
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.PlaySession
import com.elyndra.launcher.data.PlayStats
import com.elyndra.launcher.data.RaInfo
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder

/**
 * Ida y vuelta entre la instantánea de la biblioteca y las filas de Room.
 *
 * Kotlin puro, sin Android: así se prueba en la JVM que lo que se guarda es
 * exactamente lo que se vuelve a leer.
 */
object LibraryMapper {

    /** Clases de imagen, en el orden en que se guardan. Coinciden con los nombres de MediaCache. */
    val ART_KINDS = listOf("cover", "hero", "logo", "icon", "shot")

    /* ── instantánea → filas ──────────────────────────────────── */

    fun folder(f: RomFolder) = FolderEntity(
        id = f.id,
        systemId = f.systemId,
        treeUri = f.treeUri,
        rootDocId = f.rootDocId,
        displayPath = f.displayPath,
        emulatorId = f.emulatorId,
        addedAt = f.addedAt,
        lastScan = f.lastScan,
        cover = f.cover,
        hero = f.hero,
        logo = f.logo,
        icon = f.icon,
    )

    fun rom(r: RomEntry) = RomEntity(
        id = r.id,
        folderId = r.folderId,
        systemId = r.systemId,
        docId = r.docId,
        fileName = r.fileName,
        relPath = r.relPath,
        size = r.size,
        modified = r.modified,
        isDirectory = r.isDirectory,
        title = r.title,
        emulatorId = r.emulatorId,
        mainDocId = r.mainDocId,
        mainFile = r.mainFile,
        pcGameId = r.pcGameId,
        hashSize = r.hashes?.size,
        hashModified = r.hashes?.modified,
        crc = r.hashes?.crc,
        md5 = r.hashes?.md5,
        sha1 = r.hashes?.sha1,
        raHash = r.hashes?.ra,
        raComputed = r.hashes?.raComputed ?: false,
        addedAt = r.addedAt,
    )

    fun app(a: AppEntry) = AppEntity(a.packageName, a.label, a.addedAt)

    /**
     * La parte de texto de los metadatos, o null si no hay nada que guardar
     * (un juego que nunca pasó por el motor de metadatos no tiene fila).
     */
    fun metadata(key: String, m: GameMeta): GameMetadataEntity? {
        if (textOnly(m) == EMPTY_TEXT) return null
        return GameMetadataEntity(
            gameKey = key,
            name = m.name,
            description = m.description,
            releaseDate = m.releaseDate,
            developer = m.developer,
            publisher = m.publisher,
            genre = m.genre,
            players = m.players,
            rating = m.rating,
            scrapedAt = m.scrapedAt,
            matched = m.matched,
            sources = m.sources.joinToString(","),
            matchedBy = m.matchedBy,
            matchConfidence = m.matchConfidence,
            ssGameId = m.ssGameId,
            igdbId = m.igdbId,
            sgdbId = m.sgdbId,
            raGameId = m.ra?.gameId,
            raTitle = m.ra?.title,
            raAchievements = m.ra?.achievements,
            raEarned = m.ra?.earned,
            raEarnedHardcore = m.ra?.earnedHardcore,
            raPoints = m.ra?.points,
            raEarnedPoints = m.ra?.earnedPoints,
            raMatchedBy = m.ra?.matchedBy,
            raUpdatedAt = m.ra?.updatedAt,
        )
    }

    /** Una fila por clase de imagen que tenga archivo, marca de fijada u origen conocido. */
    fun artwork(key: String, m: GameMeta): List<ArtworkEntity> = ART_KINDS.mapNotNull { kind ->
        val path = pathOf(m, kind)
        val pinned = kind in m.pinned
        val origin = m.artOrigins[kind]
        if (path == null && !pinned && origin == null) {
            null
        } else {
            ArtworkEntity(key, kind, path, origin?.source, origin?.url, pinned)
        }
    }

    fun stats(key: String, s: PlayStats): PlayStatsEntity? =
        if (s == PlayStats()) null else PlayStatsEntity(key, s.minutes, s.lastPlayed, s.launches)

    fun session(s: PlaySession) = PlaySessionEntity(
        gameKey = s.key,
        startedAt = s.start,
        endedAt = s.end,
        minutes = s.minutes,
        emulatorId = s.emulatorId,
        source = s.source,
        earlyExit = s.earlyExit,
    )

    /* ── filas → instantánea ──────────────────────────────────── */

    fun assemble(
        folders: List<FolderEntity>,
        exclusions: List<FolderExclusionEntity>,
        roms: List<RomEntity>,
        apps: List<AppEntity>,
        metadata: List<GameMetadataEntity>,
        artwork: List<ArtworkEntity>,
        stats: List<PlayStatsEntity>,
        sessions: List<PlaySessionEntity>,
        state: LibraryStateEntity?,
    ): Library {
        val excluded = exclusions.groupBy({ it.folderId }, { it.docId })
        val metaByKey = metadata.associateBy { it.gameKey }
        val artByKey = artwork.groupBy { it.gameKey }
        val statsByKey = stats.associateBy { it.gameKey }
        val folderIds = folders.mapTo(HashSet()) { it.id }

        fun meta(key: String) = gameMeta(metaByKey[key], artByKey[key].orEmpty())
        fun playStats(key: String) = statsByKey[key]?.let { PlayStats(it.minutes, it.lastPlayed, it.launches) } ?: PlayStats()

        return Library(
            folders = folders.map { f ->
                RomFolder(
                    id = f.id,
                    systemId = f.systemId,
                    treeUri = f.treeUri,
                    rootDocId = f.rootDocId,
                    displayPath = f.displayPath,
                    emulatorId = f.emulatorId,
                    addedAt = f.addedAt,
                    lastScan = f.lastScan,
                    cover = f.cover,
                    hero = f.hero,
                    logo = f.logo,
                    icon = f.icon,
                    excluded = excluded[f.id].orEmpty().toSet(),
                )
            },
            // Una ROM sin carpeta no se puede enseñar ni lanzar: no entra.
            roms = roms.filter { it.folderId in folderIds }.map { r ->
                val key = "r:${r.id}"
                RomEntry(
                    id = r.id,
                    folderId = r.folderId,
                    systemId = r.systemId,
                    docId = r.docId,
                    fileName = r.fileName,
                    relPath = r.relPath,
                    size = r.size,
                    modified = r.modified,
                    isDirectory = r.isDirectory,
                    title = r.title,
                    meta = meta(key),
                    stats = playStats(key),
                    emulatorId = r.emulatorId,
                    hashes = hashes(r),
                    mainDocId = r.mainDocId,
                    mainFile = r.mainFile,
                    pcGameId = r.pcGameId,
                    addedAt = r.addedAt,
                )
            },
            apps = apps.map { a ->
                val key = "a:${a.packageName}"
                AppEntry(a.packageName, a.label, a.addedAt, meta(key), playStats(key))
            },
            sessions = sessions.sortedBy { it.startedAt }.map { s ->
                PlaySession(
                    key = s.gameKey,
                    start = s.startedAt,
                    minutes = s.minutes,
                    emulatorId = s.emulatorId,
                    end = s.endedAt,
                    source = s.source,
                    earlyExit = s.earlyExit,
                )
            },
            lastAutoScan = state?.lastAutoScan ?: 0,
        )
    }

    fun gameMeta(m: GameMetadataEntity?, art: List<ArtworkEntity>): GameMeta {
        val byKind = art.associateBy { it.kind }
        val base = if (m == null) {
            GameMeta()
        } else {
            GameMeta(
                scrapedAt = m.scrapedAt,
                matched = m.matched,
                sources = m.sources.split(',').filter { it.isNotBlank() },
                name = m.name,
                description = m.description,
                releaseDate = m.releaseDate,
                developer = m.developer,
                publisher = m.publisher,
                genre = m.genre,
                players = m.players,
                rating = m.rating,
                ssGameId = m.ssGameId,
                igdbId = m.igdbId,
                sgdbId = m.sgdbId,
                ra = m.raGameId?.let { id ->
                    RaInfo(
                        gameId = id,
                        title = m.raTitle.orEmpty(),
                        achievements = m.raAchievements ?: 0,
                        earned = m.raEarned ?: 0,
                        earnedHardcore = m.raEarnedHardcore ?: 0,
                        points = m.raPoints ?: 0,
                        earnedPoints = m.raEarnedPoints ?: 0,
                        matchedBy = m.raMatchedBy.orEmpty(),
                        updatedAt = m.raUpdatedAt ?: 0,
                    )
                },
                matchedBy = m.matchedBy,
                matchConfidence = m.matchConfidence,
            )
        }
        if (byKind.isEmpty()) return base
        return base.copy(
            cover = byKind["cover"]?.localPath,
            hero = byKind["hero"]?.localPath,
            logo = byKind["logo"]?.localPath,
            icon = byKind["icon"]?.localPath,
            screenshot = byKind["shot"]?.localPath,
            pinned = ART_KINDS.filter { byKind[it]?.pinned == true },
            artOrigins = byKind.values
                .mapNotNull { a -> a.source?.let { a.kind to ArtOrigin(it, a.remoteUrl) } }
                .toMap(),
        )
    }

    private fun hashes(r: RomEntity): FileHashes? {
        val size = r.hashSize ?: return null
        return FileHashes(
            size = size,
            modified = r.hashModified ?: 0,
            crc = r.crc,
            md5 = r.md5,
            sha1 = r.sha1,
            ra = r.raHash,
            raComputed = r.raComputed,
        )
    }

    private fun pathOf(m: GameMeta, kind: String): String? = when (kind) {
        "cover" -> m.cover
        "hero" -> m.hero
        "logo" -> m.logo
        "icon" -> m.icon
        "shot" -> m.screenshot
        else -> null
    }

    /** Los metadatos sin lo que va a la tabla de imágenes. */
    private fun textOnly(m: GameMeta) = m.copy(
        cover = null,
        hero = null,
        logo = null,
        icon = null,
        screenshot = null,
        pinned = emptyList(),
        artOrigins = emptyMap(),
    )

    private val EMPTY_TEXT = GameMeta()
}
