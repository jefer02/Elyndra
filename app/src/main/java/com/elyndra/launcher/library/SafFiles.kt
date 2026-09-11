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

/** Lectura de documentos SAF: flujos para hashes y acceso aleatorio para discos. */
class SafFiles(private val resolver: ContentResolver) {

    fun docUri(treeUri: String, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), docId)

    fun <T> openStream(treeUri: String, docId: String, block: (InputStream) -> T): T? = runCatching {
        resolver.openInputStream(docUri(treeUri, docId))?.use(block)
    }.getOrNull()

    fun <T> openRandom(treeUri: String, docId: String, block: (RandomReader) -> T): T? = runCatching {
        resolver.openFileDescriptor(docUri(treeUri, docId), "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { channel -> block(ChannelReader(channel)) }
        }
    }.getOrNull()

    /** ¿Sigue Elyndra teniendo permiso de lectura sobre el árbol? */
    fun hasPermission(treeUri: String): Boolean =
        resolver.persistedUriPermissions.any { it.uri.toString() == treeUri && it.isReadPermission }

    fun takePermission(treeUri: Uri) {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun releasePermission(treeUri: String) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
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
