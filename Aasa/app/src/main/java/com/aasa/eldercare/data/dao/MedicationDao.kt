package com.aasa.eldercare.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aasa.eldercare.data.entity.MedicationEntity
import com.aasa.eldercare.data.entity.MedicationLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * Lightweight Room access for the Medication / MedicationLog tables.
 *
 * The tool layer should reach this through [com.aasa.eldercare.data.repository.MedicationRepository]
 * – the DAO is intentionally narrow.
 */
@Dao
interface MedicationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMedication(medication: MedicationEntity): Long

    @Query("SELECT * FROM medications WHERE nameLower = :nameLower LIMIT 1")
    suspend fun findMedicationByName(nameLower: String): MedicationEntity?

    @Query("SELECT * FROM medications ORDER BY createdAt ASC")
    fun getAllMedications(): Flow<List<MedicationEntity>>

    @Insert
    suspend fun insertMedicationLog(log: MedicationLogEntity): Long

    @Query(
        """
        SELECT * FROM medication_logs
        WHERE loggedAt >= :startInclusive AND loggedAt < :endExclusive
        ORDER BY loggedAt DESC
        """
    )
    suspend fun getTodayLogs(
        startInclusive: Long,
        endExclusive: Long
    ): List<MedicationLogEntity>

    @Query(
        """
        SELECT * FROM medication_logs
        WHERE medicationId = :medicationId
        ORDER BY loggedAt DESC
        LIMIT 1
        """
    )
    suspend fun getLatestLogForMedication(medicationId: Long): MedicationLogEntity?

    /**
     * JOIN helper used by `CHECK_MEDICATION` so the tool can render a
     * readable summary like `"BP tablet -> taken"`.
     */
    @Query(
        """
        SELECT m.name AS medicationName,
               l.status AS status,
               l.loggedAt AS loggedAt
        FROM medication_logs l
        INNER JOIN medications m ON m.id = l.medicationId
        WHERE l.loggedAt >= :startInclusive AND l.loggedAt < :endExclusive
        ORDER BY l.loggedAt DESC
        """
    )
    suspend fun getTodayLogsWithNames(
        startInclusive: Long,
        endExclusive: Long
    ): List<TodayMedicationStatus>
}

/**
 * Row shape for [MedicationDao.getTodayLogsWithNames]. Kept in this file
 * because it's only ever produced by that one query.
 */
data class TodayMedicationStatus(
    val medicationName: String,
    val status: String,
    val loggedAt: Long
)
