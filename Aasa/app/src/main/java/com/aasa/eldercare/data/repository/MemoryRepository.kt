package com.aasa.eldercare.data.repository

import com.aasa.eldercare.data.dao.MemoryDao
import com.aasa.eldercare.data.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

class MemoryRepository(
    private val dao: MemoryDao
) {

    fun getAllMemories(): Flow<List<MemoryEntity>> = dao.getAllMemories()

    suspend fun findByType(type: String): List<MemoryEntity> =
        dao.findMemoriesByType(type)

    suspend fun saveMemory(
        type: String,
        title: String,
        value: String
    ): MemoryEntity {
        val entity = MemoryEntity(type = type, title = title, value = value)
        val id = dao.insertMemory(entity)
        return entity.copy(id = id)
    }
}
