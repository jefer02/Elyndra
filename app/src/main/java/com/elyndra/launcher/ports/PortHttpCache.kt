package com.elyndra.launcher.ports

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Respuestas guardadas de la tienda (catálogo, releases, datos de cada
 * repositorio) con su ETag: con ella GitHub contesta 304 si nada cambió, y
 * un 304 no gasta cupo de la API. [fetchedAt] permite no preguntar siquiera
 * mientras la copia es reciente.
 */
interface PortHttpCache {
    @Serializable
    data class Entry(val etag: String?, val body: String, val fetchedAt: Long)

    fun get(key: String): Entry?
    fun put(key: String, entry: Entry)
}

/** En archivos de `noBackupFilesDir/ports/http/` (no van a la copia de seguridad: se vuelven a pedir). */
@Singleton
class FilePortHttpCache @Inject constructor(@ApplicationContext context: Context) : PortHttpCache {

    private val dir = File(context.noBackupFilesDir, "ports/http")
    private val json = Json { ignoreUnknownKeys = true }

    override fun get(key: String): PortHttpCache.Entry? = runCatching {
        file(key).takeIf { it.exists() }?.readText()?.let { json.decodeFromString<PortHttpCache.Entry>(it) }
    }.getOrNull()

    override fun put(key: String, entry: PortHttpCache.Entry) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, file(key).name + ".tmp")
            tmp.writeText(json.encodeToString(PortHttpCache.Entry.serializer(), entry))
            tmp.renameTo(file(key))
        }
    }

    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }
}
