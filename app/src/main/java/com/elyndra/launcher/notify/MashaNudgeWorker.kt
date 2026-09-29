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
import com.elyndra.launcher.domain.insights.Insight
import com.elyndra.launcher.masha.MashaBrain
import com.elyndra.launcher.ui.gameKey
import com.elyndra.launcher.ui.insightText
import com.elyndra.launcher.ui.resolve
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.ZoneId

/**
 * Los avisos de Masha: casi nunca, y solo cuando valen la pena.
 *
 * Reglas, todas a la vez:
 *  · el usuario los tiene activados (Ajustes → Masha) y Android los permite;
 *  · como mucho uno cada [MIN_GAP_MS] (tres días);
 *  · nunca de noche (de 22:00 a 9:00, hora local);
 *  · nunca si Elyndra se abrió hace menos de [QUIET_AFTER_OPEN_MS]: quien
 *    acaba de estar dentro ya ha visto lo que Masha tenía que decir;
 *  · solo sugerencias que lo merecen fuera de la app ([Insight.notifiable]:
 *    el siguiente paso de un arco, un juego a medias de hace días…) y nunca
 *    la misma dos veces seguidas;
 *  · en un canal silencioso: se ve, no suena.
 */
@HiltWorker
class MashaNudgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val brain: MashaBrain,
    private val settings: SettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        if (!shouldNudge(now)) return Result.success()
        val insight = runCatching { brain.insights() }.getOrDefault(emptyList())
            .firstOrNull { it.notifiable && it.id != settings.mashaLastNudgeId }
            ?: return Result.success()
        MashaNotifier.post(applicationContext, insight, insightText(insight, brain::emulatorName).resolve(AppLocale.wrap(applicationContext)))
        settings.mashaLastNudgeAt = now
        settings.mashaLastNudgeId = insight.id
        return Result.success()
    }

    private fun shouldNudge(now: Long): Boolean {
        if (!settings.mashaNudges || !MashaNotifier.canPost(applicationContext)) return false
        if (now - settings.mashaLastNudgeAt < MIN_GAP_MS) return false
        if (now - settings.lastOpenedAt < QUIET_AFTER_OPEN_MS) return false
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        return hour in DAY_START_HOUR until DAY_END_HOUR
    }

    companion object {
        const val MIN_GAP_MS = 3L * 24 * 60 * 60 * 1000
        const val QUIET_AFTER_OPEN_MS = 20L * 60 * 60 * 1000
        const val DAY_START_HOUR = 9
        const val DAY_END_HOUR = 22
    }
}

/** El canal y la notificación de Masha. */
object MashaNotifier {

    private const val CHANNEL_ID = "masha"
    private const val NOTIFICATION_ID = 77

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun post(context: Context, insight: Insight, text: String) {
        // La comprobación va aquí mismo, junto a notify(): sin permiso (Android 13+) no se avisa.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val localized = AppLocale.wrap(context)
        ensureChannel(context, localized)
        // Tocar el aviso lleva a lo que propone: la ficha del juego o, si no hay juego, a Masha.
        val intent = insight.gameKey()?.let { MainActivity.showGameIntent(context, it) } ?: MainActivity.openMashaIntent(context)
        val pending = PendingIntent.getActivity(
            context,
            insight.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_elyndra)
            .setContentTitle(localized.getString(R.string.masha))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel(context: Context, localized: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, localized.getString(R.string.notif_channel_masha), NotificationManager.IMPORTANCE_LOW).apply {
                description = localized.getString(R.string.notif_channel_masha_desc)
                setShowBadge(false)
            },
        )
    }
}
