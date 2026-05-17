package com.aasa.eldercare.ui.trustedcircle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.data.entity.TrustedContactEntity
import com.aasa.eldercare.data.entity.TrustedRelationshipTypes
import com.aasa.eldercare.ui.IntentActionLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedCircleScreen(
    onBack: () -> Unit = {},
    viewModel: TrustedCircleViewModel = aasaTrustedCircleViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.preparedMessage?.id) {
        val msg = uiState.preparedMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = msg.text)
        viewModel.clearPreparedMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Trusted Circle") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        TrustedCircleContent(
            innerPadding = innerPadding,
            uiState = uiState,
            onRefresh = viewModel::refresh,
            onPrepareCall = { contact ->
                viewModel.onPrepareCall(contact)
                IntentActionLauncher.openDialer(context, contact.phoneNumber)
            },
            onPrepareAlert = { contact ->
                viewModel.onPrepareAlert(contact)
                IntentActionLauncher.openSms(
                    context = context,
                    phoneNumber = contact.phoneNumber,
                    body = buildDemoAlertMessage(contact)
                )
            },
            onSetProviderConsent = viewModel::onSetProviderConsent,
            onSetRecipientConsent = viewModel::onSetRecipientConsent,
            onDismissError = viewModel::clearError
        )
    }
}

/**
 * Phase 7 demo alert template used when the elder taps "Prepare Alert"
 * directly from the trusted-circle screen (vs. the safety flow on
 * Home, which uses the SafetyTool-supplied message instead).
 */
private fun buildDemoAlertMessage(contact: TrustedContactEntity): String =
    "Aasa demo alert from your elder. " +
        "Hi ${contact.name}, please check in when you have a moment."

@Composable
private fun TrustedCircleContent(
    innerPadding: PaddingValues,
    uiState: TrustedCircleUiState,
    onRefresh: () -> Unit,
    onPrepareCall: (TrustedContactEntity) -> Unit,
    onPrepareAlert: (TrustedContactEntity) -> Unit,
    onSetProviderConsent: (TrustedContactEntity, Boolean) -> Unit,
    onSetRecipientConsent: (TrustedContactEntity, Boolean) -> Unit,
    onDismissError: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "People who can help",
                style = MaterialTheme.typography.titleLarge
            )
            OutlinedButton(
                onClick = onRefresh,
                enabled = !uiState.isLoading
            ) {
                Text(text = if (uiState.isLoading) "Refreshing..." else "Refresh")
            }
        }

        uiState.errorMessage?.let { error ->
            ErrorCard(message = error, onDismiss = onDismissError)
        }

        when {
            uiState.isLoading && uiState.contacts.isEmpty() -> LoadingBlock()
            uiState.isEmpty -> EmptyState()
            else -> ContactList(
                contacts = uiState.contacts,
                onPrepareCall = onPrepareCall,
                onPrepareAlert = onPrepareAlert,
                onSetProviderConsent = onSetProviderConsent,
                onSetRecipientConsent = onSetRecipientConsent
            )
        }
    }
}

@Composable
private fun ContactList(
    contacts: List<TrustedContactEntity>,
    onPrepareCall: (TrustedContactEntity) -> Unit,
    onPrepareAlert: (TrustedContactEntity) -> Unit,
    onSetProviderConsent: (TrustedContactEntity, Boolean) -> Unit,
    onSetRecipientConsent: (TrustedContactEntity, Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(contacts, key = { it.id }) { contact ->
            ContactCard(
                contact = contact,
                onPrepareCall = { onPrepareCall(contact) },
                onPrepareAlert = { onPrepareAlert(contact) },
                onSetProviderConsent = { onSetProviderConsent(contact, it) },
                onSetRecipientConsent = { onSetRecipientConsent(contact, it) }
            )
        }
    }
}

@Composable
private fun ContactCard(
    contact: TrustedContactEntity,
    onPrepareCall: () -> Unit,
    onPrepareAlert: () -> Unit,
    onSetProviderConsent: (Boolean) -> Unit,
    onSetRecipientConsent: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (contact.isPrimary) {
                    PrimaryBadge()
                }
            }
            Text(
                text = contact.relationship,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = contact.phoneNumber,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (contact.relationshipType == TrustedRelationshipTypes.CARE_PROVIDER &&
                contact.pairedContactId != null
            ) {
                ConsentSection(
                    providerGranted = contact.providerConsentGranted,
                    recipientGranted = contact.recipientConsentGranted,
                    onSetProviderConsent = onSetProviderConsent,
                    onSetRecipientConsent = onSetRecipientConsent
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onPrepareCall,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Prepare Call")
                }
                OutlinedButton(
                    onClick = onPrepareAlert,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Prepare Alert")
                }
            }
        }
    }
}

@Composable
private fun ConsentSection(
    providerGranted: Boolean,
    recipientGranted: Boolean,
    onSetProviderConsent: (Boolean) -> Unit,
    onSetRecipientConsent: (Boolean) -> Unit
) {
    val fullyGranted = providerGranted && recipientGranted
    val badgeText = if (fullyGranted) {
        "Pair consent: active"
    } else {
        "Pair consent: pending"
    }
    val badgeColor = if (fullyGranted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(badgeColor)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = badgeText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { onSetProviderConsent(!providerGranted) },
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = if (providerGranted) {
                    "Undo helper consent"
                } else {
                    "Confirm helper consent"
                }
            )
        }
        OutlinedButton(
            onClick = { onSetRecipientConsent(!recipientGranted) },
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = if (recipientGranted) {
                    "Undo recipient consent"
                } else {
                    "Confirm recipient consent"
                }
            )
        }
    }
}

@Composable
private fun PrimaryBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = "Primary",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LoadingBlock() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(text = "Loading trusted circle…")
    }
}

@Composable
private fun EmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No trusted contacts added yet.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
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
                text = "Could not load trusted circle",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun aasaTrustedCircleViewModel(): TrustedCircleViewModel {
    val application = LocalContext.current.applicationContext as AasaApplication
    return viewModel(factory = TrustedCircleViewModel.Factory(application))
}
