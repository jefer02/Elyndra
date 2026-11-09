package com.elyndra.launcher.library

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.elyndra.launcher.metadata.RandomReader
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Lectura de documentos SAF: flujos para hashes y acceso aleatorio para discos.
 *
 * Lectura y permisos. La única escritura es [copyInto] ("Añadir juego" →
 * Archivo de juego): un archivo nuevo, directamente dentro de la carpeta de
 * la biblioteca, y solo si el usuario dio permiso de escritura sobre ese mismo
 * árbol. Nada se sobrescribe ni se borra, salvo la copia a medias de un fallo.
 * Cada entrada pasa antes por [SafGuard]: una carpeta virtual (sin árbol) no
 * llega nunca al ContentResolver.
 */
class SafFiles(private val resolver: ContentResolver) {

    /** El documento [docId] del árbol, o null si [treeUri] no es un árbol SAF. */
    fun docUri(treeUri: String, docId: String): Uri? {
        if (!SafGuard.allows(treeUri, "docUri")) return null
        return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), docId)
    }

    fun <T> openStream(treeUri: String, docId: String, block: (InputStream) -> T): T? {
        if (!SafGuard.allows(treeUri, "openStream")) return null
        return runCatching { docUri(treeUri, docId)?.let { resolver.openInputStream(it)?.use(block) } }.getOrNull()
    }

    fun <T> openRandom(treeUri: String, docId: String, block: (RandomReader) -> T): T? {
        if (!SafGuard.allows(treeUri, "openRandom")) return null
        return runCatching {
            docUri(treeUri, docId)?.let { uri ->
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { channel -> block(ChannelReader(channel)) }
                }
            }
        }.getOrNull()
    }

    /** ¿Sigue Elyndra teniendo permiso de lectura sobre el árbol? (Una carpeta virtual no tiene árbol: no.) */
    fun hasPermission(treeUri: String): Boolean {
        if (!SafGuard.allows(treeUri, "hasPermission")) return false
        return resolver.persistedUriPermissions.any { it.uri.toString() == treeUri && it.isReadPermission }
    }

    fun takePermission(treeUri: Uri) {
        if (!SafGuard.allows(treeUri, "takePermission")) return
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun releasePermission(treeUri: String) {
        if (!SafGuard.allows(treeUri, "releasePermission")) return
        val write = if (canWrite(treeUri)) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or write) }
    }

    /** ¿Puede Elyndra escribir en el árbol? Al añadir una carpeta solo se guarda el permiso de lectura. */
    fun canWrite(treeUri: String): Boolean {
        if (!SafGuard.allows(treeUri, "canWrite")) return false
        return resolver.persistedUriPermissions.any { it.uri.toString() == treeUri && it.isWritePermission }
    }

    /** Lectura y escritura sobre el árbol, tras volver a pedirlo con el selector del sistema. */
    fun takeWritePermission(treeUri: Uri) {
        if (!SafGuard.allows(treeUri, "takeWritePermission")) return
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    /**
     * Copia [source] a un documento nuevo [name] directamente dentro de
     * [parentDocId] del árbol (la carpeta de la biblioteca). Devuelve su
     * documentId, o null si no se pudo; lo creado a medias se borra.
     */
    fun copyInto(treeUri: String, parentDocId: String, source: Uri, name: String, mime: String): String? {
        if (!SafGuard.allows(treeUri, "copyInto")) return null
        val parent = docUri(treeUri, parentDocId) ?: return null
        val created = runCatching { DocumentsContract.createDocument(resolver, parent, mime, name) }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openInputStream(source)!!.use { input ->
                resolver.openOutputStream(created, "w")!!.use { output -> input.copyTo(output, 256 * 1024) }
            }
        }.isSuccess
        if (!ok) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            return null
        }
        return DocumentsContract.getDocumentId(created)
    }

    companion object {
        /** documentId de un archivo hermano ("primary:PS1/juego.cue" + "juego.bin"). */
        fun siblingDocId(docId: String, name: String): String {
            val slash = docId.lastIndexOf('/')
            return if (slash >= 0) docId.substring(0, slash + 1) + name else docId.substringBefore(':') + ":" + name
        }
    }
}

class ChannelReader(private val channel: FileChannel) : RandomReader {
    override val size: Long get() = channel.size()

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0 || pos >= size) return 0
        var total = 0
        while (total < len) {
            val n = channel.read(ByteBuffer.wrap(buf, off + total, len - total), pos + total)
            if (n <= 0) break
            total += n
        }
        return total
    }
}
