package com.example.sleepsync

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

const val DEFAULT_TARGET_MINUTES = 8 * 60L
private const val MIN_TARGET_MINUTES = 4 * 60L
private const val MAX_TARGET_MINUTES = 12 * 60L
private const val TARGET_STEP_MINUTES = 15L
private val DEFAULT_WAKE_TIME = LocalTime.of(7, 0)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

class SleepGoalStore(context: Context) {
    private val prefs = context.getSharedPreferences("sleep_goal", Context.MODE_PRIVATE)

    fun load(): Long = prefs.getLong(TARGET_KEY, DEFAULT_TARGET_MINUTES)

    // null until the user has set their wake times at least once.
    fun loadWakeTimes(): Map<DayOfWeek, LocalTime>? {
        if (!prefs.contains(wakeKey(DayOfWeek.MONDAY))) return null
        return DayOfWeek.entries.associateWith { LocalTime.ofSecondOfDay(prefs.getInt(wakeKey(it), 7 * 60) * 60L) }
    }

    fun save(targetMinutes: Long, wakeTimes: Map<DayOfWeek, LocalTime>) {
        val editor = prefs.edit().putLong(TARGET_KEY, targetMinutes)
        wakeTimes.forEach { (day, time) -> editor.putInt(wakeKey(day), time.toSecondOfDay() / 60) }
        editor.apply()
    }

    private fun wakeKey(day: DayOfWeek) = "wake_${day.value}"

    private companion object {
        const val TARGET_KEY = "target_minutes"
    }
}

private val clock = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepGoalSheet(
    currentMinutes: Long,
    currentWakeTimes: Map<DayOfWeek, LocalTime>?,
    onDismiss: () -> Unit,
    onSave: (targetMinutes: Long, wakeTimes: Map<DayOfWeek, LocalTime>) -> Unit,
) {
    var value by remember { mutableFloatStateOf(currentMinutes.toFloat()) }
    var wakeTimes by remember {
        mutableStateOf(currentWakeTimes ?: DayOfWeek.entries.associateWith { DEFAULT_WAKE_TIME })
    }
    var editing by remember { mutableStateOf<DayOfWeek?>(null) }
    val minutes = (value / TARGET_STEP_MINUTES).roundToLong() * TARGET_STEP_MINUTES
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(text = "수면 목표", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(20.dp))

            Text(text = "목표 수면 시간", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "차트의 목표선, 목표 달성 일수, 수면 부채 계산에 쓰여요.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = formatDuration(minutes),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Slider(
                value = value,
                onValueChange = { value = it },
                valueRange = MIN_TARGET_MINUTES.toFloat()..MAX_TARGET_MINUTES.toFloat(),
                steps = ((MAX_TARGET_MINUTES - MIN_TARGET_MINUTES) / TARGET_STEP_MINUTES - 1).toInt(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = formatDuration(MIN_TARGET_MINUTES), style = MaterialTheme.typography.bodySmall, color = muted)
                Spacer(modifier = Modifier.weight(1f))
                Text(text = formatDuration(MAX_TARGET_MINUTES), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Text(
                text = "성인은 보통 7~9시간을 권장해요. 15분 단위로 조절할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)

            Text(text = "요일별 기상 목표", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "다음 날 일어나야 하는 시각을 기준으로 오늘 밤 취침 시각을 추천해요.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            DayOfWeek.entries.forEach { day ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = day }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = dayName(day), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(text = wakeTimes.getValue(day).format(clock), style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { onSave(minutes, wakeTimes) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("저장") }
        }
    }

    editing?.let { day ->
        val group = if (day in WEEKEND) WEEKEND else DayOfWeek.entries.toSet() - WEEKEND
        TimePickerDialog(
            title = "${dayName(day)} 기상 목표",
            initial = wakeTimes.getValue(day),
            onDismiss = { editing = null },
            onConfirm = { time ->
                wakeTimes = wakeTimes + (day to time)
                editing = null
            },
            extraActionLabel = if (day in WEEKEND) "주말 모두" else "평일 모두",
            onExtraAction = { time ->
                wakeTimes = wakeTimes + group.associateWith { time }
                editing = null
            },
        )
    }
}
