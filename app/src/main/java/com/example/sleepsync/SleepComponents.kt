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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"

private val nightFormatter = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
private val sessionTimeFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

fun sessionTimeRange(session: SleepSession): String {
    val zone = ZoneId.systemDefault()
    return "${session.start.atZone(zone).format(sessionTimeFormatter)} – ${session.end.atZone(zone).format(sessionTimeFormatter)}"
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        content()
    }
}

@Composable
fun DayDetailCard(
    day: DailySleep,
    sessions: List<SleepSession>,
    targetMinutes: Long,
    ownPackage: String,
    onAdd: (() -> Unit)?,
    onDelete: (SleepSession) -> Unit,
    onDeleteAllNighter: () -> Unit,
) {
    SectionCard {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = dayLabel(day.date),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (day.minutes == null) {
                Text(text = "기록 없음", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (onAdd != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "워치를 차지 않고 잤거나 밤을 새웠다면 직접 추가할 수 있어요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onAdd) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("이 날 기록 추가")
                    }
                }
            } else {
                Text(
                    text = formatDuration(day.minutes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = targetComparison(day.minutes, targetMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                if (day.allNighter) {
                    EntryRow(
                        title = "밤샘",
                        subtitle = "${day.date.minusDays(1).format(nightFormatter)} 밤에 자지 않았어요",
                        source = "직접 입력",
                        isManual = true,
                        deleteDescription = "밤샘 기록 삭제",
                        onDelete = onDeleteAllNighter,
                    )
                }
                sessions.forEach { session ->
                    val isManual = session.source == ownPackage
                    EntryRow(
                        title = sessionTimeRange(session),
                        subtitle = formatDuration(sessionMinutes(session)),
                        source = when {
                            isManual -> "직접 입력"
                            session.source == SAMSUNG_HEALTH_PACKAGE -> "삼성 헬스"
                            else -> "다른 앱"
                        },
                        isManual = isManual,
                        deleteDescription = "직접 입력한 기록 삭제",
                        onDelete = { onDelete(session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    source: String,
    isManual: Boolean,
    deleteDescription: String,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = if (isManual) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = source,
                style = MaterialTheme.typography.labelMedium,
                color = if (isManual) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (isManual) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = deleteDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
