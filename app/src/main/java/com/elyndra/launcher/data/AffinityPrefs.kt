package com.elyndra.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.elyndra.launcher.domain.relationship.Affinity
import com.elyndra.launcher.domain.relationship.AffinityGain
import com.elyndra.launcher.domain.relationship.AffinityStore
import com.elyndra.launcher.domain.relationship.GainReason
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La relación con Lucy, guardada en el dispositivo (ver
 * [com.elyndra.launcher.domain.relationship.Affinity]): la puntuación, el
 * día local, lo ganado ese día, las últimas subidas y el nivel ya celebrado.
 */
@Singleton
class AffinityPrefs internal constructor(private val prefs: SharedPreferences) : AffinityStore {

    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences("lucy_affinity", Context.MODE_PRIVATE))

    override var score: Int
        get() = prefs.getInt(SCORE, Affinity.START)
        set(v) = prefs.edit { putInt(SCORE, v) }

    override var day: Long
        get() = prefs.getLong(DAY, -1L)
        set(v) = prefs.edit { putLong(DAY, v) }

    override var gainedToday: Int
        get() = prefs.getInt(GAINED, 0)
        set(v) = prefs.edit { putInt(GAINED, v) }

    /** "puntos:motivo:ms" separados por ";" (pocas y cortas: no merece JSON). */
    override var gains: List<AffinityGain>
        get() = prefs.getString(GAINS, null).orEmpty().split(';').mapNotNull { e ->
            val p = e.split(':')
            if (p.size != 3) return@mapNotNull null
            val points = p[0].toIntOrNull() ?: return@mapNotNull null
            val reason = GainReason.byId(p[1]) ?: return@mapNotNull null
            AffinityGain(points, reason, p[2].toLongOrNull() ?: 0L)
        }
        set(v) = prefs.edit { putString(GAINS, v.joinToString(";") { "${it.points}:${it.reason.id}:${it.at}" }) }

    override var celebrated: Int
        get() = prefs.getInt(CELEBRATED, -1)
        set(v) = prefs.edit { putInt(CELEBRATED, v) }

    private companion object {
        const val SCORE = "score"
        const val DAY = "day"
        const val GAINED = "gainedToday"
        const val GAINS = "gains"
        const val CELEBRATED = "celebrated"
    }
}
