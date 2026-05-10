package com.aasa.eldercare

import android.app.Application
import com.aasa.eldercare.agent.AgentOrchestrator
import com.aasa.eldercare.data.AppDatabase
import com.aasa.eldercare.data.repository.ConversationRepository
import com.aasa.eldercare.data.repository.MedicationRepository
import com.aasa.eldercare.data.repository.MemoryRepository
import com.aasa.eldercare.data.repository.TrustedContactRepository
import com.aasa.eldercare.data.seeder.DemoDataSeeder
import com.aasa.eldercare.model.RemoteLocalGemmaRunner
import com.aasa.eldercare.network.RetrofitClient
import com.aasa.eldercare.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide service locator. Pre-DI scaffolding: every long-lived
 * dependency (database, repositories, orchestrator) hangs off the
 * Application instance and is created lazily.
 *
 * The Application class is registered in `AndroidManifest.xml` via
 * `android:name=".AasaApplication"`.
 */
class AasaApplication : Application() {

    private val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val medicationRepository: MedicationRepository by lazy {
        MedicationRepository(database.medicationDao())
    }
    val memoryRepository: MemoryRepository by lazy {
        MemoryRepository(database.memoryDao())
    }
    val trustedContactRepository: TrustedContactRepository by lazy {
        TrustedContactRepository(database.trustedContactDao())
    }
    val conversationRepository: ConversationRepository by lazy {
        ConversationRepository(database.conversationDao())
    }

    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry.createDefault(
            medicationRepository = medicationRepository,
            memoryRepository = memoryRepository,
            trustedContactRepository = trustedContactRepository
        )
    }

    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            modelRunner = RemoteLocalGemmaRunner(RetrofitClient.apiService),
            toolRegistry = toolRegistry,
            conversationRepository = conversationRepository
        )
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            DemoDataSeeder.seed(database)
        }
    }

    /**
     * Wipe every table and reapply the demo seed. Suspends so the
     * caller can wait for completion before re-querying state.
     */
    suspend fun resetDemoData() {
        DemoDataSeeder.reset(database)
    }
}
