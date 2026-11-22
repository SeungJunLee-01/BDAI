package com.example.sleepsync

import android.content.Context
import android.util.AtomicFile
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant

data class SleepSession(
    val id: String,
    val start: Instant,
    val end: Instant,
    val source: String,
    val awakeMinutes: Long,
)

fun SleepSessionRecord.toSession() = SleepSession(
    id = metadata.id,
    start = startTime,
    end = endTime,
    source = metadata.dataOrigin.packageName,
    awakeMinutes = stages
        .filter { it.stage in AWAKE_STAGES }
        .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() },
)

private val AWAKE_STAGES = setOf(
    SleepSessionRecord.STAGE_TYPE_AWAKE,
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
)

private const val BACKFILL_DAYS = 730L
// Recent window re-read on every sync so edits and deletions in Health Connect are mirrored.
private const val RESYNC_DAYS = 14L

// Process-wide so the app screen and the background worker can't overwrite each other's writes.
private val archiveMutex = Mutex()

class SleepArchive(context: Context) {
    private val mutex = archiveMutex
    private val file = AtomicFile(File(context.filesDir, "sleep_archive.json"))

    // backfillLevel: 0 = never backfilled, 1 = backfilled without history permission, 2 = with it.
    private data class State(val sessions: List<SleepSession>, val backfillLevel: Int)

    fun sessionsBlocking(): List<SleepSession> = read().sessions

    suspend fun add(session: SleepSession): List<SleepSession> = mutex.withLock {
        val state = read()
        val sessions = (state.sessions.filter { it.id != session.id } + session).sortedBy { it.start }
        write(state.copy(sessions = sessions))
        sessions
    }

    suspend fun remove(id: String): List<SleepSession> = mutex.withLock {
        val state = read()
        val sessions = state.sessions.filter { it.id != id }
        write(state.copy(sessions = sessions))
        sessions
    }

    // Older archived sessions are never dropped, even if Health Connect no longer returns them.
    suspend fun sync(client: HealthConnectClient, hasHistoryPermission: Boolean): List<SleepSession> = mutex.withLock {
        val state = read()
        val now = Instant.now()
        val windowStart = now.minus(Duration.ofDays(RESYNC_DAYS))
        val neededBackfill = if (hasHistoryPermission) 2 else 1
        val backfilling = state.backfillLevel < neededBackfill
        val fetchFrom = if (backfilling) now.minus(Duration.ofDays(BACKFILL_DAYS)) else windowStart

        val fetched = readAll(client, fetchFrom, now).map { it.toSession() }
        val merged = state.sessions
            .filter { it.start < windowStart }
            .associateBy { it.id }
            .toMutableMap()
        fetched.forEach { merged[it.id] = it }

        val sessions = merged.values.sortedBy { it.start }
        write(State(sessions, maxOf(state.backfillLevel, neededBackfill)))
        sessions
    }

    private suspend fun readAll(client: HealthConnectClient, from: Instant, to: Instant): List<SleepSessionRecord> {
        val records = mutableListOf<SleepSessionRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = 1000,
                    pageToken = pageToken,
                )
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    private fun read(): State {
        if (!file.baseFile.exists()) return State(emptyList(), 0)
        val json = JSONObject(String(file.readFully(), Charsets.UTF_8))
        val array = json.getJSONArray("sessions")
        val sessions = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SleepSession(
                id = o.getString("id"),
                start = Instant.ofEpochMilli(o.getLong("start")),
                end = Instant.ofEpochMilli(o.getLong("end")),
                source = o.getString("source"),
                awakeMinutes = o.optLong("awake", 0),
            )
        }
        return State(sessions, json.optInt("backfill", 0))
    }

    private fun write(state: State) {
        val array = JSONArray()
        state.sessions.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("start", s.start.toEpochMilli())
                    .put("end", s.end.toEpochMilli())
                    .put("source", s.source)
                    .put("awake", s.awakeMinutes)
            )
        }
        val json = JSONObject().put("backfill", state.backfillLevel).put("sessions", array)
        val out = file.startWrite()
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}
