package com.example.sleepsync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private const val HABIT_DAYS = 60L
private const val RECENT_DAYS = 7L
private const val MIN_NIGHTS = 3
private const val MAIN_SLEEP_MIN_MINUTES = 180L
const val MAX_EXTRA_MINUTES = 90L
const val BEDTIME_STEP_MINUTES = 30

// Clock times are compared on a line cut at noon, so 23:30 and 00:30 stay adjacent.
private const val BEDTIME_CUT_MINUTES = 12 * 60

data class BedtimeStats(
    val reference: LocalTime,
    val referenceLabel: String,
    val longTerm: LocalTime,
    val nights: Int,
)

data class TonightPlan(
    val bedtime: ZonedDateTime,
    val goalBedtime: ZonedDateTime,
    val wake: ZonedDateTime,
    val extraMinutes: Long,
    val daysToGoalBedtime: Int,
    val availableIfLate: Long?,
) {
    val sleepMinutes: Long get() = Duration.between(bedtime, wake).toMinutes()
    val goalSleepMinutes: Long get() = Duration.between(goalBedtime, wake).toMinutes()
}

fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.KOREAN)

private fun toLine(time: LocalTime) = Math.floorMod(time.toSecondOfDay() / 60 - BEDTIME_CUT_MINUTES, 24 * 60)

private fun medianBedtime(sleeps: List<SleepSession>, zone: ZoneId): LocalTime {
    val sorted = sleeps.map { toLine(it.start.atZone(zone).toLocalTime()) }.sorted()
    val mid = sorted.size / 2
    val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    val rounded = (median + 2) / 5 * 5
    return LocalTime.ofSecondOfDay(Math.floorMod(rounded + BEDTIME_CUT_MINUTES, 24 * 60) * 60L)
}

// One night per wake date: the longest session, if it's long enough not to be a nap.
fun recentMainSleeps(sessions: List<SleepSession>, zone: ZoneId = ZoneId.systemDefault()): List<SleepSession> {
    val since = LocalDate.now(zone).minusDays(HABIT_DAYS)
    return sessions
        .filter { !sleepDate(it, zone).isBefore(since) }
        .groupBy { sleepDate(it, zone) }
        .mapNotNull { (_, daySessions) -> daySessions.maxByOrNull(::sessionMinutes) }
        .filter { sessionMinutes(it) >= MAIN_SLEEP_MIN_MINUTES }
}

fun analyzeBedtime(mainSleeps: List<SleepSession>, zone: ZoneId = ZoneId.systemDefault()): BedtimeStats? {
    if (mainSleeps.size < MIN_NIGHTS) return null
    val longTerm = medianBedtime(mainSleeps, zone)
    val recentSince = LocalDate.now(zone).minusDays(RECENT_DAYS)
    val recent = mainSleeps.filter { sleepDate(it, zone).isAfter(recentSince) }
    return if (recent.size >= MIN_NIGHTS) {
        BedtimeStats(medianBedtime(recent, zone), "최근 7일", longTerm, mainSleeps.size)
    } else {
        BedtimeStats(longTerm, "최근 2달", longTerm, mainSleeps.size)
    }
}

// Wake at the user's target for that weekday and move bedtime earlier; repay at most
// MAX_EXTRA_MINUTES a night. Bedtime moves at most BEDTIME_STEP_MINUTES earlier than the
// user's actual recent bedtime, so the plan advances only as fast as it is followed.
fun planTonight(
    wakeTimes: Map<DayOfWeek, LocalTime>,
    stats: BedtimeStats?,
    debtMinutes: Long,
    targetMinutes: Long,
    now: ZonedDateTime = ZonedDateTime.now(),
): TonightPlan {
    val today = now.toLocalDate()
    val wakeDate = if (now.toLocalTime() < wakeTimes.getValue(today.dayOfWeek)) today else today.plusDays(1)
    val wake = ZonedDateTime.of(wakeDate, wakeTimes.getValue(wakeDate.dayOfWeek), now.zone)

    val extra = (minOf(debtMinutes, MAX_EXTRA_MINUTES) + 7) / 15 * 15
    val goalBedtime = wake.minusMinutes(targetMinutes + extra)
    val gap = stats?.let { toLine(it.reference) - toLine(goalBedtime.toLocalTime()) } ?: 0
    val laterBy = (gap - BEDTIME_STEP_MINUTES).coerceAtLeast(0)
    val bedtime = goalBedtime.plusMinutes(laterBy.toLong())

    return TonightPlan(
        bedtime = bedtime,
        goalBedtime = goalBedtime,
        wake = wake,
        extraMinutes = extra,
        daysToGoalBedtime = if (laterBy > 0) (gap + BEDTIME_STEP_MINUTES - 1) / BEDTIME_STEP_MINUTES else 0,
        availableIfLate = if (now.isAfter(bedtime)) Duration.between(now, wake).toMinutes() else null,
    )
}

