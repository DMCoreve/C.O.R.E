import os

from dotenv import load_dotenv

load_dotenv()

GEMINI_API_KEY = os.environ["GEMINI_API_KEY"]
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.5-flash-lite")

# Respaldo si el principal agota su cuota diaria (plan gratis) o está saturado.
# Los lite primero: responden en ~0.6 s y resuelven bien las herramientas; 3.5-flash llegó
# a tardar 7-10 s por saturación (2026-09-30).
GEMINI_FALLBACK_MODELS = [
    m.strip()
    for m in os.environ.get(
        "GEMINI_FALLBACK_MODELS",
        "gemini-3.5-flash-lite,gemini-flash-lite-latest,gemini-3.5-flash,gemini-2.5-flash",
    ).split(",")
    if m.strip()
]

WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "small")

# Zona horaria por defecto si el cliente no manda la suya (el servidor de Render corre en UTC).
DEFAULT_TIMEZONE = os.environ.get("DEFAULT_TIMEZONE", "America/Caracas")

# Ciudad para "¿cómo está el clima?" cuando el usuario no dice cuál.
DEFAULT_CITY = os.environ.get("DEFAULT_CITY", "Caracas")

PIPER_MODEL_PATH = os.environ["PIPER_MODEL_PATH"]
