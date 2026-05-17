package com.aasa.eldercare.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Daily "I'm here" heartbeat taps from the Home screen.
 */
@Entity(
    tableName = "presence_pings",
    indices = [Index("createdAt")]
)
data class PresencePingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)
