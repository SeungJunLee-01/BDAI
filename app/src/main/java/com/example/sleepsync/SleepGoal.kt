package com.example.sleepsync

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

const val DEFAULT_TARGET_MINUTES = 8 * 60L
private const val MIN_TARGET_MINUTES = 4 * 60L
private const val MAX_TARGET_MINUTES = 12 * 60L
private const val TARGET_STEP_MINUTES = 15L

class SleepGoalStore(context: Context) {
    private val prefs = context.getSharedPreferences("sleep_goal", Context.MODE_PRIVATE)

    fun load(): Long = prefs.getLong(KEY, DEFAULT_TARGET_MINUTES)

    fun save(minutes: Long) {
        prefs.edit().putLong(KEY, minutes).apply()
    }

    private companion object {
        const val KEY = "target_minutes"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepGoalSheet(currentMinutes: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var value by remember { mutableFloatStateOf(currentMinutes.toFloat()) }
    val minutes = (value / TARGET_STEP_MINUTES).roundToLong() * TARGET_STEP_MINUTES
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text(text = "목표 수면 시간", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "차트의 목표선, 목표 달성 일수, 수면 부채 계산에 모두 쓰여요.",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = formatDuration(minutes),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
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
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "성인은 보통 7~9시간을 권장해요. 15분 단위로 조절할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { onSave(minutes) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("저장") }
        }
    }
}
