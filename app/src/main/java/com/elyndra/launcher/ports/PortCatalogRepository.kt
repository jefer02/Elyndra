package com.elyndra.launcher.ports

import android.content.Context
import android.util.Log
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.di.PortsHttpClient
import com.elyndra.launcher.domain.ports.PortCatalog
import com.elyndra.launcher.domain.ports.PortCatalogParser
import com.elyndra.launcher.update.UpdateError
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El catálogo de ports.
 *
 * Se pide al repositorio del catálogo (la URL de Ajustes, por defecto
 * [DEFAULT_URL]) con ETag; si no hay red, GitHub limita o lo que llega no
 * es un catálogo válido, vale la última copia buena y, si no la hay, la que
 * viaja dentro del APK (`assets/ports/catalog.json`).
 */
@Singleton
class PortCatalogRepository(
    client: OkHttpClient,
    cache: PortHttpCache,
    private val allow: HostAllowlist,
    private val url: () -> String,
    private val bundled: () -> String?,
    clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(
        @PortsHttpClient client: OkHttpClient,
        cache: PortHttpCache,
        settings: SettingsStore,
        @ApplicationContext context: Context,
    ) : this(
        client,
        cache,
        HostAllowlist.CATALOG,
        { settings.portsCatalogUrl },
        { readBundled(context) },
    )

    enum class Source { Remote, Bundled }

    /**
     * Por qué se usa el del APK: sin red de verdad ([Offline]: no hay conexión,
     * se cortó) o el repositorio no dio un catálogo ([Unavailable]: 404 porque
     * aún no existe, otro error HTTP, límite de GitHub, algo que no es un
     * catálogo). Solo lo primero es "sin conexión".
     */
    enum class Fallback { Offline, Unavailable }

    data class Loaded(val catalog: PortCatalog, val source: Source, val rejected: List<String>, val fallback: Fallback? = null)

    private val http = ConditionalGet(client, cache, clock)

    /** El catálogo, de la red (o su copia) o el que viaja en el APK. Nunca falla: como mucho, vacío. */
    suspend fun load(force: Boolean = false): Loaded {
        val attempt = runCatching { http.get(url(), allow, TTL, force) { PortCatalogParser.parse(it)?.catalog?.ports?.isNotEmpty() == true } }
            .onFailure { runCatching { Log.w(TAG, "catalog: ${it.message}") } }
        val remote = attempt.getOrNull()
            ?.let { PortCatalogParser.parse(it) }
            ?.takeIf { it.catalog.ports.isNotEmpty() }
        if (remote != null) return Loaded(remote.catalog, Source.Remote, remote.rejected).also { log(it) }
        val local = bundled()?.let { PortCatalogParser.parse(it) }
        val why = fallbackFor(attempt.exceptionOrNull())
        return Loaded(local?.catalog ?: PortCatalog(), Source.Bundled, local?.rejected.orEmpty(), why).also { log(it) }
    }

    private fun log(l: Loaded) {
        if (l.rejected.isNotEmpty()) runCatching { Log.w(TAG, "catalog entries rejected: ${l.rejected}") }
    }

    companion object {
        private const val TAG = "PortCatalog"

        /** Sin conexión solo si de verdad no hubo red; todo lo demás es "el repositorio no lo dio". */
        fun fallbackFor(error: Throwable?): Fallback = when (error) {
            is UpdateError.Network, is java.io.IOException -> Fallback.Offline
            else -> Fallback.Unavailable
        }
        const val DEFAULT_URL = "https://raw.githubusercontent.com/jefer02/elyndra-ports/main/catalog.json"
        const val BUNDLED_ASSET = "ports/catalog.json"

        private fun readBundled(context: Context): String? =
            runCatching { context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() } }.getOrNull()

        /** El catálogo cambia a mano y de tarde en tarde: se vuelve a mirar como mucho cada 6 h. */
        const val TTL = 6L * 60 * 60 * 1000
    }
}
