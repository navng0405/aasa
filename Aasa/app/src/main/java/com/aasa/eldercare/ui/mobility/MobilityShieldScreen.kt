package com.aasa.eldercare.ui.mobility

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.sensors.MobilityFeatures
import com.aasa.eldercare.ui.IntentActionLauncher

/**
 * Phase 8.7: Mobility Shield screen.
 *
 * Layout (top → bottom):
 *   1. Intro / safety copy ("This is not a medical diagnosis").
 *   2. Status card (recording state, countdown, sensor availability).
 *   3. Controls: Start 10-second check, Simulate Stable Walk,
 *      Simulate Unsteady Walk, Reset.
 *   4. Analyzing row while Gemma round-trips.
 *   5. Result card with confidence score, stability label, risk band,
 *      feature summary, Aasa explanation, and action buttons.
 *
 * IMPORTANT — every label and message intentionally avoids medical
 * diagnosis wording. The screen never claims to detect Parkinson's,
 * dementia, stroke, or any neurological disease. Action buttons only
 * open the system dialer or SMS app — Aasa never auto-calls or auto-
 * sends a message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobilityShieldScreen(
    onBack: () -> Unit,
    viewModel: MobilityShieldViewModel = aasaMobilityShieldViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Mobility Shield") },
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
        MobilityShieldContent(
            innerPadding = innerPadding,
            uiState = uiState,
            onStartCheck = viewModel::startCheck,
            onSimulateStable = viewModel::simulateStableWalk,
            onSimulateUnsteady = viewModel::simulateUnsteadyWalk,
            onReset = viewModel::reset,
            onDismissError = viewModel::dismissError,
            onCallContact = { number ->
                IntentActionLauncher.openDialer(context, number)
            },
            onAlertContact = { number, body ->
                IntentActionLauncher.openSms(context, number, body)
            }
        )
    }
}

@Composable
private fun aasaMobilityShieldViewModel(): MobilityShieldViewModel {
    val application = LocalContext.current.applicationContext as AasaApplication
    return viewModel(factory = MobilityShieldViewModel.Factory(application))
}

@Composable
private fun MobilityShieldContent(
    innerPadding: PaddingValues,
    uiState: MobilityShieldUiState,
    onStartCheck: () -> Unit,
    onSimulateStable: () -> Unit,
    onSimulateUnsteady: () -> Unit,
    onReset: () -> Unit,
    onDismissError: () -> Unit,
    onCallContact: (String) -> Unit,
    onAlertContact: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IntroCopyCard()
        SafetyDisclaimerCard()

        StatusCard(uiState = uiState)

        ControlsCard(
            uiState = uiState,
            onStartCheck = onStartCheck,
            onSimulateStable = onSimulateStable,
            onSimulateUnsteady = onSimulateUnsteady,
            onReset = onReset
        )

        if (uiState.isAnalyzing) {
            AnalyzingRow()
        }

        uiState.errorMessage?.let { msg ->
            ErrorCard(message = msg, onDismiss = onDismissError)
        }
        uiState.sensorErrorMessage?.let { msg ->
            SensorWarningCard(message = msg)
        }

        if (uiState.showResultCard) {
            MobilityResultCard(
                uiState = uiState,
                onCallContact = onCallContact,
                onAlertContact = onAlertContact,
                onDismiss = onReset
            )
        }
    }
}

// ---------------------------------------------------------------------
// Intro + safety copy
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
                text = "10-second walk check",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "Do a short 10-second walk check. Aasa will explain mobility " +
                    "confidence in simple words.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "Aasa tracks mobility confidence and notices unusual instability " +
                    "patterns. It does not diagnose disease.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun SafetyDisclaimerCard() {
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
                text = "This is not a medical diagnosis. Aasa only looks for simple " +
                    "movement changes during this short check. It will not contact " +
                    "anyone automatically — you choose what to do next.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------
// Status + controls
// ---------------------------------------------------------------------

@Composable
private fun StatusCard(uiState: MobilityShieldUiState) {
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
                    color = if (uiState.isRecording) {
                        MaterialTheme.colorScheme.tertiary
                    } else if (uiState.isAnalyzing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
                Text(
                    text = uiState.statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (uiState.isRecording) {
                Text(
                    text = "Walking… ${uiState.secondsRemaining}s remaining",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            StatusLine(
                label = "Sensor",
                value = if (uiState.sensorAvailable) {
                    if (uiState.hasGyroscope) {
                        "Accelerometer + gyroscope"
                    } else {
                        "Accelerometer"
                    }
                } else {
                    "Unavailable"
                }
            )
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
    uiState: MobilityShieldUiState,
    onStartCheck: () -> Unit,
    onSimulateStable: () -> Unit,
    onSimulateUnsteady: () -> Unit,
    onReset: () -> Unit
) {
    val busy = uiState.isBusy
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
                onClick = onStartCheck,
                enabled = !busy && uiState.sensorAvailable,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                Text(
                    text = "Start 10-second check",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            OutlinedButton(
                onClick = onSimulateStable,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(text = "Simulate Stable Walk", fontSize = 16.sp)
            }
            OutlinedButton(
                onClick = onSimulateUnsteady,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(text = "Simulate Unsteady Walk", fontSize = 16.sp)
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
private fun AnalyzingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(
            text = "Aasa is reviewing your walk…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Could not run mobility check",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
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
// Result card
// ---------------------------------------------------------------------

@Composable
private fun MobilityResultCard(
    uiState: MobilityShieldUiState,
    onCallContact: (String) -> Unit,
    onAlertContact: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val copy = MobilityResultCopy.forRisk(uiState.riskLevel)
    val container = when (copy.severity) {
        MobilityResultCopy.Severity.HIGH -> MaterialTheme.colorScheme.errorContainer
        MobilityResultCopy.Severity.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer
        MobilityResultCopy.Severity.LOW -> MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = when (copy.severity) {
        MobilityResultCopy.Severity.HIGH -> MaterialTheme.colorScheme.onErrorContainer
        MobilityResultCopy.Severity.MEDIUM -> MaterialTheme.colorScheme.onTertiaryContainer
        MobilityResultCopy.Severity.LOW -> MaterialTheme.colorScheme.onPrimaryContainer
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
                text = "Mobility Confidence",
                style = MaterialTheme.typography.labelLarge,
                color = onContainer
            )
            Text(
                text = "${uiState.confidenceScore ?: "—"} / 100",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = onContainer
            )
            Text(
                text = uiState.stabilityLabel ?: copy.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = onContainer
            )
            uiState.riskLevel?.takeIf { it.isNotBlank() }?.let { risk ->
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

            uiState.features?.let { f ->
                FeatureSummaryBlock(features = f, onContainer = onContainer)
            }

            uiState.assistantResponse?.takeIf { it.isNotBlank() }?.let { msg ->
                ResultBlock(label = "Aasa says", value = msg, onContainer = onContainer)
            }
            uiState.toolMessage?.takeIf {
                it.isNotBlank() && it != uiState.assistantResponse
            }?.let { msg ->
                ResultBlock(label = "Mobility summary", value = msg, onContainer = onContainer)
            }
            uiState.recommendedAction?.takeIf { it.isNotBlank() }?.let { msg ->
                ResultBlock(
                    label = "Suggested next step",
                    value = msg,
                    onContainer = onContainer
                )
            }

            ResultActionButtons(
                severity = copy.severity,
                contactName = uiState.contactName,
                phoneNumber = uiState.phoneNumber,
                alertMessage = uiState.alertMessage.orEmpty(),
                onCallContact = onCallContact,
                onAlertContact = onAlertContact,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun FeatureSummaryBlock(
    features: MobilityFeatures,
    onContainer: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Sensor feature summary",
            style = MaterialTheme.typography.labelMedium,
            color = onContainer
        )
        FeatureRow(
            label = "Average acceleration",
            value = "${features.averageAcceleration} m/s²",
            onContainer = onContainer
        )
        FeatureRow(
            label = "Acceleration variance",
            value = features.accelerationVariance.toString(),
            onContainer = onContainer
        )
        FeatureRow(
            label = "Peak acceleration",
            value = "${features.peakAcceleration} m/s²",
            onContainer = onContainer
        )
        FeatureRow(
            label = "Side-to-side sway",
            value = features.sideToSideSwayScore.toString(),
            onContainer = onContainer
        )
        FeatureRow(
            label = "Abrupt pauses",
            value = features.abruptPauses.toString(),
            onContainer = onContainer
        )
        FeatureRow(
            label = "Smoothness score",
            value = "${features.smoothnessScore} / 100",
            onContainer = onContainer
        )
    }
}

@Composable
private fun FeatureRow(
    label: String,
    value: String,
    onContainer: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ResultBlock(label: String, value: String, onContainer: Color) {
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
private fun ResultActionButtons(
    severity: MobilityResultCopy.Severity,
    contactName: String?,
    phoneNumber: String?,
    alertMessage: String,
    onCallContact: (String) -> Unit,
    onAlertContact: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val hasContact = !contactName.isNullOrBlank() && !phoneNumber.isNullOrBlank()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (severity != MobilityResultCopy.Severity.LOW && hasContact) {
            Button(
                onClick = {
                    phoneNumber?.let { onAlertContact(it, alertMessage) }
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                Text(
                    text = "Alert $contactName",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            OutlinedButton(
                onClick = {
                    phoneNumber?.let(onCallContact)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(text = "Call $contactName", fontSize = 16.sp)
            }
        }
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                text = if (severity == MobilityResultCopy.Severity.LOW) "Done" else "Dismiss",
                fontSize = 16.sp
            )
        }
    }
}
