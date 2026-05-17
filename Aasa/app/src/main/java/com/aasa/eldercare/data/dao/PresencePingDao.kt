package com.aasa.eldercare.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aasa.eldercare.data.entity.PresencePingEntity

@Dao
interface PresencePingDao {

    @Insert
    suspend fun insertPing(ping: PresencePingEntity): Long

    @Query("SELECT * FROM presence_pings ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentPings(limit: Int): List<PresencePingEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM presence_pings WHERE createdAt >= :startMs AND createdAt < :endMs)")
    suspend fun hasPingBetween(startMs: Long, endMs: Long): Boolean
}
