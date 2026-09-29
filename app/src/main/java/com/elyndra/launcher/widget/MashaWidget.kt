package com.elyndra.launcher.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.elyndra.launcher.MainActivity
import com.elyndra.launcher.R
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.domain.session.SessionMood
import com.elyndra.launcher.domain.session.SessionPlanner
import com.elyndra.launcher.library.AppCatalog
import com.elyndra.launcher.masha.MashaBrain
import com.elyndra.launcher.ui.gameKey
import com.elyndra.launcher.ui.insightText
import com.elyndra.launcher.ui.resolve
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File

/** Lo que el widget necesita del grafo de Hilt (Glance no inyecta). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun brain(): MashaBrain
    fun apps(): AppCatalog
}

/** Un juego propuesto en el widget. */
data class WidgetPick(val key: String, val title: String, val platform: String, val art: Bitmap?)

data class WidgetState(val line: String, val picks: List<WidgetPick>)

/**
 * Masha en la pantalla de inicio: su línea del momento (la misma sugerencia
 * que sale sobre el carrusel) y uno o dos juegos para ahora mismo, con su
 * carátula. Tocar un juego lo lanza; tocar la cabecera abre la conversación.
 *
 * Se refresca cada pocas horas y al volver de jugar (ver ElyndraWork): no
 * tiene nada escuchando.
 */
class MashaWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = runCatching { load(context) }.getOrElse {
            WidgetState(AppLocale.wrap(context).getString(R.string.widget_empty), emptyList())
        }
        provideContent { Content(context, state) }
    }

    private suspend fun load(context: Context): WidgetState {
        val entry = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val brain = entry.brain()
        val localized = AppLocale.wrap(context)
        val snapshot = brain.knowledge.snapshot()
        if (snapshot.games.isEmpty()) return WidgetState(localized.getString(R.string.widget_empty), emptyList())

        val insight = brain.insights().firstOrNull()
        val line = insight?.let { insightText(it, brain::emulatorName).resolve(localized) }
            ?: localized.getString(R.string.widget_masha_desc)

        // La sugerencia del momento primero; después, lo que encaja en un rato normal.
        val device = brain.device.snapshot()
        val keys = LinkedHashSet<String>()
        insight?.gameKey()?.let { keys += it }
        for (mood in listOf(SessionMood.Continue, SessionMood.Any)) {
            SessionPlanner.plan(20, 45, mood, snapshot.games, snapshot.profiles, device, now = snapshot.now).blocks.forEach { keys += it.game.key }
        }
        val picks = keys.mapNotNull { snapshot.game(it) }.take(MAX_PICKS).map { g ->
            val art = if (g.isApp) {
                decode(context, g.meta.icon) ?: g.app?.let { a -> runCatching { entry.apps().icon(a.packageName)?.toBitmap(ICON_PX, ICON_PX) }.getOrNull() }
            } else {
                decode(context, g.meta.cover ?: g.meta.icon)
            }
            WidgetPick(g.key, g.title, g.system?.short ?: "Android", art)
        }
        return WidgetState(line, picks)
    }

    /** Carátula reducida: un RemoteViews con imágenes grandes no se llega a pintar. */
    private fun decode(context: Context, path: String?): Bitmap? {
        val file = path?.let { File(context.filesDir, it) }?.takeIf { it.exists() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_PX) sample *= 2
        return runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
    }

    @Composable
    private fun Content(context: Context, state: WidgetState) {
        val white = ColorProvider(Color.White)
        Column(
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(22.dp)
                .background(Color(0xF0151A20))
                .padding(12.dp),
        ) {
            Row(
                GlanceModifier.fillMaxWidth().clickable(actionStartActivity(MainActivity.openMashaIntent(context))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    ImageProvider(R.drawable.masha),
                    contentDescription = null,
                    modifier = GlanceModifier.size(26.dp).cornerRadius(13.dp),
                    contentScale = ContentScale.Crop,
                )
                Spacer(GlanceModifier.width(8.dp))
                Text("Masha", style = TextStyle(color = white, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(6.dp))
            Text(
                state.line,
                maxLines = 3,
                style = TextStyle(color = ColorProvider(Color(0xDDFFFFFF)), fontSize = 11.5.sp),
                modifier = GlanceModifier.clickable(actionStartActivity(MainActivity.openMashaIntent(context))),
            )
            if (state.picks.isNotEmpty()) {
                Spacer(GlanceModifier.height(10.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    state.picks.forEachIndexed { i, pick ->
                        if (i > 0) Spacer(GlanceModifier.width(8.dp))
                        Pick(context, pick, GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }

    @Composable
    private fun Pick(context: Context, pick: WidgetPick, modifier: GlanceModifier) {
        val launch: Intent = MainActivity.launchGameIntent(context, pick.key)
        Row(
            modifier
                .cornerRadius(14.dp)
                .background(Color(0x22FFFFFF))
                .padding(6.dp)
                .clickable(actionStartActivity(launch)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pick.art != null) {
                Image(
                    ImageProvider(pick.art),
                    contentDescription = null,
                    modifier = GlanceModifier.size(38.dp, 50.dp).cornerRadius(8.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(GlanceModifier.size(38.dp, 50.dp).cornerRadius(8.dp).background(Color(0x44FFFFFF))) {}
            }
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Text(
                    pick.title,
                    maxLines = 2,
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    "▶ " + AppLocale.wrap(context).getString(R.string.widget_play) + " · " + pick.platform,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Color(0xFFC9B6FF)), fontSize = 9.5.sp),
                )
            }
        }
    }

    private companion object {
        const val MAX_PICKS = 2
        const val COVER_PX = 160
        const val ICON_PX = 96
    }
}

class MashaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MashaWidget()
}
