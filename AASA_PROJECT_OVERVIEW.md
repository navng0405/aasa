# Aasa Project Overview and Demo Script

> Read this file before answering questions about the Aasa codebase. It records what is implemented, what is intentionally hackathon-grade, and how to demo the product without overclaiming.

## 1. One-Line Pitch

Aasa is a voice-first, local-first safety companion for elders living independently. It runs Gemma 4 on the phone when available, uses deterministic safety overrides before tool dispatch, stores personal context in on-device Room, and always asks the elder to confirm before launching a call, SMS, or safety action.

## 2. Demo Story

**Opening line:** "Aasa is built for the moment when an aging parent is alone at home, needs help, but should not have to navigate a complicated app. It listens, explains gently, checks local records, and prepares safe next steps without taking control away."

**Demo arc:**

1. **Launch and greeting**  
   Open Aasa. It greets the elder by name with time-aware TTS, then can automatically listen after the greeting if mic permission is granted. Show the name card and replay greeting if needed.

2. **Medication memory grounded in Room**  
   Tap the `Medication` demo chip: `"I took my BP tablet."`  
   Then ask: `"Did I take my medicine today?"`  
   Aasa answers from the medication log, not model memory.

3. **Family memory**  
   Tap the `Memory` demo chip: `"My granddaughter Ananya's birthday is May 12."`  
   Open the Memory screen to show the saved fact.

4. **Medium-risk safety with deferred confirmation**  
   Tap `Safety`: `"I feel weak and missed my medicine."`  
   Aasa prepares an alert to Priya, but it does not send automatically. Point at the action card: the elder chooses.

5. **Emergency without auto-dialing**  
   Tap `Emergency`: `"I cannot breathe."`  
   Aasa prepares the emergency dialer and trusted-contact message. It uses `ACTION_DIAL`, never `ACTION_CALL`.

6. **Scam and fraud shield**  
   Tap `Scam Alert`.  
   Aasa highlights warning signals like gift cards, urgency, and "do not tell anyone," then suggests a safe next step in respectful language.

7. **Medicine and document lens**  
   Use the Home lens control to photograph a medicine label or document. Medicine Lens reads OCR text, compares it to the routine medicine list, and shows a safety receipt. Document Reader summarizes visible details like amounts, dates, references, and risk cues.

8. **Morning briefing**  
   Open Morning Briefing. Aasa reads Health Connect when available; otherwise it shows the required `Demo data - no wearable connected` pill and gives a gentle non-medical summary.

9. **Fall and mobility screens**  
   Open Fall Triage or Mobility Shield. Simulate a fall or run a 10-second walk check. Aasa describes risk and confidence without diagnosing.

10. **Daily heartbeat and neighbor helper**  
    Show the daily check-in card and explain the escalation: if no activity appears in the learned morning window, Aasa prepares a wellness check. A paired neighbor helper flow exists, but only activates when both provider and recipient consent flags are granted.

**Closing line:** "The important design choice is not that Aasa can call a tool. It is that the phone keeps the safety rules, the database facts, and the final confirmation local and visible."

## 3. What Is Implemented

### Core Agent

- `AgentOrchestrator` is the single entry point for text or voice turns.
- `GemmaRouter` chooses on-device Gemma 4 via LiteRT-LM when the side-loaded model is available.
- `RemoteLocalGemmaRunner` keeps the Mac FastAPI/Ollama bridge as an optional developer fallback.
- `OnDevicePromptBuilder` mirrors the server JSON contract and parses Gemma output into `AgentMessageResponse`.
- Deterministic overrides run on the raw user message before any tool executes.
- Every tool returns a `ToolResult`; tools do not directly dial, text, or alert.

### Voice and Greeting

- `SpeechToTextManager` wraps Android `SpeechRecognizer`.
- `TextToSpeechManager` wraps Android TTS.
- `GreetingBuilder` creates warm time-of-day greetings.
- `HomeViewModel.maybeGreetUser()` speaks the greeting once per session/cooldown.
- If mic permission is granted, `TtsEvent.Done` can chain into listening so the elder can answer hands-free.

