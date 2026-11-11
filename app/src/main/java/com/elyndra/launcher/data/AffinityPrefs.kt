package com.elyndra.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.elyndra.launcher.domain.relationship.Affinity
import com.elyndra.launcher.domain.relationship.AffinityStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La relación con Lucy, guardada en el dispositivo (ver
 * [com.elyndra.launcher.domain.relationship.Affinity]). Tres números: la
 * puntuación, el día local y lo ganado ese día.
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

    private companion object {
        const val SCORE = "score"
        const val DAY = "day"
        const val GAINED = "gainedToday"
    }
}
