package com.aasa.eldercare.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.agent.AgentAction
import com.aasa.eldercare.data.entity.ConversationEntity
import com.aasa.eldercare.ui.IntentActionLauncher
import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SAMPLE_MESSAGES = listOf(
    "I took my BP tablet.",
    "Did I take my medicine today?",
    "I feel weak and missed my medicine.",
    "My granddaughter Ananya's birthday is May 12.",
    "Call Priya."
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMedicationClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onTrustedCircleClick: () -> Unit,
    viewModel: HomeViewModel = aasaHomeViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val recentConversations by viewModel.recentConversations.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> viewModel.onMicPermissionResult(granted) }
    )

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        // Sync the cached permission state without auto-starting the
        // recognizer; the elder must still tap the mic button.
        viewModel.setMicPermissionGranted(granted)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "Aasa") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Talk to your local Gemma assistant",
                style = MaterialTheme.typography.titleMedium
            )

            VoiceSection(
                uiState = uiState,
                onMicClick = {
                    if (uiState.isListening) {
                        viewModel.stopListening()
                        return@VoiceSection
                    }
                    if (uiState.hasMicPermission) {
                        viewModel.startListening()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopSpeakingClick = viewModel::stopSpeaking,
                onDismissVoiceError = viewModel::clearVoiceError
            )

            OutlinedTextField(
                value = uiState.inputText,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Type a message") },
                placeholder = { Text("e.g. I took my BP tablet.") },
                enabled = !uiState.isLoading,
                minLines = 2,
                maxLines = 4
            )

            Button(
                onClick = viewModel::sendCurrentMessage,
                enabled = !uiState.isLoading && uiState.inputText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (uiState.isLoading) "Sending..." else "Send")
            }

            SampleButtons(
                enabled = !uiState.isLoading,
                onSampleClick = viewModel::sendSample
            )

            if (uiState.isLoading) {
                LoadingRow()
            }

            uiState.errorMessage?.let { error ->
                ErrorCard(message = error, onDismiss = viewModel::clearError)
            }

            PendingActionSection(
                uiState = uiState,
                onCallContact = { number ->
                    IntentActionLauncher.openDialer(context, number)
                },
                onOpenSms = { number, body ->
                    IntentActionLauncher.openSms(context, number, body)
                },
                onOpenEmergencyDialer = { number ->
                    IntentActionLauncher.openDialer(context, number)
                },
                onDismiss = viewModel::dismissPendingAction
            )

            if (uiState.toolExecutionSuccess != null) {
                ToolExecutionCard(
                    success = uiState.toolExecutionSuccess == true,
                    message = uiState.toolResultMessage.orEmpty(),
                    persistedMessage = uiState.persistedToolResultMessage,
                    data = uiState.toolResultData
                )
            }

            uiState.agentAction?.let { action ->
                ParsedResponseCard(action = action)
                RawResponseCard(rawResponse = action.rawResponse)
            }

            ConversationHistoryCard(conversations = recentConversations)

            DemoDataControls(
                isResetting = uiState.isResettingDemoData,
                onResetClick = viewModel::resetDemoData
            )

            NavigationShortcuts(
                onMedicationClick = onMedicationClick,
                onMemoryClick = onMemoryClick,
                onTrustedCircleClick = onTrustedCircleClick
            )
        }
    }
}

@Composable
private fun aasaHomeViewModel(): HomeViewModel {
    val application = LocalContext.current.applicationContext as AasaApplication
    return viewModel(factory = HomeViewModel.Factory(application))
}

// ---------------------------------------------------------------------
// Phase 7 deferred-confirmation action cards
// ---------------------------------------------------------------------

