package com.aasa.eldercare.ui.briefing

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aasa.eldercare.AasaApplication

/**
 * Phase 10 — Health Briefing screen.
 *
 * Layout, top → bottom:
 *   1. Intro / privacy copy ("On-device only, no cloud").
 *   2. Wearable status pill — mandatory "Demo data — no wearable
 *      connected" when [HealthBriefingUiState.isMockData] is true.
 *   3. Request Health Connect permissions button (only shown when
 *      mock data is active AND the device has Health Connect).
 *   4. Generate Briefing button.
 *   5. Loading row OR briefing card with highlights + paragraph.
 *
 * IMPORTANT — no medical claims. Copy is gentle, informational only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthBriefingScreen(
    onBack: () -> Unit,
    viewModel: HealthBriefingViewModel = healthBriefingViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as AasaApplication

    val permissions = remember { app.healthSnapshotRepository.requiredPermissions }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
        onResult = { _ -> viewModel.generateBriefing() }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Morning Briefing") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IntroCard()

            WearableStatusPill(
                isMockData = uiState.isMockData,
                snapshotSource = uiState.snapshotSource
            )

            if (uiState.isMockData && isHealthConnectAvailable(context)) {
                ConnectWearableCard(
                    onConnect = {
                        permissionLauncher.launch(permissions)
                    }
                )
            }

            Button(
                onClick = viewModel::generateBriefing,
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (uiState.briefingText == null) "Get my briefing"
                    else "Refresh briefing"
                )
            }

            if (uiState.isLoading) {
                LoadingRow()
            }

            uiState.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            uiState.briefingText?.let { briefing ->
                BriefingCard(
                    briefing = briefing,
                    highlights = uiState.highlights,
                    isMockData = uiState.isMockData
                )
            }

            DisclaimerCard()
        }
    }
}

@Composable
private fun IntroCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Your morning, in plain words.",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
            Text(
                text = "Aasa reads your last 24 hours from Health Connect — " +
                    "sleep, heart rate, and steps — and gives a gentle " +
                    "summary. Everything stays on this phone."
            )
        }
    }
}

@Composable
private fun WearableStatusPill(
    isMockData: Boolean,
    snapshotSource: String?
) {
    val bg = if (isMockData) Color(0xFFFFF3CD) else Color(0xFFE6F4EA)
    val fg = if (isMockData) Color(0xFF8A6D00) else Color(0xFF1B5E20)
    val label = if (isMockData)
        "Demo data — no wearable connected"
    else
        "Live data from ${snapshotSource ?: "Health Connect"}"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ConnectWearableCard(onConnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Connect a wearable",
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Aasa can read sleep, heart rate, and steps from Health Connect — " +
                    "Fitbit Air, Pixel Watch, Galaxy Watch, and others all write here. " +
                    "Read-only, on-device."
            )
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant Health Connect access")
            }
        }
    }
}

@Composable
private fun BriefingCard(
    briefing: String,
    highlights: List<String>,
    isMockData: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isMockData) Color(0xFFFFFDE7) else Color(0xFFE3F2FD)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Your briefing",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
            highlights.forEach { line ->
                Text(text = "• $line")
            }
            if (highlights.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x11000000))
                        .padding(1.dp)
                )
            }
            Text(text = briefing, fontSize = 16.sp)
        }
    }
}

@Composable
private fun DisclaimerCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
    ) {
        Text(
            text = "This is a friendly summary, not a medical opinion. " +
                "If anything feels wrong, please call Priya or your doctor.",
            modifier = Modifier.padding(16.dp),
            fontSize = 14.sp,
            color = Color(0xFF555555)
        )
    }
}

@Composable
private fun LoadingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(text = "Reading your last 24 hours…")
    }
}

@Composable
private fun healthBriefingViewModel(): HealthBriefingViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as AasaApplication
    return viewModel(factory = HealthBriefingViewModel.factory(app))
}

private fun isHealthConnectAvailable(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return try {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    } catch (_: Throwable) {
        false
    }
}
