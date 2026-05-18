package com.aasa.eldercare.ui.falltriage

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.tools.FallTriageCategories
import com.aasa.eldercare.ui.IntentActionLauncher

/**
 * Phase 8.6: Fall Detection & Triage screen.
 *
 * Layout (top → bottom):
 *   1. Intro / safety copy.
 *   2. Status card (monitoring on/off, fall status, voice status).
 *   3. Big action buttons: Start / Stop / Simulate / Reset.
 *   4. Live recognized speech card (during the LISTENING phase).
 *   5. Triage result card with category-specific action buttons.
 *   6. Friendly disclaimer reminding the user that Aasa helps triage
 *      but does not replace emergency services.
 *
 * All system intents (dialer, SMS) are launched from this screen via
 * [IntentActionLauncher]. The ViewModel never auto-acts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FallTriageScreen(
    onBack: () -> Unit,
    viewModel: FallTriageViewModel = aasaFallTriageViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pendingAutoSms by remember { mutableStateOf<Pair<String, String>?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> viewModel.onMicPermissionResult(granted) }
    )
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            val pending = pendingAutoSms
            pendingAutoSms = null
            if (granted && pending != null) {
                IntentActionLauncher.sendSmsDirect(context, pending.first, pending.second)
            } else if (pending != null) {
                IntentActionLauncher.openSms(context, pending.first, pending.second)
            }
        }
    )

    fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

    fun requestSmsPermissionIfNeeded() {
        if (!hasSmsPermission()) {
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    fun sendSmsDirectOrRequest(number: String, body: String) {
        if (hasSmsPermission()) {
            IntentActionLauncher.sendSmsDirect(context, number, body)
        } else {
            pendingAutoSms = number to body
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setMicPermissionGranted(granted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Fall Detection & Triage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        FallTriageContent(
            innerPadding = innerPadding,
            uiState = uiState,
            onStartMonitoring = {
                requestSmsPermissionIfNeeded()
                if (uiState.hasMicPermission) {
                    viewModel.startMonitoring()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    viewModel.startMonitoring()
                }
            },
            onStopMonitoring = viewModel::stopMonitoring,
            onSimulateFall = {
                requestSmsPermissionIfNeeded()
                if (!uiState.hasMicPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                viewModel.simulateFall()
            },
            onReset = viewModel::reset,
            onDismissError = viewModel::dismissError,
            onCallContact = { number ->
                IntentActionLauncher.openDialer(context, number)
            },
            onOpenSms = { number, body ->
                IntentActionLauncher.openSms(context, number, body)
            },
            onSendSmsDirect = { number, body ->
                sendSmsDirectOrRequest(number, body)
            },
            onOpenEmergencyDialer = { number ->
                IntentActionLauncher.openDialer(context, number)
            }
        )
    }
}

@Composable
private fun aasaFallTriageViewModel(): FallTriageViewModel {
    val application = LocalContext.current.applicationContext as AasaApplication
    return viewModel(factory = FallTriageViewModel.Factory(application))
}

@Composable
private fun FallTriageContent(
    innerPadding: PaddingValues,
    uiState: FallTriageUiState,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onSimulateFall: () -> Unit,
    onReset: () -> Unit,
    onDismissError: () -> Unit,
    onCallContact: (String) -> Unit,
    onOpenSms: (String, String) -> Unit,
    onSendSmsDirect: (String, String) -> Unit,
    onOpenEmergencyDialer: (String) -> Unit
) {
    if (uiState.showTriageCard &&
        uiState.triageCategory == FallTriageCategories.URGENT_RISK
    ) {
        UrgentTriageDialog(
            contactName = uiState.contactName,
            phoneNumber = uiState.phoneNumber,
            emergencyNumber = uiState.emergencyNumber ?: "911",
            alertMessage = uiState.alertMessage.orEmpty(),
            onOpenEmergencyDialer = { number ->
                onOpenEmergencyDialer(number)
                onReset()
            },
            onCallContact = { number ->
                onCallContact(number)
                onReset()
            },
            onOpenSms = { number, body ->
                onOpenSms(number, body)
                onReset()
            },
            onDismiss = onReset
        )
    }
    if (uiState.showTriageCard &&
        uiState.triageCategory == FallTriageCategories.NO_RESPONSE
    ) {
        NoResponseEscalationDialog(
            contactName = uiState.contactName,
            phoneNumber = uiState.phoneNumber,
            emergencyNumber = uiState.emergencyNumber ?: "911",
            alertMessage = uiState.alertMessage.orEmpty(),
            onAlertContact = { number, body ->
                onSendSmsDirect(number, body)
                onReset()
            },
            onOpenEmergencyDialer = { number ->
                onOpenEmergencyDialer(number)
                onReset()
            },
            onImOkay = onReset
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IntroCopyCard()

        StatusCard(uiState = uiState)

        ControlsCard(
            uiState = uiState,
            onStart = onStartMonitoring,
            onStop = onStopMonitoring,
            onSimulate = onSimulateFall,
            onReset = onReset
        )

        if (uiState.phase == FallTriageUiState.Phase.LISTENING ||
            uiState.recognizedSpeech?.isNotBlank() == true
        ) {
            RecognizedSpeechCard(
                phase = uiState.phase,
                recognized = uiState.recognizedSpeech
            )
        }

        if (uiState.phase == FallTriageUiState.Phase.ANALYZING) {
            AnalyzingRow()
        }

        uiState.voiceError?.let { msg ->
            VoiceErrorCard(message = msg, onDismiss = onDismissError)
        }
        uiState.errorMessage?.let { msg ->
            ErrorCard(message = msg, onDismiss = onDismissError)
        }
        uiState.sensorErrorMessage?.let { msg ->
            SensorWarningCard(message = msg)
        }

        if (uiState.showTriageCard) {
            TriageResultCard(
                uiState = uiState,
                onCallContact = onCallContact,
                onOpenSms = onOpenSms,
                onOpenEmergencyDialer = onOpenEmergencyDialer,
                onDismiss = onReset
            )
        }

        DisclaimerCard()
    }
}

// ---------------------------------------------------------------------
// Header / status / controls
// ---------------------------------------------------------------------

@Composable
private fun IntroCopyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Aasa is here when you fall",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "Aasa can check in after a possible fall and help you contact your trusted circle. " +
                    "You stay in control — Aasa never calls or messages anyone automatically.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun StatusCard(uiState: FallTriageUiState) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusDot(
                    color = if (uiState.isMonitoring) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
                Text(
                    text = "Monitoring: ${if (uiState.isMonitoring) "On" else "Off"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusLine(label = "Fall status", value = uiState.fallStatusLabel)
            StatusLine(label = "Voice check-in", value = uiState.voiceStatusLabel)
            if (!uiState.sensorAvailable) {
                Text(
                    text = "No accelerometer detected. Use Simulate Fall for the demo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
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
private fun ControlsCard(
    uiState: FallTriageUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSimulate: () -> Unit,
    onReset: () -> Unit
) {
    val triageBusy = uiState.phase == FallTriageUiState.Phase.CHECKING_IN ||
        uiState.phase == FallTriageUiState.Phase.LISTENING ||
        uiState.phase == FallTriageUiState.Phase.ANALYZING

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Controls",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Button(
                onClick = onStart,
                enabled = !uiState.isMonitoring && !triageBusy && uiState.sensorAvailable,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                Text(
                    text = "Start Fall Detection",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            OutlinedButton(
                onClick = onStop,
                enabled = uiState.isMonitoring && !triageBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(text = "Stop Fall Detection", fontSize = 16.sp)
            }
            Button(
                onClick = onSimulate,
                enabled = !triageBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                Text(
                    text = "Simulate Fall",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(text = "Reset", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun RecognizedSpeechCard(
    phase: FallTriageUiState.Phase,
    recognized: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (phase == FallTriageUiState.Phase.LISTENING) {
                    "Listening for your response…"
                } else {
                    "I heard"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = recognized?.takeIf { it.isNotBlank() } ?: "(no response yet)",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun AnalyzingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(
            text = "Aasa is checking how you are…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Voice",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
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
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun SensorWarningCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Sensor",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------
// Triage result card
// ---------------------------------------------------------------------

@Composable
private fun TriageResultCard(
    uiState: FallTriageUiState,
    onCallContact: (String) -> Unit,
    onOpenSms: (String, String) -> Unit,
    onOpenEmergencyDialer: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val copy = FallTriageCopy.forCategory(uiState.triageCategory)
    val container = when (copy.severity) {
        FallTriageCopy.Severity.HIGH -> MaterialTheme.colorScheme.errorContainer
        FallTriageCopy.Severity.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer
        FallTriageCopy.Severity.LOW -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (copy.severity) {
        FallTriageCopy.Severity.HIGH -> MaterialTheme.colorScheme.onErrorContainer
        FallTriageCopy.Severity.MEDIUM -> MaterialTheme.colorScheme.onTertiaryContainer
        FallTriageCopy.Severity.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
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
                text = "Triage: ${uiState.triageCategory ?: "—"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onContainer
            )
            Text(
                text = copy.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = onContainer
            )
            uiState.triageRiskLevel?.takeIf { it.isNotBlank() }?.let { risk ->
                Text(
                    text = "Risk level: $risk",
                    style = MaterialTheme.typography.titleMedium,
                    color = onContainer
                )
            }
            Text(
                text = copy.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = onContainer
            )

            uiState.assistantResponse?.takeIf { it.isNotBlank() }?.let { msg ->
                ResultBlock(
                    label = "Aasa says",
                    value = msg,
                    onContainer = onContainer
                )
            }
            uiState.toolMessage?.takeIf {
                it.isNotBlank() && it != uiState.assistantResponse
            }?.let { msg ->
                ResultBlock(
                    label = "Triage summary",
                    value = msg,
                    onContainer = onContainer
                )
            }
            uiState.recommendedAction?.takeIf { it.isNotBlank() }?.let { msg ->
                ResultBlock(
                    label = "Recommended next step",
                    value = msg,
                    onContainer = onContainer
                )
            }

            TriageActionButtons(
                category = uiState.triageCategory,
                contactName = uiState.contactName,
                phoneNumber = uiState.phoneNumber,
                emergencyNumber = uiState.emergencyNumber ?: "911",
                alertMessage = uiState.alertMessage.orEmpty(),
                onCallContact = { num -> onCallContact(num) },
                onOpenSms = { num, body -> onOpenSms(num, body) },
                onOpenEmergencyDialer = { num -> onOpenEmergencyDialer(num) },
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun ResultBlock(
    label: String,
    value: String,
    onContainer: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = onContainer
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = onContainer
        )
    }
}

@Composable
private fun TriageActionButtons(
    category: String?,
    contactName: String?,
    phoneNumber: String?,
    emergencyNumber: String,
    alertMessage: String,
    onCallContact: (String) -> Unit,
    onOpenSms: (String, String) -> Unit,
    onOpenEmergencyDialer: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val contactPhoneNumber = phoneNumber?.takeIf { it.isNotBlank() }
    val hasContact = !contactName.isNullOrBlank() && contactPhoneNumber != null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (category) {
            FallTriageCategories.URGENT_RISK -> Unit
            FallTriageCategories.NON_EMERGENCY_INJURY -> {
                if (hasContact) {
                    Button(
                        onClick = {
                            onOpenSms(contactPhoneNumber, alertMessage)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                    ) {
                        Text(
                            text = "Alert $contactName by SMS",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = { onCallContact(contactPhoneNumber) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(text = "Call $contactName", fontSize = 16.sp)
                    }
                }
            }
            FallTriageCategories.NO_RESPONSE -> {
                Unit
            }
            FallTriageCategories.FALSE_ALARM -> Unit
        }

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(text = "Dismiss", fontSize = 16.sp)
        }
    }
}

@Composable
private fun NoResponseEscalationDialog(
    contactName: String?,
    phoneNumber: String?,
    emergencyNumber: String,
    alertMessage: String,
    onAlertContact: (String, String) -> Unit,
    onOpenEmergencyDialer: (String) -> Unit,
    onImOkay: () -> Unit
) {
    val contactPhoneNumber = phoneNumber?.takeIf { it.isNotBlank() }
    val hasContact = !contactName.isNullOrBlank() && contactPhoneNumber != null
    var secondsRemaining by remember { mutableIntStateOf(NO_RESPONSE_ESCALATION_SECONDS) }

    LaunchedEffect(contactPhoneNumber, alertMessage) {
        secondsRemaining = NO_RESPONSE_ESCALATION_SECONDS
        while (secondsRemaining > 0) {
            kotlinx.coroutines.delay(1_000L)
            secondsRemaining -= 1
        }
        if (contactPhoneNumber != null) {
            onAlertContact(contactPhoneNumber, alertMessage)
        } else {
            onOpenEmergencyDialer(emergencyNumber)
        }
    }

    Dialog(onDismissRequest = { }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "No Response Heard",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = if (hasContact) {
                        "Aasa will alert $contactName in $secondsRemaining seconds unless you say you are okay."
                    } else {
                        "Aasa will open the emergency dialer in $secondsRemaining seconds unless you say you are okay."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                alertMessage.takeIf { it.isNotBlank() }?.let { body ->
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Text(
                    text = "Aasa can send the SMS automatically when SMS permission is granted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                if (hasContact) {
                    Button(
                        onClick = { onAlertContact(contactPhoneNumber, alertMessage) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(
                            text = "Alert $contactName Now",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                OutlinedButton(
                    onClick = { onOpenEmergencyDialer(emergencyNumber) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(text = "Open Emergency Dialer", fontSize = 16.sp)
                }

                OutlinedButton(
                    onClick = onImOkay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(text = "I'm Okay", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun UrgentTriageDialog(
    contactName: String?,
    phoneNumber: String?,
    emergencyNumber: String,
    alertMessage: String,
    onOpenEmergencyDialer: (String) -> Unit,
    onCallContact: (String) -> Unit,
    onOpenSms: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val contactPhoneNumber = phoneNumber?.takeIf { it.isNotBlank() }
    val hasContact = !contactName.isNullOrBlank() && contactPhoneNumber != null

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Urgent Safety Concern",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "This may need urgent help. You can open the emergency dialer or call ${contactName ?: "your trusted contact"}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                alertMessage.takeIf { it.isNotBlank() }?.let { body ->
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Text(
                    text = "Aasa will not call anyone automatically. You must confirm in the dialer or message app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Button(
                    onClick = { onOpenEmergencyDialer(emergencyNumber) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(
                        text = "Open Emergency Dialer",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (hasContact) {
                    Button(
                        onClick = { onCallContact(contactPhoneNumber) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(text = "Call $contactName", fontSize = 16.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            onOpenSms(contactPhoneNumber, alertMessage)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(text = "Open SMS to $contactName", fontSize = 16.sp)
                    }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(text = "Dismiss", fontSize = 16.sp)
                }
            }
        }
    }
}

private const val NO_RESPONSE_ESCALATION_SECONDS = 10

@Composable
private fun DisclaimerCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "About this feature",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Aasa helps you triage after a possible fall. " +
                    "It is not a medical device and does not replace emergency services. " +
                    "Aasa never calls or messages anyone automatically — you choose what to do next.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
