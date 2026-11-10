package com.elyndra.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elyndra.launcher.R
import com.elyndra.launcher.data.UserFolder
import com.elyndra.launcher.domain.library.UserFolderLogic

/**
 * Las carpetas del usuario: crearlas, renombrarlas, describirlas, borrarlas y
 * decidir en cuáles está cada juego. Todo pasa por diálogos y hojas, que el
 * mando ya recorre.
 */
class UserFolderController(private val vm: ElyndraViewModel) {

    private val repo get() = vm.app.library

    /** El juego cogido para cambiarlo de sitio dentro de la carpeta abierta; null = ninguno. */
    var grabKey by mutableStateOf<String?>(null); private set

    private fun startGrab(key: String) {
        vm.selectRom(key)
        grabKey = key
        vm.showToast(UiText.res(R.string.user_folder_reorder_hint))
    }

    private fun visibleKeys(): Set<String> = vm.folderRoms(vm.folderId).mapTo(HashSet()) { it.key }

    /** Mueve lo cogido [delta] puestos; la selección va con él. */
    fun moveGrabbed(delta: Int) {
        val key = grabKey ?: return
        val id = vm.folderId ?: return
        repo.moveInUserFolder(id, key, delta, visibleKeys())
    }

    fun drop() {
        grabKey = null
    }

    /** Tocar otra card con algo cogido lo deja en su sitio. Devuelve si había algo cogido. */
    fun dropAt(target: String): Boolean {
        val key = grabKey ?: return false
        val folder = vm.folderId?.let { repo.userFolder(it) }
        val delta = folder?.let { UserFolderLogic.distance(it.items, visibleKeys(), key, target) }
        if (delta != null && delta != 0) moveGrabbed(delta)
        drop()
        return true
    }

    /** Pide el nombre y crea la carpeta; con [addKey], ese juego entra en ella. */
    fun promptCreate(addKey: String? = null) {
        nameDialog(UiText.res(R.string.user_folder_new), "") { typed ->
            val id = repo.createUserFolder(typed)
            when {
                id == null -> vm.showToast(UiText.res(R.string.user_folder_name_empty))
                addKey != null -> {
                    repo.addToUserFolder(id, listOf(addKey))
                    repo.userFolder(id)?.let { vm.showToast(UiText.res(R.string.user_folder_added, it.name)) }
                }
                else -> {
                    if (vm.screen == Screen.Add) vm.go(Screen.Library)
                    val key = "f:$id"
                    vm.select(key)
                    vm.materialize(listOf(key))
                    vm.showToast(UiText.res(R.string.user_folder_created))
                }
            }
        }
    }

    private fun promptRename(folder: UserFolder) {
        nameDialog(UiText.res(R.string.user_folder_rename), folder.name) { typed ->
            if (UserFolderLogic.cleanName(typed) == null) {
                vm.showToast(UiText.res(R.string.user_folder_name_empty))
            } else {
                repo.renameUserFolder(folder.id, typed)
            }
        }
    }

    private fun nameDialog(title: UiText, initial: String, onName: (String) -> Unit) {
        vm.showDialog(
            DialogSpec(
                title = title,
                message = UiText.res(R.string.user_folder_name_msg),
                confirm = DialogButton(UiText.res(R.string.save)) {},
                dismiss = DialogButton(UiText.res(R.string.cancel)) {},
                input = DialogInput(
                    initial = initial,
                    placeholder = UiText.res(R.string.user_folder_name_hint),
                    numeric = false,
                    onConfirm = onName,
                ),
            ),
        )
    }

    private fun promptDescription(folder: UserFolder) {
        vm.showDialog(
            DialogSpec(
                title = UiText.res(R.string.user_folder_description),
                message = UiText.res(R.string.user_folder_description_msg),
                confirm = DialogButton(UiText.res(R.string.save)) {},
                dismiss = DialogButton(UiText.res(R.string.cancel)) {},
                input = DialogInput(
                    initial = folder.description.orEmpty(),
                    placeholder = UiText.res(R.string.user_folder_description),
                    numeric = false,
                ) { typed -> repo.describeUserFolder(folder.id, typed) },
            ),
        )
    }

