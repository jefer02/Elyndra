package com.elyndra.launcher.notify

import android.content.Context
import com.elyndra.launcher.MainActivity
import com.elyndra.launcher.R
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.data.NotificationPolicyPrefs
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.domain.notify.LucyNotice
import com.elyndra.launcher.domain.notify.NoticeContext
import com.elyndra.launcher.domain.notify.NoticeVerdict
import com.elyndra.launcher.domain.notify.NotificationPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La única puerta de los avisos de Lucy fuera de la app: pregunta a
 * [NotificationPolicy] (enfriamientos, horas de silencio, primer plano, cupo
 * diario, pausa tras notas ignoradas), publica con [LucyNotifier] y apunta lo
 * enviado. Quien detecta algo (el vigilante del calor, los workers, la pasada
 * de metadatos) solo llama aquí.
 */
@Singleton
class LucyNotices @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    val store: NotificationPolicyPrefs,
) {

    val policy = NotificationPolicy(store)

    /** Hay una sesión de juego abierta (ver SessionTracker: sobrevive a que muera el proceso). */
    private val inSession: Boolean get() = settings.pendingSessionKey != null

    private fun noticeContext() = NoticeContext(
        foreground = AppVisibility.foreground,
        inSession = inSession,
        enabled = settings.mashaNudges,
    )

    /** El dispositivo se calienta en mitad de una partida. */
    fun deviceHot(force: Boolean = false): NoticeVerdict =
        send(LucyNotice.Hot, force) { it.getString(R.string.lucy_notif_hot) }

    /** [count] juegos instalados con Elyndra fuera de pantalla (uno de ejemplo: [sampleTitle]). */
    fun newGames(count: Int, sampleTitle: String, force: Boolean = false): NoticeVerdict =
        send(LucyNotice.NewGames, force) { it.resources.getQuantityString(R.plurals.lucy_notif_new_games, count, count, sampleTitle) }

    /** Terminó en segundo plano una pasada de metadatos. */
    fun metadataDone(matched: Int, missed: Int, force: Boolean = false): NoticeVerdict =
        send(LucyNotice.MetadataDone, force) { it.getString(R.string.lucy_notif_metadata, matched, missed) }

    /** La nota del día, de un repertorio corto que rota a diario. */
    fun dailyNote(force: Boolean = false): NoticeVerdict =
        send(LucyNotice.Daily, force) { ctx ->
            val lines = ctx.resources.getStringArray(R.array.lucy_daily_lines)
            lines[policy.dailyLineIndex(lines.size)]
        }

    /**
     * [force]: solo el menú de depuración (ver LucyNoticeDebug); se salta la
     * política pero no el permiso ni el canal apagado.
     */
    private inline fun send(kind: LucyNotice, force: Boolean, text: (Context) -> String): NoticeVerdict {
        val verdict = if (force) NoticeVerdict.Allowed else policy.decide(kind, noticeContext())
        if (verdict != NoticeVerdict.Allowed) return verdict
        if (!LucyNotifier.canPost(context, kind)) return NoticeVerdict.Disabled
        val posted = LucyNotifier.post(context, kind, text(AppLocale.wrap(context)), MainActivity.openMashaIntent(context))
        if (posted && !force) policy.markSent(kind)
        return if (posted) NoticeVerdict.Allowed else NoticeVerdict.Disabled
    }
}
