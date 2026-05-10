package com.aasa.eldercare.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Long-lived facts about the elder's life that the agent can recall
 * later (favorite music, important dates, family relationships, etc.).
 * [type] is a coarse category like "FAVORITE_MUSIC", "BIRTHDAY", "NOTE".
 */
@Entity(
    tableName = "memories",
    indices = [Index("type"), Index("createdAt")]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val value: String,
    val createdAt: Long = System.currentTimeMillis()
)
