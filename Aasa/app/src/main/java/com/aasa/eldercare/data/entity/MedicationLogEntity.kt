package com.aasa.eldercare.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One "I took / skipped this medicine" event. Linked to [MedicationEntity]
 * by [medicationId]; rows are deleted along with their parent medication
 * via [ForeignKey.CASCADE].
 */
@Entity(
    tableName = "medication_logs",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("medicationId"), Index("loggedAt")]
)
data class MedicationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val status: String,
    val loggedAt: Long = System.currentTimeMillis()
)
