package com.aasa.gemmabridgepoc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AasaPocScreen(
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    AasaPocContent(
        uiState = uiState,
        onServerUrlChanged = viewModel::onServerUrlChanged,
        onMessageChanged = viewModel::onMessageChanged,
        onCheckServer = viewModel::checkServer,
        onRunPrompt = viewModel::runAasaJsonPrompt,
        onFillMedication = viewModel::fillMedicationExample,
        onFillSafety = viewModel::fillSafetyExample,
        onClear = viewModel::clear
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AasaPocContent(
    uiState: AasaPocUiState,
    onServerUrlChanged: (String) -> Unit,
    onMessageChanged: (String) -> Unit,
    onCheckServer: () -> Unit,
    onRunPrompt: () -> Unit,
    onFillMedication: () -> Unit,
    onFillSafety: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Aasa Gemma 4 Bridge POC",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        OutlinedTextField(
            value = uiState.serverUrl,
            onValueChange = onServerUrlChanged,
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = uiState.message,
            onValueChange = onMessageChanged,
            label = { Text("Message") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onCheckServer,
                enabled = !uiState.isLoading
            ) {
                Text("Check Server")
            }
            Button(
                onClick = onRunPrompt,
                enabled = !uiState.isLoading
            ) {
                Text("Run Aasa JSON Prompt")
            }
            TextButton(
                onClick = onFillMedication,
                enabled = !uiState.isLoading
            ) {
                Text("Fill Medication Example")
            }
            TextButton(
                onClick = onFillSafety,
                enabled = !uiState.isLoading
            ) {
                Text("Fill Safety Example")
            }
            TextButton(
                onClick = onClear,
                enabled = !uiState.isLoading
            ) {
                Text("Clear")
            }
        }

        StatusCard(uiState = uiState)
        ParsedResultCard(result = uiState.parsedResult)
        RawResponseCard(rawResponse = uiState.rawResponse)
    }
}

@Composable
private fun StatusCard(uiState: AasaPocUiState) {
    InfoCard(title = "Status") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator()
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = uiState.status)
                uiState.error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ParsedResultCard(result: AgentMessageResponse?) {
    InfoCard(title = "Parsed Result") {
        if (result == null) {
            Text(
                text = "No parsed result yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ResultRow(label = "Intent", value = result.intent)
            ResultRow(label = "Risk level", value = result.riskLevel)
            ResultRow(label = "Tool", value = result.tool)
            ResultRow(label = "Arguments", value = result.arguments?.toString())
            ResultRow(label = "Assistant", value = result.assistantResponse)
        }
    }
}

@Composable
private fun RawResponseCard(rawResponse: String) {
    InfoCard(title = "Raw Response") {
        Text(
            text = rawResponse.ifBlank { "No raw response yet." },
            color = if (rawResponse.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ResultRow(label: String, value: String?) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value.orEmpty().ifBlank { "Not returned" },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AasaPocPreview() {
    MaterialTheme {
        AasaPocContent(
            uiState = AasaPocUiState(),
            onServerUrlChanged = {},
            onMessageChanged = {},
            onCheckServer = {},
            onRunPrompt = {},
            onFillMedication = {},
            onFillSafety = {},
            onClear = {}
        )
    }
}
