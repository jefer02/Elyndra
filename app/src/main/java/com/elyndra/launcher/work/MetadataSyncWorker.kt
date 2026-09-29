package com.elyndra.launcher.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.metadata.MetadataEngine
import com.elyndra.launcher.metadata.ServiceCredentials
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Metadatos automáticos: completa en segundo plano lo que le falte a la
 * biblioteca (juegos sin identificar, sin carátula o nunca consultados).
 *
 * Corre con red sin límite de datos y batería no baja (ver [ElyndraWork]) y
 * en tandas de [BATCH] juegos: un worker normal tiene diez minutos de vida y,
 * desde segundo plano, Android no deja levantar un servicio en primer plano.
 * Lo que no quepa en una tanda lo recoge la siguiente. La pasada completa y
 * visible sigue siendo la de Ajustes (ScrapeService).
 */
@HiltWorker
class MetadataSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: MetadataEngine,
    private val library: LibraryRepository,
    private val settings: SettingsStore,
    private val credentials: ServiceCredentials,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.autoMeta || !credentials.anyConfigured()) return Result.success()
        // Si el usuario ya tiene una pasada en marcha, esa manda.
        if (engine.isRunning) return Result.success()
        library.awaitLoaded()
        val lib = library.current
        val keys = (lib.roms.map { it.key } + lib.apps.map { it.key })
            .filter { engine.needsWork(it) }
            .take(BATCH)
        if (keys.isEmpty()) return Result.success()
        return try {
            engine.runAndWait(keys, force = false)
            Result.success()
        } catch (e: CancellationException) {
            // WorkManager nos para (se fue la wifi, poca batería…): la tanda
            // se corta aquí y no sigue gastando datos por su cuenta.
            engine.cancel()
            throw e
        }
    }

    companion object {
        const val BATCH = 40
    }
}