@Composable
private fun PendingActionSection(
    uiState: HomeUiState,
    onCallContact: (String) -> Unit,
    onOpenSms: (String, String) -> Unit,
    onOpenEmergencyDialer: (String) -> Unit,
    onDismiss: () -> Unit
) {
    when {
        uiState.showContactActionCard -> {
            ContactActionCard(
                contactName = uiState.pendingContactName,
                phoneNumber = uiState.pendingPhoneNumber,
                onOpenDialer = {
                    uiState.pendingPhoneNumber?.let(onCallContact)
                    onDismiss()
                },
                onDismiss = onDismiss
            )
        }
        uiState.showSafetyActionCard -> {
            val number = uiState.pendingPhoneNumber
            SafetyActionCard(
                contactName = uiState.pendingContactName,
                alertMessage = uiState.pendingAlertMessage,
                onOpenSms = {
                    if (!number.isNullOrBlank()) {
                        onOpenSms(number, uiState.pendingAlertMessage.orEmpty())
                    }
                    onDismiss()
                },
                onDismiss = onDismiss
            )
        }
        uiState.showEmergencyActionCard -> {
            EmergencyActionCard(
                contactName = uiState.pendingContactName,
                contactPhoneNumber = uiState.pendingPhoneNumber,
                emergencyNumber = uiState.pendingEmergencyNumber,
                alertMessage = uiState.pendingAlertMessage,
                onOpenEmergencyDialer = {
                    val number = uiState.pendingEmergencyNumber ?: "911"
                    onOpenEmergencyDialer(number)
                    onDismiss()
                },
                onCallContact = {
                    uiState.pendingPhoneNumber?.let(onCallContact)
                    onDismiss()
                },
                onDismiss = onDismiss
            )
        }
    }
}

// ---------------------------------------------------------------------
// Voice-first section (Phase 6)
// ---------------------------------------------------------------------

@Composable
private fun VoiceSection(
    uiState: HomeUiState,
    onMicClick: () -> Unit,
    onStopSpeakingClick: () -> Unit,
    onDismissVoiceError: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MicButton(
            isListening = uiState.isListening,
            isSpeaking = uiState.isSpeaking,
            isDisabled = uiState.isLoading,
            onClick = onMicClick
        )

        if (uiState.isSpeaking) {
            OutlinedButton(
                onClick = onStopSpeakingClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Stop Speaking")
            }
        }

        uiState.recognizedSpeech?.takeIf { it.isNotBlank() }?.let { spoken ->
            RecognizedSpeechCard(text = spoken)
        }

        uiState.voiceError?.let { error ->
            VoiceErrorCard(message = error, onDismiss = onDismissVoiceError)
        }

        uiState.ttsStatus?.let { status ->
            TtsStatusPill(status = status)
        }
    }
}

@Composable
private fun MicButton(
    isListening: Boolean,
    isSpeaking: Boolean,
    isDisabled: Boolean,
    onClick: () -> Unit
) {
    val (label, container) = when {
        isListening -> "Listening..." to MaterialTheme.colorScheme.tertiary
        isSpeaking -> "Speaking..." to MaterialTheme.colorScheme.secondary
        else -> "Tap to Speak" to MaterialTheme.colorScheme.primary
    }
    Button(
        onClick = onClick,
        enabled = !isDisabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MicGlyph()
            Text(
                text = label,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Tiny circular glyph standing in for a microphone icon. Avoids the
 * extra material-icons dependency surface and keeps the mic button
 * usable on any device.
 */
@Composable
private fun MicGlyph() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "\uD83C\uDFA4",
            fontSize = 18.sp
        )
    }
}

@Composable
private fun RecognizedSpeechCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "I heard",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun VoiceErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Voice",
                style = MaterialTheme.typography.labelMedium,
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
private fun TtsStatusPill(status: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------
// Existing sections (Phase 4) -- unchanged behavior
// ---------------------------------------------------------------------

@Composable
private fun SampleButtons(
    enabled: Boolean,
    onSampleClick: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Try a sample",
            style = MaterialTheme.typography.labelLarge
        )
        SAMPLE_MESSAGES.forEach { sample ->
            OutlinedButton(
                onClick = { onSampleClick(sample) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = sample)
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(text = "Talking to local Gemma…")
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
                text = "Something went wrong",
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
private fun ToolExecutionCard(
    success: Boolean,
    message: String,
    persistedMessage: String?,
    data: Map<String, Any?>
) {
    val container = if (success) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = if (success) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tool execution result",
                    style = MaterialTheme.typography.titleMedium,
                    color = onContainer
                )
                if (persistedMessage != null) {
                    PersistedPill()
                }
            }
            LabeledLine(
                label = "Success",
                value = success.toString(),
                valueColor = onContainer
            )
            LabeledLine(
                label = "Message",
                value = message.ifBlank { "(no message)" },
                valueColor = onContainer
            )
            persistedMessage?.let {
                LabeledLine(
                    label = "Persisted to Room",
                    value = it,
                    valueColor = onContainer
                )
            }
            LabeledLine(
                label = "Data",
                value = if (data.isEmpty()) "{}" else prettyPrintJson(data),
                monospace = true,
                valueColor = onContainer
            )
        }
    }
}

