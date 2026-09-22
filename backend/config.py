import os

from dotenv import load_dotenv

load_dotenv()

GEMINI_API_KEY = os.environ["GEMINI_API_KEY"]
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")

WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "small")

PIPER_MODEL_PATH = os.environ["PIPER_MODEL_PATH"]
