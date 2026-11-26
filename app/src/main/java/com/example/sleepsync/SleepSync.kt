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
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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
        val statusStore = SyncStatusStore(applicationContext)
        val client = runCatching { HealthConnectClient.getOrCreate(applicationContext) }.getOrNull()
        if (client == null) {
            statusStore.recordFailure()
        } else {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                // Without background permission nothing is read here; the widget then shows how old its data is.
                if (READ_SLEEP in granted && READ_IN_BACKGROUND in granted) {
                    SleepArchive(applicationContext).sync(client, READ_HISTORY in granted)
                    statusStore.recordSuccess()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusStore.recordFailure()
            }
        }
        refreshWidget(applicationContext)
        return Result.success()
    }
}

data class SyncStatus(val lastSuccess: Instant?, val lastFailure: Instant?) {
    // True when the most recent attempt failed, so what's on screen may be older than it looks.
    val lastAttemptFailed: Boolean
        get() = lastFailure != null && (lastSuccess == null || lastFailure.isAfter(lastSuccess))
}

class SyncStatusStore(context: Context) {
    private val prefs = context.getSharedPreferences("sync_status", Context.MODE_PRIVATE)

    fun load() = SyncStatus(
        lastSuccess = prefs.getLong(SUCCESS_KEY, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        lastFailure = prefs.getLong(FAILURE_KEY, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
    )

    fun recordSuccess() = prefs.edit().putLong(SUCCESS_KEY, System.currentTimeMillis()).apply()

    fun recordFailure() = prefs.edit().putLong(FAILURE_KEY, System.currentTimeMillis()).apply()

    private companion object {
        const val SUCCESS_KEY = "last_success"
        const val FAILURE_KEY = "last_failure"
    }
}

private val syncClock = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
private val syncDate = DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN)

// "방금", "12분 전", "오늘 오후 3:10", "어제 오후 3:10", "11월 24일 오후 3:10"
fun syncTimeLabel(at: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val minutesAgo = Duration.between(at, now).toMinutes()
    val time = at.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    return when {
        minutesAgo < 1 -> "방금"
        minutesAgo < 60 -> "${minutesAgo}분 전"
        time.toLocalDate() == today -> "오늘 ${time.format(syncClock)}"
        time.toLocalDate() == today.minusDays(1) -> "어제 ${time.format(syncClock)}"
        else -> time.format(syncDate)
    }
}

// Widget label shown instead of "오늘 밤 추천 취침" when its numbers may be out of date; null when fresh.
fun widgetSyncWarning(status: SyncStatus, now: Instant = Instant.now()): String? {
    val success = status.lastSuccess ?: return null
    val hoursAgo = Duration.between(success, now).toHours()
    return when {
        status.lastAttemptFailed -> "⚠ 업데이트 실패 · ${syncTimeLabel(success, now)} 기록"
        hoursAgo >= WIDGET_STALE_HOURS -> "⚠ ${hoursAgo}시간 전 기록"
        else -> null
    }
}

private const val WIDGET_STALE_HOURS = 6L

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SleepWidgetProvider.refresh(applicationContext)
        return Result.success()
    }
}
