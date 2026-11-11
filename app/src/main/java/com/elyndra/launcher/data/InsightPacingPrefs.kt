package com.elyndra.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.elyndra.launcher.domain.insights.InsightPacingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El ritmo del bocadillo de Lucy, guardado: sobrevive a que Android cierre la
 * app entre partidas (ver [com.elyndra.launcher.domain.insights.InsightPacing]).
 *
 * Lo enseñado va en un solo conjunto "id\tcuándo" que se carga una vez. Lo de
 * más de [KEEP_MS] se descarta al escribir, así no crece sin fin con las
 * huellas que cambian a diario ("continue:juego:días").
 */
@Singleton
class InsightPacingPrefs internal constructor(private val prefs: SharedPreferences) : InsightPacingStore {

    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences("lucy_pacing", Context.MODE_PRIVATE))

    private val shown: HashMap<String, Long> by lazy {
        HashMap<String, Long>().apply {
            prefs.getStringSet(SHOWN, emptySet()).orEmpty().forEach { e ->
                val tab = e.lastIndexOf('\t')
                val at = e.substring(tab + 1).toLongOrNull()
                if (tab > 0 && at != null) put(e.substring(0, tab), at)
            }
        }
    }

    @Synchronized
    override fun shownAt(id: String): Long? = shown[id]

    @Synchronized
    override fun markShown(id: String, now: Long) {
        shown[id] = now
        shown.entries.removeAll { now - it.value > KEEP_MS }
        prefs.edit { putStringSet(SHOWN, shown.map { (k, v) -> "$k\t$v" }.toSet()) }
    }

    override var lastRoutineAt: Long?
        get() = prefs.getLong(ROUTINE, 0L).takeIf { it > 0 }
        set(v) = prefs.edit { if (v == null) remove(ROUTINE) else putLong(ROUTINE, v) }

    override var quietUntil: Long
        get() = prefs.getLong(QUIET, 0L)
        set(v) = prefs.edit { putLong(QUIET, v) }

    /** "Olvidar todo lo que Lucy sabe" también borra esto. */
    @Synchronized
    fun clear() {
        shown.clear()
        prefs.edit { clear() }
    }

    private companion object {
        const val SHOWN = "shown"
        const val ROUTINE = "routineAt"
        const val QUIET = "quietUntil"
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}