### Local Data

- Room database: `aasa.db`.
- Tables: medications, medication logs, memories, trusted contacts, conversations, and presence pings.
- `DemoDataSeeder` seeds:
  - `BP tablet`, `1 tablet`, `08:00 AM`
  - `Priya`, daughter, primary care recipient/contact
  - `Mrs Wong`, neighbor helper paired to Priya, consent-gated
  - favorite music memory: old Hindi songs from the 1970s
- `Reset demo data` clears Room and re-seeds the demo baseline.

### Safety and Care Features

- Medication log/check with persisted state.
- Memory save with simple type derivation.
- Trusted contact call preparation.
- Medium and high safety triage.
- Scam and fraud analysis.
- Fall triage after synthetic fall events or screen simulation.
- Mobility confidence from a foreground-only 10-second sensor recording.
- Morning briefing from Health Connect or explicit mock data.
- Daily heartbeat using presence pings, foreground activity, spoken interaction, and conversation activity.
- Neighbor helper escalation when paired contact consent is present.
- Medicine Lens OCR with conservative medicine explanation and safety receipt.
- Document Reader OCR with plain-language summary and risk cues.
- Opt-in "Hey Aasa" foreground service behind a build flag.

## 4. Non-Negotiable Rules

- **No diagnosis.** Aasa describes observations and next steps, not medical conditions.
- **No auto-dial or auto-SMS.** The UI launches `ACTION_DIAL` or `ACTION_SENDTO`; the elder still taps call/send.
- **Local-first.** Room stores app data on-device. The default model path is on-device Gemma 4. The Mac bridge is a dev fallback only.
- **Deterministic safety wins.** If local keyword rules detect a higher risk than Gemma, the local rule wins.
- **Deferred confirmation.** Tools prepare action cards; they do not take irreversible actions.
- **Background sensing is off by default.** The only background-style feature is the hackathon hotword service, and it requires a build flag, explicit toggle, mic permission, and a persistent notification.
- **Wearable data honesty.** If Health Connect data is unavailable, Morning Briefing must show the demo-data pill.
- **OCR humility.** Medicine and document reading are helpers, not authoritative verification.

## 5. Repository Layout

```text
gemmaPoc/
+-- Aasa/                    # Active Android app: Kotlin, Compose, Room, LiteRT-LM
+-- aasa-gemma-server/       # Optional Mac FastAPI -> Ollama bridge
+-- AasaGemmaBridgePoc/      # Legacy proof of concept, not active
`-- AASA_PROJECT_OVERVIEW.md # This file
```

## 6. Main Architecture

```text
HomeScreen / feature screens
        |
        v
HomeViewModel / feature ViewModels
        |
        v
AgentOrchestrator
        |
        +--> GemmaRouter
        |       +--> OnDeviceGemmaRunner (LiteRT-LM, default)
        |       +--> RemoteLocalGemmaRunner (FastAPI/Ollama fallback)
        |
        +--> deterministic overrides
        |
        v
ToolRegistry
        |
        v
AgentTool implementations
        |
        v
