package com.elyndra.launcher.di

import com.elyndra.launcher.masha.MashaAI
import com.elyndra.launcher.masha.MashaConfig
import com.elyndra.launcher.masha.MashaEndpoint
import com.elyndra.launcher.masha.deepseek.DeepSeekMashaAI
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MashaModule {

    /** El proveedor de IA de Masha. Cambiar de proveedor es cambiar esta línea. */
    @Binds
    @Singleton
    abstract fun mashaAI(impl: DeepSeekMashaAI): MashaAI

    @Binds
    abstract fun mashaEndpoint(impl: MashaConfig): MashaEndpoint

    companion object {
        /**
         * Cliente HTTP de Masha. Sin tope total de llamada (una respuesta en
         * streaming dura lo que dura), pero sí entre lecturas: DeepSeek manda
         * `: keep-alive` mientras piensa, así que un minuto de silencio es que
         * la conexión se ha quedado colgada.
         */
        @Provides
        @Singleton
        @MashaHttp
        fun mashaHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
