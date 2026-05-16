package com.aasa.eldercare.data.repository

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Phase 10 — read-only Health Connect snapshot for the Morning
 * Briefing feature.
 *
 * Reads the last 24 hours of sleep, heart rate, and steps from
 * Android Health Connect. Health Connect itself is wearable-agnostic:
 * Fitbit Air, Pixel Watch, Galaxy Watch, etc. all write into the same
 * on-device store, so this repository works for any of them once the
 * wearable's companion app is installed.
 *
 * The repository **never throws** for the "no wearable / no
 * permissions / Health Connect not installed" case. Instead it
 * returns a [HealthSnapshot] with [HealthSnapshot.isMockData] = true
 * and the UI / tool surface a "Demo data — no wearable connected"
 * pill so reviewers can see the flow end-to-end without a Fitbit Air
 * physically present.
 *
 * Privacy:
 *  - All access is read-only and stays on-device.
 *  - The data is summarized into ~3 numbers per turn (hours slept,
 *    avg resting HR, steps) and passed to the on-device Gemma 4
 *    runner. Nothing leaves the phone.
 */
class HealthSnapshotRepository(
    private val context: Context
) {

    /** Permission set we ask Health Connect for. */
    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    /**
     * Returns the last-24-hours [HealthSnapshot]. Falls back to a
     * mock snapshot whenever Health Connect is unavailable, no
     * permissions have been granted, or no wearable data exists yet.
     */
    suspend fun fetchLast24h(): HealthSnapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return mockSnapshot(
            reason = "Android < 8 — Health Connect not supported on this device."
        )

        val status = try {
            HealthConnectClient.getSdkStatus(context)
        } catch (_: Throwable) {
            return mockSnapshot(reason = "Health Connect not available on this device.")
        }
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return mockSnapshot(reason = "Health Connect provider not installed.")
        }

        val client = try {
            HealthConnectClient.getOrCreate(context)
        } catch (_: Throwable) {
            return mockSnapshot(reason = "Health Connect client could not be created.")
        }

        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (_: Throwable) {
            emptySet()
        }
        if (!granted.containsAll(requiredPermissions)) {
            return mockSnapshot(reason = "Health Connect permissions not yet granted.")
        }

        val now = Instant.now()
        val start = now.minus(24, ChronoUnit.HOURS)
        val range = TimeRangeFilter.between(start, now)

        return try {
            val sleepHours = readSleepHours(client, range)
            val avgRestingHr = readAverageHeartRate(client, range)
            val steps = readSteps(client, range)

            if (sleepHours == null && avgRestingHr == null && steps == null) {
                // Permissions granted but no wearable has actually
                // written anything yet. Surface as demo so the UI
                // still has something to render.
                mockSnapshot(reason = "No wearable data found in the last 24 hours.")
            } else {
                HealthSnapshot(
                    sleepHours = sleepHours,
                    avgRestingHeartRateBpm = avgRestingHr,
                    steps = steps,
                    capturedAtEpochMs = now.toEpochMilli(),
                    isMockData = false,
                    source = "Health Connect"
                )
            }
        } catch (t: Throwable) {
            mockSnapshot(reason = "Health Connect read failed: ${t.message}")
        }
    }

    private suspend fun readSleepHours(
        client: HealthConnectClient,
        range: TimeRangeFilter
    ): Double? {
        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = range
            )
        ).records
        if (sessions.isEmpty()) return null
        val totalMs = sessions.sumOf {
            it.endTime.toEpochMilli() - it.startTime.toEpochMilli()
        }.coerceAtLeast(0L)
        return totalMs / (1000.0 * 60.0 * 60.0)
    }

    private suspend fun readAverageHeartRate(
        client: HealthConnectClient,
        range: TimeRangeFilter
    ): Int? {
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = range
            )
        ).records
        if (records.isEmpty()) return null
        val samples = records.flatMap { it.samples }
        if (samples.isEmpty()) return null
        return samples.map { it.beatsPerMinute }.average().toInt()
    }

    private suspend fun readSteps(
        client: HealthConnectClient,
        range: TimeRangeFilter
    ): Int? {
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = range
            )
        ).records
        if (records.isEmpty()) return null
        return records.sumOf { it.count.toInt() }
    }

    private fun mockSnapshot(reason: String): HealthSnapshot {
        // Deterministic, friendly demo numbers — slightly low sleep so
        // the briefing has something gentle to talk about.
        return HealthSnapshot(
            sleepHours = 6.2,
            avgRestingHeartRateBpm = 72,
            steps = 2100,
            capturedAtEpochMs = System.currentTimeMillis(),
            isMockData = true,
            source = "Demo data ($reason)"
        )
    }
}

/**
 * Last-24-hour wearable snapshot used by HealthBriefingTool.
 *
 * Any field may be `null` if the wearable didn't report it. UI / tool
 * must handle that gracefully.
 */
data class HealthSnapshot(
    val sleepHours: Double?,
    val avgRestingHeartRateBpm: Int?,
    val steps: Int?,
    val capturedAtEpochMs: Long,
    /** True when no wearable was available and we returned synthetic numbers. */
    val isMockData: Boolean,
    val source: String
)
