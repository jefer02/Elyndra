package com.elyndra.launcher.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Un port de la tienda instalado desde Elyndra: qué entrada del catálogo,
 * con qué paquete y qué versión (etiqueta de la release). Es también la
 * "etiqueta" de port de la app en la biblioteca (`a:<paquete>`).
 */
@Entity(tableName = "installed_ports", indices = [Index("package_name")])
data class InstalledPortEntity(
    @PrimaryKey @ColumnInfo(name = "catalog_id") val catalogId: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "installed_tag") val installedTag: String,
    @ColumnInfo(name = "asset_name") val assetName: String?,
    @ColumnInfo(name = "installed_at") val installedAt: Long,
    /** Sistema original del juego (para colocarlo y buscar sus metadatos sin el catálogo a mano). */
    @ColumnInfo(name = "system_id") val systemId: String? = null,
    /** La última release vista por la comprobación diaria (PortUpdateWorker). */
    @ColumnInfo(name = "latest_tag") val latestTag: String? = null,
    @ColumnInfo(name = "latest_published_at", defaultValue = "0") val latestPublishedAt: Long = 0,
    @ColumnInfo(name = "checked_at", defaultValue = "0") val checkedAt: Long = 0,
    /** La etiqueta de la que ya se avisó (no se avisa dos veces de la misma). */
    @ColumnInfo(name = "notified_tag") val notifiedTag: String? = null,
    /** Una release firmada con otra clave: no se actualiza a ella (ver SignatureCheck). */
    @ColumnInfo(name = "blocked_tag") val blockedTag: String? = null,
)

/**
 * Un port del catálogo "aún no en Android" al que la comprobación diaria
 * (PortUpdateWorker) le ha encontrado un APK: desde ahí se puede instalar,
 * sin verificar y sin analizar (ver PortAvailability). El siguiente catálogo,
 * que sí lo revisa (repositorio, release, confianza), manda sobre esto.
 */
@Entity(tableName = "port_availability")
data class PortAvailabilityEntity(
    @PrimaryKey @ColumnInfo(name = "catalog_id") val catalogId: String,
    @ColumnInfo(name = "tag") val tag: String,
    @ColumnInfo(name = "asset_name") val assetName: String,
    @ColumnInfo(name = "found_at") val foundAt: Long,
)

@Dao
interface PortDao {
    @Query("SELECT * FROM installed_ports")
    suspend fun all(): List<InstalledPortEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(port: InstalledPortEntity)

    @Query("DELETE FROM installed_ports WHERE catalog_id = :catalogId")
    suspend fun remove(catalogId: String)

    @Query(
        "UPDATE installed_ports SET latest_tag = :tag, latest_published_at = :publishedAt, checked_at = :checkedAt " +
            "WHERE catalog_id = :catalogId",
    )
    suspend fun setLatest(catalogId: String, tag: String?, publishedAt: Long, checkedAt: Long)

    @Query("UPDATE installed_ports SET notified_tag = :tag WHERE catalog_id = :catalogId")
    suspend fun setNotified(catalogId: String, tag: String?)

    @Query("UPDATE installed_ports SET blocked_tag = :tag WHERE catalog_id = :catalogId")
    suspend fun setBlocked(catalogId: String, tag: String?)

    @Query("SELECT * FROM port_availability")
    suspend fun availability(): List<PortAvailabilityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setAvailable(row: PortAvailabilityEntity)

    @Query("DELETE FROM port_availability WHERE catalog_id NOT IN (:keep)")
    suspend fun keepAvailability(keep: List<String>)
}
