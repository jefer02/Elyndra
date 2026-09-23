package com.elyndra.launcher.launch

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.db.EmulatorDao
import com.elyndra.launcher.data.db.InstalledEmulatorEntity
import com.elyndra.launcher.data.db.LaunchEventEntity
import com.elyndra.launcher.domain.profile.LaunchRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Qué emuladores hay instalados y cómo ha ido cada lanzamiento.
 *
 * El inventario se refresca al volver a la app: recorre los paquetes de los
 * perfiles de Emulators.kt (la visibilidad la da el bloque `<queries>` del
 * manifiesto, sin QUERY_ALL_PACKAGES) y guarda versión y fechas. Así Masha
 * sabe qué hay, desde cuándo, y si algo se desinstaló.
 */
@Singleton
class EmulatorInventory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: EmulatorDao,
    private val launcher: GameLauncher,
) {
    private val pm: PackageManager get() = context.packageManager

    @Volatile
    private var cached: Set<String> = emptySet()

    /** Perfiles de emulador instalados (ids de Emulators.kt), según el último refresco. */
    val installedProfiles: Set<String> get() = cached

    /** Recorre los perfiles conocidos y actualiza la tabla. Devuelve los perfiles instalados. */
    suspend fun refresh(now: Long = System.currentTimeMillis()): Set<String> = withContext(Dispatchers.IO) {
        // Lo resuelve GameLauncher, que distingue las builds "de reemplazo"
        // publicadas bajo el paquete de otra app (com.tencent.ig…) por su
        // actividad: tener PUBG instalado no es tener BannerHub.
        val byPackage = LinkedHashMap<String, MutableList<String>>()
        Emulators.ALL.forEach { profile ->
            val component = runCatching { launcher.installedComponent(profile) }.getOrNull() ?: return@forEach
            byPackage.getOrPut(component.substringBefore('/')) { ArrayList() } += profile.id
        }
        val known = runCatching { dao.installed() }.getOrDefault(emptyList()).associateBy { it.packageName }
        val rows = byPackage.mapNotNull { (pkg, profiles) ->
            val info = packageInfo(pkg) ?: return@mapNotNull null
            InstalledEmulatorEntity(
                packageName = pkg,
                label = runCatching { info.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull() ?: pkg,
                versionName = info.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                },
                profileIds = profiles.joinToString(","),
                firstSeen = known[pkg]?.firstSeen ?: now,
                lastSeen = now,
            )
        }
        runCatching {
            dao.upsertInstalled(rows)
            dao.forgetUninstalled(now)
        }
        val profiles = rows.flatMap { it.profileIds.split(',') }.filter { it.isNotBlank() }.toSet()
        cached = profiles
        profiles
    }

    suspend fun installed(): List<InstalledEmulatorEntity> = runCatching { dao.installed() }.getOrDefault(emptyList())

    /* ── historial de lanzamientos ────────────────────────────── */

    suspend fun recordLaunch(event: LaunchEventEntity) {
        runCatching { dao.insertLaunch(event) }
    }

    suspend fun launchesFor(gameKey: String): List<LaunchRecord> =
        runCatching { dao.launchesFor(gameKey) }.getOrDefault(emptyList()).map(::record)

    suspend fun launchesForSystem(systemId: String): List<LaunchRecord> =
        runCatching { dao.launchesForSystem(systemId) }.getOrDefault(emptyList()).map(::record)

    suspend fun recentLaunches(limit: Int = 2_000): List<LaunchRecord> =
        runCatching { dao.recentLaunches(limit) }.getOrDefault(emptyList()).map(::record)

    suspend fun forgetGame(gameKey: String) {
        runCatching { dao.deleteLaunchesFor(gameKey) }
    }

    private fun record(e: LaunchEventEntity) = LaunchRecord(e.gameKey, e.systemId, e.emulatorId, e.at, e.outcome)

    private fun packageInfo(pkg: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
    }.getOrNull()
}
