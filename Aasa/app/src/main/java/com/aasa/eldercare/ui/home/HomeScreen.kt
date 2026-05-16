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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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

/**
 * Polished Home / chat screen for the demo. Layout, top → bottom:
 *
 *   1. Header (title + subtitle)
 *   2. Local Gemma 4 Edge status card
 *   3. Trust / safety copy card
 *   4. Voice section (large mic button)
 *   5. Manual text field + Send
 *   6. "Try a demo scenario" buttons (auto-send)
 *   7. Inline loading / error / pending action cards
 *   8. Risk badge + assistant-response card
 *   9. Tool execution card (elder-friendly summary)
 *  10. Optional developer details (parsed JSON, raw response,
 *      conversation history) hidden behind a toggle
 *  11. Demo data controls + section navigation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMedicationClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onTrustedCircleClick: () -> Unit,
    onScamShieldClick: () -> Unit,
    onFallTriageClick: () -> Unit,
    onMobilityShieldClick: () -> Unit,
    onHealthBriefingClick: () -> Unit,
    viewModel: HomeViewModel = aasaHomeViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val recentConversations by viewModel.recentConversations.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeveloperDetails by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> viewModel.onMicPermissionResult(granted) }
    )

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setMicPermissionGranted(granted)
    }

    // Light haptic when listening starts so the elder gets a tactile
    // "go ahead" cue without needing a chime.
    LaunchedEffect(uiState.isListening) {
        if (uiState.isListening) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Light haptic + snackbar nudge when an agent response lands so
    // the user knows Aasa is about to start speaking.
    LaunchedEffect(uiState.agentAction?.assistantResponse) {
        val response = uiState.agentAction?.assistantResponse
        if (!response.isNullOrBlank()) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Show demo-data reset confirmations + any other transient
    // messages via the snackbar host.
    LaunchedEffect(uiState.transientMessageId) {
        val message = uiState.transientMessage ?: return@LaunchedEffect
        if (uiState.transientMessageId == 0L) return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeTransientMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "Aasa") })
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HeaderSection()

            GemmaStatusCard(
                selectedMode = uiState.selectedGemmaMode,
                state = uiState.gemmaConnection,
                modelLabel = uiState.gemmaModelLabel,
                detail = uiState.gemmaStatusDetail,
                onModeSelected = viewModel::selectGemmaMode,
                onRetry = viewModel::pingGemmaServer
            )

            TrustCopyCard()

            ScamShieldEntryCard(onOpen = onScamShieldClick)

            FallTriageEntryCard(onOpen = onFallTriageClick)

            MobilityShieldEntryCard(onOpen = onMobilityShieldClick)

            HealthBriefingEntryCard(onOpen = onHealthBriefingClick)

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

            ManualInputSection(
                inputText = uiState.inputText,
                isLoading = uiState.isLoading,
                onChange = viewModel::onInputChange,
                onSend = viewModel::sendCurrentMessage
            )

            DemoScenariosSection(
                enabled = !uiState.isLoading,
                onScenarioClick = viewModel::runDemoScenario
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
                onOpenShield = onScamShieldClick,
                onDismiss = viewModel::dismissPendingAction
            )

            uiState.agentAction?.let { action ->
                AssistantResponseCard(
                    action = action,
                    riskCopy = uiState.riskCopy
                )
            }

            if (uiState.toolExecutionSuccess != null) {
                ToolSummaryCard(
                    toolName = uiState.agentAction?.tool,
                    success = uiState.toolExecutionSuccess == true,
                    message = uiState.toolResultMessage.orEmpty(),
                    persistedMessage = uiState.persistedToolResultMessage
                )
            }

            DeveloperDetailsToggle(
                expanded = showDeveloperDetails,
                onToggle = { showDeveloperDetails = !showDeveloperDetails }
            )

            if (showDeveloperDetails) {
                uiState.agentAction?.let { action ->
                    ParsedResponseCard(action = action)
                    RawResponseCard(rawResponse = action.rawResponse)
                }
                ToolExecutionDataCard(data = uiState.toolResultData)
                ConversationHistoryCard(conversations = recentConversations)
            }

            DemoDataControls(
                isResetting = uiState.isResettingDemoData,
                onResetClick = viewModel::resetDemoData
            )

            NavigationShortcuts(
                onMedicationClick = onMedicationClick,
                onMemoryClick = onMemoryClick,
                onTrustedCircleClick = onTrustedCircleClick,
                onScamShieldClick = onScamShieldClick,
                onFallTriageClick = onFallTriageClick,
                onMobilityShieldClick = onMobilityShieldClick,
                onHealthBriefingClick = onHealthBriefingClick
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
// Header + status + trust copy
// ---------------------------------------------------------------------

@Composable
private fun HeaderSection() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Aasa",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Gemma 4 Safety Agent for Independent Elders",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GemmaStatusCard(
    selectedMode: GemmaRuntimeMode,
    state: GemmaConnectionState,
    modelLabel: String?,
    detail: String?,
    onModeSelected: (GemmaRuntimeMode) -> Unit,
    onRetry: () -> Unit
) {
    val (statusLabel, dotColor) = when (state) {
        GemmaConnectionState.CONNECTED ->
            "Connected" to MaterialTheme.colorScheme.primary
        GemmaConnectionState.CONNECTING ->
            "Connecting…" to MaterialTheme.colorScheme.tertiary
        GemmaConnectionState.DISCONNECTED ->
            "Disconnected" to MaterialTheme.colorScheme.error
        GemmaConnectionState.UNKNOWN ->
            "Checking…" to MaterialTheme.colorScheme.outline
    }

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
            GemmaModeToggle(
                selectedMode = selectedMode,
                onModeSelected = onModeSelected
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusDot(color = dotColor)
                Text(
                    text = "Gemma 4 Local Edge: $statusLabel",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (state == GemmaConnectionState.DISCONNECTED) {
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
            }
            StatusLine(
                label = "Mode",
                value = when (selectedMode) {
                    GemmaRuntimeMode.ON_DEVICE -> "On-device LiteRT-LM"
                    GemmaRuntimeMode.MAC_BRIDGE -> "Mac local LLM"
                }
            )
            StatusLine(label = "Privacy", value = "No cloud LLM")
            modelLabel?.takeIf { it.isNotBlank() }?.let { name ->
                StatusLine(label = "Model", value = name)
            }
            detail?.takeIf { it.isNotBlank() }?.let { reason ->
                StatusLine(label = "Status", value = reason)
            }
        }
    }
}

@Composable
private fun GemmaModeToggle(
    selectedMode: GemmaRuntimeMode,
    onModeSelected: (GemmaRuntimeMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        GemmaModeButton(
            text = "On-device",
            selected = selectedMode == GemmaRuntimeMode.ON_DEVICE,
            onClick = { onModeSelected(GemmaRuntimeMode.ON_DEVICE) },
            modifier = Modifier.weight(1f)
        )
        GemmaModeButton(
            text = "Mac LLM",
            selected = selectedMode == GemmaRuntimeMode.MAC_BRIDGE,
            onClick = { onModeSelected(GemmaRuntimeMode.MAC_BRIDGE) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GemmaModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = if (selected) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    } else {
        ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        colors = colors,
        elevation = null
    ) {
        Text(text = text, maxLines = 1)
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(2f)
        )
    }
}

@Composable
private fun TrustCopyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "You stay in control",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "Aasa will not call or alert anyone automatically. " +
                    "You choose when to open the dialer or prepare a message.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

// ---------------------------------------------------------------------
// Manual input + demo scenarios
// ---------------------------------------------------------------------

@Composable
private fun ManualInputSection(
    inputText: String,
    isLoading: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Type a message", fontSize = 16.sp) },
            placeholder = { Text("e.g. I took my BP tablet.") },
            enabled = !isLoading,
            minLines = 2,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyLarge
        )
        Button(
            onClick = onSend,
            enabled = !isLoading && inputText.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = if (isLoading) "Sending..." else "Send",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DemoScenariosSection(
    enabled: Boolean,
    onScenarioClick: (DemoScenario) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Try a demo scenario",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Tap any of these to send the phrase to Gemma 4.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DemoScenarios.ALL.forEach { scenario ->
                DemoScenarioRow(
                    scenario = scenario,
                    enabled = enabled,
                    onClick = { onScenarioClick(scenario) }
                )
            }
        }
    }
}

@Composable
private fun DemoScenarioRow(
    scenario: DemoScenario,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = scenario.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = scenario.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
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
    onOpenShield: () -> Unit,
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
        uiState.showScamAnalysisCard -> {
            ScamAnalysisCard(
                riskCopy = uiState.scamRiskCopy,
                signals = uiState.pendingScamSignals,
                safeAction = uiState.pendingSafeAction,
                contactName = uiState.pendingContactName,
                phoneNumber = uiState.pendingPhoneNumber,
                onCallContact = {
                    uiState.pendingPhoneNumber?.let(onCallContact)
                },
                onOpenShield = onOpenShield,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(text = "Stop Speaking", fontSize = 16.sp)
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
            .height(112.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MicGlyph()
            Text(
                text = label,
                fontSize = 26.sp,
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
            .size(36.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "\uD83C\uDFA4",
            fontSize = 22.sp
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
// Assistant response + tool summary (elder-friendly)
// ---------------------------------------------------------------------

@Composable
private fun AssistantResponseCard(
    action: AgentAction,
    riskCopy: RiskCopy?
) {
    if (action.assistantResponse.isBlank() && riskCopy == null) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Aasa says",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (action.assistantResponse.isNotBlank()) {
                Text(
                    text = action.assistantResponse,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium
                )
            }
            riskCopy?.let { RiskBadge(copy = it) }
        }
    }
}

@Composable
private fun ToolSummaryCard(
    toolName: String?,
    success: Boolean,
    message: String,
    persistedMessage: String?
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
    val displayName = humanizeTool(toolName)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(success = success)
                if (persistedMessage != null) {
                    PersistedPill()
                }
            }
            Text(
                text = message.ifBlank { "(no summary)" },
                style = MaterialTheme.typography.bodyLarge,
                color = onContainer
            )
            persistedMessage?.let {
                Text(
                    text = "Saved on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(success: Boolean) {
    val (label, container, content) = if (success) {
        Triple("Success", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
    } else {
        Triple("Failed", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.SemiBold
        )
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
            text = "Saved on device",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

private fun humanizeTool(rawTool: String?): String {
    val cleaned = rawTool?.trim().orEmpty()
    if (cleaned.isBlank()) return "Aasa response"
    return when (cleaned.uppercase()) {
        "MEDICATION", "MEDICATIONTOOL" -> "MedicationTool"
        "MEMORY", "MEMORYTOOL" -> "MemoryTool"
        "SAFETY", "SAFETYTOOL" -> "SafetyTool"
        "TRUSTEDCONTACT", "TRUSTEDCONTACTTOOL" -> "TrustedContactTool"
        "REMINDER", "REMINDERTOOL" -> "ReminderTool"
        "CHAT", "CHATTOOL" -> "ChatTool"
        "SCAMSHIELD", "SCAMSHIELDTOOL" -> "ScamShieldTool"
        "FALLTRIAGE", "FALLTRIAGETOOL" -> "FallTriageTool"
        "MOBILITYSHIELD", "MOBILITYSHIELDTOOL" -> "MobilityShieldTool"
        else -> cleaned
    }
}

// ---------------------------------------------------------------------
// Loading + error
// ---------------------------------------------------------------------

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(
            text = "Talking to local Gemma 4…",
            style = MaterialTheme.typography.bodyLarge
        )
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

// ---------------------------------------------------------------------
// Developer details (collapsible)
// ---------------------------------------------------------------------

@Composable
private fun DeveloperDetailsToggle(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    OutlinedButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = if (expanded) "Hide developer details" else "Show developer details"
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
private fun ToolExecutionDataCard(data: Map<String, Any?>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Tool execution data",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (data.isEmpty()) "{}" else prettyPrintJson(data),
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

// ---------------------------------------------------------------------
// Demo data + navigation
// ---------------------------------------------------------------------

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
                text = "Wipes medications, memories, contacts, and conversations from this phone, then re-seeds the BP tablet, Priya, and the favorite-music memory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onResetClick,
                enabled = !isResetting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = if (isResetting) "Resetting..." else "Reset Demo Data",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun LabeledLine(
    label: String,
    value: String,
    monospace: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
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
    onTrustedCircleClick: () -> Unit,
    onScamShieldClick: () -> Unit,
    onFallTriageClick: () -> Unit,
    onMobilityShieldClick: () -> Unit,
    onHealthBriefingClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Sections",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            BigSectionButton(label = "Medication", onClick = onMedicationClick)
            BigSectionButton(label = "Memory", onClick = onMemoryClick)
            BigSectionButton(label = "Trusted Circle", onClick = onTrustedCircleClick)
            BigSectionButton(label = "Scam & Fraud Shield", onClick = onScamShieldClick)
            BigSectionButton(label = "Fall Detection & Triage", onClick = onFallTriageClick)
            BigSectionButton(label = "Mobility Shield", onClick = onMobilityShieldClick)
            BigSectionButton(label = "Morning Briefing", onClick = onHealthBriefingClick)
        }
    }
}

@Composable
private fun BigSectionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ---------------------------------------------------------------------
// Phase 8.5 Scam & Fraud Shield (Home surface)
// ---------------------------------------------------------------------

/**
 * Prominent entry card for the Mobility Shield (10-second walk check)
 * screen. Mirrors [FallTriageEntryCard]'s elder-friendly tone — large
 * title, plain subtitle, single big tap target.
 *
 * The copy here intentionally avoids any medical or diagnostic
 * language. Aasa "tracks mobility confidence" — it does NOT diagnose
 * Parkinson's, dementia, stroke, or any neurological disease.
 */
