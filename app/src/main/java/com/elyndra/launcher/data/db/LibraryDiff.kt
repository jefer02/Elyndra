package com.elyndra.launcher.data.db

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.PlayStats

/** Filas que hay que escribir y borrar para pasar de una instantánea a otra. */
class LibraryChanges {
    val upsertFolders = ArrayList<FolderEntity>()
    val deleteFolders = ArrayList<FolderEntity>()
    val upsertExclusions = ArrayList<FolderExclusionEntity>()
    val deleteExclusions = ArrayList<FolderExclusionEntity>()
    val upsertRoms = ArrayList<RomEntity>()
    val deleteRoms = ArrayList<RomEntity>()
    val upsertApps = ArrayList<AppEntity>()
    val deleteApps = ArrayList<AppEntity>()
    val upsertMetadata = ArrayList<GameMetadataEntity>()
    val deleteMetadata = ArrayList<GameMetadataEntity>()
    val upsertArtwork = ArrayList<ArtworkEntity>()
    val deleteArtwork = ArrayList<ArtworkEntity>()
    val upsertStats = ArrayList<PlayStatsEntity>()
    val deleteStats = ArrayList<PlayStatsEntity>()
    val upsertSessions = ArrayList<PlaySessionEntity>()
    val deleteSessions = ArrayList<PlaySessionEntity>()

    /** Nuevo valor de `lastAutoScan`, si cambió. */
    var lastAutoScan: Long? = null

    val isEmpty: Boolean
        get() = lastAutoScan == null && listOf(
            upsertFolders, deleteFolders, upsertExclusions, deleteExclusions, upsertRoms, deleteRoms,
            upsertApps, deleteApps, upsertMetadata, deleteMetadata, upsertArtwork, deleteArtwork,
            upsertStats, deleteStats, upsertSessions, deleteSessions,
        ).all { it.isEmpty() }

    /** Total de filas afectadas (para registro y pruebas). */
    val size: Int
        get() = listOf(
            upsertFolders, deleteFolders, upsertExclusions, deleteExclusions, upsertRoms, deleteRoms,
            upsertApps, deleteApps, upsertMetadata, deleteMetadata, upsertArtwork, deleteArtwork,
            upsertStats, deleteStats, upsertSessions, deleteSessions,
        ).sumOf { it.size }
}

/**
 * Diferencia entre dos instantáneas de la biblioteca, en filas de Room.
 *
 * Toda modificación de la biblioteca pasa por `copy()`, así que lo que no se
 * tocó sigue siendo *la misma instancia*: se descarta por identidad (`===`)
 * antes de convertir nada a filas. Guardar después de cambiar una ROM entre
 * diez mil cuesta lo que esa ROM, no lo que la biblioteca.
 */
object LibraryDiff {

    fun between(old: Library, new: Library): LibraryChanges {
        val c = LibraryChanges()
        val folderIds = new.folders.mapTo(HashSet()) { it.id }

        if (old.folders !== new.folders) {
            val before = old.folders.associateBy { it.id }
            for (f in new.folders) {
                val o = before[f.id]
                if (o === f) continue
                val row = LibraryMapper.folder(f)
                if (o == null || LibraryMapper.folder(o) != row) c.upsertFolders += row
                val was = o?.excluded.orEmpty()
                if (f.excluded != was) {
                    (f.excluded - was).forEach { c.upsertExclusions += FolderExclusionEntity(f.id, it) }
                    (was - f.excluded).forEach { c.deleteExclusions += FolderExclusionEntity(f.id, it) }
                }
            }
            // Borrar la carpeta arrastra en cascada sus exclusiones y sus ROMs.
            for (o in old.folders) if (o.id !in folderIds) c.deleteFolders += LibraryMapper.folder(o)
        }

        if (old.roms !== new.roms) {
            val before = old.roms.associateBy { it.id }
            val after = HashSet<String>(new.roms.size * 2)
            for (r in new.roms) {
                // Una ROM cuya carpeta ya no existe no se puede guardar (clave
                // foránea) ni tiene sentido: se deja fuera.
                if (r.folderId !in folderIds) continue
                after += r.id
                val o = before[r.id]
                if (o === r) continue
                val row = LibraryMapper.rom(r)
                if (o == null || LibraryMapper.rom(o) != row) c.upsertRoms += row
                game(c, r.key, o?.meta, r.meta, o?.stats, r.stats)
            }
            for (o in old.roms) {
                if (o.id in after) continue
                c.deleteRoms += LibraryMapper.rom(o)
                game(c, o.key, o.meta, null, o.stats, null)
            }
        }

        if (old.apps !== new.apps) {
            val before = old.apps.associateBy { it.packageName }
            val after = new.apps.mapTo(HashSet()) { it.packageName }
            for (a in new.apps) {
                val o = before[a.packageName]
                if (o === a) continue
                val row = LibraryMapper.app(a)
                if (o == null || LibraryMapper.app(o) != row) c.upsertApps += row
                game(c, a.key, o?.meta, a.meta, o?.stats, a.stats)
            }
            for (o in old.apps) {
                if (o.packageName in after) continue
                c.deleteApps += LibraryMapper.app(o)
                game(c, o.key, o.meta, null, o.stats, null)
            }
        }

        if (old.sessions !== new.sessions) {
            val before = old.sessions.associateBy { it.key to it.start }
            val after = HashSet<Pair<String, Long>>(new.sessions.size * 2)
            for (s in new.sessions) {
                val id = s.key to s.start
                after += id
                val o = before[id]
                if (o == null || o != s) c.upsertSessions += LibraryMapper.session(s)
            }
            for (o in old.sessions) if ((o.key to o.start) !in after) c.deleteSessions += LibraryMapper.session(o)
        }

        if (old.lastAutoScan != new.lastAutoScan) c.lastAutoScan = new.lastAutoScan
        return c
    }

    /** Metadatos, imágenes y contadores de un juego ([newMeta] null = el juego se fue). */
    private fun game(
        c: LibraryChanges,
        key: String,
        oldMeta: GameMeta?,
        newMeta: GameMeta?,
        oldStats: PlayStats?,
        newStats: PlayStats?,
    ) {
        if (oldMeta !== newMeta) {
            val was = oldMeta?.let { LibraryMapper.metadata(key, it) }
            val now = newMeta?.let { LibraryMapper.metadata(key, it) }
            if (now != null && now != was) c.upsertMetadata += now
            if (now == null && was != null) c.deleteMetadata += was

            val artWas = oldMeta?.let { LibraryMapper.artwork(key, it) }.orEmpty().associateBy { it.kind }
            val artNow = newMeta?.let { LibraryMapper.artwork(key, it) }.orEmpty().associateBy { it.kind }
            for ((kind, row) in artNow) if (artWas[kind] != row) c.upsertArtwork += row
            for ((kind, row) in artWas) if (kind !in artNow) c.deleteArtwork += row
        }
        if (oldStats !== newStats) {
            val was = oldStats?.let { LibraryMapper.stats(key, it) }
            val now = newStats?.let { LibraryMapper.stats(key, it) }
            if (now != null && now != was) c.upsertStats += now
            if (now == null && was != null) c.deleteStats += was
        }
    }
}
