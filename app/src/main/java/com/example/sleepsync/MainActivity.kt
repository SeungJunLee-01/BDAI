package com.example.sleepsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.draw.rotate
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
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
    var showGoalSheet by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var addSheetDate by remember { mutableStateOf<LocalDate?>(null) }
    var addAfterGrant by remember { mutableStateOf<LocalDate?>(null) }
    var saving by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SleepSession?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val syncStatusStore = remember { SyncStatusStore(context) }
    var syncStatus by remember { mutableStateOf(syncStatusStore.load()) }
    var syncing by remember { mutableStateOf(false) }
    val tutorialStore = remember { TutorialStore(context) }
    var showTutorial by remember { mutableStateOf(!tutorialStore.isDone()) }

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
        if (refreshKey == 0 || showTutorial) return@LaunchedEffect
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
        syncing = true
        val startedAt = System.currentTimeMillis()
        try {
            sessions = withContext(Dispatchers.IO) { archive.sync(client, READ_HISTORY in granted) }
            syncStatusStore.recordSuccess()
            loadState = LoadState.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncStatusStore.recordFailure()
            if (sessions.isEmpty()) {
                loadState = LoadState.Failed
            } else {
                loadState = LoadState.Ready
            }
        } finally {
            // Keep the spinner up briefly so a quick refresh is still visible as "it did something".
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < MIN_SPIN_MILLIS) withContext(NonCancellable) { delay(MIN_SPIN_MILLIS - elapsed) }
            syncStatus = syncStatusStore.load()
            syncing = false
        }
    }

    LaunchedEffect(sessions, allNighters, targetMinutes, wakeTimes, syncStatus) {
        withContext(Dispatchers.IO) { refreshWidget(context) }
    }

    if (showTutorial) {
        TutorialScreen(onFinish = {
            tutorialStore.markDone()
            showTutorial = false
            refreshKey++
        })
        return
    }

    // The calendar screen (SleepCalendar.kt) is kept but not shown for now; all sessions stay archived.
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("수면부채 계산", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { if (!syncing) refreshKey++ }) {
                        RefreshIcon(spinning = syncing)
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
                // Day details stay hidden until a bar is tapped; tapping the same bar again hides them.
                val selectedIndex = days.indexOfFirst { it.date == selectedDate }.takeIf { it >= 0 }

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
                    SyncStatusLine(status = syncStatus, syncing = syncing)
                    SleepDebtCard(debt = debt)
                    SectionCard {
                        SleepChart(
                            days = days,
                            targetMinutes = targetMinutes,
                            selectedIndex = selectedIndex,
                            onSelect = { index ->
                                val tapped = days[index].date
                                selectedDate = if (tapped == selectedDate) null else tapped
                            },
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                    if (selectedIndex != null) {
                        val day = days[selectedIndex]
                        DayDetailCard(
                            day = day,
                            sessions = sessions.filter { sleepDate(it) == day.date }.sortedBy { it.start },
                            targetMinutes = targetMinutes,
                            ownPackage = context.packageName,
                            onAdd = { openAddSheet(day.date) },
                            onDelete = { pendingDelete = it },
                            onDeleteAllNighter = { deleteAllNighter(day.date) },
                        )
                    }
                    TonightPlanCard(
                        plan = wakeTimes?.let { planTonight(it, bedtimeStats, debt.totalMinutes, targetMinutes) },
                        stats = bedtimeStats,
                        targetMinutes = targetMinutes,
                        debtMinutes = debt.totalMinutes,
                        onOpenSettings = { showGoalSheet = true },
                    )
                    Spacer(modifier = Modifier.height(80.dp))
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

private const val MIN_SPIN_MILLIS = 700L

@Composable
private fun RefreshIcon(spinning: Boolean) {
    val rotation = if (spinning) {
        rememberInfiniteTransition(label = "refresh").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
            label = "refreshRotation",
        ).value
    } else {
        0f
    }
    Icon(
        Icons.Default.Refresh,
        contentDescription = if (spinning) "새 기록을 불러오는 중" else "새로고침",
        modifier = Modifier.rotate(rotation),
    )
}

// "방금 업데이트" / "12분 전 업데이트", or a warning when the last attempt to read new records failed.
@Composable
private fun SyncStatusLine(status: SyncStatus, syncing: Boolean) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    val success = status.lastSuccess
    val text = when {
        syncing -> "새 기록을 불러오는 중…"
        status.lastAttemptFailed && success != null ->
            "새 기록을 불러오지 못했어요 · 마지막 업데이트 ${syncTimeLabel(success, now)}"
        status.lastAttemptFailed -> "새 기록을 불러오지 못했어요"
        success != null -> "${syncTimeLabel(success, now)} 업데이트"
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (!syncing && status.lastAttemptFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
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
