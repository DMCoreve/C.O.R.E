# C.O.R.E. backend

El "cerebro" de C.O.R.E.: recibe texto (o audio), razona con Gemini y devuelve la
respuesta y la acción a ejecutar. La app Android transcribe y habla on-device y manda
`speak=false`, así que en el uso normal solo corre Gemini (~1 s); Whisper (STT) y Piper
(TTS) se cargan solo si un cliente manda audio o pide la voz, y para `/note`. Pensado para ser consumido por el cliente Android
y, más adelante, por un cliente de escritorio.

## Arrancar en local

```bash
cd backend
python -m venv venv
venv\Scripts\activate          # Windows
pip install -r requirements.txt
cp .env.example .env
# Edita .env: GEMINI_API_KEY y PIPER_MODEL_PATH (descarga un modelo de voz de
# https://github.com/rhasspy/piper/blob/master/VOICES.md y guárdalo en ./models/)
uvicorn app:app --reload --port 8787
```

Prueba rápida sin micrófono:

```bash
curl -X POST http://127.0.0.1:8787/interact -F "text=Hola C.O.R.E., ¿qué tal?"
```

`GET /health` debe responder `{"estado":"ok"}`.

## Piezas

| Archivo | Qué hace |
|---|---|
| `app.py` | FastAPI: `/health` y `/interact` (arma el pipeline STT → LLM → TTS) |
| `stt.py` | Transcripción con Whisper local (`faster-whisper`), cargado al primer uso |
| `llm.py` | Razonamiento con Gemini; identidad de C.O.R.E. en el system prompt |
| `tts.py` | Síntesis de voz con Piper (local), cargado al primer uso |
| `config.py` | Carga de variables de entorno |

Cada uno es reemplazable sin tocar los demás (p.ej. cambiar Gemini por Claude en
`llm.py`, o Piper por ElevenLabs en `tts.py`).

## Deploy en Render

Mismo patrón que Neura: repo en GitHub conectado a un Web Service de Render, que
redespliega automático en cada push.

1. En [render.com](https://render.com) → **New → Web Service** → conecta el repo de
   GitHub (la carpeta raíz del repo debe ser `backend/`, o configura el
   **Root Directory** en `backend` si el repo incluye todo `jarvis/`).
2. **Build Command**: `bash build.sh` (descarga el modelo de voz de Piper; no se
   commitea al repo por su peso).
3. **Start Command**: `uvicorn app:app --host 0.0.0.0 --port $PORT`
4. Variables de entorno (panel de Render → Environment), igual que en `.env`:
   - `GEMINI_API_KEY`
   - `GEMINI_MODEL` = `gemini-2.5-flash`
   - `WHISPER_MODEL` = `base` (**no** `small` — el plan Free de Render solo da 512MB de
     RAM, y `small` se queda sin memoria al arrancar; `base` sí entra. Si tienes un
     plan con más RAM, `small` transcribe mejor)
   - `PIPER_MODEL_PATH` = `./models/es_ES-davefx-medium.onnx`
   - `DEFAULT_TIMEZONE` (opcional, por defecto `America/Caracas`): zona horaria que se usa si
     el cliente no manda `tz`. El servidor corre en UTC; sin esto "qué hora es" y los
     recordatorios salen corridos.
5. Deploy. La primera visita después de estar inactivo tarda 30-50s en el plan Free
   (se "duerme"), igual que Neura.

Una vez desplegado, cambia `BASE_URL` en
`android/app/src/main/java/com/dmcore/core/network/BackendClient.kt` de la IP local a
la URL pública de Render (`https://tu-servicio.onrender.com`) — así la app funciona
desde cualquier red, no solo en casa.
