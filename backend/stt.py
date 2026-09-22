from faster_whisper import WhisperModel

import config

_model = WhisperModel(config.WHISPER_MODEL, compute_type="int8")


def transcribe(audio_path: str) -> str:
    segments, _ = _model.transcribe(audio_path, language="es")
    return "".join(segment.text for segment in segments).strip()
