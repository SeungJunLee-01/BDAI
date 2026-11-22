package com.example.sleepsync

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val widgetClock = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

// Reads only the app's own archive and settings; the background worker keeps the archive fresh.
class SleepWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val (views, _) = buildViews(context)
        appWidgetIds.forEach { manager.updateAppWidget(it, views) }
    }

    override fun onEnabled(context: Context) {
        scheduleBackgroundSync(context)
    }

    companion object {
        // Returns tonight's recommended bedtime if it's still ahead, or null if there's no widget.
        fun refresh(context: Context): ZonedDateTime? {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SleepWidgetProvider::class.java))
            if (ids.isEmpty()) return null
            val (views, upcomingBedtime) = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
            return upcomingBedtime
        }
    }
}

private fun buildViews(context: Context): Pair<RemoteViews, ZonedDateTime?> {
    val views = RemoteViews(context.packageName, R.layout.widget_sleep)
    val openApp = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    views.setOnClickPendingIntent(R.id.widget_root, openApp)

    val sessions = SleepArchive(context).sessionsBlocking()
    val allNighters = AllNighterStore(context).load()
    if (sessions.isEmpty() && allNighters.isEmpty()) {
        views.setTextViewText(R.id.widget_label, "수면 기록")
        views.setTextViewText(R.id.widget_bedtime, "앱을 열어주세요")
        views.setTextViewText(R.id.widget_debt, "기록을 불러오면 추천해 드려요")
        return views to null
    }

    val goals = SleepGoalStore(context)
    val target = goals.load()
    val debt = calculateSleepDebt(dailySleep(lastDays(), sessions, allNighters), target).totalMinutes
    views.setTextViewText(R.id.widget_debt, if (debt > 0) "수면 부채 ${formatDuration(debt)}" else "수면 부채 없음")

    val wakeTimes = goals.loadWakeTimes()
    if (wakeTimes == null) {
        views.setTextViewText(R.id.widget_label, "오늘 밤 추천 취침")
        views.setTextViewText(R.id.widget_bedtime, "기상 목표 설정 필요")
        return views to null
    }

    val plan = planTonight(wakeTimes, analyzeBedtime(recentMainSleeps(sessions)), debt, target)
    if (plan.availableIfLate != null) {
        views.setTextViewText(R.id.widget_label, "추천 취침 ${plan.bedtime.format(widgetClock)} 지남")
        views.setTextViewText(R.id.widget_bedtime, "지금 주무세요")
        return views to null
    }
    views.setTextViewText(R.id.widget_label, "오늘 밤 추천 취침")
    views.setTextViewText(R.id.widget_bedtime, plan.bedtime.format(widgetClock))
    return views to plan.bedtime
}
