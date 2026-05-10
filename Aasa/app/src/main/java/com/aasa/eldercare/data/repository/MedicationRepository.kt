package com.aasa.eldercare.data.repository

import com.aasa.eldercare.data.dao.MedicationDao
import com.aasa.eldercare.data.dao.TodayMedicationStatus
import com.aasa.eldercare.data.entity.MedicationEntity
import com.aasa.eldercare.data.entity.MedicationLogEntity
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

/**
 * High-level operations on top of [MedicationDao]. The tools layer never
 * touches Room directly; everything goes through this repository so the
 * persistence story is testable without instrumenting Android.
 */
class MedicationRepository(
    private val dao: MedicationDao
) {

    fun getAllMedications(): Flow<List<MedicationEntity>> = dao.getAllMedications()

    suspend fun findByName(name: String): MedicationEntity? =
        dao.findMedicationByName(name.lowercase())

    /**
     * Resolve a medication by [name], creating a row when this is the
     * first time we've heard of it. Always returns a row whose [id] is
     * valid for use as a foreign key.
     */
    suspend fun findOrCreate(name: String): MedicationEntity {
        val canonical = name.trim()
        dao.findMedicationByName(canonical.lowercase())?.let { return it }

        val newEntity = MedicationEntity(name = canonical)
        val newId = dao.insertMedication(newEntity)
        // `insertMedication` is `IGNORE` on conflict; if a parallel
        // insert won the race, fall back to the row that exists.
        return if (newId != -1L) {
            newEntity.copy(id = newId)
        } else {
            dao.findMedicationByName(canonical.lowercase())
                ?: error("Medication '$canonical' could neither be created nor found")
        }
    }

    /**
     * Persist a "the elder took / skipped" event for [medicineName]. The
     * medication row is auto-created if missing.
     */
    suspend fun logMedication(
        medicineName: String,
        status: String
    ): LoggedMedication {
        val medication = findOrCreate(medicineName)
        val logId = dao.insertMedicationLog(
            MedicationLogEntity(medicationId = medication.id, status = status)
        )
        return LoggedMedication(
            medication = medication,
            logId = logId,
            status = status
        )
    }

    suspend fun getTodayLogs(): List<MedicationLogEntity> {
        val (start, end) = todayBounds()
        return dao.getTodayLogs(start, end)
    }

    suspend fun getTodayLogsWithNames(): List<TodayMedicationStatus> {
        val (start, end) = todayBounds()
        return dao.getTodayLogsWithNames(start, end)
    }

    suspend fun getLatestLogForMedication(medicationId: Long): MedicationLogEntity? =
        dao.getLatestLogForMedication(medicationId)

    private fun todayBounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.timeInMillis
        return start to end
    }

    /** Result of [logMedication]; convenient for tools that want both ids. */
    data class LoggedMedication(
        val medication: MedicationEntity,
        val logId: Long,
        val status: String
    )
}
