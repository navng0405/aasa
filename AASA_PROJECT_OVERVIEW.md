# Aasa — Gemma 4 Safety Agent for Independent Elders

> **For a fresh AI assistant**: read this file end-to-end before answering any question about the codebase. It is the single source of truth for *what Aasa is, why it exists, how it is structured, and which decisions are deliberate vs. hackathon-grade*. Don't reinvent design choices that this file already records — call them out instead.

---

## 1. One-line description

Aasa is an on-device, voice-first, Gemma-4-powered safety companion for elders living alone. It listens (text or voice), classifies the request through a small local LLM, runs a deterministic "safety override" on the user's words *before* dispatching, executes the matching local tool against an on-device Room database, and surfaces a *deferred-confirmation* action card the elder must explicitly tap before anything real happens (dialer, SMS, alert).

## 2. Why this app exists

The vision is "a calm, privacy-respecting agent that helps an aging parent stay independent without a caregiver in the room."

Specific problems the app addresses, in priority order:

1. **Fall detection + triage** — a brief accelerometer-based fall heuristic, then a *spoken* triage check ("Are you okay?") whose response is classified into FALSE_ALARM / NON_EMERGENCY_INJURY / URGENT_RISK / NO_RESPONSE, with the elder still in control of whether to dial.
2. **Medication discipline** — log "I took my BP tablet", check "Did I take my medicine today?", and answer truthfully from a real Room database — not from the LLM's hallucinated memory.
3. **Memory** — capture biographical facts ("My granddaughter Ananya's birthday is May 12.") so the agent can recall them later.
4. **Scam & Fraud Shield** — paste a suspicious SMS / WhatsApp / voicemail-transcript and get a gentle, non-condescending explanation of warning signals (gift-card requests, OTP asks, urgent-money asks) plus a safe next step.
5. **Trusted Circle** — one primary contact (default seeded as "Priya"). Aasa offers to alert this person on MEDIUM-risk situations and emergency dialer + alert on HIGH-risk.
6. **Mobility Shield** — a 10-second accelerometer-based walk check that produces a friendly "mobility confidence" score, *explicitly not a medical diagnosis*.
7. **Local-first privacy** — all persistence is on-device Room. The LLM runs **on the phone itself** via LiteRT-LM (Gemma 4 E2B int4, `gemma-4-E2B-it.litertlm`, ~2.4 GB). The Mac-side Ollama bridge is retained only as a developer fallback. No cloud LLM is ever called.

## 3. Hard rules / non-negotiables

These are baked into the code and prompt; do **not** suggest changes that violate them without checking with the user first.

