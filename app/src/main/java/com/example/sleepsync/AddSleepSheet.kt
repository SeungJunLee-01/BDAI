package com.example.sleepsync

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MAX_SLEEP_HOURS = 18L
private const val SELECTABLE_DAYS = 7L
private const val MILLIS_PER_DAY = 86_400_000L
private val DEFAULT_BED_TIME = LocalTime.of(23, 0)
private val DEFAULT_WAKE_TIME = LocalTime.of(7, 0)

// Before the usual wake time, last night hasn't ended yet, so default to the previous morning.
fun defaultWakeDate(): LocalDate =
    if (LocalTime.now() < DEFAULT_WAKE_TIME) LocalDate.now().minusDays(1) else LocalDate.now()

private val timeFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
private val shortDateFormatter = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSleepSheet(
    initialWakeDate: LocalDate,
    existingRecords: List<SleepSessionRecord>,
    allNighters: Set<LocalDate>,
    targetMinutes: Long,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSaveSleep: (start: ZonedDateTime, end: ZonedDateTime) -> Unit,
    onSaveAllNighter: (date: LocalDate) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var allNighterMode by remember { mutableStateOf(false) }
    var wakeDate by remember { mutableStateOf(initialWakeDate) }
    var bedTime by remember { mutableStateOf(DEFAULT_BED_TIME) }
    var wakeTime by remember { mutableStateOf(DEFAULT_WAKE_TIME) }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }

    // A bedtime later than the wake time means you fell asleep the night before.
    val bedDate = if (bedTime < wakeTime) wakeDate else wakeDate.minusDays(1)
    val start = ZonedDateTime.of(bedDate, bedTime, zone)
    val end = ZonedDateTime.of(wakeDate, wakeTime, zone)
    val minutes = if (allNighterMode) 0L else Duration.between(start, end).toMinutes()
    val error = if (allNighterMode) {
        when {
            wakeDate in allNighters -> "이미 밤샘으로 기록된 날이에요."
            existingRecords.any { sleepDate(it, zone) == wakeDate } -> "이 날에는 이미 수면 기록이 있어요."
            else -> null
        }
    } else {
        val overlap = existingRecords.firstOrNull { it.startTime < end.toInstant() && it.endTime > start.toInstant() }
        when {
            end.isAfter(ZonedDateTime.now(zone)) -> "일어난 시각이 아직 지나지 않았어요."
            minutes > MAX_SLEEP_HOURS * 60 -> "수면 시간은 ${MAX_SLEEP_HOURS}시간을 넘을 수 없어요."
            overlap != null -> "이 시간대에 이미 기록이 있어요 (" +
                "${overlap.startTime.atZone(zone).format(timeFormatter)} – ${overlap.endTime.atZone(zone).format(timeFormatter)})"
            else -> null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text(text = "수면 직접 추가", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "워치나 밴드 없이 잔 날, 또는 밤을 새운 날을 기록해요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(20.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !allNighterMode,
                    onClick = { allNighterMode = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("수면") }
                SegmentedButton(
                    selected = allNighterMode,
                    onClick = { allNighterMode = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("밤샘") }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (allNighterMode) {
                PickerRow(
                    label = "밤샘한 다음 날",
                    value = wakeDate.format(shortDateFormatter),
                    caption = "${wakeDate.minusDays(1).format(shortDateFormatter)} 밤에 자지 않았어요",
                ) { picker = PickerTarget.WakeDate }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "밤샘 기록은 Health Connect가 아닌 이 앱에만 저장돼요. 그날 낮잠은 따로 추가하면 합쳐져요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickerRow(label = "일어난 날", value = wakeDate.format(shortDateFormatter)) {
                        picker = PickerTarget.WakeDate
                    }
                    PickerRow(
                        label = "잠든 시각",
                        value = bedTime.format(timeFormatter),
                        caption = bedDate.format(shortDateFormatter),
                    ) { picker = PickerTarget.BedTime }
                    PickerRow(
                        label = "일어난 시각",
                        value = wakeTime.format(timeFormatter),
                        caption = wakeDate.format(shortDateFormatter),
                    ) { picker = PickerTarget.WakeTime }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "총 수면 시간",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatDuration(minutes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = targetComparison(minutes, targetMinutes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (error != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { if (allNighterMode) onSaveAllNighter(wakeDate) else onSaveSleep(start, end) },
                enabled = error == null && !saving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("저장")
                }
            }
        }
    }

    when (picker) {
        PickerTarget.WakeDate -> WakeDatePickerDialog(
            initial = wakeDate,
            onDismiss = { picker = null },
            onConfirm = { wakeDate = it; picker = null },
        )
        PickerTarget.BedTime -> TimePickerDialog(
            title = "잠든 시각",
            initial = bedTime,
            onDismiss = { picker = null },
            onConfirm = { bedTime = it; picker = null },
        )
        PickerTarget.WakeTime -> TimePickerDialog(
            title = "일어난 시각",
            initial = wakeTime,
            onDismiss = { picker = null },
            onConfirm = { wakeTime = it; picker = null },
        )
        null -> Unit
    }
}

private enum class PickerTarget { WakeDate, BedTime, WakeTime }

@Composable
private fun PickerRow(label: String, value: String, caption: String? = null, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(text = value, style = MaterialTheme.typography.titleMedium)
                if (caption != null) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeDatePickerDialog(initial: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toEpochDay() * MILLIS_PER_DAY,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = LocalDate.ofEpochDay(utcTimeMillis / MILLIS_PER_DAY)
                return !date.isAfter(today) && date.isAfter(today.minusDays(SELECTABLE_DAYS))
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onConfirm(LocalDate.ofEpochDay(it / MILLIS_PER_DAY)) } ?: onDismiss()
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(title: String, initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
