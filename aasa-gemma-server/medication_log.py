from dataclasses import dataclass
from datetime import datetime
from typing import Any


@dataclass(frozen=True)
class MedicationLogEntry:
    medication_name: str
    taken_at: datetime


class MedicationLog:
    def __init__(self) -> None:
        self._entries: list[MedicationLogEntry] = []

    def log_taken(self, medication_name: str) -> MedicationLogEntry:
        entry = MedicationLogEntry(
            medication_name=medication_name.strip(),
            taken_at=datetime.now().astimezone(),
        )
        self._entries.append(entry)
        return entry

    def find_latest(self, medication_name: str) -> MedicationLogEntry | None:
        normalized_name = normalize_medication_name(medication_name)
        for entry in reversed(self._entries):
            if normalize_medication_name(entry.medication_name) == normalized_name:
                return entry
        return None


def extract_medication_name(arguments: dict[str, Any], fallback_message: str) -> str:
    for key in ("medicationName", "medicineName", "medication", "name"):
        value = arguments.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()

    message = fallback_message.lower()
    for phrase in ("did i take", "i took", "took my", "took"):
        if phrase in message:
            candidate = fallback_message[message.find(phrase) + len(phrase) :].strip(" ?.!")
            if candidate:
                return candidate

    return "your medication"


def normalize_medication_name(value: str) -> str:
    return " ".join(value.lower().replace(".", "").split())


def format_taken_time(taken_at: datetime) -> str:
    return taken_at.strftime("%-I:%M %p")
