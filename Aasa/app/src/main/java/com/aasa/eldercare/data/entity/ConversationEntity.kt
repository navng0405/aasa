package com.aasa.eldercare.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single turn in the elder's chat with the agent.
 *
 * - [role] = "USER" for what the elder said, "ASSISTANT" for what
 *   Gemma replied with after the safety override + tool routing.
 * - [intent], [riskLevel], [tool] are only populated for ASSISTANT
 *   rows and reflect the post-override decision actually executed.
 */
@Entity(
    tableName = "conversations",
    indices = [Index("createdAt"), Index("role")]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val message: String,
    val intent: String? = null,
    val riskLevel: String? = null,
    val tool: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_USER = "USER"
        const val ROLE_ASSISTANT = "ASSISTANT"
    }
}
