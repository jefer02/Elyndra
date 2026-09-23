package com.elyndra.launcher.ui

import android.content.Context
import com.elyndra.launcher.R
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.domain.insights.Insight

/* Cómo dice Masha cada cosa. Vive aparte del controlador porque lo mismo se
   dice en tres sitios —la burbuja sobre el carrusel, el widget y los avisos— y
   tiene que sonar igual en los tres. */

fun insightText(i: Insight, emulatorName: (String) -> String): UiText = when (i) {
    Insight.EmptyLibrary -> UiText.res(R.string.masha_insight_empty)
    is Insight.DeviceHot -> UiText.res(R.string.masha_insight_hot)
    is Insight.ArcNext -> UiText.res(R.string.masha_insight_arc, i.arcTitle, i.step, i.total, i.gameTitle)
    is Insight.MetadataFinished -> UiText.res(R.string.masha_insight_meta_done, i.matched, i.missed)
    is Insight.ContinueGame -> UiText.res(R.string.masha_insight_continue, i.title, i.lastMinutes, daysAgoText(i.daysAgo))
    is Insight.EmulatorMissing -> UiText.res(R.string.masha_insight_emulator, i.systemName, emulatorName(i.emulatorId))
    is Insight.Abandoned -> UiText.res(R.string.masha_insight_abandoned, i.title, fmtMinutes(i.minutes), i.daysIdle)
    is Insight.ConfigureServices -> UiText.res(R.string.masha_insight_services, i.missingArt)
    is Insight.NeverOpened -> UiText.res(R.string.masha_insight_never, i.count, i.sampleTitle)
    is Insight.MissingArt -> UiText.res(R.string.masha_insight_art, i.count)
    is Insight.CleanUp -> UiText.res(R.string.masha_insight_cleanup, i.duplicates, i.incomplete, i.naming)
    is Insight.Unmatched -> UiText.res(R.string.masha_insight_unmatched, i.count)
}

/** El juego al que se refiere una sugerencia, si se refiere a uno. */
fun Insight.gameKey(): String? = when (this) {
    is Insight.ArcNext -> gameKey
    is Insight.ContinueGame -> gameKey
    is Insight.Abandoned -> gameKey
    is Insight.NeverOpened -> sampleKey
    else -> null
}

fun daysAgoText(days: Int): UiText = when {
    days <= 0 -> UiText.res(R.string.masha_today)
    days == 1 -> UiText.res(R.string.masha_yesterday)
    else -> UiText.plural(R.plurals.masha_days_ago, days, days)
}

/** El mismo texto fuera de Compose (widget, avisos), con el idioma de la app. */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, count, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    is UiText.Raw -> text
}
