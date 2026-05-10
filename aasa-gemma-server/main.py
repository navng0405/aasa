from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from gemma_client import GemmaClientError, OLLAMA_MODEL, call_gemma


app = FastAPI(title="Aasa Gemma Bridge POC")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class AgentMessageRequest(BaseModel):
    message: str = Field(..., min_length=1)


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
        parsed, raw_response = await call_gemma(request.message)
        return AgentMessageResponse(
            intent=str(parsed.get("intent", "CHAT")),
            riskLevel=str(parsed.get("riskLevel", "LOW")),
            tool=str(parsed.get("tool", "ChatTool")),
            arguments=parsed.get("arguments") if isinstance(parsed.get("arguments"), dict) else {},
            assistantResponse=str(parsed.get("assistantResponse", "")),
            rawResponse=raw_response,
        )
    except GemmaClientError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Unexpected server error: {exc}") from exc
