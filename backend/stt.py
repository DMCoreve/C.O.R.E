from faster_whisper import WhisperModel

import config

# Carga perezosa: /interact ya no transcribe en el servidor (el teléfono lo hace
# on-device), así que Whisper solo se carga si alguien usa /note o manda audio.
# Cargarlo al arrancar costaba RAM y segundos de arranque en el plan Free de Render.
_model: WhisperModel | None = None


def transcribe(audio_path: str) -> str:
    global _model
    if _model is None:
        _model = WhisperModel(config.WHISPER_MODEL, compute_type="int8")
    segments, _ = _model.transcribe(audio_path, language="es")
    return "".join(segment.text for segment in segments).strip()
