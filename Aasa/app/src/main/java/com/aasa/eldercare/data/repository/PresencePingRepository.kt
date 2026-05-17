package com.aasa.eldercare.data.repository

import com.aasa.eldercare.data.dao.PresencePingDao
import com.aasa.eldercare.data.entity.PresencePingEntity
import java.util.Calendar
import kotlin.math.roundToInt

class PresencePingRepository(
    private val dao: PresencePingDao
) {

    suspend fun logPing(nowMs: Long = System.currentTimeMillis()): Long =
        dao.insertPing(PresencePingEntity(createdAt = nowMs))

    suspend fun hasPingInWindow(startMs: Long, endMs: Long): Boolean =
        dao.hasPingBetween(startMs, endMs)

    suspend fun learnedMorningWindow(nowMs: Long = System.currentTimeMillis()): MorningWindow {
        val samples = dao.getRecentPings(limit = LEARNING_SAMPLE_LIMIT)
            .map { it.createdAt }
            .filter { isMorningSample(it) }
        if (samples.size < MIN_SAMPLES_FOR_LEARNING) {
            return MorningWindow(DEFAULT_START_HOUR_24, DEFAULT_END_HOUR_24)
        }
        val averageHour = samples.map { hourOfDay(it) }.average()
        val center = averageHour.roundToInt().coerceIn(6, 11)
        val start = (center - LEARNED_HALF_WINDOW_HOURS).coerceAtLeast(5)
        val end = (center + LEARNED_HALF_WINDOW_HOURS).coerceAtMost(13)
        return MorningWindow(startHour24 = start, endHour24 = end)
    }

    fun dayStart(nowMs: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun isMorningSample(epochMs: Long): Boolean {
        val hour = hourOfDay(epochMs)
        return hour in MORNING_SAMPLE_START_HOUR..MORNING_SAMPLE_END_HOUR
    }

    private fun hourOfDay(epochMs: Long): Int =
        Calendar.getInstance().apply { timeInMillis = epochMs }.get(Calendar.HOUR_OF_DAY)

    data class MorningWindow(
        val startHour24: Int,
        val endHour24: Int
    ) {
        fun startMsForDay(dayStartMs: Long): Long =
            dayStartMs + startHour24 * HOUR_MS

        fun endMsForDay(dayStartMs: Long): Long =
            dayStartMs + endHour24 * HOUR_MS
    }

    companion object {
        private const val LEARNING_SAMPLE_LIMIT = 30
        private const val MIN_SAMPLES_FOR_LEARNING = 3
        private const val MORNING_SAMPLE_START_HOUR = 5
        private const val MORNING_SAMPLE_END_HOUR = 12
        private const val DEFAULT_START_HOUR_24 = 8
        private const val DEFAULT_END_HOUR_24 = 11
        private const val LEARNED_HALF_WINDOW_HOURS = 1
        private const val HOUR_MS = 60L * 60L * 1000L
    }
}
