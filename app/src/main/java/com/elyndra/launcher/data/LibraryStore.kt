package com.elyndra.launcher.data

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Dónde se guarda la biblioteca. [LibraryRepository] trabaja siempre con la
 * instantánea en memoria y le pasa a la tienda la versión anterior y la nueva:
 * la tienda escribe solo lo que cambió.
 *
 * La de verdad es Room (`RoomLibraryStore`); las pruebas usan una en memoria.
 */
interface LibraryStore {
    suspend fun load(): Library

    /** Persiste el paso de [previous] (lo último guardado) a [next]. */
    suspend fun save(previous: Library, next: Library)
}

/**
 * El antiguo files/library.json, de antes de Room.
 *
 * Se lee una sola vez, al crear la base de datos, y después se aparta como
 * `library.json.migrated` en vez de borrarse: si algo fuera mal en la
 * importación, la biblioteca de siempre sigue en el disco.
 */
object LegacyLibraryJson {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** La biblioteca guardada, o null si no hay archivo (o está corrupto: se aparta como `.corrupt`). */
    fun read(file: File): Library? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(Library.serializer(), file.readText()) }
            .getOrElse {
                // Un JSON corrupto no debe impedir arrancar: se aparta para poder recuperarlo a mano.
                runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt")) }
                null
            }
    }

    fun encode(library: Library): String = json.encodeToString(Library.serializer(), library)

    /** Aparta el archivo ya importado. */
    fun retire(file: File) {
        runCatching { file.renameTo(File(file.parentFile, file.name + ".migrated")) }
    }
}
