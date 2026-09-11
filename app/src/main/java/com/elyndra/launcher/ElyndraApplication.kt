package com.elyndra.launcher

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.SecretStore
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.launch.GameLauncher
import com.elyndra.launcher.library.AppCatalog
import com.elyndra.launcher.library.RomScanner
import com.elyndra.launcher.library.SafFiles
import com.elyndra.launcher.metadata.MediaCache
import com.elyndra.launcher.metadata.MetadataEngine
import com.elyndra.launcher.metadata.ServiceCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Contenedor de dependencias: una sola instancia de cada pieza para toda la
 * app (Activity, ViewModel y ScrapeService las comparten).
 */
class ElyndraApplication : Application(), ImageLoaderFactory {

    /** Ámbito de aplicación: sobrevive a la Activity (descargas de metadatos, guardado). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { SettingsStore(this) }
    val secrets by lazy { SecretStore(this) }
    val credentials by lazy { ServiceCredentials(secrets, settings) }
    val library by lazy { LibraryRepository(File(filesDir, "library.json"), scope) }
    val media by lazy { MediaCache(filesDir) }
    val files by lazy { SafFiles(contentResolver) }
    val scanner by lazy { RomScanner(contentResolver) }
    val apps by lazy { AppCatalog(this) }
    val launcher by lazy { GameLauncher(this) }
    val metadata by lazy { MetadataEngine(this, library, credentials, settings, media, files, scope) }

    override fun onCreate() {
        super.onCreate()
        library.load()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(220)
        .respectCacheHeaders(false)
        .build()
}