private val clock = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

@Composable
fun TonightPlanCard(
    plan: TonightPlan?,
    stats: BedtimeStats?,
    targetMinutes: Long,
    debtMinutes: Long,
    onOpenSettings: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = "오늘 밤 추천", style = MaterialTheme.typography.labelLarge, color = muted)
            Spacer(modifier = Modifier.height(6.dp))

            if (plan == null) {
                Text(text = "기상 목표를 정해주세요", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "요일별로 일어나야 하는 시각을 정하면, 수면 부채와 최근 취침 습관을 보고 " +
                        "오늘 몇 시에 자면 좋을지 알려드려요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onOpenSettings) { Text("기상 목표 설정") }
                return@Column
            }

            val wakeLabel = "${dayName(plan.wake.dayOfWeek)} 목표 기상 ${plan.wake.format(clock)}"
            if (plan.availableIfLate != null) {
                Text(text = "지금 바로 주무세요", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "추천 취침은 ${plan.bedtime.format(clock)}였어요. 지금 자면 ${plan.wake.format(clock)} 기상까지 " +
                        "${formatDuration(plan.availableIfLate.coerceAtLeast(0))} 잘 수 있어요. ($wakeLabel)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            } else {
                Text(
                    text = "${plan.bedtime.format(clock)} 취침",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$wakeLabel · ${formatDuration(plan.sleepMinutes)} 수면",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }

            val goalBreakdown = if (plan.extraMinutes == 0L) {
                "목표 ${formatDuration(targetMinutes)}"
            } else {
                "목표 ${formatDuration(targetMinutes)} + 부채 갚기 ${formatDuration(plan.extraMinutes)}"
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (plan.daysToGoalBedtime > 0 && stats != null) {
                Text(
                    text = "필요한 수면은 ${formatDuration(plan.goalSleepMinutes)}($goalBreakdown)이라 " +
                        "${plan.goalBedtime.format(clock)}에 자야 하지만, 한 번에 당기면 잠들기 어려워요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${stats.referenceLabel} 실제 취침(${stats.reference.format(clock)})보다 " +
                        "${BEDTIME_STEP_MINUTES}분씩만 앞당겨요. 매일 지키면 약 ${plan.daysToGoalBedtime}일 뒤 " +
                        "${plan.goalBedtime.format(clock)} 취침까지 당길 수 있어요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = when {
                        plan.extraMinutes == 0L -> "갚을 수면 부채가 거의 없어 ${goalBreakdown}만큼만 자면 돼요."
                        debtMinutes > MAX_EXTRA_MINUTES ->
                            "$goalBreakdown. 부채가 커도 하루에 ${formatDuration(MAX_EXTRA_MINUTES)}까지만 더 자고 며칠에 나눠 갚아요."
                        else -> "$goalBreakdown."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = if (stats == null) {
                    "밤잠 기록이 ${MIN_NIGHTS}일 이상 쌓이면 실제 취침 습관에 맞춰 단계적으로 추천해 드려요."
                } else {
                    "${stats.referenceLabel} 취침 ${stats.reference.format(clock)} · 최근 2달 취침 " +
                        "${stats.longTerm.format(clock)} (밤잠 ${stats.nights}일 기준)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Text(
                text = "기상 시각은 설정한 목표대로 두고, 필요한 만큼 일찍 잠자리에 드는 방식이에요.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
    }
}
