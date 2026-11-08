package com.elyndra.launcher.ports

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.elyndra.launcher.data.db.InstalledPortEntity
import com.elyndra.launcher.data.db.PortDao
import com.elyndra.launcher.di.PortsHttpClient
import com.elyndra.launcher.domain.ports.InstalledPort
import com.elyndra.launcher.domain.ports.Installable
import com.elyndra.launcher.domain.ports.PortAssetPicker
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.update.ApkDownloader
import com.elyndra.launcher.update.InstallResult
import com.elyndra.launcher.update.UpdateError
import com.elyndra.launcher.update.UpdateInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Baja e instala el APK de un port.
 *
 * 1. Descarga solo de las releases de GitHub ([HostAllowlist.DOWNLOADS]),
 *    reanudable, comprobando el SHA-256 (el del catálogo o el `digest` de la API).
 * 2. Lee el paquete del propio APK antes de instalar: si el catálogo dice
 *    otro, o es Elyndra, no se instala nada.
 * 3. Lo instala con la misma sesión de [PackageInstaller] que el
 *    actualizador, atada a ese paquete; el APK se borra salga como salga.
 * 4. Lo apunta en `installed_ports`. Nunca abre el port: eso lo decide el usuario.
 */
@Singleton
class PortInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    @PortsHttpClient client: OkHttpClient,
    private val installer: UpdateInstaller,
    private val dao: PortDao,
) {
    sealed interface Outcome {
        data class Installed(val packageName: String, val label: String) : Outcome
        data object Cancelled : Outcome
        data object SignatureMismatch : Outcome
        data object PackageMismatch : Outcome
        data object Checksum : Outcome
        data object Network : Outcome
        data object RateLimited : Outcome
        data class Failed(val message: String?) : Outcome
    }

    private val downloader = ApkDownloader(client, PortsHttp.USER_AGENT)
    private val dir: File get() = File(context.noBackupFilesDir, "ports/apk")

    fun canInstall(): Boolean = installer.canInstall()

    /** La pantalla del sistema para conceder "Instalar apps desconocidas" a Elyndra. */
    fun permissionIntent(): Intent = installer.permissionIntent()

    suspend fun installed(): List<InstalledPort> =
        dao.all().map { InstalledPort(it.catalogId, it.packageName, it.installedTag, it.installedAt) }

    suspend fun forget(portId: String) = dao.remove(portId)

    fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Baja e instala [target] de [port]. [onProgress] da el avance de la
     * descarga (0..1) y [onInstalling] avisa cuando empieza la instalación.
     */
    suspend fun install(
        port: PortEntry,
        target: Installable,
        expectedPackage: String?,
        onProgress: (Float) -> Unit,
        onInstalling: () -> Unit,
    ): Outcome {
        val build = port.android ?: return Outcome.Failed("no android build")
        val apk = try {
            downloader.download(
                target.asset,
                dir,
                PortAssetPicker.expectedSha256(target.asset, build),
                onProgress,
                allow = HostAllowlist.DOWNLOADS,
                resume = true,
            )
        } catch (e: UpdateError) {
            return when (e) {
                is UpdateError.Checksum -> Outcome.Checksum
                is UpdateError.Network -> Outcome.Network
                is UpdateError.RateLimited -> Outcome.RateLimited
                else -> Outcome.Failed(e.message)
            }
        }
        val info = withContext(Dispatchers.IO) { archiveInfo(apk) }
        if (info == null) {
            apk.delete()
            return Outcome.Failed("not an apk")
        }
        val (pkg, label) = info
        val wanted = build.packageName ?: expectedPackage
        if ((wanted != null && wanted != pkg) || pkg == context.packageName) {
            apk.delete()
            return Outcome.PackageMismatch
        }
        onInstalling()
        return when (val result = installer.install(apk, pkg)) {
            InstallResult.Success -> {
                dao.upsert(InstalledPortEntity(port.id, pkg, target.release.tag, target.asset.name, System.currentTimeMillis()))
                Outcome.Installed(pkg, label)
            }
            InstallResult.Cancelled -> Outcome.Cancelled
            InstallResult.SignatureMismatch -> Outcome.SignatureMismatch
            is InstallResult.Failed -> Outcome.Failed(result.message)
        }
    }

    /** El paquete y el nombre que trae el APK, o null si no es un APK legible. */
    private fun archiveInfo(apk: File): Pair<String, String>? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.path, 0) ?: return null
        val app = info.applicationInfo
        val label = app?.let {
            it.sourceDir = apk.path
            it.publicSourceDir = apk.path
            runCatching { it.loadLabel(pm).toString() }.getOrNull()
        }
        return info.packageName to (label?.takeIf { it.isNotBlank() } ?: info.packageName)
    }
}
