package com.elyndra.launcher.masha

import com.elyndra.launcher.data.db.AiCacheDao
import com.elyndra.launcher.data.db.AiCacheEntity
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Respuestas de la IA que se pueden reutilizar.
 *
 * Solo entran las de consulta (nada que haya lanzado un juego o cambiado un
 * ajuste) y la clave incluye todo lo que las produjo: modelo, versión del
 * prompt, idioma, pregunta, los últimos turnos y la huella estable del
 * contexto. Si la biblioteca, las sesiones o los recuerdos cambian, la huella
 * cambia y la respuesta vieja deja de valer sola.
 *
 * Hay una segunda caché, gratis, en el propio DeepSeek: reutiliza el prefijo
 * común de las peticiones (prompt de sistema + herramientas + hilo). Por eso el
 * contexto va en el último mensaje y no en el de sistema: así el prefijo no
 * cambia de una pregunta a otra.
 */
@Singleton
class MashaCache @Inject constructor(private val dao: AiCacheDao) {

    private val memory = object : LinkedHashMap<String, AiCacheEntity>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiCacheEntity>?) = size > MEMORY_ENTRIES
    }

    fun key(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0001").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun get(key: String, now: Long = System.currentTimeMillis()): String? {
        synchronized(memory) { memory[key] }?.let { if (it.expiresAt > now) return it.response }
        val stored = runCatching { dao.get(key, now) }.getOrNull() ?: return null
        synchronized(memory) { memory[key] = stored }
        return stored.response
    }

    suspend fun put(key: String, response: String, ttlMs: Long, now: Long = System.currentTimeMillis()) {
        val entry = AiCacheEntity(key, response, now, now + ttlMs)
        synchronized(memory) { memory[key] = entry }
        runCatching {
            dao.put(entry)
            dao.evictExpired(now)
        }
    }

    suspend fun clear() {
        synchronized(memory) { memory.clear() }
        runCatching { dao.clear() }
    }

    private companion object {
        const val MEMORY_ENTRIES = 32
    }
}
