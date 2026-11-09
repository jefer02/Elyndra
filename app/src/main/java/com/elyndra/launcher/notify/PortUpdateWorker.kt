package com.elyndra.launcher.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.elyndra.launcher.MainActivity
import com.elyndra.launcher.R
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.domain.ports.AutoUpdatePolicy
import com.elyndra.launcher.domain.ports.PendingUpdate
import com.elyndra.launcher.domain.ports.PortAssetPicker
import com.elyndra.launcher.domain.ports.PortAvailability
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.PortRelease
import com.elyndra.launcher.domain.ports.PortUpdates
import com.elyndra.launcher.ports.GitHubClient
import com.elyndra.launcher.ports.PortCatalogRepository
import com.elyndra.launcher.ports.PortInstaller
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * La comprobación diaria de los ports instalados (ver ElyndraWork: una vez
 * al día, red sin límite, batería no baja).
 *
 * Por cada port instalado compara su etiqueta con la última release (la API
 * de GitHub con su caché ETag y el token opcional; una respuesta 304 no gasta
 * cupo). Lo nuevo se apunta en `installed_ports` para la fila "Con
 * actualización" y:
 *  · con "solo avisar" (por defecto), un aviso "Hay actualizaciones de N ports";
 *  · con "actualizar automáticamente", los que se pueden sin preguntar
 *    (AutoUpdatePolicy) se instalan; los demás, en el aviso.
 * Nunca se avisa dos veces de la misma etiqueta, y una release con otra clave
 * de firma (blocked_tag) ni se ofrece.
 *
 * También mira, unos pocos cada día y por turnos, los ports "aún no en
 * Android" ([PortAvailability.batch]): si a uno le ha salido un APK, se apunta
 * en `port_availability` y la tienda lo deja instalar (sin verificar ni
 * analizar: el aviso lo dice).
 */
@HiltWorker
class PortUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val catalog: PortCatalogRepository,
    private val github: GitHubClient,
    private val installer: PortInstaller,
    private val settings: SettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val all = runCatching { catalog.load().catalog.ports }.getOrDefault(emptyList())
        checkAvailability(all)
        val installed = installer.installed().filter { installer.isPackageInstalled(it.packageName) }
        if (installed.isEmpty()) return Result.success()
        val ports = all.associateBy { it.id }
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val latest = HashMap<String, PortRelease?>()
        for (p in installed) {
            val port = ports[p.portId] ?: continue
            val build = port.android ?: continue
            val pick = runCatching { PortAssetPicker.latest(github.releases(port.releaseRepo), build, abis, allowDebug = port.trust.debugBuild) }.getOrNull() ?: continue
            latest[p.portId] = pick.release
            installer.recordLatest(p.portId, pick.release.tag, pick.release.publishedAt)
        }
        val pending = PortUpdates.pending(installed, latest)
        if (pending.isEmpty()) return Result.success()

        val mode = settings.portsUpdateMode
        val left = mutableListOf<PendingUpdate>()
        for (u in pending) {
            val route = AutoUpdatePolicy.route(mode, Build.VERSION.SDK_INT, installer.installedByElyndra(u.packageName), foreground = false)
            val port = ports[u.portId]
            if (route != AutoUpdatePolicy.Route.Silent || port == null) {
                left += u
                continue
            }
            val target = runCatching { PortAssetPicker.latest(github.releases(port.releaseRepo), port.android!!, abis, allowDebug = port.trust.debugBuild) }.getOrNull()
            val outcome = target?.let {
                runCatching { installer.install(port, it, expectedPackage = u.packageName, onProgress = {}, onInstalling = {}, silent = true) }.getOrNull()
            }
            if (outcome !is PortInstaller.Outcome.Installed && outcome !is PortInstaller.Outcome.SignatureMismatch) left += u
        }
        // Lo de la tabla, otra vez: lo instalado sin preguntar ya no está pendiente.
        val now = installer.installed()
        val notify = PortUpdates.toNotify(left, now)
        if (notify.isNotEmpty() && PortUpdateNotifier.post(applicationContext, left.size)) {
            notify.forEach { installer.recordNotified(it.portId, it.tag) }
        }
        return Result.success()
    }

    /** Los ports sin APK de este turno: si la release más reciente ya trae uno, se apunta. */
    private suspend fun checkAvailability(all: List<PortEntry>) {
        val viewOnly = all.filter { it.notOnAndroid }
        installer.keepAvailability(viewOnly.map { it.id })
        val (batch, next) = PortAvailability.batch(viewOnly.map { it.id }, settings.portsAvailabilityCursor)
        settings.portsAvailabilityCursor = next
        val byId = viewOnly.associateBy { it.id }
        for (id in batch) {
            val port = byId[id] ?: continue
            val releases = runCatching { github.releases(port.repo) }.getOrNull() ?: continue
            val release = releases.filter { !it.draft }.maxByOrNull { it.publishedAt } ?: continue
            val apk = PortAvailability.apkIn(release.assets.map { it.name }) ?: continue
            installer.markAvailable(id, release.tag, apk)
        }
    }
}

/** El canal y el aviso de las actualizaciones de ports (como el de Masha: silencioso, IMPORTANCE_LOW). */
object PortUpdateNotifier {

    private const val CHANNEL_ID = "ports_updates"
    private const val NOTIFICATION_ID = 78

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** "Hay actualizaciones de N ports"; tocarlo abre la tienda. False si no se pudo avisar. */
    fun post(context: Context, count: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!canPost(context)) return false
        val localized = AppLocale.wrap(context)
        ensureChannel(context, localized)
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            MainActivity.openPortsIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = localized.resources.getQuantityString(R.plurals.ports_updates_notification, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_elyndra)
            .setColor(com.elyndra.launcher.data.BrandTokens.PRIMARY)
            .setContentTitle(localized.getString(R.string.ports_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }.isSuccess
    }

    private fun ensureChannel(context: Context, localized: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, localized.getString(R.string.notif_channel_ports), NotificationManager.IMPORTANCE_LOW).apply {
                description = localized.getString(R.string.notif_channel_ports_desc)
                setShowBadge(false)
            },
        )
    }
}
