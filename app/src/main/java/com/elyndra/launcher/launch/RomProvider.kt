package com.elyndra.launcher.launch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException

/**
 * Proveedor de solo lectura que entrega una ROM concreta a un emulador.
 *
 * Equivale al %ROMPROVIDER% de ES-DE: algunos emuladores (familia yuzu, los
 * EX de Robert Broglia, MAME4droid, FPse…) quieren una URI "de archivo" con
 * el nombre real al final y columnas OpenableColumns. La URI lleva dentro la
 * URI SAF del documento; el descriptor se abre con el permiso persistente de
 * Elyndra y el emulador solo recibe permiso sobre esa URI exacta.
 *
 *   content://<paquete>.roms/rom/<base64url(uri SAF)>/<nombre de archivo>
 */
class RomProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun target(uri: Uri): Uri? {
        val segments = uri.pathSegments
        if (segments.size < 3 || segments[0] != "rom") return null
        return runCatching { Uri.parse(decode(segments[1])) }.getOrNull()
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode.contains('w') || mode.contains('t')) throw SecurityException("Read-only provider")
        val t = target(uri) ?: throw FileNotFoundException(uri.toString())
        return context?.contentResolver?.openFileDescriptor(t, "r") ?: throw FileNotFoundException(uri.toString())
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val t = target(uri) ?: return null
        val name = uri.lastPathSegment ?: return null
        val size = runCatching {
            context?.contentResolver?.query(t, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        val columns = projection?.takeIf { it.isNotEmpty() } ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map { col ->
                    when (col) {
                        OpenableColumns.DISPLAY_NAME -> name
                        OpenableColumns.SIZE -> size
                        else -> null
                    }
                },
            )
        }
    }

    override fun getType(uri: Uri): String {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

        fun authority(context: Context): String = "${context.packageName}.roms"

        fun uriFor(context: Context, safUri: String, fileName: String): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath("rom")
                .appendPath(Base64.encodeToString(safUri.toByteArray(Charsets.UTF_8), FLAGS))
                .appendPath(fileName)
                .build()

        private fun decode(s: String): String = String(Base64.decode(s, FLAGS), Charsets.UTF_8)
    }
}
