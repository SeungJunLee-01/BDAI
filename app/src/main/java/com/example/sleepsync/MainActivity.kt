package com.example.sleepsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val READ_SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
private val WRITE_SLEEP = HealthPermission.getWritePermission(SleepSessionRecord::class)
private val SLEEP_PERMISSIONS = setOf(READ_SLEEP, WRITE_SLEEP)
private const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SleepTheme {
                SleepScreen()
            }
        }
    }
}

private sealed interface LoadState {
    data object Loading : LoadState
    data class Unavailable(val message: String) : LoadState
    data object NeedsPermission : LoadState
    data object Failed : LoadState
    data object Ready : LoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val client = remember { runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() }
    val allNighterStore = remember { AllNighterStore(context) }
    var allNighters by remember { mutableStateOf(allNighterStore.load()) }
    val goalStore = remember { SleepGoalStore(context) }
    var targetMinutes by remember { mutableLongStateOf(goalStore.load()) }
    var showGoalSheet by remember { mutableStateOf(false) }

    var loadState by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var records by remember { mutableStateOf<List<SleepSessionRecord>>(emptyList()) }
    var canWrite by remember { mutableStateOf(false) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var addSheetDate by remember { mutableStateOf<LocalDate?>(null) }
    var addAfterGrant by remember { mutableStateOf<LocalDate?>(null) }
    var saving by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SleepSessionRecord?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val date = addAfterGrant ?: return@rememberLauncherForActivityResult
        addAfterGrant = null
        if (WRITE_SLEEP in granted) {
            addSheetDate = date
        } else {
            scope.launch { snackbarHostState.showSnackbar("수면을 추가하려면 Health Connect 쓰기 권한이 필요해요") }
        }
    }

