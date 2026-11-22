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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        scheduleBackgroundSync(this)
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
    val requested = remember(client) { requestedPermissions(client) }
    val archive = remember { SleepArchive(context) }
    val allNighterStore = remember { AllNighterStore(context) }
    val goalStore = remember { SleepGoalStore(context) }

    var sessions by remember { mutableStateOf(archive.sessionsBlocking()) }
    var allNighters by remember { mutableStateOf(allNighterStore.load()) }
    var targetMinutes by remember { mutableLongStateOf(goalStore.load()) }
    var wakeTimes by remember { mutableStateOf(goalStore.loadWakeTimes()) }
    var loadState by remember { mutableStateOf(if (sessions.isEmpty()) LoadState.Loading else LoadState.Ready) }
    var canWrite by remember { mutableStateOf(false) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    var showGoalSheet by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var addSheetDate by remember { mutableStateOf<LocalDate?>(null) }
    var addAfterGrant by remember { mutableStateOf<LocalDate?>(null) }
    var saving by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SleepSession?>(null) }
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
            permissionLauncher.launch(requested)
        }
    }

    fun deleteAllNighter(date: LocalDate) {
        allNighterStore.remove(date)
        allNighters = allNighterStore.load()
        scope.launch {
            val result = snackbarHostState.showSnackbar("밤샘 기록을 지웠어요", actionLabel = "되돌리기")
            if (result == SnackbarResult.ActionPerformed) {
                allNighterStore.add(date)
                allNighters = allNighterStore.load()
            }
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
        if (!granted.containsAll(requested) && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(requested)
        }
        if (READ_SLEEP !in granted) {
            loadState = LoadState.NeedsPermission
            return@LaunchedEffect
        }
        try {
            sessions = withContext(Dispatchers.IO) { archive.sync(client, READ_HISTORY in granted) }
            loadState = LoadState.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (sessions.isEmpty()) {
                loadState = LoadState.Failed
            } else {
                loadState = LoadState.Ready
                snackbarHostState.showSnackbar("새 기록을 불러오지 못했어요. 보관된 기록을 보여드려요.")
            }
        }
    }

    LaunchedEffect(sessions, allNighters, targetMinutes, wakeTimes) {
        withContext(Dispatchers.IO) { refreshWidget(context) }
    }

    if (showCalendar && loadState == LoadState.Ready) {
        SleepCalendarScreen(
            sessions = sessions,
            allNighters = allNighters,
            targetMinutes = targetMinutes,
            ownPackage = context.packageName,
            snackbarHostState = snackbarHostState,
            onBack = { showCalendar = false },
            onAdd = ::openAddSheet,
            onDelete = { pendingDelete = it },
            onDeleteAllNighter = ::deleteAllNighter,
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("수면 기록", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        if (loadState == LoadState.Ready) {
                            IconButton(onClick = { showCalendar = true }) {
                                Icon(Icons.Default.DateRange, contentDescription = "수면 캘린더")
                            }
                        }
                        IconButton(onClick = { refreshKey++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                        }
                        IconButton(onClick = { showGoalSheet = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "수면 목표 설정")
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
                    onAction = { permissionLauncher.launch(requested) },
                )
                LoadState.Failed -> MessageCard(
                    padding,
                    title = "수면 기록을 불러오지 못했어요",
                    body = "잠시 후 다시 시도해주세요.",
                    actionLabel = "다시 시도",
                    onAction = { refreshKey++ },
                )
                LoadState.Ready -> {
                    val days = remember(sessions, allNighters) { dailySleep(lastDays(), sessions, allNighters) }
                    val selected = selectedDate?.takeIf { date -> days.any { it.date == date } }
                        ?: days.lastOrNull { it.minutes != null }?.date
                        ?: days.last().date
                    val selectedIndex = days.indexOfFirst { it.date == selected }
                    val daySessions = sessions.filter { sleepDate(it) == selected }.sortedBy { it.start }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val debt = remember(days, targetMinutes) { calculateSleepDebt(days, targetMinutes) }
                        val bedtimeStats = remember(sessions) { analyzeBedtime(recentMainSleeps(sessions)) }
                        SleepDebtCard(debt = debt)
                        TonightPlanCard(
                            plan = wakeTimes?.let { planTonight(it, bedtimeStats, debt.totalMinutes, targetMinutes) },
                            stats = bedtimeStats,
                            targetMinutes = targetMinutes,
                            debtMinutes = debt.totalMinutes,
                            onOpenSettings = { showGoalSheet = true },
                        )
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
                            sessions = daySessions,
                            targetMinutes = targetMinutes,
                            ownPackage = context.packageName,
                            onAdd = { openAddSheet(selected) },
                            onDelete = { pendingDelete = it },
                            onDeleteAllNighter = { deleteAllNighter(selected) },
                        )
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }

    addSheetDate?.let { date ->
        AddSleepSheet(
            initialWakeDate = date,
            existingSessions = sessions,
            allNighters = allNighters,
            targetMinutes = targetMinutes,
            saving = saving,
            onDismiss = { if (!saving) addSheetDate = null },
            onSaveAllNighter = { night ->
                allNighterStore.add(night)
                allNighters = allNighterStore.load()
                addSheetDate = null
                selectedDate = night
                scope.launch { snackbarHostState.showSnackbar("밤샘으로 기록했어요") }
            },
            onSaveSleep = { start, end ->
                val hc = client ?: return@AddSleepSheet
                scope.launch {
                    saving = true
                    val result = runCatching {
                        val id = insertManualSleep(hc, start, end)
                        archive.add(SleepSession(id, start.toInstant(), end.toInstant(), context.packageName, 0))
                    }
                    saving = false
                    result.onSuccess {
                        sessions = it
                        addSheetDate = null
                        selectedDate = end.toLocalDate()
                        snackbarHostState.showSnackbar("수면 기록을 추가했어요")
                    }.onFailure {
                        snackbarHostState.showSnackbar("저장하지 못했어요. 다시 시도해주세요.")
                    }
                }
            },
        )
    }

    if (showGoalSheet) {
        SleepGoalSheet(
            currentMinutes = targetMinutes,
            currentWakeTimes = wakeTimes,
            onDismiss = { showGoalSheet = false },
            onSave = { minutes, wakes ->
                goalStore.save(minutes, wakes)
                targetMinutes = minutes
                wakeTimes = wakes
                showGoalSheet = false
                scope.launch { snackbarHostState.showSnackbar("수면 목표를 저장했어요") }
            },
        )
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("이 기록을 삭제할까요?") },
            text = { Text("${sessionTimeRange(session)} · ${formatDuration(sessionMinutes(session))}") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    val hc = client ?: return@TextButton
                    scope.launch {
                        val result = runCatching {
                            hc.deleteRecords(SleepSessionRecord::class, listOf(session.id), emptyList())
                            archive.remove(session.id)
                        }
                        result.onSuccess { sessions = it }
                        snackbarHostState.showSnackbar(if (result.isSuccess) "기록을 삭제했어요" else "삭제하지 못했어요")
                    }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } },
        )
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

private suspend fun insertManualSleep(client: HealthConnectClient, start: ZonedDateTime, end: ZonedDateTime): String =
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
    ).recordIdsList.first()
