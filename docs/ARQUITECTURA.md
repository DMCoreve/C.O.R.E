# Arquitectura de C.O.R.E.

## Por qué un backend compartido

La spec original cubre solo Android, pero C.O.R.E. está pensado para teléfonos y
computadoras. Por eso el razonamiento (STT + LLM + TTS) vive en un backend Python aparte
(`backend/`), no dentro de la app Android. Cualquier cliente (Android hoy, un cliente de
escritorio después) es solo una interfaz que captura audio, lo manda al backend, y
reproduce la respuesta.

Lo único que **no** puede vivir en el backend es la detección de la wake word: tiene que
correr local en el dispositivo, 24/7, sin depender de red ni gastar batería en llamadas
constantes. Por eso openWakeWord corre dentro de la app Android (capa 1), y todo lo demás
(capas 2 y 3) se delega al backend compartido.

```
┌─────────────────────────┐        ┌──────────────────────────────────┐
│  Cliente (Android / PC) │        │  Backend ("cerebro", compartido)  │
│                          │        │                                    │
│  Capa 1: openWakeWord    │        │  Capa 2: STT (Whisper)             │
│  (100% local, siempre    │───────▶│  Capa 3: LLM (Gemini) + TTS (Piper)│
│  escuchando)             │  HTTP  │                                    │
│                          │◀───────│  POST /interact                    │
│  Overlay + reproducción  │        │  → transcript, response_text,      │
│  de audio                │        │    audio_base64                    │
└─────────────────────────┘        └──────────────────────────────────┘
```

## Contrato del backend

`POST /interact` (multipart):
- `audio` (archivo) **o** `text` (string, para pruebas sin micrófono)
- Respuesta: `{ transcript, response_text, audio_base64 }`

`GET /health` → `{"estado": "ok"}`

## Decisiones de esta fase y por qué

- **Kotlin nativo, no Flutter**: overlay (`SYSTEM_ALERT_WINDOW`) + foreground service +
  wake word nativo son los tres componentes más delicados del proyecto (batería, permisos
  especiales, latencia). Hacerlos directo en Kotlin evita pelear con plugins/platform
  channels de Flutter para justamente esas tres cosas.
- **openWakeWord en vez de Picovoice Porcupine**: modelos locales y gratuitos, sin cuenta
  ni límite de uso — mismo criterio "local primero" que ya se aplicó al elegir Whisper y
  Piper para STT/TTS en el backend en vez de servicios de pago.
- **Wake word "Hey Jarvis" (preentrenado), no "core" custom todavía**: entrenar "core" en
  Colab (ver `docs/ENTRENAR_WAKE_WORD.md`) resultó ser frágil — el notebook comunitario
  tiene dependencias que se rompen con versiones nuevas de Python/Colab. Para tener algo
  funcionando ya, se usa "hey jarvis", uno de los modelos que openWakeWord trae
  preentrenados de fábrica (cero entrenamiento, cero Colab). La identidad del asistente
  sigue siendo **C.O.R.E.** (así se presenta y así responde) — solo cambia la frase que lo
  activa. Entrenar "core" queda como mejora futura opcional, con el código y la guía ya
  documentados si se retoma.
- **El pipeline de detección corre 100% on-device con ONNX Runtime**: tres modelos
  encadenados (`melspectrogram.onnx` → `embedding_model.onnx` → `hey_jarvis_v0.1.onnx`,
  en `android/app/src/main/assets/`), reimplementados en Kotlin en
  `wakeword/WakeWordDetector.kt` tras validar cada forma de tensor contra el pipeline real
  de Python (ver historial de la sesión). `CoreForegroundService` corre esto en un hilo
  de fondo sobre `AudioRecord`, y al detectar la palabra, captura 4 segundos de audio y
  se los pasa a `OverlayService` para que hable con el backend.
- **Gemini como LLM por defecto**: ya hay API key en uso (Neura corre sobre Gemini), y es
  gratis en su free tier. `llm.py` no tiene abstracción de "proveedor" — cambiar a Claude
  es editar ese archivo directamente cuando haga falta, no antes.

## Qué falta después de este slice

1. (Opcional) Entrenar "core" como wake word custom y reemplazar los 3 `.onnx` en
   `assets/` — el código de `WakeWordDetector` no cambia, solo los modelos y el nombre
   del archivo del clasificador. Ver `docs/ENTRENAR_WAKE_WORD.md`.
2. Ajustar el umbral de activación (`WAKE_THRESHOLD` en `CoreForegroundService.kt`,
   hoy en 0.5) según qué tan sensible/propenso a falsos positivos resulte en uso real.
3. STT/TTS en streaming si la transcripción "en vivo" del overlay lo requiere de verdad.
4. Cliente de escritorio (Windows) reutilizando este mismo `backend/`.
5. Deploy remoto del backend (Railway/Render, como Neura) cuando el pipeline local esté
   validado.
