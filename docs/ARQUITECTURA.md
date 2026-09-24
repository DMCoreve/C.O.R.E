# Arquitectura de C.O.R.E.

## Por qué un backend compartido

La spec original cubre solo Android, pero C.O.R.E. está pensado para teléfonos y
computadoras. Por eso el razonamiento (el LLM, las herramientas, las notas) vive en un
backend Python aparte (`backend/`), no dentro de la app Android.

La voz, en cambio, se procesa en el teléfono. Al principio el audio viajaba al backend
para Whisper (STT) y Piper (TTS), pero en el plan Free de Render (CPU mínima) eso tardaba
30-40 s por pregunta. El teléfono ya trae reconocimiento y síntesis de voz rápidos, así que
Android usa `SpeechRecognizer` y `TextToSpeech` y el backend solo recibe texto (~1 s).
Whisper y Piper siguen en el backend, cargados solo si se usan, para `/note` y para
clientes sin voz propia (p. ej. un futuro cliente de escritorio).

```
┌──────────────────────────────┐          ┌──────────────────────────────┐
│  Android                     │          │  Backend (Render)            │
│                              │          │                              │
│  1. openWakeWord "Jupiter"   │          │                              │
│     (local, siempre activo)  │          │                              │
│  2. SpeechRecognizer (STT)   │  texto   │  3. Gemini + herramientas     │
│     transcripción en vivo    │─────────▶│     POST /interact           │
│                              │◀─────────│     → response_text, action  │
│  4. TextToSpeech + acciones  │          │                              │
│     (recordatorio, abrir app)│          │                              │
└──────────────────────────────┘          └──────────────────────────────┘
```

## Contrato del backend

`POST /interact` (multipart):
- `text` (lo que dijo el usuario, ya transcrito) **o** `audio` (archivo, se transcribe con Whisper)
- `tz` (opcional): zona horaria IANA del cliente, p. ej. `America/Caracas`. Sin ella se usa
  `DEFAULT_TIMEZONE`. Hace que "a las 5" sea hora local y no UTC.
- `speak` (opcional, por defecto `true`): con `false` no se genera audio (la app habla con su TTS).
- Respuesta: `{ transcript, response_text, audio_base64, action }`
- Errores: `429` si Gemini agotó la cuota por minuto del plan gratis; `502` con `detail`
  para cualquier otra falla.

`GET /health` → `{"estado": "ok"}`

## Decisiones de esta fase y por qué

- **Kotlin nativo, no Flutter**: overlay (`SYSTEM_ALERT_WINDOW`) + foreground service +
  wake word nativo son los tres componentes más delicados del proyecto (batería, permisos
  especiales, latencia). Hacerlos directo en Kotlin evita pelear con plugins/platform
  channels de Flutter para justamente esas tres cosas.
- **openWakeWord en vez de Picovoice Porcupine**: modelos locales y gratuitos, sin cuenta
  ni límite de uso — mismo criterio "local primero" que ya se aplicó al elegir Whisper y
  Piper para STT/TTS en el backend en vez de servicios de pago.
- **Wake word "Jupiter" (modelo de la comunidad), no "core" custom**: entrenar "core" en
  Colab (ver `docs/ENTRENAR_WAKE_WORD.md`) resultó frágil, y "core" es una sola sílaba
  (más falsas activaciones). Se usa `jupiter-40-30-1300` de
  [fwartner/home-assistant-wakewords-collection](https://github.com/fwartner/home-assistant-wakewords-collection)
  (`en/jupiter`), guardado como `assets/jupiter.onnx`. Probado con voces sintéticas: en
  inglés ("YÚ-pi-ter") detecta la palabra sola o en frase y no se activó con ninguna de
  35 frases negativas; en español ("JÚ-pi-ter", con jota) no responde. "Hey Jupiter"
  tampoco es fiable: está entrenado con la palabra sola. La identidad del asistente sigue
  siendo **C.O.R.E.** — solo cambia la palabra que lo activa.
- **El pipeline de detección corre 100% on-device con ONNX Runtime**: tres modelos
  encadenados (`melspectrogram.onnx` → `embedding_model.onnx` → `jupiter.onnx`,
  en `android/app/src/main/assets/`), reimplementados en Kotlin en
  `wakeword/WakeWordDetector.kt` tras validar cada forma de tensor contra el pipeline real
  de Python (ver historial de la sesión). `CoreForegroundService` corre esto en un hilo
  de fondo sobre `AudioRecord`. Al detectar la palabra suelta el micrófono y abre
  `OverlayService` en modo escucha; cuando la isla se cierra, se reanuda.
- **Voz on-device (SpeechRecognizer + TextToSpeech)**: el reconocedor del teléfono
  transcribe en vivo (se ve lo que vas diciendo en la isla) y detecta solo cuándo
  terminaste de hablar, en lugar de grabar 4 s fijos. La respuesta la lee el TTS del
  teléfono. Resultado: ~1-2 s de respuesta total en vez de 30-40 s.
- **Overlay "Isla superior"** (variante B del tablero de diseño): píldora negra arriba de
  la pantalla con el orbe, el estado, lo que dijiste y barras que siguen el volumen del
  micrófono. Tapa poco de la app que estés usando.
- **Render despierto**: el plan Free duerme el servicio a los 15 min sin tráfico (la
  primera llamada tarda 30-50 s). Mientras C.O.R.E. escucha, `CoreForegroundService` hace
  un `GET /health` cada 10 min; la app también lo despierta al abrirse y al empezar a
  escucharte. Un servicio siempre despierto usa ~744 h/mes, dentro de las 750 h gratis.
- **Gemini como LLM por defecto**: ya hay API key en uso (Neura corre sobre Gemini), y es
  gratis en su free tier. `llm.py` no tiene abstracción de "proveedor" — cambiar a Claude
  es editar ese archivo directamente cuando haga falta, no antes.

## Qué falta después de este slice

1. (Opcional) Entrenar "core" como wake word custom y reemplazar los 3 `.onnx` en
   `assets/` — el código de `WakeWordDetector` no cambia, solo los modelos y el nombre
   del archivo del clasificador. Ver `docs/ENTRENAR_WAKE_WORD.md`.
2. Ajustar el umbral de activación (`WAKE_THRESHOLD` en `CoreForegroundService.kt`,
   hoy en 0.5) según qué tan sensible/propenso a falsos positivos resulte en uso real.
3. Cliente de escritorio (Windows) reutilizando este mismo `backend/`.

## Backend en producción

Desplegado en Render (plan Free): `https://c-o-r-e-d6g3.onrender.com`. Ver
`backend/README.md` → "Deploy en Render" para el paso a paso (incluye el ajuste de
`WHISPER_MODEL=base` en vez de `small`, necesario porque el plan Free solo da 512MB de
RAM; hoy Whisper solo se usa en `/note`). La app Android ya apunta ahí (`BackendClient.kt`), no a una IP local — funciona
desde cualquier red, no solo en casa.
