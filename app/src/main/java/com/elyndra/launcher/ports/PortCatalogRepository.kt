package com.elyndra.launcher.ports

import android.content.Context
import android.util.Log
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.di.PortsHttpClient
import com.elyndra.launcher.domain.ports.PortCatalog
import com.elyndra.launcher.domain.ports.PortCatalogParser
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

    data class Loaded(val catalog: PortCatalog, val source: Source, val rejected: List<String>)

    private val http = ConditionalGet(client, cache, clock)

    /** El catálogo, de la red (o su copia) o el que viaja en el APK. Nunca falla: como mucho, vacío. */
    suspend fun load(force: Boolean = false): Loaded {
        val remote = runCatching { http.get(url(), allow, TTL, force) { PortCatalogParser.parse(it)?.catalog?.ports?.isNotEmpty() == true } }
            .onFailure { Log.w(TAG, "catalog: ${it.message}") }
            .getOrNull()
            ?.let { PortCatalogParser.parse(it) }
            ?.takeIf { it.catalog.ports.isNotEmpty() }
        if (remote != null) return Loaded(remote.catalog, Source.Remote, remote.rejected).also { log(it) }
        val local = bundled()?.let { PortCatalogParser.parse(it) }
        return Loaded(local?.catalog ?: PortCatalog(), Source.Bundled, local?.rejected.orEmpty()).also { log(it) }
    }

    private fun log(l: Loaded) {
        if (l.rejected.isNotEmpty()) runCatching { Log.w(TAG, "catalog entries rejected: ${l.rejected}") }
    }

    companion object {
        private const val TAG = "PortCatalog"
        const val DEFAULT_URL = "https://raw.githubusercontent.com/jefer02/elyndra-ports/main/catalog.json"
        const val BUNDLED_ASSET = "ports/catalog.json"

        private fun readBundled(context: Context): String? =
            runCatching { context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() } }.getOrNull()

        /** El catálogo cambia a mano y de tarde en tarde: se vuelve a mirar como mucho cada 6 h. */
        const val TTL = 6L * 60 * 60 * 1000
    }
}
