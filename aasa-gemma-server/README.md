# Aasa Gemma Server POC

FastAPI bridge for the Phase 0.5 AasaGemmaBridgePoc.

Android will call:

```text
http://127.0.0.1:8000/agent/message
```

Through:

```bash
adb reverse tcp:8000 tcp:8000
```

## 1. Install Ollama

Install Ollama from:

```text
https://ollama.com
```

Confirm it is running:

```bash
ollama list
```

If `gemma4:e2b` is not listed, either pull the model you want or set the model name:

```bash
export OLLAMA_MODEL=your-local-model-name
```

The server default is:

```bash
OLLAMA_MODEL=gemma4:e2b
```

## 2. Create Python Environment

From this folder:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## 3. Run Server

```bash
uvicorn main:app --host 127.0.0.1 --port 8000 --reload
```

Expected health check:

```bash
curl http://127.0.0.1:8000/health
```

Expected shape:

```json
{"status":"ok","ollamaModel":"gemma4:e2b"}
```

## 4. Test Aasa Prompt

```bash
curl -X POST http://127.0.0.1:8000/agent/message \
  -H "Content-Type: application/json" \
  -d '{"message":"I took my BP tablet."}'
```

Expected response shape:

```json
{
  "intent": "LOG_MEDICATION",
  "riskLevel": "LOW",
  "tool": "MedicationTool",
  "arguments": {
    "medicineName": "BP tablet",
    "status": "taken"
  },
  "assistantResponse": "Okay, I logged your BP tablet as taken today.",
  "rawResponse": "{...}"
}
```

## 5. Android USB Bridge

With the Pixel 4a plugged in:

```bash
adb devices
adb reverse tcp:8000 tcp:8000
```

Then the Android app can call:

```text
http://127.0.0.1:8000/agent/message
```

## Troubleshooting

If Ollama model name fails:

```bash
ollama list
export OLLAMA_MODEL=<model-name-from-list>
uvicorn main:app --host 127.0.0.1 --port 8000 --reload
```

If the server cannot reach Ollama, open the Ollama app or run:

```bash
ollama serve
```

If Android cannot reach the server, rerun:

```bash
adb reverse tcp:8000 tcp:8000
```

## POC Medication Log Behavior

The FastAPI bridge currently keeps a small in-memory medication log while the
server process is running. For example:

1. User: `I took BP tablet.`
2. Aasa logs the medication with the current time.
3. User: `Did I take BP tablet?`
4. Aasa answers from the in-memory log, such as:

```text
Yes, you took your BP tablet at 7:28 PM.
```

This log resets when the FastAPI server restarts. The real app should later move
this into Room on Android.
