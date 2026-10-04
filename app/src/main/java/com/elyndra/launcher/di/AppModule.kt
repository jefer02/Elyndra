package com.elyndra.launcher.di

import com.elyndra.launcher.metadata.TranslationCache
import com.elyndra.launcher.metadata.RoomTranslationStore
import com.elyndra.launcher.metadata.MlKitTranslator
import com.elyndra.launcher.metadata.DescriptionTranslator
import com.elyndra.launcher.metadata.TranslationPacks
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.elyndra.launcher.data.db.TranslationDao
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
import com.elyndra.launcher.sound.BackgroundMusic
import com.elyndra.launcher.sound.CustomSoundImporter
import com.elyndra.launcher.sound.PrefsKeyValues
import com.elyndra.launcher.sound.SoundManager
import com.elyndra.launcher.sound.SoundSettings
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
    fun soundSettings(@ApplicationContext context: Context) =
        SoundSettings(PrefsKeyValues(context.getSharedPreferences("settings", Context.MODE_PRIVATE)))

    @Provides
    @Singleton
    fun sounds(
        @ApplicationContext context: Context,
        settings: SoundSettings,
        @ApplicationScope scope: CoroutineScope,
    ) = SoundManager(context, settings, scope, CustomSoundImporter(context))

    @Provides
    @Singleton
    fun music(@ApplicationContext context: Context, settings: SoundSettings) = BackgroundMusic(context, settings)

    @Provides
    @Singleton
    fun translator(): DescriptionTranslator = MlKitTranslator()

    @Provides
    @Singleton
    fun translations(dao: TranslationDao, translator: DescriptionTranslator) = TranslationCache(RoomTranslationStore(dao), translator)

    /** Los paquetes de idioma: "Wi-Fi" es cualquier red que no se cobre por uso. */
    @Provides
    @Singleton
    fun translationPacks(
        @ApplicationContext context: Context,
        translator: DescriptionTranslator,
        @ApplicationScope scope: CoroutineScope,
    ) = TranslationPacks(translator, scope, unmetered = {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    })

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
