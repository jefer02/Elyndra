package com.elyndra.launcher.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Repinta el widget de Masha con lo que haya que decir ahora (ver ElyndraWork). */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { MashaWidget().updateAll(applicationContext) }
        return Result.success()
    }
}
