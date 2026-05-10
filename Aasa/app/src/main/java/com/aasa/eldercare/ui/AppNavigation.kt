package com.aasa.eldercare.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aasa.eldercare.ui.home.HomeScreen
import com.aasa.eldercare.ui.medication.MedicationScreen
import com.aasa.eldercare.ui.memory.MemoryScreen
import com.aasa.eldercare.ui.trustedcircle.TrustedCircleScreen

object AasaRoutes {
    const val HOME = "home"
    const val MEDICATION = "medication"
    const val MEMORY = "memory"
    const val TRUSTED_CIRCLE = "trusted_circle"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AasaRoutes.HOME
    ) {
        composable(AasaRoutes.HOME) {
            HomeScreen(
                onMedicationClick = { navController.navigate(AasaRoutes.MEDICATION) },
                onMemoryClick = { navController.navigate(AasaRoutes.MEMORY) },
                onTrustedCircleClick = { navController.navigate(AasaRoutes.TRUSTED_CIRCLE) }
            )
        }
        composable(AasaRoutes.MEDICATION) {
            MedicationScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(AasaRoutes.MEMORY) {
            MemoryScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(AasaRoutes.TRUSTED_CIRCLE) {
            TrustedCircleScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
