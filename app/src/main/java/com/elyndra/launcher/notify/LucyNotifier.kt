package com.elyndra.launcher.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.elyndra.launcher.R
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.domain.notify.LucyNotice

/**
 * Los canales y las notificaciones de Lucy (Masha en el código): uno por tipo
 * de aviso, para que cada uno se pueda apagar en los ajustes de Android.
 *
 * Solo pinta; cuándo puede salir cada uno lo decide [LucyNotices].
 */
object LucyNotifier {

    private class Channel(val id: String, val name: Int, val desc: Int, val importance: Int, val notificationId: Int)

    /** La nota diaria conserva el id "masha" del canal de antes: quien lo apagó lo sigue teniendo apagado. */
    private val CHANNELS = mapOf(
        LucyNotice.Daily to Channel("masha", R.string.notif_channel_masha, R.string.notif_channel_masha_desc, NotificationManager.IMPORTANCE_LOW, 77),
        LucyNotice.Hot to Channel("lucy_hot", R.string.notif_channel_lucy_hot, R.string.notif_channel_lucy_hot_desc, NotificationManager.IMPORTANCE_DEFAULT, 78),
        LucyNotice.NewGames to Channel("lucy_new_games", R.string.notif_channel_lucy_new_games, R.string.notif_channel_lucy_new_games_desc, NotificationManager.IMPORTANCE_LOW, 79),
        LucyNotice.MetadataDone to Channel("lucy_metadata", R.string.notif_channel_lucy_metadata, R.string.notif_channel_lucy_metadata_desc, NotificationManager.IMPORTANCE_LOW, 80),
    )

    /** Android 13+: ¿concedido el permiso? Antes no hace falta. */
    fun permitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Permiso, notificaciones de la app y el canal de [kind] encendidos. */
    fun canPost(context: Context, kind: LucyNotice): Boolean {
        if (!permitted(context)) return false
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        val channel = nm.getNotificationChannel(CHANNELS.getValue(kind).id) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Publica [text] en el canal de [kind]; tocarla abre [intent] (por defecto, la pantalla de Lucy). Devuelve si salió. */
    fun post(context: Context, kind: LucyNotice, text: String, intent: Intent): Boolean {
        // La comprobación va aquí mismo, junto a notify(): sin permiso (Android 13+) no se avisa.
        if (!permitted(context)) return false
        val localized = AppLocale.wrap(context)
        val channel = CHANNELS.getValue(kind)
        ensureChannels(context, localized)
        val pending = PendingIntent.getActivity(
            context,
            channel.notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_elyndra)
            .setColor(BrandTokens.PRIMARY)
            .setContentTitle(localized.getString(R.string.masha))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (kind == LucyNotice.Hot) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setCategory(if (kind == LucyNotice.Hot) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_RECOMMENDATION)
            .setSilent(kind != LucyNotice.Hot)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(channel.notificationId, notification) }.isSuccess
    }

    /**
     * Crea (o renombra, si cambió el idioma o el texto) los cuatro canales.
     * createNotificationChannel con un id existente solo actualiza nombre y
     * descripción: lo que el usuario cambió (importancia, sonido) se respeta.
     */
    fun ensureChannels(context: Context, localized: Context = AppLocale.wrap(context)) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        CHANNELS.values.forEach { c ->
            nm.createNotificationChannel(
                NotificationChannel(c.id, localized.getString(c.name), c.importance).apply {
                    description = localized.getString(c.desc)
                    setShowBadge(false)
                },
            )
        }
    }
}
