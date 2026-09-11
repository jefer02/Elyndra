package com.elyndra.launcher.library

/**
 * Traducción de identificadores del Storage Access Framework a rutas reales.
 *
 * El proveedor de almacenamiento externo usa documentIds con la forma
 * "volumen:ruta/relativa" ("primary:ROMs/PSP", "1A2B-3C4D:Juegos"). Algunos
 * emuladores (RetroArch, NooDS, ePSXe…) solo aceptan rutas absolutas, así
 * que se reconstruyen igual que hace ES-DE con %ROM%. Todo son cadenas para
 * poder probarlo en la JVM sin android.net.Uri.
 */
object SafPaths {

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Autoridad de una URI "content://autoridad/…". */
    fun authorityOf(uri: String): String? =
        uri.removePrefix("content://").substringBefore('/').takeIf { uri.startsWith("content://") && it.isNotEmpty() }

    /** Ruta absoluta del documento, o null si el proveedor no es almacenamiento local. */
    fun docIdToPath(authority: String?, docId: String): String? {
        if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
        if (authority != null && authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val relative = docId.substring(colon + 1).trim('/')
        val base = when {
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            volume.equals("home", ignoreCase = true) -> "/storage/emulated/0/Documents"
            else -> "/storage/$volume"
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    /** Texto para enseñar al usuario: la ruta si se conoce, si no el nombre del documento. */
    fun displayPath(authority: String?, docId: String): String =
        docIdToPath(authority, docId) ?: docId.substringAfter(':').ifEmpty { docId }

    /** Nombre de la última carpeta del documentId ("primary:ROMs/PSP" → "PSP"). */
    fun lastSegment(docId: String): String =
        docId.substringAfter(':').trimEnd('/').substringAfterLast('/')
}
