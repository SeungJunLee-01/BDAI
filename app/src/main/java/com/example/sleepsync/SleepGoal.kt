package com.example.sleepsync

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

const val DEFAULT_TARGET_MINUTES = 8 * 60L
private const val MIN_TARGET_MINUTES = 4 * 60L
private const val MAX_TARGET_MINUTES = 12 * 60L
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
    var hoursText by remember { mutableStateOf(TextFieldValue(twoDigits(currentMinutes / 60))) }
    var minutesText by remember { mutableStateOf(TextFieldValue(twoDigits(currentMinutes % 60))) }
    val minuteFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val initialWakeTimes = remember { currentWakeTimes ?: DayOfWeek.entries.associateWith { DEFAULT_WAKE_TIME } }
    var wakeTimes by remember { mutableStateOf(initialWakeTimes) }
    var editing by remember { mutableStateOf<DayOfWeek?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    var discardConfirmed by remember { mutableStateOf(false) }

    val hours = hoursText.text.toLongOrNull()
    val mins = minutesText.text.toLongOrNull()
    val minutes = if (hours != null && mins != null && mins < 60) hours * 60 + mins else null
    val targetError = when {
        minutes == null -> "시간과 분을 숫자로 입력해주세요. 분은 59까지예요."
        minutes !in MIN_TARGET_MINUTES..MAX_TARGET_MINUTES ->
            "${formatDuration(MIN_TARGET_MINUTES)}에서 ${formatDuration(MAX_TARGET_MINUTES)} 사이로 입력해주세요."
        else -> null
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // Swipe-down, tapping outside and Back all ask the sheet state before hiding, so blocking the
    // hide here covers every way of closing the sheet without pressing 저장.
    val hasChanges = minutes != currentMinutes || wakeTimes != initialWakeTimes
    val latestHasChanges by rememberUpdatedState(hasChanges)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && latestHasChanges && !discardConfirmed) {
                askDiscard = true
                false
            } else {
                true
            }
        },
    )
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
                text = "차트의 목표선과 수면 부채 계산에 쓰여요.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClockDigitsField(
                    value = hoursText,
                    onValueChange = { hoursText = it },
                    onTypedTwoDigits = { minuteFocus.requestFocus() },
                    contentDescription = "목표 수면 시간",
                    imeAction = ImeAction.Next,
                    onIme = { minuteFocus.requestFocus() },
                )
                Text(
                    text = ":",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                ClockDigitsField(
                    value = minutesText,
                    onValueChange = { minutesText = it },
                    contentDescription = "목표 수면 분",
                    imeAction = ImeAction.Done,
                    onIme = { focusManager.clearFocus() },
                    modifier = Modifier.focusRequester(minuteFocus),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = targetError ?: "= ${formatDuration(minutes ?: 0)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (targetError != null) MaterialTheme.colorScheme.error else muted,
            )
            Text(
                text = "성인은 보통 7~9시간을 권장해요. " +
                    "${formatDuration(MIN_TARGET_MINUTES)}에서 ${formatDuration(MAX_TARGET_MINUTES)} 사이로 입력할 수 있어요.",
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
                onClick = { if (targetError == null && minutes != null) onSave(minutes, wakeTimes) },
                enabled = targetError == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("저장") }
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("저장하지 않고 닫을까요?") },
            text = { Text("바꾼 목표 수면 시간이나 기상 목표가 저장되지 않아요.") },
            confirmButton = {
                TextButton(onClick = {
                    askDiscard = false
                    discardConfirmed = true
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }) { Text("저장 안 하고 닫기") }
            },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text("계속 수정") } },
        )
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

private fun twoDigits(value: Long) = value.toString().padStart(2, '0')

// One two-digit box of an "HH:MM" clock. Only digits are accepted; the whole value is selected on
// focus so typing replaces it, and a single digit is padded ("7" → "07") when focus leaves.
@Composable
private fun ClockDigitsField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    contentDescription: String,
    onTypedTwoDigits: () -> Unit = {},
    imeAction: ImeAction,
    onIme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            if (next.text.length <= 2 && next.text.all(Char::isDigit)) {
                val typedTwoDigits = next.text.length == 2 && next.text != value.text
                onValueChange(next)
                if (typedTwoDigits) onTypedTwoDigits()
            }
        },
        modifier = modifier
            .width(96.dp)
            .semantics { this.contentDescription = contentDescription }
            .onFocusChanged { state ->
                val text = value.text
                if (state.isFocused) {
                    onValueChange(value.copy(selection = TextRange(0, text.length)))
                } else if (text.length == 1) {
                    onValueChange(TextFieldValue(text.padStart(2, '0')))
                }
            },
        textStyle = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
    )
}
