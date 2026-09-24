import base64
import logging
import os
import tempfile
import time
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from pydantic import BaseModel

import llm
import notes
import stt
import tts

log = logging.getLogger("core")
logging.basicConfig(level=logging.INFO)

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
async def interact(
    text: str | None = Form(None),
    audio: UploadFile | None = File(None),
    tz: str | None = Form(None),
    speak: bool = Form(True),
):
    """
    La app manda el texto ya transcrito en el teléfono y speak=false (habla con su
    propio TTS), así solo se paga la llamada a Gemini. audio y speak=true se mantienen
    para clientes que no tengan reconocimiento/voz propios.
    """
    t0 = time.monotonic()
    try:
        transcript = await _transcribe_upload(audio) if audio is not None else (text or "")
        t_stt = time.monotonic()
        if not transcript.strip():
            return InteractResponse(
                transcript="", response_text="No te escuché bien, ¿me lo repites?", audio_base64="",
            )
        result = llm.ask(transcript, tz_name=tz)
        t_llm = time.monotonic()
        audio_b64 = base64.b64encode(tts.synthesize(result.text)).decode("ascii") if speak else ""
        t_tts = time.monotonic()
    except Exception as e:
        log.exception("interact falló")
        # 429 = cuota por minuto de Gemini (plan gratis); la app lo muestra distinto
        # de un error de red porque basta con esperar unos segundos.
        status = 429 if getattr(e, "code", None) == 429 else 502
        raise HTTPException(status_code=status, detail=f"{type(e).__name__}: {e}"[:300])

    log.info(
        "interact stt=%.1fs llm=%.1fs tts=%.1fs total=%.1fs",
        t_stt - t0, t_llm - t_stt, t_tts - t_llm, t_tts - t0,
    )
    return InteractResponse(
        transcript=transcript,
        response_text=result.text,
        audio_base64=audio_b64,
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
