package com.elyndra.launcher.domain

import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.FileHashes
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.PlaySession
import com.elyndra.launcher.data.PlayStats
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder

/** Bibliotecas de prueba: carpetas, ROMs, apps y sesiones con lo justo. */
object Fixtures {

    const val NOW = 1_800_000_000_000L
    const val DAY = 24L * 60 * 60 * 1000
    const val MIN = 60L * 1000

    fun folder(id: String, systemId: String, emulatorId: String? = null) = RomFolder(
        id = id,
        systemId = systemId,
        treeUri = "content://tree/$id",
        rootDocId = "primary:$id",
        displayPath = "ROMs/$id",
        emulatorId = emulatorId,
        addedAt = NOW - 100 * DAY,
    )

    fun rom(
        id: String,
        folder: RomFolder,
        fileName: String = "$id.iso",
        meta: GameMeta = GameMeta(),
        stats: PlayStats = PlayStats(),
        md5: String? = null,
        emulatorId: String? = null,
        addedAt: Long = NOW - 50 * DAY,
    ) = RomEntry(
        id = id,
        folderId = folder.id,
        systemId = folder.systemId,
        docId = "primary:${folder.id}/$fileName",
        fileName = fileName,
        relPath = fileName,
        size = 1000,
        modified = 1,
        title = fileName.substringBeforeLast('.'),
        meta = meta,
        stats = stats,
        emulatorId = emulatorId,
        hashes = md5?.let { FileHashes(1000, 1, md5 = it) },
        addedAt = addedAt,
    )

    fun app(pkg: String, label: String, meta: GameMeta = GameMeta(), stats: PlayStats = PlayStats()) =
        AppEntry(pkg, label, NOW - 30 * DAY, meta, stats)

    fun session(key: String, daysAgo: Double, minutes: Int, emulatorId: String? = null, early: Boolean = minutes < 3) =
        PlaySession(
            key = key,
            start = NOW - (daysAgo * DAY).toLong(),
            minutes = minutes,
            emulatorId = emulatorId,
            end = NOW - (daysAgo * DAY).toLong() + minutes * MIN,
            earlyExit = early,
        )

    fun library(folders: List<RomFolder>, roms: List<RomEntry>, apps: List<AppEntry> = emptyList(), sessions: List<PlaySession> = emptyList()) =
        Library(folders = folders, roms = roms, apps = apps, sessions = sessions)

    /** Metadatos con carátula (y lo demás que se pida). */
    fun withArt(name: String? = null, genre: String? = null, release: String? = null, logo: Boolean = false, hero: Boolean = false) = GameMeta(
        scrapedAt = NOW - DAY,
        matched = true,
        sources = listOf("ss"),
        name = name,
        genre = genre,
        releaseDate = release,
        cover = "media/x/cover.jpg",
        logo = if (logo) "media/x/logo.png" else null,
        hero = if (hero) "media/x/hero.jpg" else null,
    )
}
