package com.example.sleepsync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class ChartColors(
    val bar: Color,
    val barSelected: Color,
    val grid: Color,
    val baseline: Color,
    val primaryInk: Color,
    val secondaryInk: Color,
    val mutedInk: Color,
)

private val LightChartColors = ChartColors(
    bar = Color(0xFF2A78D6),
    barSelected = Color(0xFF1C5CAB),
    grid = Color(0xFFE1E0D9),
    baseline = Color(0xFFC3C2B7),
    primaryInk = Color(0xFF0B0B0B),
    secondaryInk = Color(0xFF52514E),
    mutedInk = Color(0xFF898781),
)

private val DarkChartColors = ChartColors(
    bar = Color(0xFF3987E5),
    barSelected = Color(0xFF86B6EF),
    grid = Color(0xFF2C2C2A),
    baseline = Color(0xFF383835),
    primaryInk = Color(0xFFF0EFEC),
    secondaryInk = Color(0xFFC3C2B7),
    mutedInk = Color(0xFF898781),
)

@Composable
fun chartColors(): ChartColors = if (isSystemInDarkTheme()) DarkChartColors else LightChartColors

data class DailySleep(val date: LocalDate, val minutes: Long?, val allNighter: Boolean = false)

fun sleepDate(session: SleepSession, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    session.end.atZone(zone).toLocalDate()

fun sessionMinutes(session: SleepSession): Long =
    ChronoUnit.MINUTES.between(session.start, session.end)

// A night belongs to the date you woke up; naps on the same date are added in.
// An all-nighter date counts as recorded, starting from zero minutes.
fun dailySleep(
    dates: List<LocalDate>,
    sessions: List<SleepSession>,
    allNighters: Set<LocalDate>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DailySleep> {
    val minutesByDate = sessions
        .groupBy { sleepDate(it, zone) }
        .mapValues { (_, daySessions) -> daySessions.sumOf(::sessionMinutes) }
    return dates.map { date ->
        val allNighter = date in allNighters
        DailySleep(date, minutesByDate[date] ?: if (allNighter) 0L else null, allNighter)
    }
}

fun lastDays(days: Int = 7): List<LocalDate> {
    val today = LocalDate.now()
    return (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
}

fun formatDuration(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "${m}분"
        m == 0L -> "${h}시간"
        else -> "${h}시간 ${m}분"
    }
}

fun targetComparison(minutes: Long, targetMinutes: Long): String {
    val diff = minutes - targetMinutes
    return when {
        diff == 0L -> "목표 ${formatDuration(targetMinutes)}을 딱 채웠어요"
        diff > 0 -> "목표보다 ${formatDuration(diff)} 더 잤어요"
        else -> "목표보다 ${formatDuration(-diff)} 부족해요"
    }
}

private val dayLabelFormatter = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)

fun dayLabel(date: LocalDate): String {
    val label = date.format(dayLabelFormatter)
    return if (date == LocalDate.now()) "오늘 · $label" else label
}

@Composable
fun SleepChart(
    days: List<DailySleep>,
    targetMinutes: Long,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = chartColors()
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = colors.mutedInk)
    val selectedAxisStyle = axisStyle.copy(color = colors.primaryInk, fontWeight = FontWeight.SemiBold)
    val xFormatter = remember { DateTimeFormatter.ofPattern("M/d") }
    val today = LocalDate.now()

    val maxMinutes = maxOf(days.mapNotNull { it.minutes }.maxOrNull() ?: 0L, targetMinutes)
    val maxHours = maxOf(10, ((maxMinutes + 59) / 60).toInt().let { if (it % 2 == 0) it else it + 1 })
    val description = "최근 7일 수면 시간 차트. " + days.joinToString(". ") { day ->
        dayLabel(day.date) + ", " + (if (day.allNighter) "밤샘, " else "") +
            (day.minutes?.let { formatDuration(it) + ", " + targetComparison(it, targetMinutes) } ?: "기록 없음")
    }

    val recorded = days.mapNotNull { it.minutes }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "최근 7일", style = MaterialTheme.typography.titleMedium)
                if (recorded.isNotEmpty()) {
                    Text(
                        text = "평균 ${formatDuration(recorded.sum() / recorded.size)} · 기록 ${recorded.size}일",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryInk,
                    )
                }
            }
            Canvas(modifier = Modifier.size(width = 18.dp, height = 2.dp)) {
                drawLine(
                    color = colors.secondaryInk,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "목표 ${formatDuration(targetMinutes)}", style = MaterialTheme.typography.bodySmall, color = colors.secondaryInk)
        }
        Spacer(modifier = Modifier.height(16.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .semantics { contentDescription = description }
                .pointerInput(days) {
                    detectTapGestures { tap ->
                        val left = 32.dp.toPx()
                        val slotWidth = (size.width - left) / days.size
                        val index = ((tap.x - left) / slotWidth).toInt()
                        if (tap.x >= left && index in days.indices) onSelect(index)
                    }
                },
        ) {
            val left = 32.dp.toPx()
            val right = size.width
            val top = 6.dp.toPx()
            val bottom = size.height - 24.dp.toPx()
            val plotHeight = bottom - top
            val slotWidth = (right - left) / days.size
            val barWidth = minOf(24.dp.toPx(), slotWidth * 0.6f)
            val corner = 4.dp.toPx()
            fun yOf(minutes: Long) = bottom - minutes / (maxHours * 60f) * plotHeight

            for (hour in 0..maxHours step 2) {
                val y = yOf(hour * 60L)
                drawLine(
                    color = if (hour == 0) colors.baseline else colors.grid,
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = 1.dp.toPx(),
                )
                val label = textMeasurer.measure("${hour}h", axisStyle)
                drawText(label, topLeft = Offset(left - 6.dp.toPx() - label.size.width, y - label.size.height / 2))
            }

            days.forEachIndexed { index, day ->
                val centerX = left + slotWidth * (index + 0.5f)
                day.minutes?.takeIf { it > 0 }?.let { minutes ->
                    val barTop = yOf(minutes)
                    val radius = CornerRadius(minOf(corner, bottom - barTop))
                    val bar = Path().apply {
                        addRoundRect(
                            RoundRect(
                                rect = Rect(centerX - barWidth / 2, barTop, centerX + barWidth / 2, bottom),
                                topLeft = radius,
                                topRight = radius,
                                bottomLeft = CornerRadius.Zero,
                                bottomRight = CornerRadius.Zero,
                            )
                        )
                    }
                    drawPath(bar, color = if (index == selectedIndex) colors.barSelected else colors.bar)
                }
                if (day.minutes == 0L) {
                    val zero = textMeasurer.measure(if (day.allNighter) "밤샘" else "0", axisStyle)
                    drawText(zero, topLeft = Offset(centerX - zero.size.width / 2, bottom - 4.dp.toPx() - zero.size.height))
                }

                val text = if (day.date == today) "오늘" else day.date.format(xFormatter)
                val label = textMeasurer.measure(text, if (index == selectedIndex) selectedAxisStyle else axisStyle)
                drawText(label, topLeft = Offset(centerX - label.size.width / 2, bottom + 6.dp.toPx()))
            }

            val targetY = yOf(targetMinutes)
            drawLine(
                color = colors.secondaryInk,
                start = Offset(left, targetY),
                end = Offset(right, targetY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
        }
    }
}
