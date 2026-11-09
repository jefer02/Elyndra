package com.elyndra.launcher.ports

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.elyndra.launcher.data.db.InstalledPortEntity
import com.elyndra.launcher.data.db.PortAvailabilityEntity
import com.elyndra.launcher.data.db.PortDao
import com.elyndra.launcher.di.PortsHttpClient
import com.elyndra.launcher.domain.ports.InstalledPort
import com.elyndra.launcher.domain.ports.Installable
import com.elyndra.launcher.domain.ports.PortAssetPicker
import com.elyndra.launcher.domain.ports.PortAvailability
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.SignatureCheck
import com.elyndra.launcher.update.ApkDownloader
import com.elyndra.launcher.update.InstallResult
import com.elyndra.launcher.update.UpdateError
import com.elyndra.launcher.update.UpdateInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
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
        /** El desarrollador cambió la clave de firma: no se actualiza (desinstalar borraría los datos guardados). */
        data object SignatureMismatch : Outcome
        /** Sin preguntar no se pudo (ver UpdateInstaller): hay que hacerlo con el diálogo. */
        data object NeedsUserAction : Outcome
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
        dao.all().map {
            InstalledPort(it.catalogId, it.packageName, it.installedTag, it.installedAt, it.systemId, it.latestTag, it.latestPublishedAt, it.notifiedTag, it.blockedTag)
        }

    /** Lo que vio la comprobación diaria de la última release de [portId]. */
    suspend fun recordLatest(portId: String, tag: String?, publishedAt: Long) = dao.setLatest(portId, tag, publishedAt, System.currentTimeMillis())

    suspend fun recordNotified(portId: String, tag: String?) = dao.setNotified(portId, tag)

    /** ¿Instaló Elyndra [pkg]? (Android solo deja actualizar sin preguntar a quien la instaló.) */
    fun installedByElyndra(pkg: String): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
        installer == context.packageName
    }.getOrDefault(false)

    suspend fun forget(portId: String) = dao.remove(portId)

    /** Los APK que encontró la comprobación diaria para ports "aún no en Android" (ver PortAvailability). */
    suspend fun availability(): Map<String, PortAvailability.Found> =
        dao.availability().associate { it.catalogId to PortAvailability.Found(it.tag, it.assetName) }

    suspend fun markAvailable(portId: String, tag: String, assetName: String) =
        dao.setAvailable(PortAvailabilityEntity(portId, tag, assetName, System.currentTimeMillis()))

    /** Lo que ya no es "aún no en Android" en el catálogo (o ya no está) se olvida. */
    suspend fun keepAvailability(viewOnlyIds: List<String>) = dao.keepAvailability(viewOnlyIds)

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
        silent: Boolean = false,
    ): Outcome {
        val build = port.android ?: return Outcome.Failed("no android build")
        val downloaded = try {
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
        // Un zip (ver PortBuild.archive): el APK de dentro, con las mismas reglas que el generador.
        val apk = if (build.archive != null) {
            val pattern = runCatching { Regex(build.archive) }.getOrNull() ?: Regex("""(?i)\.apk$""")
            val result = withContext(Dispatchers.IO) { ZipApk.extract(downloaded, File(dir, "unzipped"), pattern, build.innerSha256) }
            downloaded.delete()
            when (result) {
                is ZipApk.Result.Ok -> result.apk
                is ZipApk.Result.Bad -> return if (result.reason == "checksum") Outcome.Checksum else Outcome.Failed(result.reason)
            }
        } else {
            downloaded
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
        // Otra clave de firma: ni se intenta (Android lo rechazaría y desinstalar borraría los datos guardados).
        val decision = withContext(Dispatchers.IO) { SignatureCheck.decide(installedSigners(pkg), apkSigners(apk)) }
        if (!SignatureCheck.allowsUpdate(decision)) {
            apk.delete()
            dao.setBlocked(port.id, target.release.tag)
            return Outcome.SignatureMismatch
        }
        onInstalling()
        return when (val result = installer.install(apk, pkg, silent)) {
            InstallResult.Success -> {
                val now = System.currentTimeMillis()
                dao.upsert(
                    InstalledPortEntity(
                        port.id, pkg, target.release.tag, target.asset.name, now,
                        systemId = port.platform,
                        latestTag = target.release.tag,
                        latestPublishedAt = target.release.publishedAt,
                        checkedAt = now,
                    ),
                )
                Outcome.Installed(pkg, label)
            }
            InstallResult.Cancelled -> Outcome.Cancelled
            InstallResult.SignatureMismatch -> {
                dao.setBlocked(port.id, target.release.tag)
                Outcome.SignatureMismatch
            }
            InstallResult.NeedsUserAction -> Outcome.NeedsUserAction
            is InstallResult.Failed -> Outcome.Failed(result.message)
        }
    }

    /** Las huellas SHA-256 de quien firma la app instalada [pkg], o null si no está instalada. */
    private fun installedSigners(pkg: String): SignatureCheck.Signers? {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        return signersOf(info)
    }

    /** Las del APK descargado (con su linaje de rotación, si lo trae). */
    private fun apkSigners(apk: File): SignatureCheck.Signers? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
        } ?: return null
        return signersOf(info)
    }

    private fun signersOf(info: PackageInfo): SignatureCheck.Signers? {
        if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return null
            return if (signing.hasMultipleSigners()) {
                SignatureCheck.Signers(signing.apkContentsSigners.map { sha256(it.toByteArray()) }.toSet())
            } else {
                val history = signing.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) }
                SignatureCheck.Signers(setOfNotNull(history.lastOrNull()), history)
            }
        }
        @Suppress("DEPRECATION")
        val signatures = info.signatures ?: return null
        return SignatureCheck.Signers(signatures.map { sha256(it.toByteArray()) }.toSet())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
