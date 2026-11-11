package com.elyndra.launcher.notify

import com.elyndra.launcher.domain.notify.LucyNotice
import com.elyndra.launcher.domain.notify.NoticeVerdict

/** En release no se simulan avisos de Lucy (la de depuración está en src/debug). */
object LucyNoticeDebug {
    val label: String? = null

    @Suppress("UNUSED_PARAMETER")
    fun simulate(notices: LucyNotices, kind: LucyNotice): NoticeVerdict = NoticeVerdict.Disabled
}
