package com.aasa.eldercare.ui.trustedcircle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.data.entity.TrustedContactEntity
import com.aasa.eldercare.data.repository.TrustedContactRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives [TrustedCircleScreen]. Reads contacts from
 * [TrustedContactRepository] and produces transient "Call prepared
 * for X" / "Alert prepared for X" confirmation messages.
 *
 * Phase 5 stops at preparing — no dialer, no SMS, no permissions.
 */
class TrustedCircleViewModel(
    contactRepository: TrustedContactRepository
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val preparedMessage =
        MutableStateFlow<TrustedCirclePreparedMessage?>(null)

    private val contactsFlow = contactRepository.getAllContacts()
        .catch { error ->
            errorMessage.value = error.toReadableMessage()
            emit(emptyList())
        }
        .onEach {
            isRefreshing.value = false
        }

    val uiState: StateFlow<TrustedCircleUiState> = combine(
        contactsFlow,
        isRefreshing,
        errorMessage,
        preparedMessage
    ) { contacts, refreshing, error, prepared ->
        TrustedCircleUiState(
            isLoading = refreshing,
            contacts = contacts,
            preparedMessage = prepared,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = TrustedCircleUiState(isLoading = true)
    )

    fun refresh() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        errorMessage.value = null
        viewModelScope.launch {
            delay(SPINNER_VISIBLE_MILLIS)
            isRefreshing.value = false
        }
    }

    fun onPrepareCall(contact: TrustedContactEntity) {
        publishPreparedMessage("Call prepared for ${contact.name}.")
    }

    fun onPrepareAlert(contact: TrustedContactEntity) {
        publishPreparedMessage("Alert prepared for ${contact.name}.")
    }

    fun clearPreparedMessage() {
        preparedMessage.value = null
    }

    fun clearError() {
        errorMessage.value = null
    }

    private fun publishPreparedMessage(text: String) {
        preparedMessage.value = TrustedCirclePreparedMessage(
            id = System.currentTimeMillis(),
            text = text
        )
    }

    private fun Throwable.toReadableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    class Factory(
        private val application: AasaApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TrustedCircleViewModel::class.java)) {
                "Unknown ViewModel class: $modelClass"
            }
            return TrustedCircleViewModel(
                contactRepository = application.trustedContactRepository
            ) as T
        }
    }

    companion object {
        private const val SPINNER_VISIBLE_MILLIS = 300L
    }
}
