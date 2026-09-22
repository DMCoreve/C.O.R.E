import io
import wave

from piper import PiperVoice

import config

_voice = PiperVoice.load(config.PIPER_MODEL_PATH)


def synthesize(text: str) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav_file:
        _voice.synthesize_wav(text, wav_file)
    return buffer.getvalue()
