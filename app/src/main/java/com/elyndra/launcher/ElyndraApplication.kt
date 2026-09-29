package com.elyndra.launcher

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.SecretStore
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.di.ApplicationScope
import com.elyndra.launcher.launch.GameLauncher
import com.elyndra.launcher.library.AppCatalog
import com.elyndra.launcher.library.RomScanner
import com.elyndra.launcher.library.SafFiles
import com.elyndra.launcher.metadata.LocalMedia
import com.elyndra.launcher.metadata.MediaCache
import com.elyndra.launcher.metadata.MetadataEngine
import com.elyndra.launcher.metadata.ServiceCredentials
import com.elyndra.launcher.work.ElyndraWork
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Raíz de la app. Las dependencias las monta Hilt (ver `di/`); aquí se
 * exponen las de siempre con su nombre de siempre (`app.library`,
 * `app.settings`…) para el código que aún las pide a la Application.
 * El código nuevo las recibe por constructor.
 */
@HiltAndroidApp
class ElyndraApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    /** Ámbito de aplicación: sobrevive a la Activity (descargas de metadatos, guardado). */
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var secrets: SecretStore
    @Inject lateinit var credentials: ServiceCredentials
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var media: MediaCache
    @Inject lateinit var files: SafFiles
    @Inject lateinit var localMedia: LocalMedia
    @Inject lateinit var scanner: RomScanner
    @Inject lateinit var apps: AppCatalog
    @Inject lateinit var launcher: GameLauncher
    @Inject lateinit var metadata: MetadataEngine

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var work: ElyndraWork

    override fun onCreate() {
        // Hilt inyecta los campos en `super.onCreate()`.
        super.onCreate()
        library.load()
        // Iniciar WorkManager (su base de datos, sus planificadores) no puede
        // retrasar el primer fotograma: se programa fuera del hilo principal.
        scope.launch { work.schedulePeriodic() }
    }

    /** WorkManager se inicia a demanda con la fábrica de Hilt (ver el manifiesto). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(220)
        .respectCacheHeaders(false)
        .build()
}
