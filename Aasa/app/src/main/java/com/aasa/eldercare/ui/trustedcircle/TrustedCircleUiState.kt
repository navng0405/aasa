package com.aasa.eldercare.ui.trustedcircle

import com.aasa.eldercare.data.entity.TrustedContactEntity

/**
 * One-shot message produced when the elder taps "Prepare Call" or
 * "Prepare Alert". Intentionally a string rather than an enum / sealed
 * hierarchy so the UI can render it directly.
 *
 * Phase 5 stays read-only: no real dialer, no SMS. We just confirm
 * that an action *would* have been prepared.
 */
data class TrustedCirclePreparedMessage(
    val id: Long,
    val text: String
)

data class TrustedCircleUiState(
    val isLoading: Boolean = false,
    val contacts: List<TrustedContactEntity> = emptyList(),
    val preparedMessage: TrustedCirclePreparedMessage? = null,
    val errorMessage: String? = null
) {
    val isEmpty: Boolean get() = !isLoading && contacts.isEmpty()
}
