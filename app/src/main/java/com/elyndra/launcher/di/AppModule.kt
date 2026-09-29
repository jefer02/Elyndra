package com.elyndra.launcher.di

import android.content.Context
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.LibraryStore
import com.elyndra.launcher.data.SecretStore
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.launch.GameLauncher
import com.elyndra.launcher.library.AppCatalog
import com.elyndra.launcher.library.RomScanner
import com.elyndra.launcher.library.SafFiles
import com.elyndra.launcher.metadata.LocalMedia
import com.elyndra.launcher.metadata.MediaCache
import com.elyndra.launcher.metadata.MetadataEngine
import com.elyndra.launcher.metadata.MetadataPriorityStore
import com.elyndra.launcher.metadata.ServiceCredentials
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

/**
 * Las piezas de siempre de Elyndra, una sola instancia de cada una para toda
 * la app (Activity, ViewModel, servicio, workers y widget las comparten).
 *
 * Son clases sin anotaciones de Hilt a propósito: siguen siendo Kotlin plano,
 * construible a mano en las pruebas. Aquí es donde se decide cómo se montan.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context) = SettingsStore(context)

    @Provides
    @Singleton
    fun secrets(@ApplicationContext context: Context) = SecretStore(context)

    @Provides
    @Singleton
    fun credentials(secrets: SecretStore, settings: SettingsStore) = ServiceCredentials(secrets, settings)

    @Provides
    @Singleton
    fun metadataPriority(settings: SettingsStore) = MetadataPriorityStore(settings)

    @Provides
    @LegacyLibraryFile
    fun legacyLibraryFile(@ApplicationContext context: Context) = File(context.filesDir, "library.json")

    @Provides
    @Singleton
    fun library(store: LibraryStore, @ApplicationScope scope: CoroutineScope) = LibraryRepository(store, scope)

    @Provides
    @Singleton
    fun media(@ApplicationContext context: Context) = MediaCache(context.filesDir)

    @Provides
    @Singleton
    fun safFiles(@ApplicationContext context: Context) = SafFiles(context.contentResolver)

    @Provides
    @Singleton
    fun localMedia(@ApplicationContext context: Context) = LocalMedia(context.contentResolver)

    @Provides
    @Singleton
    fun scanner(@ApplicationContext context: Context) = RomScanner(context.contentResolver)

    @Provides
    @Singleton
    fun apps(@ApplicationContext context: Context) = AppCatalog(context)

    @Provides
    @Singleton
    fun launcher(@ApplicationContext context: Context, settings: SettingsStore) = GameLauncher(context, settings)

    @Provides
    @Singleton
    fun metadata(
        @ApplicationContext context: Context,
        library: LibraryRepository,
        credentials: ServiceCredentials,
        settings: SettingsStore,
        priority: MetadataPriorityStore,
        media: MediaCache,
        files: SafFiles,
        @ApplicationScope scope: CoroutineScope,
    ) = MetadataEngine(context, library, credentials, settings, priority, media, files, scope)
}
