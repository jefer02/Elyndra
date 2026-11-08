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

/** Cliente HTTP del actualizador: sin tope de tiempo total, que un APK pesa ~100 MB. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateHttp

/** La dirección de la API de releases (en las pruebas, la de un MockWebServer). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateReleasesUrl

/**
 * Cliente HTTP de la tienda de ports: no sigue redirecciones solo (las sigue
 * PortsHttp comprobando cada host) y sin tope de tiempo total, como el del actualizador.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PortsHttpClient