@Composable
private fun MobilityShieldEntryCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Mobility Shield",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = "Run a 10-second walk check. Aasa explains mobility confidence " +
                    "in simple words — this is not a medical diagnosis.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Open Mobility Shield",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Phase 10 — Morning Briefing entry card. Mirrors the other
 * elder-friendly entry cards. The actual screen surfaces a
 * mandatory "Demo data — no wearable connected" pill when no
 * wearable is connected.
 */
@Composable
private fun HealthBriefingEntryCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Morning Briefing",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "A gentle look at your last 24 hours — sleep, heart rate, and steps. " +
                    "Wearable-ready (Fitbit Air, Pixel Watch, and others). " +
                    "On-device, no cloud.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Open Morning Briefing",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Prominent entry card for the Fall Detection & Triage screen. Mirrors
 * [ScamShieldEntryCard]'s elder-friendly tone — large title, plain
 * subtitle, single big tap target.
 */
@Composable
private fun FallTriageEntryCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Fall Detection & Triage",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "Check in after a possible fall and alert your trusted circle if needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Open Fall Triage",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Prominent entry card encouraging the elder to open the dedicated
 * shield screen when they receive a suspicious message. Sits high in
 * the layout so it's visible without scrolling.
 */
@Composable
private fun ScamShieldEntryCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Scam & Fraud Shield",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = "Got a strange message? Paste it here and Aasa will check it for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Open Scam Shield",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Inline result card surfaced on Home after the demo "Scam Alert"
 * scenario (or any other turn that triggers ScamShieldTool).
 *
 * Mirrors the polished result card on [com.aasa.eldercare.ui.scamshield.ScamShieldScreen]
 * but stays compact so it reads cleanly inside the Home scroll. The
 * "Call Priya" tap launches `ACTION_DIAL`; we never auto-call.
 */
