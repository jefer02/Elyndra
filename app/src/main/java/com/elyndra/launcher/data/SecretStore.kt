package com.elyndra.launcher.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credenciales de los servicios de metadatos, cifradas con AES-256/GCM.
 *
 * La clave vive en el Android Keystore y nunca sale del dispositivo; en
 * SharedPreferences solo queda `base64(iv ‖ texto cifrado)`. Si la clave
 * desaparece (restauración, borrado de credenciales del sistema), el valor
 * se da por perdido y el usuario vuelve a introducirlo.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private val key: SecretKey? by lazy { runCatching { loadOrCreateKey() }.getOrNull() }

    fun get(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        val k = key ?: return ""
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.getOrElse { "" }
    }

    fun set(name: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit { remove(name) }
            return
        }
        val k = key ?: return
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val out = cipher.iv + encrypted
            prefs.edit { putString(name, Base64.encodeToString(out, Base64.NO_WRAP)) }
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "elyndra.secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

/** Nombres de cada credencial dentro de [SecretStore]. */
object SecretKeys {
    const val SS_USER = "ss.user"
    const val SS_PASSWORD = "ss.password"
    const val SS_DEV_ID = "ss.devId"
    const val SS_DEV_PASSWORD = "ss.devPassword"
    const val IGDB_CLIENT_ID = "igdb.clientId"
    const val IGDB_CLIENT_SECRET = "igdb.clientSecret"
    const val IGDB_TOKEN = "igdb.token"
    const val SGDB_KEY = "sgdb.key"
    const val RA_USER = "ra.user"
    const val RA_KEY = "ra.key"
}
