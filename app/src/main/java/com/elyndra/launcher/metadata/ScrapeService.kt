package com.elyndra.launcher.metadata

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.elyndra.launcher.ElyndraApplication
import com.elyndra.launcher.MainActivity
import com.elyndra.launcher.R
import com.elyndra.launcher.data.AppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano (tipo dataSync) mientras se descargan metadatos,
 * para que la pasada siga aunque el usuario salga a jugar. El trabajo lo hace
 * [MetadataEngine]; aquí solo se enseña el progreso y se para al terminar.
 */
class ScrapeService : Service() {

    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as ElyndraApplication
        val engine = app.metadata
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(engine.progress.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            // Sin primer plano (restricciones del sistema) la pasada sigue igualmente mientras la app viva.
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = app.scope.launch(Dispatchers.Main) {
                engine.progress.collect { p ->
                    if (!p.running) {
                        ServiceCompat.stopForeground(this@ScrapeService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(p))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limita dataSync a 6 h/día: al agotarse se cancela la pasada. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as ElyndraApplication).metadata.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        super.onDestroy()
    }

    private fun localized(): Context = AppLocale.wrap(this)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, localized().getString(R.string.notif_channel_metadata), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(p: MetadataEngine.Progress): Notification {
        val ctx = localized()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_elyndra)
            .setContentTitle(ctx.getString(R.string.notif_metadata_title))
            .setContentText(
                if (p.total > 0) ctx.getString(R.string.notif_metadata_progress, p.done, p.total, p.current)
                else ctx.getString(R.string.notif_metadata_preparing),
            )
            .setProgress(p.total.coerceAtLeast(1), p.done, p.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "metadata"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ScrapeService::class.java)) }
        }
    }
}