@Composable
private fun PersistedPill() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "Saved in Room",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Composable
private fun ParsedResponseCard(action: AgentAction) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Parsed response",
                style = MaterialTheme.typography.titleMedium
            )
            if (action.assistantResponse.isNotBlank()) {
                LabeledLine(label = "Assistant", value = action.assistantResponse)
            }
            LabeledLine(label = "Intent", value = action.intent)
            LabeledLine(label = "Risk level", value = action.riskLevel)
            LabeledLine(label = "Tool", value = action.tool)
            if (action.arguments.isNotEmpty()) {
                LabeledLine(
                    label = "Arguments",
                    value = prettyPrintJson(action.arguments),
                    monospace = true
                )
            }
        }
    }
}

@Composable
private fun RawResponseCard(rawResponse: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Raw response",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = rawResponse?.takeIf { it.isNotBlank() }
                    ?: "(server returned no rawResponse field)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConversationHistoryCard(conversations: List<ConversationEntity>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Recent conversations (from Room)",
                style = MaterialTheme.typography.titleMedium
            )
            if (conversations.isEmpty()) {
                Text(
                    text = "No conversations yet. Send a message to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                conversations.take(8).forEach { entry ->
                    ConversationRow(entry)
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(entry: ConversationEntity) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(roleColor(entry.role))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = entry.role,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.message.ifBlank { "(empty)" },
                style = MaterialTheme.typography.bodyMedium
            )
            val meta = buildString {
                append(formatTimestamp(entry.createdAt))
                entry.intent?.let { append("  •  intent=$it") }
                entry.tool?.let { append("  •  tool=$it") }
                entry.riskLevel?.let { append("  •  risk=$it") }
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun roleColor(role: String) = when (role) {
    ConversationEntity.ROLE_USER -> MaterialTheme.colorScheme.secondaryContainer
    ConversationEntity.ROLE_ASSISTANT -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun DemoDataControls(
    isResetting: Boolean,
    onResetClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Demo data",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Wipes all medications, memories, contacts and conversations from Room and re-seeds the BP tablet, Priya, and favorite-music memory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onResetClick,
                enabled = !isResetting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (isResetting) "Resetting..." else "Reset demo data")
            }
        }
    }
}

@Composable
private fun LabeledLine(
    label: String,
    value: String,
    monospace: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun NavigationShortcuts(
    onMedicationClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onTrustedCircleClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Sections",
                style = MaterialTheme.typography.labelLarge
            )
            OutlinedButton(
                onClick = onMedicationClick,
                modifier = Modifier.fillMaxWidth()
            ) { Text(text = "Medication") }
            OutlinedButton(
                onClick = onMemoryClick,
                modifier = Modifier.fillMaxWidth()
            ) { Text(text = "Memory") }
            OutlinedButton(
                onClick = onTrustedCircleClick,
                modifier = Modifier.fillMaxWidth()
            ) { Text(text = "Trusted Circle") }
        }
    }
}

private val prettyGson = GsonBuilder().setPrettyPrinting().create()

private fun prettyPrintJson(value: Any): String =
    runCatching { prettyGson.toJson(value) }.getOrDefault(value.toString())

private val timestampFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTimestamp(epochMs: Long): String =
    runCatching { timestampFormatter.format(Date(epochMs)) }.getOrDefault("")
