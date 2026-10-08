package com.elyndra.launcher.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Cómo acabó una instalación. */
sealed interface InstallResult {
    /** Instalada (normalmente el proceso muere antes de llegar a verlo). */
    data object Success : InstallResult
    /** El usuario dijo que no en el diálogo del sistema. */
    data object Cancelled : InstallResult
    /** La app instalada está firmada con otra clave (p. ej. una build de depuración): Android no la sustituye. */
    data object SignatureMismatch : InstallResult
    data class Failed(val message: String?) : InstallResult
}

/**
 * Instala un APK de actualización con [PackageInstaller].
 *
 * Se usa una sesión y no `ACTION_VIEW` porque la sesión devuelve el
 * resultado: así se distingue "el usuario canceló" de "la firma no
 * coincide", y se puede explicar en vez de dejar al usuario con el
 * "App no instalada" genérico del sistema.
 */
@Singleton
class UpdateInstaller @Inject constructor(@ApplicationContext private val context: Context) {

    /** El permiso "Instalar apps desconocidas" para Elyndra (Android 8+, siempre en minSdk 26). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** La pantalla del sistema donde se concede ese permiso, ya en la ficha de Elyndra. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Copia [apk] en una sesión, la confirma y espera el resultado. El APK se
     * borra en cuanto está copiado (la sesión guarda su propia copia), salga
     * como salga.
     */
    suspend fun install(apk: File): InstallResult {
        val installer = context.packageManager.packageInstaller
        val sessionId = try {
            withContext(Dispatchers.IO) {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(apk.length())
                }
                val id = installer.createSession(params)
                try {
                    installer.openSession(id).use { session ->
                        apk.inputStream().use { input ->
                            session.openWrite("elyndra.apk", 0, apk.length()).use { out ->
                                input.copyTo(out, 64 * 1024)
                                session.fsync(out)
                            }
                        }
                    }
                } catch (e: Exception) {
                    runCatching { installer.abandonSession(id) }
                    throw e
                }
                id
            }
        } catch (e: Exception) {
            return InstallResult.Failed(e.message)
        } finally {
            apk.delete()
        }
        return commit(installer, sessionId)
    }

    private suspend fun commit(installer: PackageInstaller, sessionId: Int): InstallResult = suspendCancellableCoroutine { cont ->
        val action = "${context.packageName}.UPDATE_INSTALL.$sessionId"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // El sistema pide confirmación: su diálogo, encima de Elyndra.
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    val started = confirm != null && runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                    if (started) return
                    finish(InstallResult.Failed(message))
                    return
                }
                finish(resultOf(status, message))
            }

            private fun finish(result: InstallResult) {
                runCatching { context.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(result)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        cont.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
        }
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, sessionId, Intent(action).setPackage(context.packageName), flags)
            installer.openSession(sessionId).use { it.commit(callback.intentSender) }
        } catch (e: Exception) {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
            if (cont.isActive) cont.resume(InstallResult.Failed(e.message))
        }
    }

    internal companion object {
        /** Lo que dice el sistema, traducido a lo que hay que contarle al usuario. */
        fun resultOf(status: Int, message: String?): InstallResult = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallResult.Success
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Cancelled
            // INSTALL_FAILED_UPDATE_INCOMPATIBLE llega como conflicto o incompatible, según la versión de Android.
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                if (isSignatureProblem(message) || status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
                    InstallResult.SignatureMismatch
                } else {
                    InstallResult.Failed(message)
                }
            else -> if (isSignatureProblem(message)) InstallResult.SignatureMismatch else InstallResult.Failed(message)
        }

        private fun isSignatureProblem(message: String?): Boolean {
            val m = message?.lowercase() ?: return false
            return "signature" in m || "update_incompatible" in m || "certificate" in m
        }
    }
}
