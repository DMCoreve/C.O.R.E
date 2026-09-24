import io
import wave

from piper import PiperVoice

import config

# Carga perezosa, igual que stt.py: la app ahora habla con el TTS del teléfono y
# pide speak=false, así que Piper solo se carga si un cliente pide el audio.
_voice: PiperVoice | None = None


def synthesize(text: str) -> bytes:
    global _voice
    if _voice is None:
        _voice = PiperVoice.load(config.PIPER_MODEL_PATH)
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav_file:
        _voice.synthesize_wav(text, wav_file)
    return buffer.getvalue()