Room repositories / Android intents / OCR / Health Connect / sensors
```

One turn in practice:

1. Save the user message to `conversations`.
2. Ask the selected model runner for a JSON action.
3. Convert JSON to `AgentAction`.
4. Apply deterministic overrides to route safety, scam, medication, memory, mobility, health, wellness, and neighbor flows.
5. Execute the matching local `AgentTool`.
6. If the tool returns a better grounded message, use it as the final assistant response.
7. Save the assistant turn to `conversations`.
8. Update UI state and speak the response.

## 7. Deterministic Override Order

The override order in `AgentOrchestrator` matters:

```text
0a. Mobility check synthetic prompt -> MobilityShieldTool
0.  Fall triage synthetic prompt    -> FallTriageTool
1.  HIGH safety phrase              -> SafetyTool / HIGH
1.  MEDIUM safety phrase            -> SafetyTool / MEDIUM
1c. Health briefing request         -> HealthBriefingTool
1d. Neighbor check request          -> NeighborCheckTool
1d. Wellness check request          -> WellnessCheckTool
1b. Scam analysis trigger           -> ScamShieldTool
2.  Medication check query          -> MedicationTool / CHECK_MEDICATION
2.  Medication log statement        -> MedicationTool / LOG_MEDICATION
3.  Memory save statement           -> MemoryTool / SAVE_MEMORY
else                                -> trust Gemma action, with userMessage added
```

The raw user text is always injected as `arguments["userMessage"]` so downstream tools can re-scan it.

## 8. Tool Catalog

Tool names are constants in `ToolNames`; action-card payload keys live in `ToolResultKeys`.

| Tool | Main role | Persists? | Action card? |
|---|---|---:|---:|
| `ChatTool` | Plain assistant reply | No | No |
| `MedicationTool` | Log/check medicine | Yes | No |
| `MemoryTool` | Save elder facts | Yes | No |
| `ReminderTool` | Placeholder reminder acknowledgement | No | No |
| `SafetyTool` | Medium/high safety escalation | No | Yes |
| `TrustedContactTool` | Prepare contact call | No | Yes |
| `ScamShieldTool` | Analyze suspicious messages | No | Yes |
| `FallTriageTool` | Classify fall response | No | Yes |
| `MobilityShieldTool` | Explain walk-check confidence | No | Yes |
| `HealthBriefingTool` | Summarize Health Connect snapshot | No | Yes/result screen |
| `WellnessCheckTool` | Prepare daily heartbeat check-in | No | Yes |
| `NeighborCheckTool` | Prepare paired-neighbor check | No | Yes |
| `DocumentReaderTool` | Summarize OCR document text | No | Yes/result card |

## 9. Screens and Routes

Routes in `AppNavigation`:

- `home`: command center, voice input, demo chips, action cards, medicine/document lens, heartbeat, model status, recent conversations.
- `medication`: persisted medication list and logs.
- `memory`: saved memories.
- `trusted_circle`: trusted contacts and consent/provider state.
- `scam_shield`: dedicated suspicious-message analysis.
- `fall_triage`: foreground fall detection and simulated fall flow.
- `mobility_shield`: foreground walk check and confidence explanation.
- `health_briefing`: Health Connect / demo-data morning summary.

## 10. Feature Notes for the Demo

### Medication

Files: `MedicationTool`, `MedicationRepository`, `MedicationScreen`, `DemoDataSeeder`.

Say: "This is not a model pretending to remember. It writes a medication log row and then reads it back."

Good prompts:

- `"I took my BP tablet."`
- `"Did I take my medicine today?"`

### Memory

Files: `MemoryTool`, `MemoryRepository`, `MemoryScreen`.

Say: "Aasa stores simple family context so later interactions can feel personal."

Good prompt:

- `"My granddaughter Ananya's birthday is May 12."`

### Trusted Circle and Deferred Actions

Files: `SafetyTool`, `TrustedContactTool`, `ContactActionCard`, `SafetyActionCard`, `EmergencyActionCard`, `IntentActionLauncher`.

Say: "The agent can prepare a call or SMS, but it cannot send one. The elder stays in control."

Good prompts:

- `"Call Priya."`
- `"I feel weak and missed my medicine."`
- `"I cannot breathe."`

### Scam Shield

Files: `ScamShieldTool`, `ScamKeywords`, `ScamShieldScreen`, Home scam result card.

Say: "The tone matters here. Aasa does not shame the elder; it points out signals and gives one safe next step."

Good prompt:

- `"Analyze this suspicious message: Hi Grandma, I'm in trouble. Don't call anyone. Buy two Apple gift cards and send me the codes quickly."`

### Medicine Lens

Files: `MedicineLensAnalyzer`, `HomeViewModel.analyzeMedicinePhoto`, Home lens UI.

Say: "This is on-device OCR plus conservative explanation. It can match routine medicines like the BP tablet, but it never certifies a pill as safe."

What to show:

- Camera/image picker from Home.
- Safety receipt: what Aasa saw, routine medicine match, confidence, next step.
- Duplicate-dose warning if the routine medicine is already logged today.

### Document Reader

Files: `DocumentReaderAnalyzer`, `DocumentReaderTool`, Home lens UI.

Say: "The same lens can read a bill, letter, or form and pull out visible details like due dates, amounts, references, and risk cues."

What to show:

- Switch lens mode to document.
- Photograph or select a page.
- Result card with summary and risk band.

### Morning Briefing

Files: `HealthSnapshotRepository`, `HealthBriefingTool`, `HealthBriefingScreen`.

Say: "Aasa is wearable-ready through Health Connect, not a vendor SDK. If no wearable is connected, it must say demo data."

Good prompts:

- Tap `Get my briefing`.
- From Home: `"How did I sleep?"` or `"Morning briefing."`

### Fall Triage

Files: `FallDetectionManager`, `FallTriageTool`, `FallTriageScreen`.

Say: "The sensor heuristic is foreground-only and hackathon-grade. The important piece is the triage flow after a possible fall."

What to show:

- Simulate fall.
- Respond with `"I'm okay"` or `"I cannot get up"`.
- Show false alarm vs emergency action path.

### Mobility Shield

Files: `MobilitySensorManager`, `MobilityFeatureExtractor`, `MobilityShieldTool`, `MobilityShieldScreen`.

Say: "Aasa says mobility confidence, not a diagnosis."

What to show:

- Run or simulate a 10-second walk check.
- Point out the score, stability label, and feature summary.

### Daily Heartbeat and Neighbor Helper

Files: `PresencePingRepository`, `PresencePingEntity`, `WellnessCheckTool`, `NeighborCheckTool`, Home heartbeat UI.

Say: "Aasa watches for a daily heartbeat through app activity, conversation, speech, or check-in taps. If the morning window passes silently, it prepares a check-in message. Neighbor helper escalation is consent-gated."

Important caveat:

- `Mrs Wong` is seeded as a paired helper, but `NeighborCheckTool` only uses her when both `providerConsentGranted` and `recipientConsentGranted` are true.

### Hey Aasa Hotword

Files: `HotwordService`, `UserPreferences.hotwordEnabled`, `HomeScreen.HotwordCard`.

Say: "This is intentionally opt-in and visibly running. It is a foreground microphone service behind `-PaasaEnableHotword=true`, not a hidden always-on listener."

Build flag:

```bash
cd Aasa
./gradlew :app:installDebug -PaasaEnableHotword=true
```

## 11. Build and Run

### Android app

```bash
cd Aasa
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb shell am start -n com.aasa.eldercare/.MainActivity
```

### Optional Mac bridge

Use this only as a developer fallback or for long prompt demos:

```bash
cd aasa-gemma-server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 127.0.0.1 --port 8000 --reload
adb reverse tcp:8000 tcp:8000
curl http://127.0.0.1:8000/health
```

To enable bridge fallback in the app build:

```bash
cd Aasa
./gradlew :app:installDebug -PaasaEnableGemmaBridge=true
```

### On-device model side-load

The `.litertlm` model is not bundled in the APK. Side-load it to:

```text
/sdcard/Android/data/com.aasa.eldercare/files/models/gemma-4-E2B-it.litertlm
```

Expected model: `gemma-4-E2B-it.litertlm` from `litert-community/gemma-4-E2B-it-litert-lm`.

## 12. Permissions

Required or feature-specific permissions:

- `RECORD_AUDIO`: voice input, greeting reply, hotword service.
- Health Connect read permissions for sleep, heart rate, and steps: Morning Briefing.
- Camera/gallery access through Android pickers: Medicine Lens and Document Reader.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`: hotword builds only.

