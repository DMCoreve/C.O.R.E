import json
import uuid
from datetime import datetime
from pathlib import Path

_NOTES_PATH = Path(__file__).parent / "notes.json"


def _load() -> list[dict]:
    if not _NOTES_PATH.exists():
        return []
    return json.loads(_NOTES_PATH.read_text(encoding="utf-8"))


def _save(notes: list[dict]) -> None:
    _NOTES_PATH.write_text(json.dumps(notes, ensure_ascii=False, indent=2), encoding="utf-8")


def add_note(transcript: str) -> dict:
    note = {
        "id": uuid.uuid4().hex[:8],
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "transcript": transcript,
    }
    notes = _load()
    notes.append(note)
    _save(notes)
    return note


def list_notes() -> list[dict]:
    return _load()
