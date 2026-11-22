package com.example.sleepsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

val READ_SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
val WRITE_SLEEP = HealthPermission.getWritePermission(SleepSessionRecord::class)
const val READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
const val READ_IN_BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

private const val SYNC_WORK = "sleep-sync"
private const val BEDTIME_WIDGET_WORK = "widget-bedtime-refresh"

@OptIn(ExperimentalFeatureAvailabilityApi::class)
fun requestedPermissions(client: HealthConnectClient?): Set<String> {
    val base = setOf(READ_SLEEP, WRITE_SLEEP)
    client ?: return base
    fun available(feature: Int) =
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    return base +
        listOfNotNull(
            READ_HISTORY.takeIf { available(HealthConnectFeatures.FEATURE_HEALTH_DATA_HISTORIC_READ) },
            READ_IN_BACKGROUND.takeIf { available(HealthConnectFeatures.FEATURE_HEALTH_DATA_BACKGROUND_READ) },
        )
}

fun scheduleBackgroundSync(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        SYNC_WORK,
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<SleepSyncWorker>(1, TimeUnit.HOURS).build(),
    )
}

// Redraws the widget now, and once more right after tonight's recommended bedtime so it flips
// to "지금 주무세요" on time instead of waiting for the next hourly sync.
fun refreshWidget(context: Context) {
    val workManager = WorkManager.getInstance(context)
    val bedtime = SleepWidgetProvider.refresh(context)
    if (bedtime == null) {
        workManager.cancelUniqueWork(BEDTIME_WIDGET_WORK)
        return
    }
    val delay = Duration.between(ZonedDateTime.now(), bedtime).plusMinutes(1)
    workManager.enqueueUniqueWork(
        BEDTIME_WIDGET_WORK,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build(),
    )
}

class SleepSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val client = runCatching { HealthConnectClient.getOrCreate(applicationContext) }.getOrNull()
        if (client != null) {
            runCatching {
                val granted = client.permissionController.getGrantedPermissions()
                if (READ_SLEEP in granted && READ_IN_BACKGROUND in granted) {
                    SleepArchive(applicationContext).sync(client, READ_HISTORY in granted)
                }
            }
        }
        refreshWidget(applicationContext)
        return Result.success()
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SleepWidgetProvider.refresh(applicationContext)
        return Result.success()
    }
}
