package com.elyndra.launcher.di

import com.elyndra.launcher.update.ReleaseCache
import com.elyndra.launcher.update.StoredReleaseCache
import com.elyndra.launcher.update.UpdateSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Las piezas del actualizador (releases de GitHub): ver docs/RELEASING.md. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    abstract fun releaseCache(impl: StoredReleaseCache): ReleaseCache

    companion object {
        @Provides
        @Singleton
        @UpdateHttp
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        @Provides
        @UpdateReleasesUrl
        fun releasesUrl(): String = UpdateSource.API_RELEASES
    }
}
