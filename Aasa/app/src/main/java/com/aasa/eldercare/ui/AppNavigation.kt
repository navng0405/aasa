package com.aasa.eldercare.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aasa.eldercare.ui.falltriage.FallTriageScreen
import com.aasa.eldercare.ui.home.HomeScreen
import com.aasa.eldercare.ui.medication.MedicationScreen
import com.aasa.eldercare.ui.memory.MemoryScreen
import com.aasa.eldercare.ui.mobility.MobilityShieldScreen
import com.aasa.eldercare.ui.scamshield.ScamShieldScreen
import com.aasa.eldercare.ui.trustedcircle.TrustedCircleScreen

object AasaRoutes {
    const val HOME = "home"
    const val MEDICATION = "medication"
    const val MEMORY = "memory"
    const val TRUSTED_CIRCLE = "trusted_circle"
    const val SCAM_SHIELD = "scam_shield"
    const val FALL_TRIAGE = "fall_triage"
    const val MOBILITY_SHIELD = "mobility_shield"
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
                onTrustedCircleClick = { navController.navigate(AasaRoutes.TRUSTED_CIRCLE) },
                onScamShieldClick = { navController.navigate(AasaRoutes.SCAM_SHIELD) },
                onFallTriageClick = { navController.navigate(AasaRoutes.FALL_TRIAGE) },
                onMobilityShieldClick = { navController.navigate(AasaRoutes.MOBILITY_SHIELD) }
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
        composable(AasaRoutes.SCAM_SHIELD) {
            ScamShieldScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(AasaRoutes.FALL_TRIAGE) {
            FallTriageScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(AasaRoutes.MOBILITY_SHIELD) {
            MobilityShieldScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
