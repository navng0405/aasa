package com.aasa.eldercare.model

import android.content.Context
import android.util.Log
import com.aasa.eldercare.network.AgentMessageResponse
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 9: on-device Gemma 4 E2B via LiteRT-LM.
 *
 * The `.litertlm` file is **not** bundled in the APK (it is 2.4 GB and license-gated).
 * Side-load it once per device via:
 *   adb shell mkdir -p /sdcard/Android/data/com.aasa.eldercare/files/models
 *   adb push gemma-4-E2B-it.litertlm \
 *     /sdcard/Android/data/com.aasa.eldercare/files/models/gemma-4-E2B-it.litertlm
 *
 * Lifecycle:
 *   - [isAvailable] checks the file is present and lazily initializes the [Engine]
 *     on a background thread (LiteRT-LM docs warn this can take up to 10 s).
 *   - One long-lived [Engine] per process; one long-lived `Conversation` reused
 *     across turns so the KV-cache stays warm.
 *   - [release] is called from `AasaApplication.onTerminate` (best-effort; Android
 *     rarely calls onTerminate on a real device, but the OS reclaims native memory
 *     when the process dies).
 */
class OnDeviceGemmaRunner(
    private val appContext: Context
) : ModelRunner {

    override val label: String = "On-device · Gemma 4 E2B"

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    @Volatile private var initFailed: Boolean = false
    @Volatile private var initFailReason: String? = null

    /** Serialize generation. LiteRT-LM is not thread-safe for parallel decodes. */
    private val mutex: Mutex = Mutex()

    /**
     * Absolute path to the side-loaded model. We resolve through
     * [Context.getExternalFilesDir] so this works whether the directory has been
     * created by the app yet or pre-created via `adb shell mkdir`.
     */
    private val modelsDir: File by lazy {
        File(appContext.getExternalFilesDir(null), "models")
    }

    private val expectedModelFile: File by lazy {
        File(modelsDir, MODEL_FILENAME)
    }

    @Volatile private var selectedModelFile: File? = null

    /** Public — read by the UI to explain why on-device is or isn't active. */
    val statusReason: String?
        get() = when {
            initFailed -> initFailReason ?: "engine initialization failed"
            resolveModelFile() == null -> "model file not found at ${expectedModelFile.absolutePath}"
            engine == null -> "engine not initialized yet"
            else -> null
        }

    val expectedModelPath: String
        get() = expectedModelFile.absolutePath

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (initFailed) return@withContext false
        if (resolveModelFile() == null) {
            modelsDir.mkdirs()
            Log.w(
                TAG,
                "Gemma 4 model not found. Expected $expectedModelFile or any .litertlm in ${modelsDir.absolutePath}"
            )
            return@withContext false
        }
        ensureEngine()
        engine != null
    }

    override suspend fun sendMessage(
        message: String,
        recentContext: String
    ): AgentMessageResponse =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val convo = conversation
                    ?: throw IllegalStateException(
                        "OnDeviceGemmaRunner.sendMessage called before isAvailable() succeeded"
                    )

                val prompt = OnDevicePromptBuilder.build(
                    userMessage = message,
                    recentContext = recentContext
                )

                val raw = StringBuilder()
                convo.sendMessageAsync(prompt).collect { chunk ->
                    raw.append(chunk.toString())
                }

                val rawText = raw.toString()
                Log.d(TAG, "Gemma 4 raw output: ${rawText.take(400)}")
                OnDevicePromptBuilder.parse(rawText)
            }
        }

    /**
     * Eagerly initialize the engine. Safe to call from `AasaApplication.onCreate`
     * on a background coroutine so the first user turn doesn't pay the full
     * 4–10 second cold-start.
     */
    suspend fun preWarm() {
        isAvailable()
    }

    /** Best-effort teardown. */
    fun release() {
        try {
            conversation?.close()
        } catch (_: Throwable) { /* ignore */ }
        try {
            engine?.close()
        } catch (_: Throwable) { /* ignore */ }
        conversation = null
        engine = null
    }

    // ---------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------

    private fun ensureEngine() {
        if (engine != null || initFailed) return
        synchronized(this) {
            if (engine != null || initFailed) return
            try {
                Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
                val modelFile = resolveModelFile()
                    ?: throw IllegalStateException(
                        "Model file missing. Push $MODEL_FILENAME to ${modelsDir.absolutePath}"
                    )
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    // Pick a writable cache dir so 2nd-load is faster.
                    cacheDir = appContext.cacheDir.absolutePath
                )
                val newEngine = Engine(config)
                newEngine.initialize()
                conversation = newEngine.createConversation()
                engine = newEngine
                Log.i(TAG, "LiteRT-LM engine initialized from ${modelFile.absolutePath}")
            } catch (t: Throwable) {
                initFailed = true
                initFailReason = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "LiteRT-LM engine init failed", t)
                try { engine?.close() } catch (_: Throwable) { /* ignore */ }
                engine = null
                conversation = null
            }
        }
    }

    companion object {
        private const val TAG = "OnDeviceGemmaRunner"

        /** Exact filename pushed via `adb push`. Do not rename without updating docs. */
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
    }

    private fun resolveModelFile(): File? {
        selectedModelFile?.takeIf { it.exists() }?.let { return it }
        expectedModelFile.takeIf { it.exists() }?.let {
            selectedModelFile = it
            return it
        }
        val discovered = modelsDir
            .listFiles { file -> file.isFile && file.extension.equals("litertlm", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.firstOrNull()

        if (discovered != null) {
            selectedModelFile = discovered
            Log.i(TAG, "Using discovered LiteRT-LM model ${discovered.absolutePath}")
        }
        return discovered
    }
}