    private fun confirmDelete(folder: UserFolder) {
        vm.showDialog(
            DialogSpec(
                title = UiText.res(R.string.user_folder_delete),
                message = UiText.res(R.string.user_folder_delete_msg, folder.name),
                destructive = true,
                confirm = DialogButton(UiText.res(R.string.user_folder_delete)) { vm.deleteUserFolder(folder) },
                dismiss = DialogButton(UiText.res(R.string.cancel)) {},
            ),
        )
    }

    /** Las opciones de la carpeta (Y en el carrusel o L3/R3 dentro de ella). */
    fun groups(item: LibraryItem.Folder): List<SheetGroup> {
        val folder = item.userFolder ?: return emptyList()
        return listOf(
            SheetGroup(
                UiText.res(R.string.sheet_group_play),
                listOf(SheetAction(UiText.res(R.string.open), icon = SheetIcon.Play, primary = true) { vm.open(item) }),
            ),
            vm.artworkGroup(item.key, folder.name),
            SheetGroup(
                UiText.res(R.string.sheet_group_manage),
                listOf(
                    SheetAction(UiText.res(R.string.folder_add_title), icon = SheetIcon.Add) { vm.userFolderPicker.open(folder.id) },
                    SheetAction(UiText.res(R.string.user_folder_rename), detail = UiText.Raw(folder.name), icon = SheetIcon.GameId) { promptRename(folder) },
                    SheetAction(UiText.res(R.string.user_folder_description), icon = SheetIcon.Details) { promptDescription(folder) },
                ),
            ),
            vm.removalGroup(UiText.res(R.string.user_folder_delete)) { confirmDelete(folder) },
        )
    }

    fun subtitle(folder: UserFolder, count: Int): UiText =
        folder.description?.let { UiText.Raw(it) } ?: UiText.plural(R.plurals.user_folder_games, count, count)

    /** "Añadir a carpeta" y, dentro de una carpeta del usuario que lo tiene, "Quitar de esta carpeta". */
    fun membershipActions(key: String, title: String): List<SheetAction> {
        val here = vm.currentFolder()?.userFolder?.takeIf { vm.screen == Screen.Folder && key in it.items }
        return listOfNotNull(
            SheetAction(UiText.res(R.string.user_folder_add_to), icon = SheetIcon.Add, opensSheet = true) { pickFolder(key, title) },
            here?.let { folder ->
                SheetAction(UiText.res(R.string.user_folder_remove_item), icon = SheetIcon.Remove) { toggle(folder, key) }
            },
            here?.takeIf { it.items.size > 1 }?.let {
                SheetAction(UiText.res(R.string.user_folder_reorder), icon = SheetIcon.Sort) { startGrab(key) }
            },
        )
    }

    /** La lista de carpetas de un juego: elegir una lo mete o lo saca; arriba, crear otra. */
    fun pickFolder(key: String, title: String) {
        val create = SheetAction(UiText.res(R.string.user_folder_new_ellipsis), icon = SheetIcon.Add, opensSheet = true) { promptCreate(addKey = key) }
        val folders = UserFolderLogic.sorted(vm.library.userFolders).map { folder ->
            SheetAction(
                UiText.Raw(folder.name),
                detail = UiText.plural(R.plurals.user_folder_games, folder.items.size, folder.items.size),
                selected = key in folder.items,
            ) { toggle(folder, key) }
        }
        vm.showSheet(ActionSheetSpec(UiText.res(R.string.user_folder_add_to), UiText.Raw(title), listOf(create) + folders))
    }

    private fun toggle(folder: UserFolder, key: String) {
        if (key in folder.items) {
            repo.removeFromUserFolder(folder.id, listOf(key))
            vm.showToast(UiText.res(R.string.user_folder_removed, folder.name))
        } else {
            repo.addToUserFolder(folder.id, listOf(key))
            vm.showToast(UiText.res(R.string.user_folder_added, folder.name))
        }
    }
}
