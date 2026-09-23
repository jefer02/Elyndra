package com.elyndra.launcher.data.db

import androidx.room.withTransaction
import com.elyndra.launcher.data.LegacyLibraryJson
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * La biblioteca en Room.
 *
 * Al arrancar se lee entera (cinco consultas planas, sin JOIN: con diez mil
 * ROMs son milisegundos) y se monta la instantánea. Cada guardado aplica, en
 * una sola transacción, las filas que calcula [LibraryDiff].
 *
 * La primera vez —base de datos recién creada, sin fila de estado— importa el
 * antiguo library.json en la misma transacción que marca la importación:
 * o entra todo, o no entra nada y se vuelve a intentar en el siguiente arranque.
 */
class RoomLibraryStore(
    private val db: ElyndraDatabase,
    private val legacyFile: File,
) : LibraryStore {

    private val dao get() = db.libraryDao()

    override suspend fun load(): Library = withContext(Dispatchers.IO) {
        if (dao.state() == null) importLegacy()
        LibraryMapper.assemble(
            folders = dao.folders(),
            exclusions = dao.exclusions(),
            roms = dao.roms(),
            apps = dao.apps(),
            metadata = dao.metadata(),
            artwork = dao.artwork(),
            stats = dao.stats(),
            sessions = dao.sessions(),
            state = dao.state(),
        )
    }

    override suspend fun save(previous: Library, next: Library) = withContext(Dispatchers.IO) {
        val changes = LibraryDiff.between(previous, next)
        if (!changes.isEmpty) db.withTransaction { apply(changes) }
    }

    private suspend fun importLegacy() {
        val legacy = LegacyLibraryJson.read(legacyFile)
        db.withTransaction {
            if (legacy != null) apply(LibraryDiff.between(Library(), legacy))
            dao.upsertState(
                LibraryStateEntity(
                    lastAutoScan = legacy?.lastAutoScan ?: 0,
                    importedAt = if (legacy != null) System.currentTimeMillis() else 0,
                ),
            )
        }
        if (legacy != null) LegacyLibraryJson.retire(legacyFile)
    }

    /**
     * Primero lo que crea (padres antes que hijos, por las claves foráneas) y
     * después lo que borra (hijos antes que padres).
     */
    private suspend fun apply(c: LibraryChanges) {
        if (c.upsertFolders.isNotEmpty()) dao.upsertFolders(c.upsertFolders)
        if (c.upsertExclusions.isNotEmpty()) dao.upsertExclusions(c.upsertExclusions)
        if (c.upsertRoms.isNotEmpty()) dao.upsertRoms(c.upsertRoms)
        if (c.upsertApps.isNotEmpty()) dao.upsertApps(c.upsertApps)
        if (c.upsertMetadata.isNotEmpty()) dao.upsertMetadata(c.upsertMetadata)
        if (c.upsertArtwork.isNotEmpty()) dao.upsertArtwork(c.upsertArtwork)
        if (c.upsertStats.isNotEmpty()) dao.upsertStats(c.upsertStats)
        if (c.upsertSessions.isNotEmpty()) dao.upsertSessions(c.upsertSessions)

        if (c.deleteSessions.isNotEmpty()) dao.deleteSessions(c.deleteSessions)
        if (c.deleteStats.isNotEmpty()) dao.deleteStats(c.deleteStats)
        if (c.deleteArtwork.isNotEmpty()) dao.deleteArtwork(c.deleteArtwork)
        if (c.deleteMetadata.isNotEmpty()) dao.deleteMetadata(c.deleteMetadata)
        if (c.deleteApps.isNotEmpty()) dao.deleteApps(c.deleteApps)
        if (c.deleteRoms.isNotEmpty()) dao.deleteRoms(c.deleteRoms)
        if (c.deleteExclusions.isNotEmpty()) dao.deleteExclusions(c.deleteExclusions)
        if (c.deleteFolders.isNotEmpty()) dao.deleteFolders(c.deleteFolders)

        c.lastAutoScan?.let { dao.setLastAutoScan(it) }
    }
}
