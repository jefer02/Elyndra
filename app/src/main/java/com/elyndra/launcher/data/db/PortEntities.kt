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
)

@Dao
interface PortDao {
    @Query("SELECT * FROM installed_ports")
    suspend fun all(): List<InstalledPortEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(port: InstalledPortEntity)

    @Query("DELETE FROM installed_ports WHERE catalog_id = :catalogId")
    suspend fun remove(catalogId: String)
}
