package com.aasa.eldercare.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onMedicationClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onTrustedCircleClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Aasa")
        Text(text = "Home placeholder")
        Button(onClick = onMedicationClick) {
            Text(text = "Medication")
        }
        Button(onClick = onMemoryClick) {
            Text(text = "Memory")
        }
        Button(onClick = onTrustedCircleClick) {
            Text(text = "Trusted Circle")
        }
    }
}
