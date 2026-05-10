package com.aasa.eldercare.ui.scamshield

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aasa.eldercare.AasaApplication
import com.aasa.eldercare.ui.IntentActionLauncher
import com.aasa.eldercare.ui.home.RiskCopy
import com.aasa.eldercare.ui.home.ScamRiskCopy

/**
 * Phase 8.5: Scam & Fraud Shield screen.
 *
 * Workflow:
 *  1. Elder pastes a suspicious SMS / WhatsApp / email snippet.
 *  2. Tap "Analyze Message".
 *  3. The screen calls [ScamShieldViewModel.analyzeMessage] which goes
 *     through [com.aasa.eldercare.agent.AgentOrchestrator] → Gemma 4 +
 *     [com.aasa.eldercare.tools.ScamShieldTool].
 *  4. The result card surfaces risk band, warning signals, the safe
 *     next step, the assistant's gentle explanation, and an optional
 *     "Call <trusted contact>" tap that opens the dialer (no auto-call).
 *
 * Copy is intentionally non-blaming ("This message looks suspicious"
 * rather than "you are being scammed"). Layout uses big touch targets
 * (≥48dp) and large body text for elder users.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScamShieldScreen(
    onBack: () -> Unit,
    viewModel: ScamShieldViewModel = aasaScamShieldViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Scam & Fraud Shield") },
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
        ScamShieldContent(
            innerPadding = innerPadding,
            uiState = uiState,
            onInputChange = viewModel::onInputChange,
            onAnalyze = viewModel::analyzeMessage,
            onClear = viewModel::clear,
            onUseDemo = viewModel::useDemo,
            onDismissError = viewModel::dismissError,
            onCallContact = { number ->
                IntentActionLauncher.openDialer(context, number)
            }
        )
    }
}

@Composable
private fun aasaScamShieldViewModel(): ScamShieldViewModel {
    val application = LocalContext.current.applicationContext as AasaApplication
    return viewModel(factory = ScamShieldViewModel.Factory(application))
}

@Composable
private fun ScamShieldContent(
    innerPadding: PaddingValues,
    uiState: ScamShieldUiState,
    onInputChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onClear: () -> Unit,
    onUseDemo: (String) -> Unit,
    onDismissError: () -> Unit,
    onCallContact: (String) -> Unit
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

        SuspiciousMessageInput(
            value = uiState.inputText,
            enabled = !uiState.isAnalyzing,
            onChange = onInputChange
        )

        ActionButtons(
            canAnalyze = uiState.inputText.isNotBlank() && !uiState.isAnalyzing,
            isAnalyzing = uiState.isAnalyzing,
            onAnalyze = onAnalyze,
            onUseGiftCardDemo = { onUseDemo(ScamShieldDemoMessages.GIFT_CARD) },
            onUseBankDemo = { onUseDemo(ScamShieldDemoMessages.BANK) },
            onClear = onClear
        )

        if (uiState.isAnalyzing) {
            AnalyzingRow()
        }

        uiState.errorMessage?.let { message ->
            ErrorCard(message = message, onDismiss = onDismissError)
        }

        if (uiState.hasResult) {
            ScamResultCard(
                uiState = uiState,
                onCallContact = {
                    uiState.phoneNumber?.let(onCallContact)
                },
                onClear = onClear
            )
        }
    }
}

// ---------------------------------------------------------------------
// Intro / safety copy
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
                text = "Pause before you reply",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "Paste a suspicious message. Aasa will explain warning signs " +
                    "and suggest a safe next step.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "Aasa will not contact anyone automatically. You choose what to do next.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

// ---------------------------------------------------------------------
// Input field + buttons
// ---------------------------------------------------------------------

@Composable
private fun SuspiciousMessageInput(
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = "Suspicious message", fontSize = 16.sp) },
        placeholder = {
            Text(
                text = "Paste the SMS, WhatsApp, email, or voicemail text here.",
                fontSize = 16.sp
            )
        },
        enabled = enabled,
        minLines = 5,
        maxLines = 10,
        textStyle = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun ActionButtons(
    canAnalyze: Boolean,
    isAnalyzing: Boolean,
    onAnalyze: () -> Unit,
    onUseGiftCardDemo: () -> Unit,
    onUseBankDemo: () -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onAnalyze,
            enabled = canAnalyze,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text(
                text = if (isAnalyzing) "Analyzing..." else "Analyze Message",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        OutlinedButton(
            onClick = onUseGiftCardDemo,
            enabled = !isAnalyzing,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(text = "Use Gift Card Scam Demo", fontSize = 16.sp)
        }
        OutlinedButton(
            onClick = onUseBankDemo,
            enabled = !isAnalyzing,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(text = "Use Bank Scam Demo", fontSize = 16.sp)
        }
        OutlinedButton(
            onClick = onClear,
            enabled = !isAnalyzing,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(text = "Clear", fontSize = 16.sp)
        }
    }
}

@Composable
private fun AnalyzingRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(
            text = "Aasa is checking this message...",
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Could not check this message",
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

// ---------------------------------------------------------------------
// Result card
// ---------------------------------------------------------------------

@Composable
private fun ScamResultCard(
    uiState: ScamShieldUiState,
    onCallContact: () -> Unit,
    onClear: () -> Unit
) {
    val riskCopy: ScamRiskCopy = uiState.riskCopy ?: ScamRiskCopy.fromRaw("LOW")
    val container = when (riskCopy.level) {
        RiskCopy.RiskLevel.HIGH -> MaterialTheme.colorScheme.errorContainer
        RiskCopy.RiskLevel.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer
        RiskCopy.RiskLevel.LOW -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (riskCopy.level) {
        RiskCopy.RiskLevel.HIGH -> MaterialTheme.colorScheme.onErrorContainer
        RiskCopy.RiskLevel.MEDIUM -> MaterialTheme.colorScheme.onTertiaryContainer
        RiskCopy.RiskLevel.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
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
                text = "Scam Risk: ${riskCopy.level.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onContainer
            )
            Text(
                text = riskCopy.label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = onContainer
            )
            Text(
                text = riskCopy.explanation,
                style = MaterialTheme.typography.bodyLarge,
                color = onContainer
            )

            if (uiState.scamSignals.isNotEmpty()) {
                ResultSection(
                    title = "Why it may not be safe",
                    onContainerColor = onContainer
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    uiState.scamSignals.forEach { signal ->
                        Text(
                            text = "• $signal",
                            style = MaterialTheme.typography.bodyLarge,
                            color = onContainer
                        )
                    }
                }
            }

            uiState.safeAction?.takeIf { it.isNotBlank() }?.let { action ->
                ResultSection(
                    title = "Safe next step",
                    onContainerColor = onContainer
                )
                Text(
                    text = action,
                    style = MaterialTheme.typography.bodyLarge,
                    color = onContainer
                )
            }

            uiState.assistantExplanation?.takeIf { it.isNotBlank() }?.let { explanation ->
                ResultSection(
                    title = "Aasa says",
                    onContainerColor = onContainer
                )
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = onContainer
                )
            }

            if (uiState.canCallContact) {
                Button(
                    onClick = onCallContact,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                ) {
                    Text(
                        text = "Call ${uiState.contactName}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            OutlinedButton(
                onClick = onClear,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(text = "Clear", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ResultSection(title: String, onContainerColor: androidx.compose.ui.graphics.Color) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = onContainerColor
    )
}
