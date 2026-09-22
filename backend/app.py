import base64
import os
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, Form, UploadFile
from pydantic import BaseModel

import llm
import notes
import stt
import tts

app = FastAPI(title="C.O.R.E. backend")


class InteractResponse(BaseModel):
    transcript: str
    response_text: str
    audio_base64: str
    action: dict | None = None


class NoteResponse(BaseModel):
    transcript: str
    note_id: str


async def _transcribe_upload(audio: UploadFile) -> str:
    # delete=False porque en Windows un NamedTemporaryFile abierto queda bloqueado:
    # faster-whisper (vía PyAV) no puede reabrirlo mientras lo tenemos con el "with".
    suffix = Path(audio.filename or "audio.wav").suffix
    tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
    try:
        tmp.write(await audio.read())
        tmp.close()
        return stt.transcribe(tmp.name)
    finally:
        os.unlink(tmp.name)


@app.get("/health")
def health():
    return {"estado": "ok"}


@app.post("/interact", response_model=InteractResponse)
async def interact(text: str | None = Form(None), audio: UploadFile | None = File(None)):
    transcript = await _transcribe_upload(audio) if audio is not None else (text or "")

    result = llm.ask(transcript)
    audio_bytes = tts.synthesize(result.text)

    return InteractResponse(
        transcript=transcript,
        response_text=result.text,
        audio_base64=base64.b64encode(audio_bytes).decode("ascii"),
        action=result.action,
    )


@app.post("/note", response_model=NoteResponse)
async def note(audio: UploadFile = File(...)):
    transcript = await _transcribe_upload(audio)
    saved = notes.add_note(transcript)
    return NoteResponse(transcript=transcript, note_id=saved["id"])


@app.get("/notes")
def get_notes():
    return notes.list_notes()
