package com.elyndra.launcher.masha

import com.elyndra.launcher.BuildConfig
import com.elyndra.launcher.data.SecretKeys
import com.elyndra.launcher.data.SecretStore
import com.elyndra.launcher.data.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/** Lo que necesita un proveedor de IA para llamar: clave, modelo, dirección y permiso. */
interface MashaEndpoint {
    val apiKey: String
    val model: String
    val baseUrl: String
    val onlineEnabled: Boolean
    val hasKey: Boolean get() = apiKey.isNotEmpty()
}

/**
 * Configuración de la IA de Masha, en un solo sitio.
 *
 * La clave de DeepSeek llega compilada desde local.properties (`masha.apiKey`,
 * vía BuildConfig) y el usuario puede poner la suya en Ajustes, que se guarda
 * cifrada con el Keystore y manda sobre la compilada.
 *
 * Aviso que vale para cualquier clave metida en un APK: se puede extraer
 * desensamblándolo. Para publicar, la llamada debería pasar por un backend
 * propio que guarde la clave (basta con cambiar [baseUrl] y la cabecera).
 */
@Singleton
class MashaConfig @Inject constructor(
    private val secrets: SecretStore,
    private val settings: SettingsStore,
) : MashaEndpoint {
    override val apiKey: String
        get() = secrets.get(SecretKeys.MASHA_KEY).trim().ifEmpty { BuildConfig.MASHA_API_KEY.trim() }

    override val model: String get() = BuildConfig.MASHA_MODEL.trim().ifEmpty { DEFAULT_MODEL }

    override val baseUrl: String get() = BuildConfig.MASHA_BASE_URL.trim().trimEnd('/').ifEmpty { DEFAULT_BASE_URL }

    /** La clave en uso es la compilada (no una puesta por el usuario). */
    val usesBuiltInKey: Boolean
        get() = secrets.get(SecretKeys.MASHA_KEY).isBlank() && BuildConfig.MASHA_API_KEY.isNotBlank()

    /** El usuario deja que Masha use la IA en línea (Ajustes → Masha). */
    override val onlineEnabled: Boolean get() = settings.mashaOnline

    fun setUserKey(key: String) = secrets.set(SecretKeys.MASHA_KEY, key.trim())

    val userKey: String get() = secrets.get(SecretKeys.MASHA_KEY)

    companion object {
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    }
}
