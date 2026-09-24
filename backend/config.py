import os

from dotenv import load_dotenv

load_dotenv()

GEMINI_API_KEY = os.environ["GEMINI_API_KEY"]
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")

WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "small")

# Zona horaria por defecto si el cliente no manda la suya (el servidor de Render corre en UTC).
DEFAULT_TIMEZONE = os.environ.get("DEFAULT_TIMEZONE", "America/Caracas")

PIPER_MODEL_PATH = os.environ["PIPER_MODEL_PATH"]
