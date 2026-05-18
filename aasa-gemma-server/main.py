from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from gemma_client import GemmaClientError, OLLAMA_MODEL, call_gemma
from medication_log import (
    MedicationLog,
    extract_medication_name,
    format_taken_time,
)


app = FastAPI(title="Aasa Gemma Bridge POC")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

medication_log = MedicationLog()


class AgentMessageRequest(BaseModel):
    message: str = Field(..., min_length=1)
    recentContext: str = ""
    deviceLocale: str = ""


class AgentMessageResponse(BaseModel):
    intent: str
    riskLevel: str
    tool: str
    arguments: dict[str, Any]
    assistantResponse: str
    rawResponse: str


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "ollamaModel": OLLAMA_MODEL}


@app.post("/agent/message", response_model=AgentMessageResponse)
async def agent_message(request: AgentMessageRequest) -> AgentMessageResponse:
    try:
        parsed, raw_response = await call_gemma(
            request.message,
            request.recentContext,
            request.deviceLocale,
        )
        intent = str(parsed.get("intent", "CHAT"))
        tool = str(parsed.get("tool", "ChatTool"))
        arguments = parsed.get("arguments") if isinstance(parsed.get("arguments"), dict) else {}
        assistant_response = str(parsed.get("assistantResponse", ""))

        if tool == "MedicationTool" and intent == "LOG_MEDICATION":
            medication_name = extract_medication_name(arguments, request.message)
            entry = medication_log.log_taken(medication_name)
            arguments = {
                **arguments,
                "medicationName": medication_name,
                "takenAt": entry.taken_at.isoformat(),
            }
            assistant_response = (
                f"Okay, I logged that you took your {medication_name} at "
                f"{format_taken_time(entry.taken_at)}."
            )

        if tool == "MedicationTool" and intent == "CHECK_MEDICATION":
            medication_name = extract_medication_name(arguments, request.message)
            entry = medication_log.find_latest(medication_name)
            arguments = {
                **arguments,
                "medicationName": medication_name,
            }
            if entry is None:
                assistant_response = (
                    f"I do not see {medication_name} in your medication log yet."
                )
            else:
                arguments["takenAt"] = entry.taken_at.isoformat()
                assistant_response = (
                    f"Yes, you took your {entry.medication_name} at "
                    f"{format_taken_time(entry.taken_at)}."
                )

        return AgentMessageResponse(
            intent=intent,
            riskLevel=str(parsed.get("riskLevel", "LOW")),
            tool=tool,
            arguments=arguments,
            assistantResponse=assistant_response,
            rawResponse=raw_response,
        )
    except GemmaClientError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Unexpected server error: {exc}") from exc