- **No medical diagnosis.** Tools and prompts are explicit about not naming conditions (Parkinson's, dementia, stroke, etc.). They describe behavior ("your walk looked a little less steady than usual"), not pathology.
- **No auto-dial / no auto-SMS.** Phone actions go through `Intent.ACTION_DIAL` (never `ACTION_CALL`) so the `CALL_PHONE` permission is *not* requested. SMS uses `ACTION_SENDTO` with `smsto:` so the elder still has to press send.
- **No background sensing.** Both `FallDetectionManager` and `MobilitySensorManager` only run while their screen is foregrounded; the ViewModel calls `stop()` in `onCleared`. There is no `WorkManager` job, no foreground service, no boot-time start.
- **No cloud LLM.** Default inference path is **on-device only** (LiteRT-LM + `gemma-4-E2B-it.litertlm`). The optional Mac bridge at `http://127.0.0.1:8000/agent/message` is a **dev fallback only** and must be explicitly enabled at build time.
- **Deferred confirmation, always.** Tools never act — they return a `ToolResult` whose `data["actionType"]` tells the UI to render an action card with explicit elder-controlled buttons.
- **Deterministic safety wins.** If the on-device keyword scanner says "this is HIGH risk" and Gemma says LOW, the device **escalates** to HIGH. We never downgrade.

## 4. Repository layout (workspace root)

```
aasa/
├── Aasa/                    # Android app (Kotlin / Compose / Material 3)
├── AasaGemmaBridgePoc/      # Older standalone Android POC (legacy; Phase 0.5)
├── aasa-gemma-server/       # Mac-side FastAPI ↔ Ollama bridge (Python)
└── AASA_PROJECT_OVERVIEW.md # this file
```

The active product is `Aasa/` + `aasa-gemma-server/`. `AasaGemmaBridgePoc/` predates Phase 1 and is not part of the build pipeline.

## 5. Component map

```
                        ┌─────────────────────────────┐
                        │  Mac (developer machine)    │
                        │  ┌─────────────────────┐    │
                        │  │  Ollama (gemma4:e2b)│    │
                        │  └────────▲────────────┘    │
                        │           │                 │
                        │  ┌────────┴────────────┐    │
                        │  │  FastAPI bridge     │    │
                        │  │  aasa-gemma-server  │    │
                        │  │  POST /agent/message│    │
                        │  └────────▲────────────┘    │
                        └───────────┼─────────────────┘
                                    │ adb reverse tcp:8000
                                    │
                        ┌───────────┴─────────────────┐
                        │  Pixel 4a (Android app)     │
                        │                             │
                        │  HomeScreen ──► HomeVM      │
                        │   ▲   ▲          │          │
                        │   │   │          ▼          │
                        │   │   │   AgentOrchestrator │
                        │   │   │     │       │       │
                        │   │   │     ▼       ▼       │
                        │   │   │  ToolRegistry       │
                        │   │   │   (9 AgentTools)    │
                        │   │   │     │               │
                        │   │   │     ▼               │
                        │   │   │  Repositories       │
                        │   │   │     │               │
                        │   │   │     ▼               │
                        │   │   │  Room (aasa.db)     │
                        │   │   │                     │
                        │   │   └── STT / TTS         │
                        │   │      (foreground)       │
                        │   │                         │
                        │   └── Sensors (Fall, Walk)  │
                        │       (foreground only)     │
                        └─────────────────────────────┘
```

## 6. Phase history (chronological)

The codebase carries phase markers in comments and commit messages. Reading the phases in order is the fastest way to understand why a piece of code looks the way it does.

| Phase | What landed | Key artifacts |
|------:|---|---|
| 0.5 | Initial Bridge POC | `AasaGemmaBridgePoc/` (legacy, not in active app) |
| 1   | Android skeleton (Compose, Material 3, Nav) | `Aasa/` package layout, Home placeholder |
| 2   | Connect HomeScreen to FastAPI bridge | `RetrofitClient`, `ApiService`, `RemoteLocalGemmaRunner`, `ApiModels` |
| 3   | Agentic dispatch | `AgentOrchestrator`, `ToolRegistry`, 6 mock tools, deferred-confirmation seed |
| 3.5 | Deterministic safety override | `SafetyKeywords` (HIGH/MEDIUM phrases), orchestrator override |
| 4   | Real persistence | Room: 5 entities, 4 DAOs, 4 repositories; `AasaApplication` service locator; `DemoDataSeeder` |
| 4.x | Intent overrides for medication + memory | `IntentKeywords` (medication-check, medication-log, memory-save) |
| 5   | Per-feature screens | `MedicationScreen`, `MemoryScreen`, `TrustedCircleScreen` + their VMs/UiStates |
| 6   | Voice-first | `SpeechToTextManager`, `TextToSpeechManager`, mic permission, `RECORD_AUDIO` |
| 7   | Deferred-confirmation action cards | `ContactActionCard`, `SafetyActionCard`, `EmergencyActionCard`, `IntentActionLauncher` |
| 8   | Local Gemma status card + demo affordances | `GemmaConnectionState` + health probe; `DemoScenarios`; "Reset demo data" |
| 8.5 | Scam & Fraud Shield | `ScamShieldTool`, `ScamKeywords`, `ScamShieldScreen` |
| 8.6 | Fall Triage | `FallDetectionManager`, `FallTriageTool`, `FallTriageKeywords`, `FallTriageScreen` |
| 8.7 | Mobility Shield | `MobilitySensorManager`, `MobilityFeatureExtractor`, `MobilityShieldTool`, `MobilityShieldScreen` |
| 9   | **On-device Gemma 4 via LiteRT-LM** | `model/GemmaRunner`, `OnDeviceGemmaRunner`, `RemoteGemmaRunner`, `GemmaRouter`, `OnDevicePromptBuilder`. Bridge demoted to dev fallback. |

## 7. The agent flow (one user turn end-to-end)

`HomeViewModel.sendMessage(text)` calls `AgentOrchestrator.handleUserMessage(text)`:

1. **Persist** the user message into `conversations` table (role=`USER`).
2. **Call** the FastAPI bridge → Ollama → Gemma → JSON.
3. **Map** the network DTO (`AgentMessageResponse`) into a domain `AgentAction` with `Map<String, Any?>` arguments (`AgentModels.kt:toAgentAction`).
4. **Run deterministic overrides** on the *raw user message* (in priority order — see §8). The override may rewrite `intent`, `tool`, `riskLevel`, `assistantResponse`, and inject extra `arguments`.
5. **Dispatch** through `ToolRegistry.execute(action)` to the matching `AgentTool`. Tools may read/write Room.
6. **If** the tool returned a non-blank `ToolResult.message`, **overwrite** `assistantResponse` with that message so the spoken / displayed reply matches what actually happened (e.g. "Today's medication status: BP tablet → taken.").
7. **Persist** the assistant turn into `conversations` (role=`ASSISTANT`, with `intent` / `riskLevel` / `tool`).
8. **Return** an `AgentExecutionResult(action, toolResult)` to `HomeViewModel`.
9. `HomeViewModel` parses `toolResult.data["actionType"]` into a `PendingAction` payload, updates `HomeUiState`, and triggers TTS for the spoken reply.

## 8. Deterministic overrides (most distinctive design choice)

Lives in `AgentOrchestrator.applyDeterministicOverrides(userMessage, action)`. **Order matters**:

```
0a. Mobility-check synthetic prompt  → MobilityShieldTool / MOBILITY_CHECK
0.  Fall-triage synthetic prompt     → FallTriageTool / FALL_TRIAGE
1.  HIGH safety phrase               → SafetyTool / HIGH
1.  MEDIUM safety phrase             → SafetyTool / MEDIUM
1b. Scam analysis trigger            → ScamShieldTool / ANALYZE_SCAM
2.  Medication check query           → MedicationTool / CHECK_MEDICATION
2.  Medication log statement         → MedicationTool / LOG_MEDICATION
3.  Memory save statement            → MemoryTool / SAVE_MEMORY
else                                 → trust Gemma (only `arguments` enriched with `userMessage`)
```

Every match also injects `arguments["userMessage"] = <original message>` so downstream tools can re-scan it.

The trigger lists live as **plain substring matches on a lowercased view** of the user's text:

- `tools/SafetyKeywords.kt` — HIGH (`chest pain`, `cannot breathe`, `fell down`, `fainted`, `severe weakness`) + MEDIUM (`feel weak`, `dizzy`, `missed my medicine`, …)
- `tools/IntentKeywords.kt` — medication nouns + check triggers + log triggers + memory-save triggers + mobility-check triggers + memory-type derivation
- `tools/ScamKeywords.kt` — gift-card / wire-transfer / OTP / urgent-threat patterns
- `tools/FallTriageKeywords.kt` — URGENT_RISK / NON_EMERGENCY_INJURY / FALSE_ALARM phrase classifier

Why substrings, not regex? Substrings are easier to reason about and less likely to silently miss a phrasing. The orchestrator was burned twice by regex word-boundary edge cases (`"did I take BP tablet"`).

**Risk aggregation rule everywhere**: take the *worst* of `(deterministic phrase scan, Gemma's risk level)`. We never downgrade. See `SafetyTool`, `FallTriageTool`, `MobilityShieldTool`.

## 9. Tool catalog (all 9 `AgentTool`s)

Every tool implements `interface AgentTool { val name; suspend fun execute(action: AgentAction): ToolResult }`. Tool names live in `tools/AgentTool.kt#ToolNames`.

| Tool | Name string | Persists? | Triggers UI action card? |
|---|---|---|---|
| `ChatTool` | `ChatTool` | No | No (just echoes assistant response) |
| `MedicationTool` | `MedicationTool` | **Yes** (`medications`, `medication_logs`) | No |
| `MemoryTool` | `MemoryTool` | **Yes** (`memories`) | No |
| `ReminderTool` | `ReminderTool` | No (placeholder) | No |
| `SafetyTool` | `SafetyTool` | No | **Yes** — `ALERT_TRUSTED_CONTACT` (MEDIUM) or `HIGH_RISK_SAFETY` (HIGH) |
| `TrustedContactTool` | `TrustedContactTool` | No | **Yes** — `CALL_CONTACT` |
| `ScamShieldTool` | `ScamShieldTool` | No | **Yes** — `SCAM_ANALYSIS` |
| `FallTriageTool` | `FallTriageTool` | No | **Yes** — `FALL_TRIAGE` |
| `MobilityShieldTool` | `MobilityShieldTool` | No | **Yes** — `MOBILITY_CHECK` |

Per-tool notes worth knowing:

- **`MedicationTool`** — `LOG_MEDICATION` does `findOrCreate` + insert log row. `CHECK_MEDICATION` does a JOIN against `medications` + `medication_logs` and returns `"Today's medication status: BP tablet → taken."` Defaults: `medicineName="medicine"`, `status="taken"`.
- **`MemoryTool`** — falls back to `arguments["userMessage"]` for value when Gemma left fields empty. Tries to derive `"<Name> - birthday"` from `"<Name>'s birthday"` patterns.
- **`SafetyTool`** — needs `TrustedContactRepository` to attach a contact to the action card. MEDIUM emits a "Alert Priya?" card; HIGH emits an emergency-dialer-+-alert card. Pre-baked `alertMessage` SMS bodies live in `companion object`.
- **`FallTriageTool`** — combines Gemma's `triageCategory` with `FallTriageKeywords.classify()` via "worst wins". Always returns `success=true` so the UI can render the triage card even with no contact, with a friendly "no trusted contact set up" message appended.
- **`MobilityShieldTool`** — score 0–100, `LOW ≥ 80`, `MEDIUM 60–79`, `HIGH < 60`. Echoes `featureSummary` back to the UI for the bar-chart-y card.
- **`ScamShieldTool`** — strips `"analyze this suspicious message:"` prefixes via `ScamKeywords.extractMessageText()`, then scans for HIGH signals (gift card / OTP / wire transfer / urgent money / "don't tell family") and MEDIUM (suspicious link / account locked).

`ToolResult.data` keys are constants in `tools/ToolResult.kt#ToolResultKeys`. UI action types are constants in `ToolActionTypes`. **Never use string literals** for these in new code.

## 10. Data model (Room schema, version 1)

Database name: `aasa.db`. `fallbackToDestructiveMigration()` is on (hackathon stance — replace with real migrations before shipping). All entities live in `data/entity/`, DAOs in `data/dao/`, repositories in `data/repository/`. Wired in `data/AppDatabase.kt`.

| Table | Entity | Notable columns |
|---|---|---|
| `medications` | `MedicationEntity` | `id, name, dosage?, scheduleTime?, createdAt, nameLower (unique)` |
| `medication_logs` | `MedicationLogEntity` | `id, medicationId (FK CASCADE), status, loggedAt` |
| `memories` | `MemoryEntity` | `id, type, title, value, createdAt` |
| `trusted_contacts` | `TrustedContactEntity` | `id, name, relationship, phoneNumber, isPrimary, nameLower (unique)` |
| `conversations` | `ConversationEntity` | `id, role (USER/ASSISTANT), message, intent?, riskLevel?, tool?, createdAt` |

`DemoDataSeeder` runs on cold start (idempotent) and seeds:

- one `MedicationEntity("BP tablet", "1 tablet", "08:00 AM")`
- one `TrustedContactEntity("Priya", "Daughter", "+91-9000000001", isPrimary=true)`
- one `MemoryEntity(type="FAVORITE_MUSIC", title="Favorite music", value="Old Hindi songs from the 1970s")`

The "Reset demo data" button on Home calls `AasaApplication.resetDemoData()` → `DemoDataSeeder.reset()` → `clearAllTables()` + re-seed.

## 11. Voice (Phase 6)

- `voice/SpeechToTextManager.kt` — wraps Android `SpeechRecognizer`. Emits `SpeechEvent` (Ready, Begin, End, Partial, Recognized, Error). Mic permission is requested per `RECORD_AUDIO`.
- `voice/TextToSpeechManager.kt` — wraps Android `TextToSpeech`. Locale fallback to `Locale.US`. Emits `TtsEvent` (Ready, Started, Done, Error). Released in `onCleared`.
- Both managers are owned by `HomeViewModel` and never start automatically; the UI flips them via mic-button taps.
- `AndroidManifest.xml` includes the necessary `<queries>` for `RecognitionService` and `TTS_SERVICE` (Android 11+ package visibility).

## 12. Sensors (foreground-only)

- **`sensors/FallDetectionManager.kt`** (Phase 8.6) — `TYPE_ACCELEROMETER` at `SENSOR_DELAY_GAME` (~20 Hz). Heuristic: spike > 25 m/s² → 2.5 s stillness window → if ≥ 1.5 s of near-gravity samples → emit `PossibleFall`. State machine: `IDLE → MONITORING → POSSIBLE_FALL`. There's also a `simulateFall()` for demos. The `FallTriageScreen` ViewModel collects events and triggers the spoken triage flow.
- **`sensors/MobilitySensorManager.kt`** (Phase 8.7) — fixed 10-second recording (`RECORDING_DURATION_MS`). Records accelerometer + gyroscope (when present), then auto-stops. Emits `MobilityCheckEvent.Completed(samples)`.
- **`sensors/MobilityFeatureExtractor.kt`** — turns sample arrays into `MobilityFeatures(mobilityConfidenceScore, stabilityLabel, averageAcceleration, accelerationVariance, peakAcceleration, sideToSideSwayScore, abruptPauses, smoothnessScore)`. **Hackathon-grade** — no clinical gait analysis, never claims to.

Both managers explicitly: never run in background, never persist raw samples to disk, never expose continuous-monitoring APIs.

## 13. UI structure

`Aasa/app/src/main/java/com/aasa/eldercare/ui/`

- `AppNavigation.kt` — Compose `NavHost` with seven routes:
  - `home` (default), `medication`, `memory`, `trusted_circle`, `scam_shield`, `fall_triage`, `mobility_shield`
- `home/HomeScreen.kt` (~1500 LOC) — the "command center". Hosts:
  - mic button + recognized-speech card
  - text input + sample chips (`DemoScenarios.ALL`)
  - Gemma connection status card (driven by `GemmaConnectionState`)
  - parsed response card / raw response card
  - tool-execution result card with "Saved in Room" pill
  - **action cards** (`ContactActionCard`, `SafetyActionCard`, `EmergencyActionCard`, scam-analysis card) — only one is visible at a time, gated by `HomeUiState.show*ActionCard`
  - recent-conversations card (live `Flow` from Room)
  - "Reset demo data" + nav shortcuts to feature screens
- `home/HomeUiState.kt` — single state class for everything above. Includes `RiskCopy` and `ScamRiskCopy` helpers that map raw `LOW/MEDIUM/HIGH` strings to elder-friendly labels.
- `home/RiskBadge.kt` — colored pill matching `RiskCopy.RiskLevel`.
- `home/{Contact,Safety,Emergency}ActionCard.kt` — the deferred-confirmation cards. Each has explicit "Open Dialer" / "Open SMS" / "Dismiss" buttons that call `IntentActionLauncher`.
- `IntentActionLauncher.kt` — `Intent.ACTION_DIAL` and `Intent.ACTION_SENDTO` only. `try/catch ActivityNotFoundException` with a friendly toast fallback.
- Each feature screen has a matching `*ViewModel`, `*UiState`, and Compose screen file.

## 14. Server bridge (`aasa-gemma-server/`)

Tiny FastAPI app:

- `main.py` — `POST /agent/message` and `GET /health`. Plus an in-memory `medication_log` (legacy POC fallback that the Android app no longer relies on now that Room exists, but the server still runs it).
- `gemma_client.py` — calls `ollama` HTTP and parses JSON.
- `prompt_builder.py` — the **system prompt** that makes Gemma return the structured JSON contract (intents, tools, risk levels, scam rules, fall triage rules, mobility rules). This is a critical file — changes to intents/tools require updates here too.
- `medication_log.py` — name-extraction helpers used by the in-memory POC log.
- Default Ollama model: `gemma4:e2b` (override via `OLLAMA_MODEL` env var).
- Reachable from the device via `adb reverse tcp:8000 tcp:8000`. The Android `network_security_config.xml` permits cleartext for `127.0.0.1`.

## 15. Build & run (developer cheat sheet)

**Server (Mac):**
```bash
cd aasa-gemma-server
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
ollama list           # confirm gemma4:e2b is available, or set OLLAMA_MODEL
uvicorn main:app --host 127.0.0.1 --port 8000 --reload
curl http://127.0.0.1:8000/health
```

**Android (Pixel 4a, USB-attached):**
```bash
adb devices
adb reverse tcp:8000 tcp:8000
cd Aasa
./gradlew :app:installDebug
adb shell am start -n com.aasa.eldercare/.MainActivity
```

**Permissions to grant on the device first run:**
- `RECORD_AUDIO` (mic)

**Re-seed demo data:** tap "Reset demo data" on the Home screen, or:
```bash
adb uninstall com.aasa.eldercare && ./gradlew :app:installDebug
```

## 16. Demo scenarios shipped in `DemoScenarios.ALL`

| Label | Phrase | Routed tool |
|---|---|---|
| Medication | "I took my BP tablet." | `MedicationTool` (LOG) |
| Memory | "My granddaughter Ananya's birthday is May 12." | `MemoryTool` (SAVE) |
| Safety | "I feel weak and missed my medicine." | `SafetyTool` (MEDIUM) |
| Emergency | "I cannot breathe." | `SafetyTool` (HIGH) |
| Call Priya | "Call Priya." | `TrustedContactTool` |
| Scam Alert | "Analyze this suspicious message: Hi Grandma, I'm in trouble … gift cards …" | `ScamShieldTool` (HIGH) |

## 17. Gradle / dependency stack

`Aasa/app/build.gradle.kts`:

- **Plugins**: `com.android.application` 8.2.2, `kotlin.android` 1.9.22, `ksp` 1.9.22-1.0.17
- **SDK**: `compileSdk 34`, `minSdk 24`, `targetSdk 34`. Java 17.
- **Compose BOM**: `2024.02.02`. Material 3, Foundation, Material Icons Core. Activity Compose `1.8.2`. Navigation Compose `2.7.7`.
- **Lifecycle**: `2.7.0` (runtime-ktx + viewmodel-compose).
- **Coroutines**: `1.7.3`.
- **Networking**: Retrofit `2.9.0` + Gson converter, OkHttp logging interceptor `4.12.0`.
- **Room**: `2.6.1` (runtime + ktx + KSP compiler).
- **No Hilt / Dagger / Koin** — DI is the hand-rolled `AasaApplication` service locator.

## 18. Things deliberately *not* yet done

If the user asks you to add any of these, treat as new feature work, not bug fixing.

- Room migrations (currently `fallbackToDestructiveMigration()`).
- Real DI (Hilt). Service locator is intentional Phase-< 9 scaffolding.
- Background fall detection (foreground service + `BOOT_COMPLETED` receiver). Explicitly out of scope for the hackathon build.
- Reminders persistence — `ReminderTool` is still a placeholder.
- Multi-contact trusted circle UI flow. Schema supports it; the seeder + UI assume one primary.
- Swappable on-device model (e.g. MediaPipe / TFLite Gemma). ~~Today it's purely server-bridged.~~ *(Done in Phase 9: LiteRT-LM + Gemma 4 E2B `.litertlm`, with the bridge demoted to a dev fallback.)*
- Multi-language. Strings are English; locale fallback exists for TTS only.
- Real automated tests. There is no `app/src/test/` or `androidTest/` worth referencing.
- Crash reporting / analytics. None wired up.
- Migrating off the legacy in-memory medication log in `aasa-gemma-server/main.py`.

## 19. Glossary (pulled from the codebase)

- **Action card** — Compose card on the Home screen that asks the elder for explicit confirmation before launching a dialer/SMS intent. Driven by `HomeUiState.pending*` fields.
- **AgentAction** — domain model after JSON → Kotlin mapping. `intent / riskLevel / tool / arguments / assistantResponse / rawResponse`.
- **AgentExecutionResult** — `(action, toolResult)` returned from `AgentOrchestrator`.
- **Deferred confirmation** — the design rule that tools never act, they only return a payload the UI renders as an action card with explicit elder-controlled buttons.
- **Deterministic override** — on-device, substring-based rules that rewrite Gemma's routing before tool dispatch.
- **GemmaConnectionState** — `UNKNOWN / CONNECTING / CONNECTED / DISCONNECTED`. Driven by a periodic `/health` probe.
- **PendingAction** — `HomeViewModel.PendingAction` + the matching `HomeUiState.pending*` fields.
- **ServiceLocator** — `AasaApplication`. Lazily instantiates `AppDatabase`, repositories, `ToolRegistry`, and `AgentOrchestrator`.
- **Tool** — implementer of `AgentTool`. Always `suspend`. Reads/writes through repositories. Returns `ToolResult`.
- **ToolResult.data["actionType"]** — the bridge between the tool and the UI action card.
- **`userMessage`** — special key the orchestrator injects into `arguments` so downstream tools can re-scan the original user input regardless of Gemma's parsing.

## 20. How to use this doc

When opening a new chat with an AI assistant about Aasa, paste:

> Read `AASA_PROJECT_OVERVIEW.md` at the workspace root before answering anything. It records the architecture, the deliberate hackathon-grade vs. production-grade decisions, the tool catalog, and the deterministic safety override rules. Then I'll give you my actual question.

Then state the actual question.

---

## 21. Phase 9 — On-device Gemma 4 via LiteRT-LM

Aasa now runs **Gemma 4 E2B directly on the phone** through Google's LiteRT-LM Kotlin SDK (`com.google.ai.edge.litertlm:litertlm-android`). The Mac bridge is retained but demoted to a dev fallback.

### Runtime stack

- **Model file**: `gemma-4-E2B-it.litertlm` (int4, ~2.4 GB) from `litert-community/gemma-4-E2B-it-litert-lm` on HuggingFace (Gemma license, gated).
- **Library**: LiteRT-LM, Kotlin/Coroutines API (`Engine`, `Conversation`, `sendMessageAsync(...): Flow<Message>`).
- **Backend**: `Backend.CPU()` on the Pixel 4a (no Adreno 618 GPU path; no NPU). Higher-end devices can opt into `Backend.GPU()` later — Snapdragon 8 Elite chips even have a dedicated NPU `.litertlm` variant.
- **Pixel 4a perf reality**: ~80–120 prefill tk/s, ~6–10 decode tk/s, TTFT 4–7 s. Typical 60-token JSON response takes **10–15 s end-to-end**. Demoed turns are kept short; the bridge handles long prompts (scam paste).

### Architecture

```
                  AgentOrchestrator
                          │
                          ▼
                   GemmaRouter (ModelRunner)
                          │
            ┌─────────────┴──────────────┐
            ▼                            ▼
    OnDeviceGemmaRunner            RemoteGemmaRunner
    (LiteRT-LM + Gemma 4)          (FastAPI → Ollama, dev fallback)
            │                            │
            └────────► same AgentMessageResponse JSON shape ◄────────┘
                       (then same deterministic overrides, ToolRegistry, Room)
```

### Routing rules (`GemmaRouter`)

1. On-device runner reports `isAvailable() == true` → on-device.
2. Otherwise → show a local setup error with the exact model path.
3. If the app is built with `-PaasaEnableGemmaBridge=true`, the router may use the Mac bridge as a developer fallback.

The router exposes `activeRunnerLabel` ("On-device · Gemma 4 E2B" vs "Mac bridge · gemma4:e2b") + `lastRoutingReason` so the Home status card can show *why* the active runner won.

### Files

| File | Role |
|---|---|
| `model/ModelRunner.kt` | Interface — extended with `label: String` and `suspend fun isAvailable(): Boolean`. |
| `model/RemoteLocalGemmaRunner.kt` | (Kept name for now; thin wrapper around FastAPI bridge.) Implements `isAvailable()` via `/health` probe. |
| `model/OnDeviceGemmaRunner.kt` | Wraps `com.google.ai.edge.litertlm.Engine`. Pre-warms engine on first `isAvailable()` call; reuses a single long-lived `Conversation` per app process. |
| `model/OnDevicePromptBuilder.kt` | Trimmed system prompt mirroring `aasa-gemma-server/prompt_builder.py` plus a `parseModelOutput(text): AgentMessageResponse` that strips Markdown code-fences and extracts the first JSON object. |
| `model/GemmaRouter.kt` | Implements `ModelRunner`, owns both runners, exposes `activeRunnerLabel` / `lastRoutingReason`. |

### Model side-load (hackathon-grade delivery)

The `.litertlm` file is **not bundled in the APK** (2.4 GB is way over Play Store limits and the model is license-gated anyway). It must be side-loaded to:

```
/sdcard/Android/data/com.aasa.eldercare/files/models/gemma-4-E2B-it.litertlm
```

Setup steps (one time per device — see `docs/MODEL_SETUP.md`):

```bash
# After accepting the Gemma license at:
# https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
huggingface-cli login
huggingface-cli download litert-community/gemma-4-E2B-it-litert-lm \
  --local-dir ~/aasa-models/gemma-4-e2b-it-litert-lm \
  --local-dir-use-symlinks False

adb shell mkdir -p /sdcard/Android/data/com.aasa.eldercare/files/models
adb push ~/aasa-models/gemma-4-e2b-it-litert-lm/gemma-4-E2B-it.litertlm \
  /sdcard/Android/data/com.aasa.eldercare/files/models/gemma-4-E2B-it.litertlm
```

If the file is absent the app boots fine, but model turns remain mobile-only and show a setup error with the expected path. For convenience, the runner accepts either the exact filename above or the first `.litertlm` file found in the same `models/` directory.

### What is *not* yet done in Phase 9

- **Native function calling via `@Tool` annotations.** The on-device path still uses the same prompt-engineered JSON contract as the bridge so the two are byte-identical downstream. A Phase 9.5 migration to LiteRT-LM `@Tool` / `@ToolParam` annotations is queued — that would eliminate JSON parsing entirely and let constrained decoding guarantee a valid tool call.
- **In-app downloader.** The model is side-loaded via `adb push` for hackathon judging. A Phase 9.5 in-app downloader with progress UI is queued.
- **GPU / NPU backends.** Pixel 4a is CPU-only here. Snapdragon 8 Elite users would benefit from `Backend.GPU()` and the dedicated `gemma-4-E2B-it_qualcomm_sm8750.litertlm` NPU variant.
- **Pre-warming UX.** First inference on a cold engine pays the full initialization cost (up to 10 s per LiteRT-LM docs). The router warms the engine on app start in the background; the status card shows a "warming up" state.

---

*Last updated: Phase 9 — on-device Gemma 4 via LiteRT-LM (`gemma-4-E2B-it.litertlm`).*
