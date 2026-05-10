package com.aasa.eldercare.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aasa.eldercare.agent.AgentAction
import com.google.gson.GsonBuilder

private val SAMPLE_MESSAGES = listOf(
    "I took my BP tablet.",
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
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory())
) {
    val uiState by viewModel.uiState.collectAsState()

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

            if (uiState.toolExecutionSuccess != null) {
                ToolExecutionCard(
                    success = uiState.toolExecutionSuccess == true,
                    message = uiState.toolResultMessage.orEmpty(),
                    data = uiState.toolResultData
                )
            }

            uiState.agentAction?.let { action ->
                ParsedResponseCard(action = action)
                RawResponseCard(rawResponse = action.rawResponse)
            }

            NavigationShortcuts(
                onMedicationClick = onMedicationClick,
                onMemoryClick = onMemoryClick,
                onTrustedCircleClick = onTrustedCircleClick
            )
        }
    }
}

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
            Text(
                text = "Tool execution result",
                style = MaterialTheme.typography.titleMedium,
                color = onContainer
            )
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
