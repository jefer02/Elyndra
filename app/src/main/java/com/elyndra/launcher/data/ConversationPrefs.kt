package com.elyndra.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.elyndra.launcher.masha.ConversationStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * De qué iba la última conversación con Lucy y cuándo fue, guardado en el
 * dispositivo (ver [com.elyndra.launcher.masha.ConversationRecall]).
 */
@Singleton
class ConversationPrefs internal constructor(private val prefs: SharedPreferences) : ConversationStore {

    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences("lucy_conversation", Context.MODE_PRIVATE))

    override var summary: String?
        get() = prefs.getString(SUMMARY, null)
        set(v) = prefs.edit { if (v == null) remove(SUMMARY) else putString(SUMMARY, v) }

    override var topicGame: String?
        get() = prefs.getString(GAME, null)
        set(v) = prefs.edit { if (v == null) remove(GAME) else putString(GAME, v) }

    override var lastActivityAt: Long
        get() = prefs.getLong(ACTIVITY, 0L)
        set(v) = prefs.edit { putLong(ACTIVITY, v) }

    override var summarizedUpTo: Long
        get() = prefs.getLong(SUMMARIZED, 0L)
        set(v) = prefs.edit { putLong(SUMMARIZED, v) }

    override fun clear() = prefs.edit { clear() }

    private companion object {
        const val SUMMARY = "summary"
        const val GAME = "topicGame"
        const val ACTIVITY = "lastActivityAt"
        const val SUMMARIZED = "summarizedUpTo"
    }
}
