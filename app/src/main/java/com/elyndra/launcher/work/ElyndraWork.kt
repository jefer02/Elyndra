package com.elyndra.launcher.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.elyndra.launcher.notify.MashaNudgeWorker
import com.elyndra.launcher.widget.WidgetRefreshWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Todo el trabajo en segundo plano de Elyndra, en un sitio.
 *
 * Es poco y espaciado a propósito: un lanzador no tiene por qué gastar
 * batería mientras se juega. Nada de esto corre con la batería baja.
 */
@Singleton
class ElyndraWork @Inject constructor(@ApplicationContext private val context: Context) {

    private val work get() = WorkManager.getInstance(context)

    fun schedulePeriodic() {
        // Metadatos que falten: una vez al día, solo con wifi (o red sin límite).
        work.enqueueUniquePeriodicWork(
            METADATA,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MetadataSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setInitialDelay(2, TimeUnit.HOURS)
                .build(),
        )
        // Lo que enseña el widget cambia despacio: cada pocas horas basta.
        work.enqueueUniquePeriodicWork(
            WIDGET,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
        // Masha mira si tiene algo que de verdad valga un aviso. Casi nunca lo tiene.
        work.enqueueUniquePeriodicWork(
            NUDGE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MashaNudgeWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(6, TimeUnit.HOURS)
                .build(),
        )
    }

    /** Refresca el widget ya (al volver de una sesión, al terminar una pasada de metadatos…). */
    fun refreshWidgetNow() {
        work.enqueueUniqueWork(
            WIDGET_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInitialDelay(3, TimeUnit.SECONDS).build(),
        )
    }

    private companion object {
        const val METADATA = "elyndra.metadata.sync"
        const val WIDGET = "elyndra.widget.refresh"
        const val WIDGET_NOW = "elyndra.widget.refresh.now"
        const val NUDGE = "elyndra.masha.nudge"
    }
}
