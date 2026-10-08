package com.elyndra.launcher.update

import android.content.Context
import com.elyndra.launcher.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La copia de la última lista de releases: el ETag en [SettingsStore] y el
 * JSON en un archivo (fuera de la copia de seguridad: se vuelve a pedir).
 */
@Singleton
class StoredReleaseCache @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsStore,
) : ReleaseCache {

    private val file = File(context.noBackupFilesDir, "updates/releases.json")

    override val etag: String? get() = settings.updateEtag

    override val body: String? get() = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()

    override fun store(etag: String?, body: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(body)
            settings.updateEtag = etag
        }
    }
}