    fun openAddSheet(date: LocalDate) {
        if (canWrite) {
            addSheetDate = date
        } else {
            addAfterGrant = date
            permissionLauncher.launch(SLEEP_PERMISSIONS)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshKey) {
        if (refreshKey == 0) return@LaunchedEffect
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                loadState = LoadState.Unavailable("이 기기에서는 Health Connect를 사용할 수 없어요.")
                return@LaunchedEffect
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                loadState = LoadState.Unavailable("Health Connect 업데이트가 필요해요. Play 스토어에서 업데이트해주세요.")
                return@LaunchedEffect
            }
        }
        if (client == null) {
            loadState = LoadState.Unavailable("Health Connect에 연결하지 못했어요.")
            return@LaunchedEffect
        }
        val granted = client.permissionController.getGrantedPermissions()
        canWrite = WRITE_SLEEP in granted
        if (!granted.containsAll(SLEEP_PERMISSIONS) && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(SLEEP_PERMISSIONS)
        }
        if (READ_SLEEP !in granted) {
            loadState = LoadState.NeedsPermission
            return@LaunchedEffect
        }
        loadState = runCatching { fetchSleepRecords(client) }
            .onSuccess { records = it }
            .fold(onSuccess = { LoadState.Ready }, onFailure = { LoadState.Failed })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("수면 기록", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                    }
                    IconButton(onClick = { showGoalSheet = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "목표 수면 시간 설정")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (loadState == LoadState.Ready) {
                ExtendedFloatingActionButton(
                    onClick = { openAddSheet(defaultWakeDate()) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("수면 추가") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val state = loadState) {
            LoadState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is LoadState.Unavailable -> MessageCard(padding, title = "Health Connect를 쓸 수 없어요", body = state.message)
            LoadState.NeedsPermission -> MessageCard(
                padding,
                title = "수면 데이터 권한이 필요해요",
                body = "워치로 측정한 수면을 불러오려면 Health Connect에서 수면 권한을 허용해주세요. " +
                    "버튼을 눌러도 창이 뜨지 않으면 Health Connect 앱 → 앱 권한 → 수면 기록에서 허용할 수 있어요.",
                actionLabel = "권한 허용하기",
                onAction = { permissionLauncher.launch(SLEEP_PERMISSIONS) },
            )
            LoadState.Failed -> MessageCard(
                padding,
                title = "수면 기록을 불러오지 못했어요",
                body = "잠시 후 다시 시도해주세요.",
                actionLabel = "다시 시도",
                onAction = { refreshKey++ },
            )
            LoadState.Ready -> {
                val days = remember(records, allNighters) { dailySleepTotals(records, allNighters) }
                val selected = selectedDate?.takeIf { date -> days.any { it.date == date } }
                    ?: days.lastOrNull { it.minutes != null }?.date
                    ?: days.last().date
                val selectedIndex = days.indexOfFirst { it.date == selected }
                val sessions = records.filter { sleepDate(it) == selected }.sortedBy { it.startTime }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SleepDebtCard(debt = remember(days, targetMinutes) { calculateSleepDebt(days, targetMinutes) })
                    SummaryRow(days, targetMinutes)
                    SectionCard {
                        SleepChart(
                            days = days,
                            targetMinutes = targetMinutes,
                            selectedIndex = selectedIndex,
                            onSelect = { selectedDate = days[it].date },
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                    DayDetailCard(
                        day = days[selectedIndex],
                        sessions = sessions,
                        targetMinutes = targetMinutes,
                        ownPackage = context.packageName,
                        onAdd = { openAddSheet(selected) },
                        onDelete = { pendingDelete = it },
                        onDeleteAllNighter = {
                            allNighterStore.remove(selected)
                            allNighters = allNighterStore.load()
                            scope.launch {
                                val result = snackbarHostState.showSnackbar("밤샘 기록을 지웠어요", actionLabel = "되돌리기")
                                if (result == SnackbarResult.ActionPerformed) {
                                    allNighterStore.add(selected)
                                    allNighters = allNighterStore.load()
                                }
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }

    addSheetDate?.let { date ->
        AddSleepSheet(
            initialWakeDate = date,
            existingRecords = records,
            allNighters = allNighters,
            targetMinutes = targetMinutes,
            saving = saving,
            onDismiss = { if (!saving) addSheetDate = null },
            onSaveAllNighter = { date ->
                allNighterStore.add(date)
                allNighters = allNighterStore.load()
                addSheetDate = null
                selectedDate = date
                scope.launch { snackbarHostState.showSnackbar("밤샘으로 기록했어요") }
            },
            onSaveSleep = { start, end ->
                val hc = client ?: return@AddSleepSheet
                scope.launch {
                    saving = true
                    val result = runCatching { insertManualSleep(hc, start, end) }
                    saving = false
                    if (result.isSuccess) {
                        addSheetDate = null
                        selectedDate = end.toLocalDate()
                        refreshKey++
                        snackbarHostState.showSnackbar("수면 기록을 추가했어요")
                    } else {
                        snackbarHostState.showSnackbar("저장하지 못했어요. 다시 시도해주세요.")
                    }
                }
            },
        )
    }

    if (showGoalSheet) {
        SleepGoalSheet(
            currentMinutes = targetMinutes,
            onDismiss = { showGoalSheet = false },
            onSave = { minutes ->
                goalStore.save(minutes)
                targetMinutes = minutes
                showGoalSheet = false
                scope.launch { snackbarHostState.showSnackbar("목표를 ${formatDuration(minutes)}으로 바꿨어요") }
            },
        )
    }

    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("이 기록을 삭제할까요?") },
            text = { Text("${sessionTimeRange(record)} · ${formatDuration(sessionMinutes(record))}") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    val hc = client ?: return@TextButton
                    scope.launch {
                        val result = runCatching {
                            hc.deleteRecords(SleepSessionRecord::class, listOf(record.metadata.id), emptyList())
                        }
                        refreshKey++
                        snackbarHostState.showSnackbar(if (result.isSuccess) "기록을 삭제했어요" else "삭제하지 못했어요")
                    }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        content()
    }
}

@Composable
private fun SummaryRow(days: List<DailySleep>, targetMinutes: Long) {
    val recorded = days.mapNotNull { it.minutes }
    val average = if (recorded.isEmpty()) null else recorded.sum() / recorded.size
    val metGoal = recorded.count { it >= targetMinutes }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            label = "평균 수면",
            value = average?.let(::formatDuration) ?: "–",
            caption = "기록된 ${recorded.size}일 기준",
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "목표 달성",
            value = "${metGoal}일",
            caption = "최근 7일 중",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayDetailCard(
    day: DailySleep,
    sessions: List<SleepSessionRecord>,
    targetMinutes: Long,
    ownPackage: String,
    onAdd: () -> Unit,
    onDelete: (SleepSessionRecord) -> Unit,
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
                sessions.forEach { record ->
                    SessionRow(
                        record = record,
                        isManual = record.metadata.dataOrigin.packageName == ownPackage,
                        onDelete = { onDelete(record) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(record: SleepSessionRecord, isManual: Boolean, onDelete: () -> Unit) {
    EntryRow(
        title = sessionTimeRange(record),
        subtitle = formatDuration(sessionMinutes(record)),
        source = when {
            isManual -> "직접 입력"
            record.metadata.dataOrigin.packageName == SAMSUNG_HEALTH_PACKAGE -> "삼성 헬스"
            else -> "다른 앱"
        },
        isManual = isManual,
        deleteDescription = "직접 입력한 기록 삭제",
        onDelete = onDelete,
    )
}

private val nightFormatter = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)

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

@Composable
private fun MessageCard(
    padding: PaddingValues,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
        SectionCard {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (actionLabel != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

private val sessionTimeFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

private fun sessionTimeRange(record: SleepSessionRecord): String {
    val zone = ZoneId.systemDefault()
    return "${record.startTime.atZone(zone).format(sessionTimeFormatter)} – ${record.endTime.atZone(zone).format(sessionTimeFormatter)}"
}

private suspend fun insertManualSleep(client: HealthConnectClient, start: ZonedDateTime, end: ZonedDateTime) {
    client.insertRecords(
        listOf(
            SleepSessionRecord(
                startTime = start.toInstant(),
                startZoneOffset = start.offset,
                endTime = end.toInstant(),
                endZoneOffset = end.offset,
                metadata = Metadata(recordingMethod = Metadata.RECORDING_METHOD_MANUAL_ENTRY),
            )
        )
    )
}

private suspend fun fetchSleepRecords(client: HealthConnectClient): List<SleepSessionRecord> {
    val end = Instant.now()
    // Start a day early so the oldest chart night, which began the evening before, is included.
    val start = LocalDate.now().minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant()
    val request = ReadRecordsRequest(
        recordType = SleepSessionRecord::class,
        timeRangeFilter = TimeRangeFilter.between(start, end),
    )
    return client.readRecords(request).records
}
