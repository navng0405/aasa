package com.aasa.eldercare.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aasa.eldercare.data.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentConversations(limit: Int): Flow<List<ConversationEntity>>
}
