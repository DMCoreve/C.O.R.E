from dataclasses import dataclass
from datetime import datetime

from google import genai
from google.genai import types

import config

SYSTEM_PROMPT = """Eres C.O.R.E. (Central Operating & Response Engine), el asistente \
personal de Diego, vinculado a la marca DMCore. Eres su único usuario: puedes llamarlo \
por su nombre de forma natural (no en cada respuesta, solo cuando suene bien decirlo, \
como saludos o respuestas importantes). Respondes de forma breve, directa y natural, \
como si hablaras en voz alta (tus respuestas se leen con síntesis de voz). \
Si no sabes algo con certeza, lo dices en vez de inventarlo.

Cuando el usuario pida poner un recordatorio, abrir una app, o tomar nota de lo que se \
está hablando (dictado continuo, no una frase corta), usa la función correspondiente en \
vez de responder solo con texto. Para recordatorios, calcula "when_iso" a partir de la \
fecha y hora actual que se te da abajo."""

_client = genai.Client(api_key=config.GEMINI_API_KEY)

_SET_REMINDER = types.FunctionDeclaration(
    name="set_reminder",
    description="Programa un recordatorio en el teléfono del usuario para una fecha y hora específica.",
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "title": types.Schema(
                type=types.Type.STRING,
                description="Qué hay que recordar, en pocas palabras.",
            ),
            "when_iso": types.Schema(
                type=types.Type.STRING,
                description="Fecha y hora exacta en formato ISO 8601 (ej. 2026-09-22T16:00:00), "
                "calculada a partir de la fecha/hora actual dada.",
            ),
        },
        required=["title", "when_iso"],
    ),
)

_OPEN_APP = types.FunctionDeclaration(
    name="open_app",
    description="Abre una aplicación en el teléfono del usuario.",
    parameters=types.Schema(
        type=types.Type.OBJECT,
        properties={
            "app_name": types.Schema(
                type=types.Type.STRING,
                description="Nombre de la app tal como la nombró el usuario (ej. 'whatsapp', 'cámara', 'spotify').",
            ),
        },
        required=["app_name"],
    ),
)

_START_DICTATION = types.FunctionDeclaration(
    name="start_dictation",
    description="Empieza a grabar y transcribir de forma continua lo que se está hablando, "
    "hasta que el usuario la detenga, y lo guarda como una nota. Úsala cuando el usuario pida "
    "'tomar nota', 'anota esto', 'graba lo que se está hablando', etc. — no para una frase corta.",
    parameters=types.Schema(type=types.Type.OBJECT, properties={}),
)

_TOOLS = [types.Tool(function_declarations=[_SET_REMINDER, _OPEN_APP, _START_DICTATION])]


@dataclass
class AskResult:
    text: str
    action: dict | None


def _confirmation_text(name: str, args: dict) -> str:
    if name == "set_reminder":
        return f'Listo, te recuerdo "{args.get("title", "eso")}".'
    if name == "open_app":
        return f'Abriendo {args.get("app_name", "la app")}.'
    if name == "start_dictation":
        return "Te escucho, avísame cuando quieras que pare."
    return "Hecho."


def ask(transcript: str, history: list[dict[str, str]] | None = None) -> AskResult:
    history = history or []
    contents = [
        {"role": "user" if turn["role"] == "user" else "model", "parts": [{"text": turn["text"]}]}
        for turn in history
    ]
    contents.append({"role": "user", "parts": [{"text": transcript}]})

    now = datetime.now().isoformat(timespec="seconds")
    system = f"{SYSTEM_PROMPT}\n\nFecha y hora actual: {now}."

    response = _client.models.generate_content(
        model=config.GEMINI_MODEL,
        contents=contents,
        config={"system_instruction": system, "tools": _TOOLS},
    )

    part = response.candidates[0].content.parts[0]
    if part.function_call:
        name = part.function_call.name
        args = dict(part.function_call.args)
        return AskResult(text=_confirmation_text(name, args), action={"name": name, "args": args})

    return AskResult(text=response.text, action=None)
