package com.aasa.eldercare.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One medication the elder is supposed to take. Identified by [name]
 * (case-insensitive, indexed) so the agent can find or create rows from
 * a Gemma-extracted argument like "Metformin".
 */
@Entity(
    tableName = "medications",
    indices = [Index(value = ["nameLower"], unique = true)]
)
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dosage: String? = null,
    val scheduleTime: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Mirrors [name] in lowercase so we can build a unique index Room
     * actually checks (SQLite `LOWER()` in indexes is fragile; storing
     * the canonical form is simpler and survives migrations).
     */
    @ColumnInfo(name = "nameLower") val nameLower: String = name.lowercase()
)
