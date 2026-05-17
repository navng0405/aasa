package com.aasa.eldercare

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aasa.eldercare.agent.AgentOrchestrator
import com.aasa.eldercare.data.AppDatabase
import com.aasa.eldercare.data.repository.HealthSnapshotRepository
import com.aasa.eldercare.data.repository.ConversationRepository
import com.aasa.eldercare.data.repository.MedicationRepository
import com.aasa.eldercare.data.repository.MemoryRepository
import com.aasa.eldercare.data.repository.PresencePingRepository
import com.aasa.eldercare.data.repository.TrustedContactRepository
import com.aasa.eldercare.data.preferences.UserPreferences
import com.aasa.eldercare.data.seeder.DemoDataSeeder
import com.aasa.eldercare.model.GemmaRouter
import com.aasa.eldercare.model.OnDeviceGemmaRunner
import com.aasa.eldercare.model.RemoteLocalGemmaRunner
import com.aasa.eldercare.network.RetrofitClient
import com.aasa.eldercare.tools.ToolRegistry
import com.aasa.eldercare.voice.HotwordService
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
    val presencePingRepository: PresencePingRepository by lazy {
        PresencePingRepository(database.presencePingDao())
    }

    /** Phase 10: Health Connect snapshot for the Morning Briefing screen. */
    val healthSnapshotRepository: HealthSnapshotRepository by lazy {
        HealthSnapshotRepository(this)
    }

    /** Phase 11: personalization (user name, last-greeted timestamp). */
    val userPreferences: UserPreferences by lazy {
        UserPreferences(this)
    }

    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry.createDefault(
            medicationRepository = medicationRepository,
            memoryRepository = memoryRepository,
            trustedContactRepository = trustedContactRepository,
            healthSnapshotRepository = healthSnapshotRepository
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
        GemmaRouter(
            onDevice = onDeviceGemmaRunner,
            bridge = remoteGemmaRunner,
            bridgeEnabled = BuildConfig.AASA_ENABLE_GEMMA_BRIDGE
        )
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
        observeAppForegroundForHotword()
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
     * Keep the hackathon wake-word recognizer out of the foreground app.
     * Android's platform SpeechRecognizer is effectively single-owner on
     * many devices; if the hotword service keeps relistening while Home is
     * greeting or doing tap-to-talk, both sessions flap between busy/error.
     */
    private fun observeAppForegroundForHotword() {
        if (!BuildConfig.AASA_ENABLE_HOTWORD) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                runCatching { HotwordService.stop(this@AasaApplication) }
            }

            override fun onStop(owner: LifecycleOwner) {
                if (!userPreferences.hotwordEnabled || !hasMicPermission()) return
                runCatching { HotwordService.start(this@AasaApplication) }
            }
        })
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Wipe every table and reapply the demo seed. Suspends so the
     * caller can wait for completion before re-querying state.
     */
    suspend fun resetDemoData() {
        DemoDataSeeder.reset(database)
    }
}
