package com.elyndra.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.elyndra.launcher.domain.notify.LucyNotice
import com.elyndra.launcher.domain.notify.NotificationPolicyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lo que recuerda la política de avisos de Lucy (ver
 * [com.elyndra.launcher.domain.notify.NotificationPolicy]): una clave por dato,
 * así que cada lectura es O(1). La última apertura de la app es la de
 * [SettingsStore.lastOpenedAt], que ya escribe el ViewModel al volver.
 */
@Singleton
class NotificationPolicyPrefs internal constructor(
    private val prefs: SharedPreferences,
    private val settings: SettingsStore,
) : NotificationPolicyStore {

    @Inject constructor(@ApplicationContext context: Context, settings: SettingsStore) :
        this(context.getSharedPreferences("lucy_notify", Context.MODE_PRIVATE), settings)

    override fun lastSentAt(kind: LucyNotice): Long = prefs.getLong("sent.${kind.key}", 0L)

    override fun setLastSentAt(kind: LucyNotice, at: Long) = prefs.edit { putLong("sent.${kind.key}", at) }

    override var sentDay: Long
        get() = prefs.getLong("day", -1L)
        set(v) = prefs.edit { putLong("day", v) }

    override var sentToday: Int
        get() = prefs.getInt("dayCount", 0)
        set(v) = prefs.edit { putInt("dayCount", v) }

    override var dailyStreak: Int
        get() = prefs.getInt("dailyStreak", 0)
        set(v) = prefs.edit { putInt("dailyStreak", v) }

    override val lastOpenedAt: Long get() = settings.lastOpenedAt

    /** Ventana de "juegos nuevos instalados": desde cuándo se buscan. 0 = aún no se ha mirado nunca. */
    var newGamesSince: Long
        get() = prefs.getLong("newGamesSince", 0L)
        set(v) = prefs.edit { putLong("newGamesSince", v) }
}
