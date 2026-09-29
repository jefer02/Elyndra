package com.elyndra.launcher.di

import javax.inject.Qualifier

/** Ámbito de aplicación: sobrevive a la Activity (descargas de metadatos, guardado, Masha). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** El antiguo files/library.json, que se importa una vez a Room. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LegacyLibraryFile

/** Cliente HTTP de Masha: tiempos de espera largos, pensado para streaming. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MashaHttp
