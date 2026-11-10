package com.elyndra.launcher.library

import android.net.Uri
import android.util.Log
import com.elyndra.launcher.data.RomFolder

/**
 * La única puerta de una carpeta de la biblioteca a SAF.
 *
 * Una carpeta VIRTUAL (la de los ports de un sistema sin carpeta, ver
 * PortPlacement) no tiene árbol: nada de SAF puede tocarla, ni leer, ni pedir
 * o soltar permisos. Cada entrada de [SafFiles] y [RomScanner] pasa por aquí
 * antes de llamar al ContentResolver, y lo de fuera saca el árbol de una
 * carpeta con [tree], nunca con `Uri.parse(folder.treeUri)` (SafGuardTest lo
 * vigila). Lo rechazado se apunta en el registro.
 */
object SafGuard {

    private const val TAG = "SafGuard"

    /** Quién se entera de un rechazo (el registro; las pruebas lo cambian para contarlos). */
    @Volatile
    var onReject: (String) -> Unit = { Log.w(TAG, it) }

    /** ¿Es [treeUri] un árbol SAF? (no vacío y `content://`). */
    fun allows(treeUri: String?, what: String = "SAF"): Boolean {
        val ok = !treeUri.isNullOrBlank() && treeUri.startsWith("content://")
        if (!ok) onReject("$what refused: not a SAF tree (virtual folder or empty URI)")
        return ok
    }

    fun allows(treeUri: Uri?, what: String = "SAF"): Boolean = allows(treeUri?.toString(), what)

    /** ¿Se puede tocar [folder] con SAF? Las virtuales, nunca. */
    fun allows(folder: RomFolder, what: String = "SAF"): Boolean {
        if (folder.isVirtual) {
            onReject("$what refused: virtual folder ${folder.id}")
            return false
        }
        return allows(folder.treeUri, what)
    }

    /** El árbol SAF de [folder], o null (y apuntado) si es virtual o no tiene. */
    fun tree(folder: RomFolder, what: String = "SAF"): Uri? = if (allows(folder, what)) Uri.parse(folder.treeUri) else null
}
