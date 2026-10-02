package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.db.DescriptionTranslationEntity
import com.elyndra.launcher.data.db.TranslationDao
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Traducción con Google ML Kit, en el dispositivo: el texto no sale de él.
 * La red solo se usa para bajar un modelo de idioma, y eso solo lo pide
 * [TranslationCache] cuando el usuario ya aceptó (con "solo Wi-Fi" por defecto).
 */
class MlKitTranslator : DescriptionTranslator {

    private val models by lazy { RemoteModelManager.getInstance() }

    private fun code(lang: String): String? = TranslateLanguage.fromLanguageTag(lang)

    override fun supports(from: String, to: String): Boolean = code(from) != null && code(to) != null

    override suspend fun downloadedModels(): Set<String> =
        models.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()

    override suspend fun download(langs: Set<String>, wifiOnly: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        for (lang in langs) {
            val c = code(lang) ?: error("unsupported $lang")
            models.download(TranslateRemoteModel.Builder(c).build(), conditions).await()
        }
    }

    override suspend fun translate(text: String, from: String, to: String): String {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(code(from) ?: error("unsupported $from"))
            .setTargetLanguage(code(to) ?: error("unsupported $to"))
            .build()
        val translator = Translation.getClient(options)
        return try {
            // Sin condiciones de descarga: los modelos ya están (lo comprueba quien llama).
            translator.translate(text).await()
        } finally {
            translator.close()
        }
    }

    override suspend fun deleteModel(lang: String) {
        val c = code(lang) ?: return
        models.deleteDownloadedModel(TranslateRemoteModel.Builder(c).build()).await()
    }
}

/** Las traducciones, en Room. */
class RoomTranslationStore(private val dao: TranslationDao) : TranslationStore {
    override suspend fun get(gameKey: String, targetLang: String): StoredTranslation? =
        dao.get(gameKey, targetLang)?.let { StoredTranslation(it.gameKey, it.targetLang, it.sourceLang, it.sourceHash, it.text) }

    override suspend fun put(t: StoredTranslation) = dao.put(
        DescriptionTranslationEntity(t.gameKey, t.targetLang, t.sourceLang, t.sourceHash, t.text, System.currentTimeMillis()),
    )
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
