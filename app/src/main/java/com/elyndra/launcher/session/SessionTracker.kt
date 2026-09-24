package com.elyndra.launcher.session

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.PlaySession
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.domain.profile.GameProfiles
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mide las sesiones de juego.
 *
 * Al lanzar se apunta qué juego, con qué emulador y qué paquete (en
 * SettingsStore: sobrevive a que Android mate el proceso mientras se juega).
 * Al volver a Elyndra se cierra la sesión:
 *
 *  · Con acceso de uso (UsageStatsManager), el tiempo es el que el paquete
 *    del emulador estuvo de verdad en primer plano: si el usuario salió a
 *    WhatsApp a mitad de partida, eso no cuenta.
 *  · Sin él —el permiso es especial y opcional; Elyndra no lo pide al
 *    arrancar—, el tiempo entre el lanzamiento y la vuelta, como siempre.
 *
 * Una sesión de menos de tres minutos se marca como salida inmediata: no
 * suma tiempo de juego, pero le dice al orquestador que algo no fue bien.
 */
@Singleton
class SessionTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val repo: LibraryRepository,
) {

    /** ¿Ha concedido el usuario el acceso de uso a Elyndra? */
    fun hasUsageAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** La pantalla del sistema donde se concede (con Elyndra ya señalada, si el sistema lo admite). */
    fun usageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Algunos sistemas no aceptan el paquete en la URI: esta es la genérica. */
    fun usageAccessFallbackIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)


    fun begin(key: String, emulatorId: String?, packageName: String?, now: Long = System.currentTimeMillis()) {
        settings.pendingSessionKey = key
        settings.pendingSessionStart = now
        settings.pendingSessionEmulator = emulatorId
        settings.pendingSessionPackage = packageName
    }

    /**
     * Cierra la sesión pendiente y la guarda en la biblioteca. Devuelve la
     * sesión, o null si no había ninguna o no era creíble (más de doce horas:
     * lo normal es que el emulador se quedara abierto y olvidado).
     */
    fun finish(now: Long = System.currentTimeMillis()): PlaySession? {
        val key = settings.pendingSessionKey ?: return null
        val start = settings.pendingSessionStart
        val emulator = settings.pendingSessionEmulator
        val pkg = settings.pendingSessionPackage
        settings.pendingSessionKey = null
        settings.pendingSessionEmulator = null
        settings.pendingSessionPackage = null
        if (start <= 0 || now <= start) return null

        val measured = pkg?.takeIf { hasUsageAccess() }?.let { foregroundMillis(it, start, now) }
        val millis = measured ?: (now - start)
        val minutes = (millis / 60_000L).toInt()
        if (minutes > MAX_SESSION_MINUTES) return null
        val session = PlaySession(
            key = key,
            start = start,
            minutes = minutes,
            emulatorId = emulator,
            end = now,
            source = if (measured != null) PlaySession.SOURCE_USAGE else PlaySession.SOURCE_LIFECYCLE,
            earlyExit = GameProfiles.isEarlyExit(minutes),
        )
        repo.recordSession(session)
        return session
    }

    /**
     * Tiempo en primer plano de [pkg] entre [from] y [to], sumando cada tramo
     * de "reanudada" a "pausada". Null si el sistema no registró nada de ese
     * paquete (algunas builds se lanzan con otro nombre de proceso): entonces
     * se usa la medida de siempre.
     */
    private fun foregroundMillis(pkg: String, from: Long, to: Long): Long? = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        var total = 0L
        var resumedAt: Long? = null
        var seen = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName != pkg) continue
            seen = true
            when (e.eventType) {
                // 1 = ACTIVITY_RESUMED (antes MOVE_TO_FOREGROUND), mismo valor en todas las versiones.
                RESUMED -> if (resumedAt == null) resumedAt = e.timeStamp
                // 2 = ACTIVITY_PAUSED (antes MOVE_TO_BACKGROUND); 23 = ACTIVITY_STOPPED.
                PAUSED, STOPPED -> resumedAt?.let { total += (e.timeStamp - it).coerceAtLeast(0); resumedAt = null }
            }
        }
        resumedAt?.let { total += (to - it).coerceAtLeast(0) }
        if (seen) total else null
    }.getOrNull()

    private companion object {
        const val MAX_SESSION_MINUTES = 12 * 60
        const val RESUMED = 1
        const val PAUSED = 2
        const val STOPPED = 23
    }
}
