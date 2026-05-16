package com.aasa.eldercare

import android.app.Application
import com.aasa.eldercare.agent.AgentOrchestrator
import com.aasa.eldercare.data.AppDatabase
import com.aasa.eldercare.data.repository.ConversationRepository
import com.aasa.eldercare.data.repository.MedicationRepository
import com.aasa.eldercare.data.repository.MemoryRepository
import com.aasa.eldercare.data.repository.TrustedContactRepository
import com.aasa.eldercare.data.seeder.DemoDataSeeder
import com.aasa.eldercare.model.GemmaRouter
import com.aasa.eldercare.model.OnDeviceGemmaRunner
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

    /** Phase 9: on-device Gemma 4 via LiteRT-LM. Side-loaded model file. */
    val onDeviceGemmaRunner: OnDeviceGemmaRunner by lazy {
        OnDeviceGemmaRunner(appContext = this)
    }

    /** Mac bridge — kept as a developer fallback. */
    val remoteGemmaRunner: RemoteLocalGemmaRunner by lazy {
        RemoteLocalGemmaRunner(RetrofitClient.apiService)
    }

    /**
     * Picks per turn between on-device (default) and bridge (fallback).
     * Implements [com.aasa.eldercare.model.ModelRunner] so the orchestrator is unchanged.
     */
    val gemmaRouter: GemmaRouter by lazy {
        GemmaRouter(onDevice = onDeviceGemmaRunner, bridge = remoteGemmaRunner)
    }

    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            modelRunner = gemmaRouter,
            toolRegistry = toolRegistry,
            conversationRepository = conversationRepository
        )
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            DemoDataSeeder.seed(database)
        }
        // Pre-warm the on-device engine off the main thread. Cold-start can be
        // up to ~10 s on Pixel 4a — doing it eagerly hides that from the first turn.
        applicationScope.launch {
            try {
                onDeviceGemmaRunner.preWarm()
            } catch (_: Throwable) {
                // Pre-warm is best-effort; router will retry on first real turn.
            }
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
