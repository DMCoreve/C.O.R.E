import os

from dotenv import load_dotenv

load_dotenv()

GEMINI_API_KEY = os.environ["GEMINI_API_KEY"]
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.5-flash")

# Respaldo si el principal agota su cuota diaria (plan gratis) o está saturado.
GEMINI_FALLBACK_MODELS = [
    m.strip()
    for m in os.environ.get(
        "GEMINI_FALLBACK_MODELS",
        "gemini-3.5-flash,gemini-3.5-flash-lite,gemini-flash-lite-latest,gemini-2.5-flash",
    ).split(",")
    if m.strip()
]

WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "small")

# Zona horaria por defecto si el cliente no manda la suya (el servidor de Render corre en UTC).
DEFAULT_TIMEZONE = os.environ.get("DEFAULT_TIMEZONE", "America/Caracas")

PIPER_MODEL_PATH = os.environ["PIPER_MODEL_PATH"]