@Composable
private fun ScamAnalysisCard(
    riskCopy: ScamRiskCopy?,
    signals: List<String>,
    safeAction: String?,
    contactName: String?,
    phoneNumber: String?,
    onCallContact: () -> Unit,
    onOpenShield: () -> Unit,
    onDismiss: () -> Unit
) {
    val container = when (riskCopy?.level) {
        RiskCopy.RiskLevel.HIGH -> MaterialTheme.colorScheme.errorContainer
        RiskCopy.RiskLevel.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (riskCopy?.level) {
        RiskCopy.RiskLevel.HIGH -> MaterialTheme.colorScheme.onErrorContainer
        RiskCopy.RiskLevel.MEDIUM -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Scam Risk: ${riskCopy?.level?.name ?: "—"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onContainer
            )
            riskCopy?.let { copy ->
                Text(
                    text = copy.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onContainer
                )
                Text(
                    text = copy.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer
                )
            }

            if (signals.isNotEmpty()) {
                Text(
                    text = "Why it may not be safe",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    signals.forEach { signal ->
                        Text(
                            text = "• $signal",
                            style = MaterialTheme.typography.bodyLarge,
                            color = onContainer
                        )
                    }
                }
            }

            safeAction?.takeIf { it.isNotBlank() }?.let { action ->
                Text(
                    text = "Safe next step",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer
                )
                Text(
                    text = action,
                    style = MaterialTheme.typography.bodyLarge,
                    color = onContainer
                )
            }

            if (!contactName.isNullOrBlank() && !phoneNumber.isNullOrBlank()) {
                Button(
                    onClick = onCallContact,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Call $contactName",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenShield,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(text = "Open Shield", fontSize = 16.sp)
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(text = "Dismiss", fontSize = 16.sp)
                }
            }
        }
    }
}

private val prettyGson = GsonBuilder().setPrettyPrinting().create()

private fun prettyPrintJson(value: Any): String =
    runCatching { prettyGson.toJson(value) }.getOrDefault(value.toString())

private val timestampFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTimestamp(epochMs: Long): String =
    runCatching { timestampFormatter.format(Date(epochMs)) }.getOrDefault("")
