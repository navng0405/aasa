package com.aasa.eldercare.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * High-risk safety action. Shown when [com.aasa.eldercare.tools.SafetyTool]
 * classifies the user message as HIGH. Offers two escalation paths:
 *
 *  - "Open Emergency Dialer" → ACTION_DIAL with 911 prefilled (elder
 *    still confirms).
 *  - "Call <contact>" → ACTION_DIAL with the trusted contact's number.
 *
 * Phase 7 deliberately does **not** auto-dial 911 and does **not**
 * request `CALL_PHONE`.
 */
@Composable
fun EmergencyActionCard(
    contactName: String?,
    contactPhoneNumber: String?,
    emergencyNumber: String?,
    alertMessage: String?,
    onOpenEmergencyDialer: () -> Unit,
    onCallContact: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "High Safety Concern",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = alertMessage?.takeIf { it.isNotBlank() }
                    ?: "Consider contacting emergency help or a trusted contact.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "Aasa will not call anyone automatically. You must confirm in the dialer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Button(
                onClick = onOpenEmergencyDialer,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(
                    text = "Open Emergency Dialer (${emergencyNumber ?: "911"})"
                )
            }

            if (!contactPhoneNumber.isNullOrBlank()) {
                Button(
                    onClick = onCallContact,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Call ${contactName ?: "trusted contact"}")
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Dismiss")
                }
            }
        }
    }
}
