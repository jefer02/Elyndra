package com.elyndra.launcher.notify

import com.elyndra.launcher.domain.notify.LucyNotice
import com.elyndra.launcher.domain.notify.NoticeVerdict

/**
 * Solo en builds de depuración (src/debug): lanza a mano cada aviso de Lucy
 * para probar canales y textos sin esperar a que pase de verdad. Se salta la
 * política (horas, enfriamientos, cupo) pero no el permiso ni un canal
 * apagado, y no cuenta como enviado. La de src/release no hace nada, así que
 * esto no existe en el APK firmado.
 */
object LucyNoticeDebug {

    /** El rótulo de la sección en Ajustes → Lucy (texto de desarrollo, sin traducir). */
    val label: String? = "Simulate Lucy notifications (debug build)"

    fun simulate(notices: LucyNotices, kind: LucyNotice): NoticeVerdict = when (kind) {
        LucyNotice.Hot -> notices.deviceHot(force = true)
        LucyNotice.NewGames -> notices.newGames(3, "Celeste", force = true)
        LucyNotice.MetadataDone -> notices.metadataDone(42, 3, force = true)
        LucyNotice.Daily -> notices.dailyNote(force = true)
    }
}
