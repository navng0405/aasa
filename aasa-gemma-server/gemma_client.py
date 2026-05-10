import json
import os
from typing import Any

import httpx

from prompt_builder import build_aasa_prompt


OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "gemma4:e2b")


class GemmaClientError(RuntimeError):
    pass


async def call_gemma(user_message: str) -> tuple[dict[str, Any], str]:
    prompt = build_aasa_prompt(user_message)
    payload = {
        "model": OLLAMA_MODEL,
        "prompt": prompt,
        "stream": False,
        "format": "json",
        "options": {
            "temperature": 0.1,
        },
    }

    try:
        async with httpx.AsyncClient(timeout=90.0) as client:
            response = await client.post(f"{OLLAMA_BASE_URL}/api/generate", json=payload)
            response.raise_for_status()
    except httpx.HTTPStatusError as exc:
        raise GemmaClientError(
            f"Ollama returned HTTP {exc.response.status_code}: {exc.response.text}"
        ) from exc
    except httpx.RequestError as exc:
        raise GemmaClientError(
            f"Could not reach Ollama at {OLLAMA_BASE_URL}. Is Ollama running?"
        ) from exc

    data = response.json()
    raw_response = data.get("response", "")
    parsed = extract_json_object(raw_response)
    return parsed, raw_response


def extract_json_object(raw_text: str) -> dict[str, Any]:
    text = raw_text.strip()
    if not text:
        raise GemmaClientError("Gemma returned an empty response.")

    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass

    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        raise GemmaClientError(f"Could not find a JSON object in Gemma response: {text}")

    candidate = text[start : end + 1]
    try:
        parsed = json.loads(candidate)
    except json.JSONDecodeError as exc:
        raise GemmaClientError(f"Could not parse JSON from Gemma response: {text}") from exc

    if not isinstance(parsed, dict):
        raise GemmaClientError("Gemma JSON response was not an object.")

    return parsed
