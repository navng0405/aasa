package com.aasa.eldercare.ui.memory

import com.aasa.eldercare.data.entity.MemoryEntity

data class MemoryUiState(
    val isLoading: Boolean = false,
    val memories: List<MemoryEntity> = emptyList(),
    val errorMessage: String? = null
) {
    val isEmpty: Boolean get() = !isLoading && memories.isEmpty()
}
