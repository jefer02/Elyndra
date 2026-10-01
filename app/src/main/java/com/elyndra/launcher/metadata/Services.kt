package com.elyndra.launcher.metadata

import com.elyndra.launcher.BuildConfig
import com.elyndra.launcher.data.SecretKeys
import com.elyndra.launcher.data.SecretStore
import com.elyndra.launcher.data.SettingsStore

/**
 * Los servicios de metadatos. [keyless]: no piden cuenta ni clave y vienen
 * encendidos de serie (se apagan en Ajustes), así que hay carátulas e
 * información desde el primer arranque.
 */
enum class Service(val id: String, val keyless: Boolean = false) {
    ScreenScraper("ss"),
    Igdb("igdb"),
    SteamGridDb("sgdb"),
    RetroAchievements("ra"),
    /** thumbnails.libretro.com: carátulas, capturas y pantallas de título de consolas. */
    Libretro("libretro", keyless = true),
    /** Tienda de Steam: descripción traducida e imágenes de juegos de PC (y de Android que estén en Steam). */
    Steam("steam", keyless = true),
}

/**
 * Credenciales de cada servicio: lo que el usuario escribió en Ajustes
 * (cifrado en [SecretStore]) y, para ScreenScraper, las credenciales de
 * desarrollador de la app (local.properties → BuildConfig) si el usuario no
 * ha puesto otras. También guarda el token de IGDB.
 */
class ServiceCredentials(
    private val secrets: SecretStore,
    private val settings: SettingsStore,
) : IgdbTokenStore {

    val builtInScreenScraperDev: Boolean
        get() = BuildConfig.SS_DEV_ID.isNotBlank() && BuildConfig.SS_DEV_PASSWORD.isNotBlank()

    fun ss(): SsCredentials = SsCredentials(
        devId = secrets.get(SecretKeys.SS_DEV_ID).ifBlank { BuildConfig.SS_DEV_ID },
        devPassword = secrets.get(SecretKeys.SS_DEV_PASSWORD).ifBlank { BuildConfig.SS_DEV_PASSWORD },
        softName = BuildConfig.SS_SOFTNAME,
        user = secrets.get(SecretKeys.SS_USER).trim(),
        password = secrets.get(SecretKeys.SS_PASSWORD),
    )

    fun igdb(): IgdbCredentials = IgdbCredentials(
        secrets.get(SecretKeys.IGDB_CLIENT_ID).trim(),
        secrets.get(SecretKeys.IGDB_CLIENT_SECRET).trim(),
    )

    fun sgdbKey(): String = secrets.get(SecretKeys.SGDB_KEY).trim()

    fun ra(): RaCredentials = RaCredentials(secrets.get(SecretKeys.RA_USER).trim(), secrets.get(SecretKeys.RA_KEY).trim())

    /** Hay credenciales suficientes para intentar usar el servicio. */
    fun isConfigured(service: Service): Boolean = when (service) {
        // Sin cuenta de usuario ScreenScraper funciona como anónimo (cupo menor), pero
        // sin credenciales de desarrollador no responde a ninguna llamada.
        Service.ScreenScraper -> ss().hasDev
        Service.Igdb -> igdb().isComplete
        Service.SteamGridDb -> sgdbKey().isNotEmpty()
        Service.RetroAchievements -> ra().isComplete
        Service.Libretro, Service.Steam -> settings.keylessEnabled(service.id)
    }

    fun anyConfigured(): Boolean = Service.entries.any { isConfigured(it) }

    override fun load(): IgdbToken? {
        val token = secrets.get(SecretKeys.IGDB_TOKEN)
        return if (token.isBlank()) null else IgdbToken(token, settings.igdbTokenExpiry)
    }

    override fun save(token: IgdbToken?) {
        secrets.set(SecretKeys.IGDB_TOKEN, token?.accessToken.orEmpty())
        settings.igdbTokenExpiry = token?.expiresAt ?: 0L
    }
}