No `CALL_PHONE` permission is needed because calls use `ACTION_DIAL`.

## 13. Dependency Stack

- Android Gradle Plugin `8.2.2`
- Kotlin `1.9.22`
- Compose BOM `2024.02.02`
- Material 3, Navigation Compose, Lifecycle ViewModel Compose
- Coroutines `1.7.3`
- Retrofit/Gson/OkHttp for the optional bridge
- Room `2.6.1`
- LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android:0.11.0`
- Health Connect `androidx.health.connect:connect-client:1.1.0-alpha07`
- ML Kit Text Recognition for medicine/document OCR
- No Hilt/Dagger/Koin; `AasaApplication` is the service locator.

## 14. What Is Hackathon-Grade

- Room uses destructive migration fallback.
- Fall and mobility heuristics are demos, not validated medical systems.
- Hotword uses Android `SpeechRecognizer`, not a low-power keyword spotting model.
- The on-device model is side-loaded with `adb`, not downloaded in-app.
- Prompt-engineered JSON is still used instead of native constrained tool calling.
- Reminders are still a placeholder.
- Automated tests are minimal/nonexistent.
- The optional server still contains legacy in-memory medication POC code.
- Neighbor helper consent state exists, but the full multi-party onboarding UX is not complete.

## 15. Suggested 3-Minute Demo Script

**0:00 - 0:20: Set the premise**  
"This is Aasa, a local-first safety companion for an elder living alone. The big design principle is that AI can prepare help, but the elder confirms every real-world action."

**0:20 - 0:45: Greeting and voice**  
Open Home. Let Aasa greet by name. Say: "It speaks first, then can listen for the reply. The elder does not have to hunt for a chat box."

**0:45 - 1:15: Grounded medication**  
Tap `Medication`. Then ask `"Did I take my medicine today?"`  
Say: "This answer comes from Room on the phone."

**1:15 - 1:45: Safety card**  
Tap `Safety`. Show alert card.  
Say: "Medium risk prepares a trusted contact message, but nothing sends until the elder taps."

**1:45 - 2:10: Scam shield**  
Tap `Scam Alert`.  
Say: "Aasa explains the warning signs without blaming the elder."

**2:10 - 2:35: Lens or briefing**  
Pick one depending on demo setup:

- Medicine/document lens: show OCR result and safety receipt.
- Morning briefing: show Health Connect/demo-data pill and summary.

**2:35 - 3:00: Close with architecture**  
Show model status/recent conversations.  
Say: "Gemma decides the conversational shape, but local deterministic rules, local tools, and local confirmation make the safety behavior reliable."

## 16. Phrases That Work Well

```text
I took my BP tablet.
Did I take my medicine today?
My granddaughter Ananya's birthday is May 12.
Call Priya.
I feel weak and missed my medicine.
I cannot breathe.
Analyze this suspicious message: Hi Grandma, I'm in trouble and need help now. Please don't call anyone. Buy two Apple gift cards worth $500 and send me the codes quickly.
How did I sleep?
Morning briefing.
Run a mobility check.
Fall detected. User response: I cannot get up.
Daily heartbeat timeout: wellness check timeout.
Daily heartbeat timeout: neighbor check escalation.
```

## 17. Glossary

- **Action card:** A UI card that asks the elder to confirm a prepared call/SMS/safety action.
- **AgentAction:** Parsed model action with `intent`, `riskLevel`, `tool`, `arguments`, and `assistantResponse`.
- **Deferred confirmation:** Tools prepare actions; the elder executes or dismisses them.
- **Deterministic override:** Local substring/rule scan that can reroute a model action before tool dispatch.
- **GemmaRouter:** Chooses on-device Gemma or optional bridge.
- **PendingAction:** HomeViewModel payload used to render action cards.
- **ToolResult:** Tool execution output consumed by the ViewModel/UI.
- **Safety receipt:** Medicine Lens explanation of what was seen, what matched, confidence, and next step.

---

Last updated: current app state after Phase 12 plus Medicine/Document Lens, Daily Heartbeat, and Neighbor Helper.
