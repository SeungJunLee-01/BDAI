package com.example.sleepsync

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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

data class DebtContribution(
    val date: LocalDate,
    val minutes: Long,
    val weight: Int,
    val weightedMinutes: Long,
    val allNighter: Boolean,
)

data class SleepDebt(
    val totalMinutes: Long,
    val contributions: List<DebtContribution>,
    val missingDays: Int,
    val windowDays: Int,
    val targetMinutes: Long,
)

// days is ordered oldest → today; today weighs windowDays/windowDays, the oldest 1/windowDays.
fun calculateSleepDebt(days: List<DailySleep>, targetMinutes: Long): SleepDebt {
    val window = days.size
    val contributions = days.mapIndexedNotNull { index, day ->
        val minutes = day.minutes ?: return@mapIndexedNotNull null
        val weight = index + 1
        DebtContribution(
            date = day.date,
            minutes = minutes,
            weight = weight,
            weightedMinutes = ((targetMinutes - minutes) * weight / window.toDouble()).roundToLong(),
            allNighter = day.allNighter,
        )
    }
    return SleepDebt(
        totalMinutes = contributions.sumOf { it.weightedMinutes }.coerceAtLeast(0),
        contributions = contributions.reversed(),
        missingDays = days.count { it.minutes == null },
        windowDays = window,
        targetMinutes = targetMinutes,
    )
}

private val shortDate = DateTimeFormatter.ofPattern("M/d (E)", java.util.Locale.KOREAN)

private fun weightLabel(weight: Int, window: Int) = if (weight == window) "1" else "$weight/$window"

private fun signedDuration(minutes: Long) = when {
    minutes > 0 -> "+" + formatDuration(minutes)
    minutes < 0 -> "−" + formatDuration(abs(minutes))
    else -> "0분"
}

@Composable
fun SleepDebtCard(debt: SleepDebt, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
            Text(text = "수면 부채", style = MaterialTheme.typography.labelLarge, color = muted)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (debt.totalMinutes > 0) formatDuration(debt.totalMinutes) else "없음",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    debt.contributions.isEmpty() -> "최근 7일 동안 기록이 없어 계산할 수 없어요"
                    debt.totalMinutes > 0 -> "최근 며칠 잠이 목표보다 부족했어요"
                    else -> "목표만큼 충분히 자고 있어요"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            Text(
                text = buildString {
                    append("최근 날일수록 크게 반영해요")
                    if (debt.missingDays > 0) append(" · 기록 없는 ${debt.missingDays}일 제외")
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )

            if (debt.contributions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "계산 내역 접기" else "계산 내역 보기")
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (expanded) {
                val numbers = MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum"))
                val header = MaterialTheme.typography.labelMedium
                DebtRow("날짜", "수면", "가중치", "부채", header, muted)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                debt.contributions.forEach { c ->
                    DebtRow(
                        date = if (c.date == today) "오늘" else c.date.format(shortDate),
                        sleep = when {
                            !c.allNighter -> formatDuration(c.minutes)
                            c.minutes == 0L -> "밤샘"
                            else -> "밤샘+" + formatDuration(c.minutes)
                        },
                        weight = weightLabel(c.weight, debt.windowDays),
                        contribution = signedDuration(c.weightedMinutes),
                        style = numbers,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "부채 = (목표 ${formatDuration(debt.targetMinutes)} − 수면) × 가중치의 합. " +
                        "목표보다 더 잔 날은 부채를 줄여요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun DebtRow(
    date: String,
    sleep: String,
    weight: String,
    contribution: String,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = date, style = style, color = color, modifier = Modifier.weight(1.1f))
        Text(text = sleep, style = style, color = color, modifier = Modifier.weight(1.2f))
        Text(text = weight, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(0.6f))
        Text(text = contribution, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1.1f))
    }
}
