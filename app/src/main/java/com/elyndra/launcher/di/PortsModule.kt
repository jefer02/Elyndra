package com.elyndra.launcher.di

import com.elyndra.launcher.data.SecretKeys
import com.elyndra.launcher.data.SecretStore
import com.elyndra.launcher.ports.FilePortHttpCache
import com.elyndra.launcher.ports.GitHubToken
import com.elyndra.launcher.ports.PortHttpCache
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Las piezas de la tienda de ports (catálogo, API de GitHub, descargas). */
@Module
@InstallIn(SingletonComponent::class)
abstract class PortsModule {

    @Binds
    abstract fun httpCache(impl: FilePortHttpCache): PortHttpCache

    companion object {
        @Provides
        @Singleton
        @PortsHttpClient
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        @Provides
        fun githubToken(secrets: SecretStore): GitHubToken = GitHubToken { secrets.get(SecretKeys.GITHUB_TOKEN).takeIf { it.isNotBlank() } }
    }
}
