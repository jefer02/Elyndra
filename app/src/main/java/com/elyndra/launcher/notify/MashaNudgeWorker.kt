package com.elyndra.launcher.notify

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.domain.notify.InstalledGame
import com.elyndra.launcher.domain.notify.NewGameDetector
import com.elyndra.launcher.domain.notify.NoticeVerdict
import com.elyndra.launcher.library.AppCatalog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * La vuelta periódica de Lucy (Masha en el código) fuera de la app, cada
 * pocas horas (ver ElyndraWork). Hace dos cosas baratas:
 *
 *  1. Juegos Android instalados con Elyndra cerrado que aún no están en la
 *     biblioteca: un solo aviso con todos ("3 juegos nuevos…"). Una consulta
 *     al PackageManager, sin leer carpetas.
 *  2. La nota del día, de un repertorio corto en la voz de Lucy.
 *
 * Cuándo puede salir cada cosa (de día, una nota al día como mucho, nunca con
 * la app delante, pausa tras tres notas sin abrirla…) lo decide
 * NotificationPolicy a través de [LucyNotices]. Aquí solo se detecta.
 */
@HiltWorker
class MashaNudgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notices: LucyNotices,
    private val library: LibraryRepository,
    private val apps: AppCatalog,
    private val settings: SettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.mashaNudges) return Result.success()
        runCatching { checkNewGames(System.currentTimeMillis()) }
        // Va después: si acaba de salir el de juegos nuevos, hoy ya no toca nota.
        notices.dailyNote()
        return Result.success()
    }

    private suspend fun checkNewGames(now: Long) {
        val store = notices.store
        val since = store.newGamesSince
        // La primera vuelta solo marca desde cuándo mirar: lo instalado antes no es "nuevo".
        if (since <= 0L) {
            store.newGamesSince = now
            return
        }
        library.awaitLoaded()
        val inLibrary = library.current.apps.mapTo(HashSet()) { it.packageName }
        val installed = apps.launchable().filter { it.isGame }.map { InstalledGame(it.packageName, it.label, it.installedAt) }
        val fresh = NewGameDetector.fresh(installed, inLibrary, since)
        if (fresh.isEmpty()) {
            store.newGamesSince = now
            return
        }
        when (notices.newGames(fresh.size, fresh.first().label)) {
            // Dicho, visto en la app o apagado: la ventana avanza.
            NoticeVerdict.Allowed, NoticeVerdict.Foreground, NoticeVerdict.Disabled -> store.newGamesSince = now
            // De noche, en enfriamiento o sin cupo: se vuelve a mirar en la próxima vuelta.
            else -> Unit
        }
    }
}
