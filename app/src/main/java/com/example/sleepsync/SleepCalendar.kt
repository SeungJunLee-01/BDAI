package com.example.sleepsync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

private val WEEKDAYS = listOf("일", "월", "화", "수", "목", "금", "토")

private fun compactDuration(minutes: Long) = "${minutes / 60}:${"%02d".format(minutes % 60)}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepCalendarScreen(
    sessions: List<SleepSession>,
    allNighters: Set<LocalDate>,
    targetMinutes: Long,
    ownPackage: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAdd: (LocalDate) -> Unit,
    onDelete: (SleepSession) -> Unit,
    onDeleteAllNighter: (LocalDate) -> Unit,
) {
    BackHandler(onBack = onBack)
    val today = LocalDate.now()
    val currentMonth = YearMonth.from(today)
    val earliestMonth = remember(sessions, allNighters) {
        val earliest = (sessions.map { sleepDate(it) } + allNighters).minOrNull() ?: today
        YearMonth.from(earliest)
    }
    var monthsBack by rememberSaveable { mutableIntStateOf(0) }
    val month = currentMonth.minusMonths(monthsBack.toLong())
    var selectedEpochDay by rememberSaveable {
        val latest = sessions.map { sleepDate(it) }.filter { !it.isAfter(today) }.maxOrNull() ?: today
        mutableLongStateOf(latest.toEpochDay())
    }
    val selected = LocalDate.ofEpochDay(selectedEpochDay)

    val monthDays = remember(month, sessions, allNighters) {
        dailySleep((1..month.lengthOfMonth()).map { month.atDay(it) }, sessions, allNighters)
    }
    val selectedDay = remember(selected, sessions, allNighters) {
        dailySleep(listOf(selected), sessions, allNighters).first()
    }
    val selectedSessions = remember(selected, sessions) {
        sessions.filter { sleepDate(it) == selected }.sortedBy { it.start }
    }
    val canAdd = !selected.isAfter(today) && selected.isAfter(today.minusDays(SELECTABLE_DAYS))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("수면 캘린더", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    MonthHeader(
                        month = month,
                        canGoBack = month > earliestMonth,
                        canGoForward = month < currentMonth,
                        onBack = { monthsBack++ },
                        onForward = { monthsBack-- },
                    )
                    MonthSummary(monthDays, targetMinutes)
                    Spacer(modifier = Modifier.height(12.dp))
                    MonthGrid(
                        month = month,
                        days = monthDays,
                        today = today,
                        selected = selected,
                        targetMinutes = targetMinutes,
                        onSelect = { selectedEpochDay = it.toEpochDay() },
                    )
                }
            }
            DayDetailCard(
                day = selectedDay,
                sessions = selectedSessions,
                targetMinutes = targetMinutes,
                ownPackage = ownPackage,
                onAdd = if (canAdd) ({ onAdd(selected) }) else null,
                onDelete = onDelete,
                onDeleteAllNighter = { onDeleteAllNighter(selected) },
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전 달")
        }
        Text(
            text = "${month.year}년 ${month.monthValue}월",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 달")
        }
    }
}

@Composable
private fun MonthSummary(days: List<DailySleep>, targetMinutes: Long) {
    val recorded = days.mapNotNull { it.minutes }
    val text = if (recorded.isEmpty()) {
        "이 달에는 기록이 없어요"
    } else {
        "평균 ${formatDuration(recorded.sum() / recorded.size)} · " +
            "목표 달성 ${recorded.count { it >= targetMinutes }}일 · 기록 ${recorded.size}일"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    days: List<DailySleep>,
    today: LocalDate,
    selected: LocalDate,
    targetMinutes: Long,
    onSelect: (LocalDate) -> Unit,
) {
    val leadingBlanks = month.atDay(1).dayOfWeek.value % 7
    val cells: List<DailySleep?> = List(leadingBlanks) { null } + days
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(modifier = Modifier.fillMaxWidth()) {
        WEEKDAYS.forEach { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    cells.chunked(7).forEach { week ->
        Row(modifier = Modifier.fillMaxWidth()) {
            week.forEach { day ->
                Box(modifier = Modifier.weight(1f).padding(2.dp)) {
                    if (day != null) {
                        DayCell(
                            day = day,
                            isToday = day.date == today,
                            isSelected = day.date == selected,
                            isFuture = day.date.isAfter(today),
                            targetMinutes = targetMinutes,
                            onClick = { onSelect(day.date) },
                        )
                    }
                }
            }
            repeat(7 - week.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DayCell(
    day: DailySleep,
    isToday: Boolean,
    isSelected: Boolean,
    isFuture: Boolean,
    targetMinutes: Long,
    onClick: () -> Unit,
) {
    val colors = chartColors()
    val shape = RoundedCornerShape(10.dp)
    val value = when {
        day.minutes == null -> null
        day.allNighter && day.minutes == 0L -> "밤샘"
        else -> compactDuration(day.minutes)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(shape)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .then(if (isToday && !isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
            .clickable(enabled = !isFuture, onClick = onClick)
            .semantics {
                contentDescription = dayLabel(day.date) + ", " + (value?.let { if (it == "밤샘") it else formatDuration(day.minutes!!) } ?: "기록 없음")
            }
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                isFuture -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(3.dp))
            val fraction = (day.minutes!!.toFloat() / targetMinutes).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.grid),
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.bar),
                    )
                }
            }
        }
    }
}
