package com.elyndra.launcher.metadata

import java.security.MessageDigest

/* ─────────────────────────────────────────────────────────────
   Traducción de descripciones en el dispositivo.

   Solo descripciones (nunca nombres de juegos), solo a demanda o
   con "Traducir automáticamente" encendido, y nunca bajando un
   modelo sin preguntar: [TranslationCache.translate] devuelve
   [TranslationResult.NeedsModels] y quien llama pide permiso.

   Cada traducción se guarda con su idioma y el hash del original;
   si la descripción cambia, la guardada deja de valer.
   ───────────────────────────────────────────────────────────── */

/** El motor de traducción (ML Kit en la app; uno falso en las pruebas). */
interface DescriptionTranslator {
    /** ¿Sabe traducir entre estos dos idiomas? */
    fun supports(from: String, to: String): Boolean

    /** Idiomas cuyo modelo ya está en el dispositivo. */
    suspend fun downloadedModels(): Set<String>

    /** Baja los modelos que falten. [wifiOnly]: solo por Wi-Fi. Lanza si falla. */
    suspend fun download(langs: Set<String>, wifiOnly: Boolean)

    /** Traduce con los modelos ya descargados. Lanza si falla. */
    suspend fun translate(text: String, from: String, to: String): String

    suspend fun deleteModel(lang: String)
}

/** Lo guardado de una traducción. */
data class StoredTranslation(
    val gameKey: String,
    val targetLang: String,
    val sourceLang: String,
    val sourceHash: String,
    val text: String,
)

/** Dónde se guardan (Room en la app; un mapa en las pruebas). */
interface TranslationStore {
    suspend fun get(gameKey: String, targetLang: String): StoredTranslation?
    suspend fun put(t: StoredTranslation)
}

sealed interface TranslationResult {
    data class Done(val text: String) : TranslationResult
    /** Faltan estos modelos: hay que preguntar antes de bajarlos. */
    data class NeedsModels(val langs: Set<String>) : TranslationResult
    data object Unsupported : TranslationResult
    data object Failed : TranslationResult
}

class TranslationCache(private val store: TranslationStore, private val translator: DescriptionTranslator) {

    /** La traducción guardada de [sourceText] a [target], si sigue valiendo para ese original. */
    suspend fun cached(gameKey: String, target: String, sourceLang: String, sourceText: String): String? {
        val t = runCatching { store.get(gameKey, target) }.getOrNull() ?: return null
        return t.text.takeIf { t.sourceLang == sourceLang && t.sourceHash == hash(sourceText) }
    }

    /**
     * Traduce (o devuelve la guardada). Con [allowDownload] baja lo que falte
     * —solo cuando el usuario ya lo aceptó—; si no, avisa con [TranslationResult.NeedsModels].
     */
    suspend fun translate(
        gameKey: String,
        target: String,
        sourceLang: String,
        sourceText: String,
        allowDownload: Boolean = false,
        wifiOnly: Boolean = true,
    ): TranslationResult {
        if (sourceLang == target) return TranslationResult.Done(sourceText)
        cached(gameKey, target, sourceLang, sourceText)?.let { return TranslationResult.Done(it) }
        if (!translator.supports(sourceLang, target)) return TranslationResult.Unsupported
        val have = runCatching { translator.downloadedModels() }.getOrElse { return TranslationResult.Failed }
        val missing = setOf(sourceLang, target) - have
        if (missing.isNotEmpty()) {
            if (!allowDownload) return TranslationResult.NeedsModels(missing)
            runCatching { translator.download(missing, wifiOnly) }.getOrElse { return TranslationResult.Failed }
        }
        val text = runCatching { translator.translate(sourceText, sourceLang, target) }.getOrElse { return TranslationResult.Failed }
            .trim().takeIf { it.isNotEmpty() } ?: return TranslationResult.Failed
        runCatching { store.put(StoredTranslation(gameKey, target, sourceLang, hash(sourceText), text)) }
        return TranslationResult.Done(text)
    }

    suspend fun downloadedModels(): Set<String> = translator.downloadedModels()

    suspend fun deleteModel(lang: String) = translator.deleteModel(lang)

    companion object {
        /** Tamaño aproximado de cada modelo de idioma (lo que se enseña antes de bajarlo). */
        const val MODEL_MB = 30

        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }
}
