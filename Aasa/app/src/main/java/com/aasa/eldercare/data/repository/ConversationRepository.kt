package com.aasa.eldercare.data.repository

import com.aasa.eldercare.data.dao.ConversationDao
import com.aasa.eldercare.data.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

class ConversationRepository(
    private val dao: ConversationDao
) {

    fun getRecent(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<ConversationEntity>> =
        dao.getRecentConversations(limit)

    suspend fun getRecentSnapshot(limit: Int = DEFAULT_RECENT_LIMIT): List<ConversationEntity> =
        dao.getRecentConversationSnapshot(limit)

    suspend fun saveUserMessage(message: String): Long =
        dao.insertConversation(
            ConversationEntity(
                role = ConversationEntity.ROLE_USER,
                message = message
            )
        )

    suspend fun saveAssistantMessage(
        message: String,
        intent: String?,
        riskLevel: String?,
        tool: String?
    ): Long = dao.insertConversation(
        ConversationEntity(
            role = ConversationEntity.ROLE_ASSISTANT,
            message = message,
            intent = intent,
            riskLevel = riskLevel,
            tool = tool
        )
    )

    suspend fun hasUserMessageInWindow(startMs: Long, endMs: Long): Boolean =
        dao.hasConversationInWindow(
            role = ConversationEntity.ROLE_USER,
            startMs = startMs,
            endMs = endMs
        )

    companion object {
        private const val DEFAULT_RECENT_LIMIT = 20
    }
}
