package com.elyndra.launcher.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.metadata.ArtUpgrade
import com.elyndra.launcher.metadata.MetadataEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Una vez, tras poner SteamGridDB e IGDB primero en las imágenes: vuelve a
 * pedir el arte que salió de una fuente que ahora va detrás (ver
 * [com.elyndra.launcher.metadata.ArtUpgrade]). Solo imágenes: ni textos ni
 * hashes, y nunca lo fijado a mano ni lo de la carpeta del juego.
 *
 * En tandas de [BATCH], como [MetadataSyncWorker]; si quedan, se vuelve a
 * encolar (ver [ElyndraWork.scheduleArtUpgrade]). Cuando no queda nada, se
 * apunta como hecha y no vuelve a correr. Si hay una pasada en marcha, esa
 * manda: se reintenta más tarde.
 */
@HiltWorker
class ArtUpgradeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: MetadataEngine,
    private val library: LibraryRepository,
    private val settings: SettingsStore,
    private val work: ElyndraWork,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (settings.artUpgradeDone) return Result.success()
        if (engine.isRunning) return Result.retry()
        library.awaitLoaded()
        // Las que ya se intentaron y siguen igual (ninguna fuente tenía nada mejor) no se repiten.
        val skip = inputData.getInt(KEY_SKIP, 0)
        val keys = engine.artUpgradeKeys()
        val batch = ArtUpgrade.batch(keys, skip, BATCH)
        if (batch.isEmpty()) {
            settings.artUpgradeDone = true
            return Result.success()
        }
        return try {
            engine.runAndWait(batch, artOnly = true)
            // Lo que quede, en otra tanda (un worker tiene diez minutos de vida).
            val remaining = engine.artUpgradeKeys()
            val nextSkip = ArtUpgrade.nextSkip(skip, batch, remaining)
            if (remaining.size > nextSkip) work.scheduleArtUpgrade(next = true, skip = nextSkip) else settings.artUpgradeDone = true
            Result.success()
        } catch (e: CancellationException) {
            engine.cancel()
            throw e
        }
    }

    companion object {
        const val BATCH = 40

        /** Dato de entrada: cuántas claves pendientes saltarse (ver [ArtUpgrade.nextSkip]). */
        const val KEY_SKIP = "skip"
    }
}
